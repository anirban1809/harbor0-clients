package app.harbor0.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.util.UUID

/** How the sync setup sheet was opened. */
sealed interface SyncSetup {
    /** A new local folder; the cloud folder is created or chosen. */
    data object New: SyncSetup
    /** A folder already synced on another device, linked on this phone. */
    data class Remote(val folder: SyncFolder): SyncSetup
    /** An invitation from another account. */
    data class Invite(val invitation: SyncInvitation): SyncSetup
    /** A different local folder for an existing sync or backup folder. */
    data class ChangeLocal(val root: SyncRoot): SyncSetup
}

/** The Sync page and this phone's backup controls: engine state, folders synced elsewhere, and invitations. */
class SyncState(private val m: WorkspaceModel) {
    private val controller get() = m.syncController
    var view by mutableStateOf<SyncView?>(null); private set
    var startError by mutableStateOf<String?>(null); private set
    var remoteFolders by mutableStateOf<List<SyncFolder>>(emptyList()); private set
    var invitations by mutableStateOf(Loadable<List<SyncInvitation>>()); private set
    private var job: Job? = null
    /** What harbor0 may read in the photo library; declared before init, which reads it; checked again when the app returns and after a permission request. */
    var photoAccess by mutableStateOf(MediaAccess.FULL); private set
    /** Battery settings (or Samsung's sleeping apps) stop harbor0 in the background; read with photo access, before init too. */
    var backgroundRestricted by mutableStateOf(false); private set
    /** Android's battery optimization still applies, so backups may stop soon after the app is closed. */
    var batteryOptimized by mutableStateOf(false); private set
    private val backgroundPreferences get() = m.context.getSharedPreferences("background", Context.MODE_PRIVATE)
    /** This phone's own auto-start page, until it has been opened once from harbor0 (whether it's on can't be read). */
    var autoStart by mutableStateOf<Intent?>(null); private set
    /** This phone's backup wait and whether files changing mid-backup are skipped; saved on the phone, not the account. */
    var backupChoices by mutableStateOf(controller.currentBackupSettings()); private set

    init {
        m.viewModelScope.launch { controller.view.collect { view = it } }
        m.viewModelScope.launch { controller.startError.collect { startError = it } }
        checkPhotoAccess()
    }
    val roots get() = view?.roots.orEmpty()
    val syncRoots get() = roots.filter { it.mode == "sync" }
    val backupRoots get() = roots.filter { it.mode == "backup" }
    val runtime get() = view?.runtime ?: SyncRuntime()
    val jobs get() = view?.jobs.orEmpty()
    fun reset() { job?.cancel(); remoteFolders = emptyList(); invitations = Loadable() }
    /** This phone's status across its sync folders. */
    val label: String get() = if (view == null) (if (startError != null) "Offline" else "Starting") else globalSyncState(syncRoots, runtime, jobs.filter { j -> syncRoots.any { it.id == j.rootId } })
    fun state(root: SyncRoot) = folderState(root, runtime, jobs)
    fun tone(label: String) = when (label) {
        "Up to date" -> Tone.Success; "Syncing", "Starting", CHECKING -> Tone.Accent; "Paused", "Offline", WAITING -> Tone.Neutral; else -> Tone.Danger
    }
    val requirements get() = syncRequirements(syncRoots, runtime, jobs)
    val receivedInvitations get() = invitations.data.orEmpty().filter { it.direction == "RECEIVED" && it.revokedAt == null && it.syncState != "DECLINED" && syncRoots.none { r -> r.remoteId == it.driveItemId } }
    fun isLocal(item: DriveItem) = syncRoots.any { it.remoteId == item.id }
    /** This phone's sync folder for a cloud folder, if it syncs here. */
    fun localRoot(folderId: String?) = folderId?.let { id -> syncRoots.firstOrNull { it.remoteId == id } }
    fun canLinkHere(item: DriveItem) = view != null && item.isFolder && item.syncRemovedAt == null && (item.id in m.drive.syncIds || remoteFolders.any { it.id == item.id }) &&
        syncRoots.none { it.remoteId == item.id } && (item.ownerUserId == null || m.user == null || item.ownerUserId == m.user?.id)
    fun linkHere(item: DriveItem) = setup(SyncSetup.Remote(remoteFolders.firstOrNull { it.id == item.id } ?: SyncFolder(item.id, item.name, item.ownerUserId, item.parentId)))
    /** This phone's photo backup, if it has one. */
    val photoRoot get() = backupRoots.firstOrNull { it.isPhotos }
    fun checkPhotoAccess() {
        photoAccess = controller.photoAccess(); backgroundRestricted = BackupWorker.backgroundRestricted(m.context)
        batteryOptimized = BackupWorker.batteryOptimized(m.context)
        autoStart = if (backgroundPreferences.getBoolean("autoStartOpened", false)) null else BackupWorker.autoStartPage(m.context)
    }
    fun openAutoStart() {
        val page = autoStart ?: return
        if (runCatching { m.context.startActivity(page) }.isSuccess) { backgroundPreferences.edit().putBoolean("autoStartOpened", true).apply(); autoStart = null }
        else openAppSettings(m.context)
    }
    /** Whether a device in the list is this phone: its sync session, or the same installation signed in again. */
    fun isThisPhone(device: Device?): Boolean {
        if (device == null) return false
        if (device.id == view?.deviceId) return true
        val key = controller.devicePublicId
        return key != null && device.devicePublicId == key
    }
    val phoneName get() = m.devices.data.orEmpty().firstOrNull { isThisPhone(it) }?.name ?: controller.deviceName
    /** The backup folder on this phone for a backup listed by the server, if any. */
    fun localBackup(backup: BackupRoot) = backupRoots.firstOrNull { it.backupId == backup.id || it.remoteId == backup.remoteRootDriveItemId }

    fun refresh(wake: Boolean = true) {
        job?.cancel()
        if (wake) controller.wake()
        job = m.viewModelScope.launch {
            try { remoteFolders = m.api.all<SyncFolder>("/v1/sync/folders") } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            invitations = invitations.copy(loading = true, error = null)
            invitations = try { Loadable(m.api.all<SyncInvitation>("/v1/sync/shares")) }
                catch (e: CancellationException) { throw e } catch (e: Exception) { invitations.copy(loading = false, error = m.message(e)) }
        }
    }
    private fun run(done: String? = null, close: Boolean = true, block: suspend () -> Unit) = m.action(done, close) { block(); refresh() }

    // Setup -------------------------------------------------------------------------------------
    fun setup(kind: SyncSetup) { m.error = null; m.overlay = Overlay.SyncSetupSheet(kind) }
    /** Starts syncing, then opens the folder in My Drive, where its panel shows how syncing is going. */
    fun start(kind: SyncSetup, local: Uri?) = run("Sync started. Files will appear as they sync.") {
        val id = when (kind) {
            SyncSetup.New -> controller.addSyncRoot(local ?: error("Choose a folder on this phone."))
            is SyncSetup.Remote -> controller.addSyncRoot(local ?: error("Choose a folder on this phone."), cloud = CloudChoice.Existing(kind.folder.id))
            is SyncSetup.Invite -> {
                val uri = local ?: error("Choose a folder on this phone.")
                if (kind.invitation.syncState == "PENDING") respond(kind.invitation, "ACCEPTED")
                controller.addSyncRoot(uri, cloud = CloudChoice.Existing(kind.invitation.driveItemId), shareId = kind.invitation.id)
            }
            is SyncSetup.ChangeLocal -> { controller.changeLocal(kind.root.id, local ?: error("Choose a folder on this phone.")); return@run }
        }
        val root = withTimeoutOrNull(5_000) { controller.view.first { v -> v?.roots?.any { it.id == id && it.remoteId != null } == true } }?.roots?.first { it.id == id }
        m.navigate(Section.Drive)
        if (root?.remoteId != null) m.drive.openSyncFolder(root.remoteId, root.localName) else m.drive.openPlace(Place.Synced)
    }
    private suspend fun respond(invitation: SyncInvitation, action: String) {
        m.api.request("/v1/sync/shares/${HarborApi.segment(invitation.id)}/respond", "POST", buildJsonObject { put("action", action) })
    }
    fun decline(invitation: SyncInvitation) = m.confirm(Confirmation("Decline “${invitation.name}”?",
        "${invitation.owner.displayName.ifBlank { "The owner" }} will see that you declined. You can be invited again later.", "Decline", "Invitation declined.", cancel = "Cancel") {
        respond(invitation, "DECLINED"); refresh()
    })

    // Photo backup -------------------------------------------------------------------------------
    fun setupPhotos() { m.error = null; checkPhotoAccess(); m.overlay = Overlay.PhotoBackup }
    /** Starts the photo backup, then opens its folder in My Drive, where the backup panel shows its progress. */
    fun backUpPhotos(options: PhotoOptions) = run("Backing up your photos. New photos are saved within a few minutes.") {
        checkPhotoAccess()
        val root = controller.addPhotoBackup(options)
        m.navigate(Section.Drive)
        m.drive.openBackupFolder(root.remoteId!!, root.backupId, root.localName)
        m.backups.refresh()
    }
    fun photoOptions(root: SyncRoot, options: PhotoOptions) = run(close = false) { controller.photoOptions(root.id, options) }
    suspend fun photoAlbums(root: SyncRoot) = controller.photoAlbums(root)
    fun saveAlbums(root: SyncRoot, excluded: List<String>) = run("Albums saved.") { controller.rootSettings(root.id, root.paused, excluded.sorted()) }

    /** Applies at once: the engine is woken, and its next pass uses the new choices. */
    fun saveBackupChoices(settings: BackupSettings) {
        if (settings == backupChoices) return
        controller.setBackupSettings(settings)
        backupChoices = settings
    }

    // Folder actions ----------------------------------------------------------------------------
    fun backUpFolder(uri: Uri) = run("Backup added. Files are saved after they have been unchanged for ${backupWaitLabel(backupChoices.waitMinutes)}.", close = false) { controller.addBackup(uri); m.backups.refresh() }
    fun pauseAll(paused: Boolean) = run(if (paused) "Sync paused." else "Sync resumed.", close = false) { controller.pauseAll(paused) }
    fun togglePause(root: SyncRoot) = run(if (root.paused) "Resumed ${root.localName}." else "Paused ${root.localName}.", close = false) { controller.rootSettings(root.id, !root.paused, root.excluded) }
    fun saveExclusions(root: SyncRoot, text: String) = run("Exclusions saved.") {
        controller.rootSettings(root.id, root.paused, text.lines().map { it.trim().trim('/') }.filter { it.isNotEmpty() }.distinct())
    }
    fun stopHere(root: SyncRoot) = m.confirm(Confirmation(if (root.mode == "backup") "Stop backing up ${root.localName} on this phone?" else "Stop syncing on this phone?",
        if (root.shareId != null) "This shared folder will stop syncing on this phone. Your local files will stay where they are. Other devices and the owner’s folder are unaffected."
        else "“${root.localName}” will stop syncing on this phone. Your local files will stay where they are, and your other devices keep syncing it.",
        "Stop syncing here", "Stopped syncing ${root.localName} on this phone. Local files are preserved.", cancel = "Cancel") {
        controller.stopOnThisPhone(root.id); refresh()
    })
    fun removeEverywhere(folderId: String, name: String) = m.confirm(Confirmation("Remove “$name” from sync?", REMOVE_SYNC_DESCRIPTION,
        "Remove from sync", REMOVE_SYNC_DONE, cancel = "Cancel") {
        controller.removeEverywhere(folderId); refresh(); m.drive.refresh()
    })
    fun dismiss(issue: SyncIssue) = run(close = false) { controller.dismiss(issue.id) }
    fun retry(jobId: String) = run("Retrying.", close = false) { controller.retry(jobId) }
    fun backupNow(root: SyncRoot) = run(close = false) {
        m.notify(when {
            !controller.backupNow(root.id) -> return@run m.notify("Your cloud storage is full. ${if (root.isPhotos) "New photos are" else "${root.localName} is"} backed up once space is freed.", "error")
            root.id in runtime.waitingForWifi -> "${if (root.isPhotos) "Photos are" else "${root.localName} is"} backed up when this phone is on Wi-Fi."
            else -> "Backing up ${if (root.isPhotos) "photos" else root.localName} now."
        })
    }
    fun archive(root: SyncRoot) = m.confirm(Confirmation("Archive ${root.localName}?",
        "harbor0 makes one last full backup, then removes the local files whose content is saved. Files that keep changing are left in place. Restore the folder at any time to download everything again.",
        "Archive folder", "Archiving ${root.localName}. Local files are removed once everything is saved.", cancel = "Cancel") {
        controller.archive(root.id, true); m.backups.refresh()
    })
    fun restoreArchive(root: SyncRoot) = run("Restoring ${root.localName}. Files download in the background.", close = false) { controller.archive(root.id, false); m.backups.refresh() }
    fun disconnectBackup(root: SyncRoot) = m.confirm(Confirmation("Disconnect ${root.localName}?",
        if (root.isPhotos) "Stop backing up photos from this phone. Every saved photo and version stays in My Drive and becomes editable. Nothing on this phone is deleted."
        else "Stop backing up this folder. All archived files and versions remain in Cloud and become editable. Local files stay where they are.",
        "Disconnect backup", "Backup disconnected. Its folder and versions are now in Cloud.", danger = false, cancel = "Cancel") {
        controller.disconnectBackup(root.id); m.backups.close(); m.backups.refresh()
    })

    // Sharing -------------------------------------------------------------------------------------
    fun members(folderId: String) = invitations.data.orEmpty().filter { it.direction == "SENT" && it.driveItemId == folderId && it.revokedAt == null }
    fun invite(folderId: String, recipient: String) = m.action("Invitation sent. Sync starts after they accept and choose a local folder.") {
        m.api.request("/v1/sync/shares", "POST", buildJsonObject {
            put("operationId", UUID.randomUUID().toString()); put("driveItemId", folderId); put("recipient", recipientJson(recipient))
        })
        refresh()
    }
    fun removeMember(member: SyncInvitation) = m.confirm(Confirmation("Remove access for ${member.recipient.displayName.ifBlank { "@" + member.recipient.username }}?",
        "Sync will stop on their devices when they reconnect. Files already downloaded will remain.", "Remove access", "Access removed. Their existing local files are preserved.", cancel = "Keep access") {
        m.api.request("/v1/shares/${HarborApi.segment(member.id)}", "DELETE", operation()); refresh()
    })
}
