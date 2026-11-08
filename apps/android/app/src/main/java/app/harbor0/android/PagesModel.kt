package app.harbor0.android

import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong

/** Trash: a paged search of deleted items with restore, permanent deletion and Empty Trash. */
class TrashState(private val m: WorkspaceModel) {
    private val api get() = m.api
    var items by mutableStateOf<List<DriveItem>>(emptyList()); private set
    var page by mutableStateOf<String?>(null); private set
    var nextCursor by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    var loadError by mutableStateOf<String?>(null); private set
    var selected by mutableStateOf<Set<String>>(emptySet())
    var grid by mutableStateOf(false)
    var loadedQuery by mutableStateOf<String?>(null); private set
    private var job: Job? = null
    fun reset() { job?.cancel(); items = emptyList(); page = null; nextCursor = null; loaded = false; loadError = null; selected = emptySet() }
    fun refresh(cursor: String? = page) {
        job?.cancel()
        if (cursor != page) { loaded = false; items = emptyList() }
        page = cursor
        val query = m.query.trim().takeIf { it.isNotEmpty() }
        loadedQuery = query.orEmpty()
        job = m.viewModelScope.launch {
            loading = true; loadError = null
            try {
                val result = api.get<Page<DriveItem>>(withQuery("/v1/search", "q" to query, "trash" to "true", "cursor" to cursor))
                items = result.items; nextCursor = result.nextCursor; loaded = true
                selected = selected.filter { id -> result.items.any { it.id == id } }.toSet()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { loadError = m.message(e) }
            finally { loading = false }
        }
    }
    val selection get() = items.filter { it.id in selected }
    fun restore(targets: List<DriveItem>) = m.action(close = true) {
        val failed = bulk(targets, { it.name }, m::message) { item ->
            api.request("/v1/drive/items/${HarborApi.segment(item.id)}/restore", "POST", api.mutation(item))
            items = items.filter { it.id != item.id }
        }
        selected = emptySet(); refresh(); m.drive.refresh()
        failed?.let(::error)
    }
    fun deletePermanently(targets: List<DriveItem>) = m.confirm(Confirmation(
        if (targets.size > 1) "Delete ${targets.size} items permanently?" else "Delete ${targets.firstOrNull()?.name ?: "this item"} permanently?",
        "This removes the selected items and their version history, and cannot be undone. Content needed by your sent transfers remains stored and counted until those transfers are cancelled or expire.",
        "Delete permanently", cancel = "Cancel") {
        val failed = bulk(targets, { it.name }, m::message, { "delete $it" }) { item ->
            api.request("/v1/drive/items/${HarborApi.segment(item.id)}/permanent", "DELETE", api.mutation(item))
            items = items.filter { it.id != item.id }
        }
        selected = emptySet(); refresh(); m.refreshAccount()
        failed?.let(::error)
    })
    fun empty() = m.confirm(Confirmation("Empty Trash?",
        "Permanently delete all items in Trash, including items on other pages and their version history? This cannot be undone. Content needed by sent transfers remains stored until those transfers end.",
        "Empty Trash", "Trash emptied.", cancel = "Cancel") {
        api.emptyTrash(); selected = emptySet(); refresh(null); m.refreshAccount()
    })
}

/** Shared: transfers received or sent, with the shared-access list underneath, and public links. */
class SharedState(private val m: WorkspaceModel) {
    private val api get() = m.api
    val links = PublicLinksState(m)
    var tab by mutableStateOf("Received"); private set
    var transfers by mutableStateOf(Loadable<List<Transfer>>()); private set
    var page by mutableStateOf<String?>(null); private set
    var nextCursor by mutableStateOf<String?>(null); private set
    var shares by mutableStateOf(Loadable<List<Share>>()); private set
    /** Extra manifest entries loaded with “Show more files”, and the cursor for the next page. */
    var entries by mutableStateOf<Map<String, Pair<List<ManifestEntry>, String?>>>(emptyMap()); private set
    var entriesLoading by mutableStateOf<String?>(null); private set
    private var job: Job? = null
    fun reset() { job?.cancel(); tab = "Received"; transfers = Loadable(); shares = Loadable(); page = null; nextCursor = null; entries = emptyMap(); links.reset() }
    fun select(next: String) { if (next == tab) return; tab = next; page = null; transfers = Loadable(); shares = Loadable(); entries = emptyMap(); refresh() }
    fun refresh(cursor: String? = page) {
        job?.cancel()
        if (tab == "Links") { if (links.enabled) links.refresh() else select("Received"); return }
        if (cursor != page) transfers = Loadable()
        page = cursor
        val direction = tab.lowercase()
        job = m.viewModelScope.launch {
            transfers = transfers.copy(loading = true, error = null)
            try {
                val result = api.get<Page<Transfer>>(withQuery("/v1/transfers/$direction", "cursor" to cursor))
                transfers = Loadable(result.items); nextCursor = result.nextCursor
            } catch (e: CancellationException) { throw e } catch (e: Exception) { transfers = transfers.copy(loading = false, error = m.message(e)) }
            shares = shares.copy(loading = true, error = null)
            try { shares = Loadable(api.get<Page<Share>>("/v1/shares/$direction").items.filter { it.item != null && it.revokedAt == null }) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { shares = shares.copy(loading = false, error = m.message(e)) }
        }
    }
    fun moreEntries(transfer: Transfer) {
        val cursor = entries[transfer.id]?.second ?: transfer.nextEntryCursor ?: return
        entriesLoading = transfer.id
        m.viewModelScope.launch {
            try {
                val result = api.get<Page<ManifestEntry>>(withQuery("/v1/transfers/${HarborApi.segment(transfer.id)}/items", "cursor" to cursor))
                entries = entries + (transfer.id to ((entries[transfer.id]?.first.orEmpty() + result.items) to result.nextCursor))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { m.report(e) }
            finally { entriesLoading = null }
        }
    }
    fun act(transfer: Transfer, action: String) {
        val path = "/v1/transfers/${HarborApi.segment(transfer.id)}/$action"
        // Declining or cancelling cannot be undone, so ask first.
        if (action == "decline" || action == "cancel") return m.confirm(Confirmation(
            if (action == "decline") "Decline this transfer?" else "Cancel this transfer?",
            if (action == "decline") "You won’t be able to download these files unless they are sent again." else "The recipient will no longer be able to accept or download these files.",
            if (action == "decline") "Decline transfer" else "Cancel transfer", if (action == "decline") "Transfer declined." else "Transfer cancelled.") {
            api.request(path, "POST", operation()); refresh()
        })
        m.action(if (action == "save") "Saving to My Drive. Large folders finish in the background." else "Transfer accepted. You can now download or save the files.") {
            api.request(path, "POST", buildJsonObject {
                operation().forEach { (k, v) -> put(k, v) }
                if (action == "save") put("targetParentId", JsonNull)
            })
            refresh()
        }
    }
    fun download(transfer: Transfer, entry: ManifestEntry) = m.downloadToDevice(entry.displayName, buildJsonObject {
        put("transferId", transfer.id); put("entryId", entry.id)
    }, entry.mimeType)
    fun removeAccess(share: Share) = m.confirm(Confirmation("Remove access to ${share.item?.name}?",
        "The person you shared this with will no longer be able to open it.", "Remove access", "Access removed.") {
        api.request("/v1/shares/${HarborApi.segment(share.id)}", "DELETE", operation()); refresh()
    })
}
fun transferStatus(t: Transfer, received: Boolean): Pair<String, Tone> {
    val label = when {
        t.preparationState == "BUILDING" -> "Preparing files"
        t.preparationState == "FAILED" -> "Preparation failed"
        t.saveState == "SAVING" -> "Saving…"
        t.savedAt != null -> "Saved to My Drive"
        else -> when (t.state) {
            "PENDING_RECIPIENT_SIGNUP" -> "Waiting for sign-up"; "PENDING" -> if (received) "Waiting for you" else "Waiting for recipient"
            "ACCEPTED" -> "Accepted"; "DECLINED" -> "Declined"; "CANCELLED" -> "Cancelled"; "EXPIRED" -> "Expired"; else -> t.state
        }
    }
    val tone = when { t.preparationState == "FAILED" -> Tone.Danger; t.state == "ACCEPTED" -> Tone.Success; t.state.startsWith("PENDING") -> Tone.Accent; else -> Tone.Neutral }
    return label to tone
}

/** Relative time as the web backups page shows it: “5 minutes ago”, “yesterday”, “on Sep 30, 2026”. */
fun ago(iso: String, now: Long = System.currentTimeMillis()): String {
    val time = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull() ?: return iso
    val minutes = ((time - now) / 60000.0).roundToLong()
    fun unit(value: Long, name: String) = if (value < 0) (if (value == -1L && name == "day") "yesterday" else "${-value} $name${if (value == -1L) "" else "s"} ago")
        else (if (value == 1L && name == "day") "tomorrow" else "in $value $name${if (value == 1L) "" else "s"}")
    if (abs(minutes) < 1) return "just now"
    if (abs(minutes) < 60) return unit(minutes, "minute")
    val hours = (minutes / 60.0).roundToLong()
    if (abs(hours) < 24) return unit(hours, "hour")
    val days = (hours / 24.0).roundToLong()
    return if (abs(days) < 7) unit(days, "day") else "on ${dateLabel(iso, time = true)}"
}

/** Restores of a backup still being worked through: how many of the current batch are done. */
data class RestoreBatch(val done: Int, val total: Int) {
    val pending get() = total - done
    val fraction get() = if (total == 0) 0f else done.toFloat() / total
    /** As the web backup panel: getting ready, then “Restored X of Y files…”. */
    val detail get() = when {
        done == 0 -> "Getting ready to restore ${plural(pending, "file")}…"
        total == 1 -> "Restoring 1 file…"
        else -> "Restored ${"%,d".format(Locale.US, done)} of ${"%,d".format(Locale.US, total)} files…"
    }
}
/** The batch of restores that began with the oldest one still waiting; null when none are waiting. */
fun restoreProgress(restores: List<BackupRestore>): RestoreBatch? {
    val pending = restores.filter { it.state == "PENDING" }
    if (pending.isEmpty()) return null
    val since = pending.minOf { it.requestedAt }
    val done = restores.count { it.state != "PENDING" && it.requestedAt >= since }
    return RestoreBatch(done, done + pending.size)
}
data class BackupStatus(val label: String, val tone: Tone, val detail: String, val fraction: Float? = null,
    /** This phone's run under way: its id and the bytes shown as saved, which later statuses never show fewer of. */
    val saved: Pair<String, Long>? = null)
/**
 * A backup folder's status badge and explanation, as the web `BackupFolderPanel`. [local] is the
 * folder on this phone when it was backed up from here; [runs] is null until history has loaded.
 * For a folder on this phone, [runtime] is the engine's own state: its restores file by file
 * (desktop `restoreShown`), and whether the folder waits for cloud storage.
 */
fun backupStatus(root: BackupRoot, runs: List<BackupRun>?, restores: List<BackupRestore>, local: SyncRoot? = null, devicePaused: Boolean = false,
    runtime: SyncRuntime? = null, waiting: Int = 0, waitMinutes: Int = backupSettings.waitMinutes, shown: Pair<String, Long>? = null): BackupStatus {
    val device = if (local != null) "this phone" else root.deviceName ?: "the source computer"
    val latest = runs?.firstOrNull()
    val good = runs?.firstOrNull { it.state == "COMPLETED" || it.state == "PARTIAL" }
    val archived = root.state == "ARCHIVED" || (local?.archive != null && local.archive != "pending")
    val restoring = restoreProgress(restores)?.takeIf { local != null }
    val live = local?.let { runtime?.restoring?.get(it.id) }
    val storage = if (local != null && runtime != null) backupStorageLabel(local, runtime) else null
    // This phone's run as the engine sees it: the server's run only counts files once they are recorded (a batch at a
    // time, read every 15 s), so the bytes on their way are added in.
    val run = local?.let { runtime?.backupRuns?.get(it.id) }
    val sending = runtime?.active?.takeIf { local != null && it.rootId == local.id && it.direction == "upload" }
    return when {
        root.state == "REMOVED" -> BackupStatus("Stopped", Tone.Neutral, "No new versions are saved. The backed-up files are now regular files in My Drive.")
        local?.archive == "restoring" -> BackupStatus("Restoring", Tone.Accent, "Putting the folder back on this phone. Backups resume when it’s done.")
        local?.archive == "removing" -> BackupStatus("Archiving", Tone.Accent, "Everything is saved. Removing the copy on this phone…")
        archived -> BackupStatus("Archived", Tone.Neutral, "Backups from $device are stopped. Every backed-up file and version is kept here, read-only.")
        local?.archive == "pending" -> BackupStatus("Archiving", Tone.Accent, "Saving a final backup. The copy on this phone is removed once every file is saved.")
        if (local != null) local.paused || devicePaused else root.state == "PAUSED" ->
            BackupStatus("Paused", Tone.Neutral, "No new versions are saved until you resume. Saved versions are still here.")
        live != null -> BackupStatus("Restoring", Tone.Accent,
            if (live.total == 1) "Restoring ${baseName(live.relativePath)}…" else "Restoring ${live.done + 1} of ${live.total} files…",
            minOf(.99f, (live.done + if (live.size > 0) live.loaded.toFloat() / live.size else 0f) / maxOf(1, live.total)))
        restoring != null -> BackupStatus("Restoring", Tone.Accent, restoring.detail, restoring.fraction)
        // Photos on mobile data: [waiting] files (or a run under way) wait for Wi-Fi.
        local != null && runtime != null && local.id in runtime.waitingForWifi && (waiting > 0 || latest?.state == "RUNNING") ->
            BackupStatus(PHOTOS_WIFI, Tone.Neutral, "${plural(maxOf(waiting, 1), "file")} ${if (waiting == 1) "is" else "are"} saved when this phone is on Wi-Fi.")
        run != null && (latest?.state == "RUNNING" || sending != null) -> {
            val counted = latest?.takeIf { it.state == "RUNNING" }
            val plannedBytes = counted?.plannedSizeBytes
            // The server records a finished file a moment after the next one starts: the total never goes back meanwhile.
            val saved = maxOf((counted?.sizeBytes ?: 0) + (sending?.loaded ?: 0), shown?.takeIf { counted != null && it.first == counted.id }?.second ?: 0)
            val done = maxOf(run.done, counted?.fileCount ?: 0).coerceAtMost(run.total)
            val bytes = when {
                plannedBytes != null && plannedBytes > 0 -> " · ${bytesLabel(minOf(saved, plannedBytes))} of ${bytesLabel(plannedBytes)}"
                sending != null && sending.total > 0 -> " · ${bytesLabel(sending.loaded)} of ${bytesLabel(sending.total)} sending"
                else -> ""
            }
            val partial = sending?.takeIf { it.total > 0 }?.let { it.loaded.toFloat() / it.total } ?: 0f
            BackupStatus("Backing up", Tone.Accent, "Saving ${"%,d".format(Locale.US, minOf(done + 1, run.total))} of ${plural(run.total, "file")}$bytes",
                if (plannedBytes != null && plannedBytes > 0) minOf(.99f, saved.toFloat() / plannedBytes) else minOf(.99f, (done + partial) / maxOf(1, run.total)),
                counted?.let { it.id to saved })
        }
        latest?.state == "RUNNING" -> {
            val planned = latest.plannedFileCount
            val plannedBytes = latest.plannedSizeBytes
            if (planned != null && plannedBytes != null) BackupStatus("Backing up", Tone.Accent,
                "Saved ${"%,d".format(Locale.US, latest.fileCount)} of ${plural(planned, "file")} · ${bytesLabel(latest.sizeBytes)} of ${bytesLabel(plannedBytes)}",
                if (plannedBytes > 0) latest.sizeBytes.toFloat() / plannedBytes else if (planned > 0) latest.fileCount.toFloat() / planned else null)
            else BackupStatus("Backing up", Tone.Accent, "Saving ${plural(latest.fileCount, "file")} so far…")
        }
        storage != null -> BackupStatus(storage.first, Tone.Danger, storage.second)
        root.state == "ERROR" || latest?.state == "FAILED" -> BackupStatus("Needs attention", Tone.Danger,
            latest?.error?.let { "The last backup didn’t finish: $it" } ?: "The last backup didn’t finish. It will try again automatically.")
        good != null -> BackupStatus(if (latest?.state == "PARTIAL") "Some files skipped" else "Backed up", if (latest?.state == "PARTIAL") Tone.Danger else Tone.Success,
            "Last backed up ${ago(good.completedAt ?: good.startedAt)}.")
        else -> BackupStatus("Waiting", Tone.Success, if (runs == null) "Checking backup status…" else if (local?.isPhotos == true) "The first backup starts in a moment."
            // Another device's wait isn't known here.
            else "The first backup runs once files have been unchanged for ${if (local != null) backupWaitLabel(waitMinutes) else "a few minutes"}.")
    }
}

/**
 * The backup folder open in My Drive: its status, runs and restores, as the web `BackupFolderPanel`
 * shown above a backup's files. The Backups page itself moved into My Drive → Backups.
 */
class BackupsState(private val m: WorkspaceModel) {
    private val api get() = m.api
    /** Every backup root, including stopped ones, from the last refresh. */
    var roots by mutableStateOf<List<BackupRoot>>(emptyList()); private set
    /** The open folder; the panel shows when it is the top folder of a backup. */
    var folderId by mutableStateOf<String?>(null); private set
    var runs by mutableStateOf<List<BackupRun>?>(null); private set
    var restores by mutableStateOf<List<BackupRestore>>(emptyList()); private set
    private var historyFor: String? = null
    var expandedRun by mutableStateOf<String?>(null); private set
    var runFiles by mutableStateOf<List<BackupEntry>?>(null); private set
    var loadError by mutableStateOf<String?>(null); private set
    private var jobs = mutableListOf<Job>()
    val root get() = folderId?.let { id -> (roots + m.drive.allBackupRoots).firstOrNull { it.remoteRootDriveItemId == id } }
    val removed get() = root?.state == "REMOVED"
    val archived get() = root?.state == "ARCHIVED" || local?.archive.let { it != null && it != "pending" }
    val local get() = root?.let { m.sync.localBackup(it) }
    fun reset() { close(); roots = emptyList(); loadError = null }
    fun close() { jobs.forEach { it.cancel() }; jobs.clear(); folderId = null; runs = null; restores = emptyList(); historyFor = null; expandedRun = null; runFiles = null }
    private fun launch(block: suspend CoroutineScope.() -> Unit) { jobs.removeAll { !it.isActive }; jobs += m.viewModelScope.launch(block = block) }
    /** Follows the folder open in My Drive; loads its history when it is a backup's top folder. */
    fun watch(id: String?) {
        if (id != folderId) { close(); folderId = id }
        // The backup list may arrive after the folder opened; history loads once the folder is known to be a backup.
        if (id != null && root != null && historyFor != root?.id) refresh()
    }
    fun refresh() {
        launch {
            try {
                roots = api.all<BackupRoot>("/v1/backups"); loadError = null
                loadHistory()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { loadError = m.message(e) }
        }
    }
    private suspend fun loadHistory() {
        val id = root?.id ?: return
        val base = "/v1/backups/${HarborApi.segment(id)}"
        val history = api.all<BackupRun>("$base/runs").sortedByDescending { it.startedAt }
        val recovery = api.all<BackupRestore>("$base/restores").sortedByDescending { it.requestedAt }
        if (root?.id != id) return
        runs = history; restores = recovery; historyFor = id
    }
    /** History only shows loading placeholders when the folder changes, never on background refreshes. */
    val historyLoading get() = historyFor == null || historyFor != root?.id
    val waiting get() = if (historyLoading) 0 else restores.count { it.state == "PENDING" }
    // The most this phone's run under way has shown as saved (see BackupStatus.saved). Not state: it only steadies what is shown.
    private var shownSaved: Pair<String, Long>? = null
    fun status(): BackupStatus? = root?.let { backupStatus(it, runs.takeUnless { historyLoading }, if (historyLoading) emptyList() else restores, local, m.sync.runtime.paused,
        m.sync.runtime, local?.let { l -> m.sync.jobs.count { j -> j.rootId == l.id } } ?: 0, m.sync.backupChoices.waitMinutes, shownSaved) }
        ?.also { status -> status.saved?.let { shownSaved = it } }
    fun toggleRun(run: BackupRun) {
        if (expandedRun == run.id) { expandedRun = null; return }
        expandedRun = run.id; runFiles = null
        val id = root?.id ?: return
        launch {
            try { runFiles = api.all<BackupEntry>("/v1/backups/${HarborApi.segment(id)}/runs/${HarborApi.segment(run.id)}/files") }
            catch (e: CancellationException) { throw e } catch (e: Exception) { m.report(e) }
        }
    }
    val name get() = root?.localPathDisplayName ?: "this folder"
    val deviceName get() = if (local != null) "this phone" else root?.deviceName ?: "the source computer"
    fun stop() {
        val r = root ?: return
        m.confirm(Confirmation("Stop backing up ${r.localPathDisplayName}?",
            "No new versions will be saved. Everything already backed up stays in My Drive as regular files. Nothing on your computer is deleted.",
            "Stop backing up", "Stopped backing up ${r.localPathDisplayName}. Its files stay in My Drive.", cancel = "Keep backing up") {
            api.request("/v1/backups/${HarborApi.segment(r.id)}", "DELETE"); refresh(); m.drive.refresh()
        })
    }
    fun remove() {
        val r = root ?: return
        m.confirm(Confirmation("Remove ${r.localPathDisplayName} from Backups?",
            "This removes the folder’s backup history. Its files stay in My Drive, and nothing on your computer is deleted.",
            "Remove", "Removed ${r.localPathDisplayName} from Backups.", cancel = "Cancel") {
            api.request("/v1/backups/${HarborApi.segment(r.id)}/forget", "POST"); expandedRun = null; refresh(); m.drive.refresh()
        })
    }
    /** Puts one saved version back on the device the backup came from. */
    fun restore(itemId: String, versionId: String, name: String, createdAt: String) {
        val r = root ?: return
        val requestId = UUID.randomUUID().toString()
        m.confirm(Confirmation("Restore $name?", "The copy of $name on $deviceName will be replaced with the version from ${dateLabel(createdAt, time = true)}.",
            "Restore", "Restoring $name. Follow its progress in History.", danger = false, cancel = "Cancel",
            note = (if (local != null) "Any changes made to the file since then will be replaced. " else "If $deviceName is offline, the restore happens the next time it’s online. ") +
                "To keep both, download this version instead.") {
            api.request("/v1/backups/${HarborApi.segment(r.id)}/restores", "POST", buildJsonObject {
                put("id", requestId); put("itemId", itemId); put("versionId", versionId)
            })
            // Backed up from this phone: it picks the restore up now rather than at its next check.
            if (local != null) m.syncController.restoreSoon()
            refresh()
        })
    }
    fun download(itemId: String, versionId: String, name: String) = m.downloadToDevice(name, buildJsonObject {
        put("driveItemId", itemId); put("versionId", versionId)
    }, null)
    suspend fun versions(item: DriveItem) = m.drive.versions(item)
}

/** Search results shown in place on pages without their own search (Shared, Backups, Devices…), as the web does. */
class SearchState(private val m: WorkspaceModel) {
    private val api get() = m.api
    var items by mutableStateOf<List<DriveItem>>(emptyList()); private set
    var page by mutableStateOf<String?>(null); private set
    var nextCursor by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    var loadError by mutableStateOf<String?>(null); private set
    var loadedQuery by mutableStateOf<String?>(null); private set
    var grid by mutableStateOf(false)
    private var job: Job? = null
    fun reset() { job?.cancel(); items = emptyList(); page = null; nextCursor = null; loaded = false; loadError = null; loadedQuery = null; loading = false }
    fun refresh(cursor: String? = page) {
        val query = m.query.trim()
        job?.cancel()
        if (query.isEmpty()) return reset()
        if (query != loadedQuery || cursor != page) { loaded = false; items = emptyList() }
        page = cursor; loadedQuery = query
        job = m.viewModelScope.launch {
            loading = true; loadError = null
            try {
                val result = api.get<Page<DriveItem>>(withQuery("/v1/search", "q" to query, "cursor" to cursor))
                items = result.items; nextCursor = result.nextCursor; loaded = true
            } catch (e: CancellationException) { throw e } catch (e: Exception) { loadError = m.message(e) }
            finally { loading = false }
        }
    }
}
