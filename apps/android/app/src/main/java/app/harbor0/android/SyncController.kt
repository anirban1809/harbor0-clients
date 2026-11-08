package app.harbor0.android

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Process-wide services shared by the UI and background work. */
object Harbor {
    @Volatile private var api: HarborApi? = null
    @Volatile private var sync: SyncController? = null
    @Volatile private var vault: E2eeVault? = null
    /** The signed-in account's end-to-end encryption keys, shared by the app and background sync. */
    fun vault(context: Context): E2eeVault = vault ?: synchronized(this) {
        vault ?: E2eeVault(KeystoreE2eeCopies(context.applicationContext)).also { vault = it }
    }
    fun api(context: Context): HarborApi = api ?: synchronized(this) {
        api ?: HarborApi(BuildConfig.API_URL, KeystoreCredentials(context.applicationContext), deviceKeys = KeystoreDeviceKeys(context.applicationContext)).also {
            // Every request goes through the vault: names and sizes are plaintext everywhere in the app.
            it.transport = vault(context).sealed(it.direct)
            api = it
        }
    }
    fun sync(context: Context): SyncController = sync ?: synchronized(this) {
        sync ?: SyncController(context.applicationContext, api(context), vault(context)).also { sync = it }
    }
}
/** What sync says while an encrypted account is locked on this phone (desktop `engine.hold`). */
const val LOCKED_HOLD = "Unlock your files to back up photos. Nothing is backed up until then."

/** Where a new sync folder lives in the cloud, as the desktop `addSyncRoot` options. */
sealed interface CloudChoice {
    /** Keep the current folder, or create one named after the local folder (numbered if the name is taken). */
    data object Automatic: CloudChoice
    data class Existing(val id: String): CloudChoice
    data class NewIn(val parentId: String?, val name: String): CloudChoice
}

/**
 * Runs the sync engine for the signed-in account: registers this phone as a sync device, keeps the engine running
 * while the process is alive, schedules periodic background passes, and carries out folder setup (desktop main.ts).
 */
class SyncController(private val context: Context, val api: HarborApi, val vault: E2eeVault = E2eeVault(MemoryE2eeCopies())) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var relay: Job? = null
    private var account: String? = null
    @Volatile var engine: SyncEngine? = null; private set
    private val _view = MutableStateFlow<SyncView?>(null)
    /** Null until sync has started for the signed-in account. */
    val view: StateFlow<SyncView?> = _view.asStateFlow()
    private val _startError = MutableStateFlow<String?>(null)
    val startError: StateFlow<String?> = _startError.asStateFlow()
    var foreground = true
        set(value) {
            field = value; engine?.foreground = value; if (value) engine?.wake()
            // Leaving the app: the last moment the service may still be started without a background exemption.
            if (!value && backgroundWork(_view.value, minSync = LARGE_SYNC_FILES) != null) keepBackingUp()
        }
    @Volatile private var serviceAsked = 0L
    /** Starts the "Backing up" / "Syncing" service at most every 30 s; WorkManager keeps the one already running. */
    private fun keepBackingUp() {
        val now = System.currentTimeMillis()
        if (now - serviceAsked < 30_000) return
        serviceAsked = now
        runCatching { BackupWorker.start(context) }
    }
    /**
     * The engine's view of the API: its own share of the request budget, with retries. Shared by the engine and its
     * transfers. Its transport is strict: while an encrypted account is locked, file requests are refused rather than
     * handing sync encrypted names.
     */
    private val engineApi by lazy { EngineApi(api, transport = vault.sealed(api.direct, strict = true)) }
    private val transfers by lazy { SyncTransfers(engineApi, keys = vault.transferKeys()) }
    private var accountCheck: Job? = null

    // This phone's backup choices, kept apart from any account: they stay as set across sign-ins (desktop backup-settings.json).
    private val backupPreferences get() = context.getSharedPreferences("backup-settings", Context.MODE_PRIVATE)

    init {
        // Read before any engine starts, so the first backup pass already uses them.
        backupSettings = readBackupSettings()
        // Locking or unlocking changes what the engine may do at once: it pauses, or picks up where it stopped.
        scope.launch {
            var locked: Boolean? = null
            vault.view.collect { view ->
                val now = view.enabled && view.keys == null
                if (now != locked) { locked = now; engine?.wake() }
            }
        }
    }
    val deviceName: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(100).ifEmpty { "Android phone" }

    /** Starts sync for [userId]; returns the running engine. Safe to call repeatedly. */
    suspend fun connect(userId: String): SyncEngine = lock.withLock {
        engine?.takeIf { account == userId }?.let { return it }
        stopLocked()
        val journal = SyncJournal(context, userId)
        // The backend's device id is this session's id; registering keeps the proven installation identity.
        val deviceId = try {
            val device = harborJson.decodeFromJsonElement<DeviceResponse>(api.request("/v1/sync/devices/register", "POST", buildJsonObject {
                put("name", deviceName); put("platform", "ANDROID"); put("appVersion", BuildConfig.VERSION_NAME.take(32))
            })).device
            journal.set("deviceId", device.id); journal.set("deviceName", device.name)
            device.devicePublicId?.let { journal.set("devicePublicId", it) }
            device.id
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            // An encrypted account can't sync on this version; starting from the saved id would only repeat the refusal.
            if (e is ApiException && e.code == "E2EE_UPDATE_REQUIRED") { journal.close(); throw e }
            journal.get<String>("deviceId") ?: run { journal.close(); _startError.value = message(e); throw e }
        }
        _startError.value = null
        // Keep the last known cloud location while offline, or while locked, when names can't be read.
        if (!vault.locked) for (root in journal.roots()) runCatching { journal.root(root.copy(cloudPath = if (root.shareId != null) root.cloudPath else cloudLocation(root.remoteId))) }
        val created = SyncEngine(engineApi, journal, deviceId, { localTree(context, it) }, transfers, scope) {
            journal.get<String>("deviceName") ?: deviceName
        }
        created.foreground = foreground
        created.hold = { if (vault.locked) LOCKED_HOLD else null }
        val network = context.getSystemService(ConnectivityManager::class.java)
        created.metered = { meteredNow(network) }
        // Only this phone may change its backups: they follow encryption being turned on or off.
        created.backupConversion = BackupConversion({
            val view = vault.current()
            if (vault.uploadKeys() != null) "encrypted" else if (view.decrypting && vault.readKeys() != null) "plain" else null
        }, engineApi.raw(), java.io.File(context.cacheDir, "convert"))
        account = userId; engine = created
        relay = scope.launch {
            var photos: Boolean? = null
            created.view.collect { view ->
                _view.value = view
                // A photo backup added or gone (disconnected elsewhere, say): the library is watched only while there is one.
                val now = view.roots.any { it.isPhotos }
                if (now != photos) { if (photos != null) armPhotos(); photos = now; watchPhotos(now) }
                // A backup or large sync under way keeps going once the app is left: the service can only be started
                // from here while the app is on screen (or by a background pass when Android allows it).
                if (foreground && backgroundWork(view, minSync = LARGE_SYNC_FILES) != null) keepBackingUp()
            }
        }
        // Encryption turned on, off or being turned off on the web reaches this phone through the account.
        accountCheck = scope.launch {
            while (isActive) {
                delay(60_000)
                if (vault.current().enabled) try { api.request("/v1/users/me") } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
        }
        created.start()
        retireFolders(created)
        schedule(journal.roots().isNotEmpty())
        watchPhotos()
        created
    }
    /**
     * Phones only back up their photos. Synced folders and folder backups set up by an earlier version stop on this phone:
     * local files and everything in the cloud stay, and other devices keep syncing.
     */
    private suspend fun retireFolders(engine: SyncEngine) {
        for (root in engine.journal.roots().filter { !it.isPhotos }) {
            runCatching { engine.removeRoot(root.id) }.onSuccess { releaseIfUnused(Uri.parse(root.treeUri)) }
        }
    }
    /** Stops sync, e.g. on sign-out. Local files, the journal and queued work are kept. */
    suspend fun disconnect(cancelBackground: Boolean = true) = lock.withLock {
        stopLocked()
        if (cancelBackground) WorkManager.getInstance(context).apply { cancelUniqueWork(WORK); cancelUniqueWork(PHOTO_WORK); cancelUniqueWork(BackupWorker.WORK) }
    }
    private suspend fun stopLocked() {
        engine?.let { it.stop(); it.journal.close() }
        watchPhotos(false)
        relay?.cancel(); relay = null
        accountCheck?.cancel(); accountCheck = null
        engine = null; account = null; _view.value = null
    }
    fun message(e: Throwable) = if (e is java.io.IOException) "Could not connect. Check your connection and try again." else e.message ?: "This action could not be completed. Try again."
    private fun running() = engine ?: throw IllegalStateException("Sync is starting. Try again in a moment.")

    /** Background passes run about every 15 minutes while any folder is set up; Android may run them later. */
    fun schedule(enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        armPhotos()
        if (!enabled) { work.cancelUniqueWork(WORK); return }
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
    }
    private fun photoRoot() = engine?.journal?.roots()?.firstOrNull { it.isPhotos }
    /**
     * A background pass about a minute after new photos or videos appear in the library (on Wi-Fi only unless the
     * backup may use mobile data), armed again after each run. [policy]: REPLACE when the options changed.
     */
    fun armPhotos(policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val work = WorkManager.getInstance(context)
        val options = photoRoot()?.photos ?: run { work.cancelUniqueWork(PHOTO_WORK); return }
        work.enqueueUniqueWork(PHOTO_WORK, policy, OneTimeWorkRequestBuilder<SyncWorker>().setInputData(workDataOf(PHOTOS_INPUT to true))
            .setConstraints(photoConstraints(options)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
    }
    // While the app process lives, a change in the library wakes the engine (debounced) rather than waiting for its next scan.
    private var observer: ContentObserver? = null
    private var photoWake: Job? = null
    private var networkWatch: ConnectivityManager.NetworkCallback? = null
    @Synchronized private fun watchPhotos(wanted: Boolean = photoRoot() != null) {
        if (wanted == (observer != null)) return
        val resolver = context.contentResolver
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        observer?.let { resolver.unregisterContentObserver(it) }; observer = null
        networkWatch?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }; networkWatch = null
        if (!wanted) return
        // Photos waiting for Wi-Fi go as soon as the phone is on it, and stop between files when it leaves.
        networkWatch = object: ConnectivityManager.NetworkCallback() {
            private var last: Boolean? = null
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                val now = meteredNow(connectivity)
                if (now != last) { last = now; engine?.nudge() }
            }
            override fun onLost(network: android.net.Network) { last = null; engine?.nudge() }
        }.also { watch -> runCatching { connectivity?.registerDefaultNetworkCallback(watch) } }
        observer = object: ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { photoWake?.cancel(); photoWake = scope.launch { delay(3_000); engine?.libraryChanged() } }
        }.also { watcher -> photoCollections.forEach { runCatching { resolver.registerContentObserver(it, true, watcher) } } }
    }

    /** "My Drive / a / b" for a cloud folder. */
    suspend fun cloudLocation(id: String?): String {
        val names = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var current = id
        while (current != null) {
            check(seen.add(current) && seen.size < 64) { "Folder path is unavailable." }
            val item = api.get<ItemResponse>("/v1/drive/items/${HarborApi.segment(current)}").item
            names.add(0, item.name); current = item.parentId
        }
        return (listOf("My Drive") + names).joinToString(" / ")
    }

    // Folder setup (desktop IPC handlers) -----------------------------------------------------------------
    private fun treeName(uri: Uri) = SafTree(context, uri).name
    /** Backs up a folder chosen with the system picker. */
    suspend fun addBackup(uri: Uri) {
        val engine = running()
        SafTree.persist(context, uri)
        try {
            val name = withContext(Dispatchers.IO) { treeName(uri) }.take(240)
            val probe = SyncRoot(UUID.randomUUID().toString(), uri.toString(), name, null, "backup")
            engine.validateRoot(probe, uri)
            val response = api.request("/v1/backups", "POST", buildJsonObject {
                put("operationId", UUID.randomUUID().toString()); put("deviceId", engine.deviceId); put("name", safeName(name))
            })["root"]!!.jsonObject
            engine.addRoot(probe.copy(remoteId = response["remoteRootDriveItemId"]!!.jsonPrimitive.content, backupId = response["id"]!!.jsonPrimitive.content,
                cloudPath = "Backups / $name"))
            schedule(true)
        } catch (e: Exception) { releaseIfUnused(uri); throw e }
    }
    /** Starts syncing a local folder with a cloud folder, or changes an existing folder's mapping. */
    suspend fun addSyncRoot(uri: Uri?, rootId: String? = null, cloud: CloudChoice = CloudChoice.Automatic, shareId: String? = null): String {
        val engine = running()
        val existing = rootId?.let { id -> engine.journal.root(id)?.takeIf { it.mode == "sync" } ?: throw IllegalStateException("Sync folder was not found.") }
        val selected = uri ?: existing?.treeUri?.let(Uri::parse) ?: throw IllegalStateException("Choose your local folder again.")
        if (uri != null) SafTree.persist(context, uri)
        try {
            val localName = withContext(Dispatchers.IO) { treeName(selected) }
            val automatic = cloud is CloudChoice.Automatic
            val remoteId: String? = when (cloud) { CloudChoice.Automatic -> existing?.remoteId; is CloudChoice.Existing -> cloud.id; is CloudChoice.NewIn -> cloud.parentId }
            val newFolderName = when {
                automatic && remoteId == null -> localName.take(200).ifBlank { "Synced folder" }
                cloud is CloudChoice.NewIn -> safeName(cloud.name.trim())
                else -> null
            }
            val shared = shareId?.let { api.request("/v1/sync/shares/${HarborApi.segment(it)}/status") }
            val sharedItem = shared?.get("item")?.let { harborJson.decodeFromJsonElement<DriveItem>(it) }
            if (shared != null && (rootId != null || cloud is CloudChoice.NewIn || remoteId != sharedItem?.id)) throw IllegalStateException("Choose the invited folder to start shared sync.")
            val parentPath = if (sharedItem != null) "Shared with me / ${sharedItem.name}" else cloudLocation(remoteId)
            if (!automatic && newFolderName == null && remoteId == null) throw IllegalStateException("Choose a cloud folder. My Drive itself cannot be selected.")
            val share = shared?.get("share")?.jsonObject
            var root = SyncRoot(existing?.id ?: UUID.randomUUID().toString(), selected.toString(), localName, remoteId, "sync", cloudPath = parentPath,
                shareId = share?.get("id")?.jsonPrimitive?.contentOrNull, sharedBy = share?.get("ownerUserId")?.jsonPrimitive?.contentOrNull,
                excluded = existing?.excluded.orEmpty())
            // Validate local overlap before creating a cloud folder.
            engine.validateRoot(if (newFolderName != null) root.copy(remoteId = "__new__") else root, selected)
            if (newFolderName != null) {
                // Synced files are kept in the cloud; every linked device downloads changes from there.
                val operationId = root.id
                suspend fun create(name: String, op: String) = harborJson.decodeFromJsonElement<ItemResponse>(api.request("/v1/drive/folders", "POST", buildJsonObject {
                    put("name", name); put("parentId", remoteId); put("operationId", op)
                })).item
                val item = try { create(newFolderName, operationId) } catch (e: ApiException) {
                    if (!automatic || e.code != "NAME_CONFLICT") throw e
                    // Number the folder like a file manager would. Each attempt uses its own stable operation so retries stay safe.
                    var found: DriveItem? = null
                    var number = 2
                    while (found == null) {
                        try { found = create("${newFolderName.take(190)} $number", numberedOperationId(operationId, number)) }
                        catch (numbered: ApiException) { if (number >= 50 || numbered.code != "NAME_CONFLICT") throw numbered }
                        number++
                    }
                    found
                }
                root = root.copy(remoteId = item.id, cloudPath = "$parentPath / ${item.name}")
            }
            if (existing != null) engine.updateRoot(root) else engine.addRoot(root)
            if (existing != null && existing.treeUri != root.treeUri) releaseIfUnused(Uri.parse(existing.treeUri))
            schedule(true)
            return root.id
        } catch (e: Exception) { if (uri != null) releaseIfUnused(uri); throw e }
    }
    /** Stops syncing one folder on this phone only; other devices keep syncing it. Local files stay. */
    suspend fun stopOnThisPhone(id: String) {
        val engine = running()
        val root = engine.journal.root(id) ?: throw IllegalStateException("Folder was not found.")
        engine.removeRoot(id)
        releaseIfUnused(Uri.parse(root.treeUri))
        schedule(engine.journal.roots().isNotEmpty())
    }
    /** Removes a folder from sync on every linked device. Local copies are preserved everywhere. */
    suspend fun removeEverywhere(folderId: String) {
        val engine = running()
        val uris = engine.journal.roots().filter { it.remoteId == folderId }.map { it.treeUri }
        engine.removeSyncedFolder(folderId)
        uris.forEach { releaseIfUnused(Uri.parse(it)) }
        schedule(engine.journal.roots().isNotEmpty())
    }
    suspend fun changeLocal(id: String, uri: Uri) {
        val engine = running()
        val root = engine.journal.root(id) ?: throw IllegalStateException("Folder was not found.")
        if (root.isPhotos) throw IllegalStateException("A photo backup always saves the photos on this phone.")
        if (root.mode == "sync") { addSyncRoot(uri, id); return }
        SafTree.persist(context, uri)
        try {
            val name = withContext(Dispatchers.IO) { treeName(uri) }
            engine.updateRoot(root.copy(treeUri = uri.toString(), localName = name, paused = false))
            releaseIfUnused(Uri.parse(root.treeUri))
        } catch (e: Exception) { releaseIfUnused(uri); throw e }
    }
    suspend fun rootSettings(id: String, paused: Boolean, excluded: List<String>) {
        val engine = running()
        val root = engine.journal.root(id) ?: throw IllegalStateException("Folder was not found.")
        require(excluded.size <= 1000 && excluded.all(::validRelative)) { "Enter folders inside this folder, like Photos/Old." }
        engine.updateRoot(root.copy(paused = paused, excluded = excluded))
    }
    suspend fun pauseAll(paused: Boolean) = running().pause(paused)
    suspend fun dismiss(issueId: String) = running().dismissConflict(issueId)
    suspend fun retry(jobId: String) = running().retry(jobId)
    /** Back up now; false when storage is full and files are already waiting for it, so nothing was started. */
    suspend fun backupNow(id: String): Boolean = running().backupNow(id)
    /**
     * Looks for restores requested for this phone's backup folders now (after the person asked for one here) rather
     * than at the next check. Progress shows in [SyncRuntime.restoring] and [SyncRuntime.restored].
     */
    fun restoreSoon() = engine?.restoreSoon()
    suspend fun archive(id: String, archived: Boolean) = running().archiveBackup(id, archived)
    suspend fun disconnectBackup(id: String) {
        val engine = running()
        val root = engine.journal.root(id)
        engine.disconnectBackup(id)
        root?.let { releaseIfUnused(Uri.parse(it.treeUri)) }
        schedule(engine.journal.roots().isNotEmpty())
    }
    fun wake() = engine?.wake()
    fun currentBackupSettings() = backupSettings
    private fun readBackupSettings(): BackupSettings {
        val saved = backupPreferences
        val wait = saved.getInt("wait-minutes", BackupSettings().waitMinutes)
        return BackupSettings(if (wait in BACKUP_WAIT_CHOICES) wait else BackupSettings().waitMinutes, saved.getBoolean("skip-changing", true))
    }
    /** Saves this phone's backup choices; they apply from the next pass, which is started now. */
    fun setBackupSettings(settings: BackupSettings) {
        require(settings.waitMinutes in BACKUP_WAIT_CHOICES) { "Choose a wait of ${BACKUP_WAIT_CHOICES.joinToString(", ")} minutes." }
        backupPreferences.edit().putInt("wait-minutes", settings.waitMinutes).putBoolean("skip-changing", settings.skipChanging).apply()
        backupSettings = settings
        wake()
    }
    private fun releaseIfUnused(uri: Uri) {
        if (uri.toString() == PHOTOS_URI) { watchPhotos(); return }
        if (engine?.journal?.roots()?.any { it.treeUri == uri.toString() } == true) return
        SafTree.release(context, uri)
    }

    // Photo backup ------------------------------------------------------------------------------------------
    /** What harbor0 may read in the photo library now. */
    fun photoAccess(): MediaAccess = AndroidMediaSource(context).access()
    /** This installation's device key, to recognise this phone's earlier sessions. */
    val devicePublicId: String? get() = engine?.journal?.get<String>("devicePublicId")
    /**
     * Backs up this phone's photos as the backup folder "Photos". A "Photos" backup this phone made before and no
     * longer has (its app data was cleared, say) is taken up again, files it already holds included, instead of a second one.
     */
    suspend fun addPhotoBackup(options: PhotoOptions): SyncRoot {
        val engine = running()
        if (engine.journal.roots().any { it.isPhotos }) throw IllegalStateException("Photos from this phone are already backed up.")
        if (photoAccess() == MediaAccess.NONE) throw IllegalStateException("Allow harbor0 to see your photos to back them up.")
        val local = engine.journal.roots().mapNotNull { it.backupId }.toSet()
        val (backup, reused) = photoBackupRoot(api, engine.deviceId, engine.journal.get<String>("devicePublicId"), local)
        val root = SyncRoot(UUID.randomUUID().toString(), PHOTOS_URI, PHOTOS_NAME, backup.remoteRootDriveItemId, "backup", backup.id, cloudPath = "Backups / $PHOTOS_NAME", photos = options)
        val seed = if (!reused) emptyList() else
            photoSeed(api, root.id, backup.remoteRootDriveItemId, withContext(Dispatchers.IO) { scanTree(localTree(context, root), root.excluded) }.orEmpty())
        engine.addRoot(root, seed)
        schedule(true)
        watchPhotos()
        return root
    }
    suspend fun photoOptions(id: String, options: PhotoOptions) {
        val engine = running()
        val root = engine.journal.root(id)?.takeIf { it.isPhotos } ?: throw IllegalStateException("Photo backup was not found on this phone.")
        engine.updateRoot(root.copy(photos = options))
        armPhotos(ExistingWorkPolicy.REPLACE)
    }
    /** The albums in the photo library, for choosing which to leave out. */
    suspend fun photoAlbums(root: SyncRoot): List<String> = withContext(Dispatchers.IO) { localTree(context, root).list("").map { it.name } }

    companion object {
        const val WORK = "harbor0-sync"
        const val PHOTO_WORK = "harbor0-photos"
        const val PHOTOS_INPUT = "photos"
        val photoCollections: List<Uri> get() = listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        /** New photos start a pass a minute after the library settles, at most five minutes after the first change. */
        fun photoConstraints(options: PhotoOptions): Constraints = Constraints.Builder().setRequiredNetworkType(photoNetwork(options)).apply {
            photoCollections.forEach { addContentUriTrigger(it, true) }
        }.setTriggerContentUpdateDelay(1, TimeUnit.MINUTES).setTriggerContentMaxDelay(5, TimeUnit.MINUTES).build()
    }
}

/** One background pass: restores the session if needed, then syncs every folder once. */
class SyncWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val api = Harbor.api(applicationContext)
        val controller = Harbor.sync(applicationContext)
        return try {
            if (!api.signedIn.value) api.restore()
            if (!api.signedIn.value) { controller.schedule(false); return Result.success() }
            val user = api.get<Account>("/v1/users/me").user
            // A remembered unlock opens again here; otherwise sync waits, locked, for the app.
            if (controller.vault.locked) controller.vault.restore(api.e2eeApi())
            val engine = controller.connect(user.id)
            if (engine.journal.roots().isEmpty()) { controller.schedule(false); return Result.success() }
            engine.tick()
            // Anything left after a full pass (a long backup, a big sync) goes on in the service rather than waiting for
            // the next pass, where allowed.
            if (backgroundWork(controller.view.value) != null) BackupWorker.start(applicationContext)
            // The process may end soon after a background pass; what it reported goes now.
            engine.flushReports()
            // Watching the library again for the next new photos, once this run has finished.
            if (inputData.getBoolean(SyncController.PHOTOS_INPUT, false)) controller.armPhotos(ExistingWorkPolicy.APPEND_OR_REPLACE)
            Result.success()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (e is ApiException && (e.status == 401 || e.code == "DEVICE_REVOKED")) Result.success() else Result.retry()
        }
    }
}
