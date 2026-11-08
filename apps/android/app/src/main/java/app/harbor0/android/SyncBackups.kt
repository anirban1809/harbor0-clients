package app.harbor0.android

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.io.IOException
import java.time.Instant
import java.util.Locale
import java.util.UUID

private const val ARCHIVE_ATTEMPTS = 3
/** A request the server will keep refusing; retrying every pass would stall all other folders. */
private fun refused(error: Throwable): String? {
    if (error !is ApiException || error.status < 400 || error.status >= 500 || error.status in listOf(401, 429) ||
        error.code in listOf("BACKUP_DISCONNECTED", "AUTH_INVALID", "DEVICE_REVOKED")) return null
    return if (error.status == 404) "This harbor0 server does not support archiving yet. Update the server and try again." else error.message
}
/** A transient failure: keep the run and its upload state for the next pass. */
private fun transient(error: Throwable) = (error is IOException && error !is LocalReadException) || (error is ApiException && error.status >= 500)
/**
 * End-to-end encryption turned on or off (desktop `BackupConversion`): what this phone's backups should be stored as
 * ("encrypted", "plain", or null to leave them as they are), the server's own view of them ([raw], whose names stay as
 * stored), and private space for one file at a time while it is converted.
 */
class BackupConversion(val form: () -> String?, val raw: EngineApi, val scratch: java.io.File)
/** Saves several small new files of a run at once (SyncEngine.backupBatch). Null when the server has no batch routes. */
typealias BackupBatch = suspend (root: SyncRoot, jobs: List<LocalJob>, runId: String) -> Map<String, BatchOutcome>?

/**
 * Folder backups on this phone (desktop `backups.ts`): runs after files have been quiet for the wait chosen in Settings
 * ([backupSettings], 10 minutes unless changed; photos at once), "Back up now",
 * archive (a final full backup, then removal of only the local files whose content is saved) and restore of an
 * archived folder, and restores requested from other devices ([BackupRestores]). A run's files are compared with the
 * free cloud storage before any is uploaded: a full account starts no run, and files too big for what is left wait.
 */
class FolderBackups(private val api: EngineApi, private val journal: SyncJournal, private val transfers: SyncTransfers,
    private val tree: (SyncRoot) -> LocalTree, private val restores: BackupRestores) {
    /** Small new files go in batches while set; cleared when the server turns out to have no batch routes. */
    var batch: BackupBatch? = null
    /** How backups follow end-to-end encryption being turned on or off; see [convert]. */
    var conversion: BackupConversion? = null
    /** The account's storage turned out to be short: what the waiting files need and what is free. */
    var onStorageFull: (StorageNeed) -> Unit = {}
    /** The account's storage now, or null when it can't be read (offline, an older server). */
    var storage: suspend () -> Storage? = { api.storage() }
    /** A folder whose uploads wait for now (photos waiting for Wi-Fi); restores still go ahead. */
    var held: (SyncRoot) -> Boolean = { false }
    // A run's file list is read once per run; rewriting it after every file would cost more than the backup.
    private val runJobs = mutableMapOf<String, Pair<String, List<String>>>()
    private fun ignored(root: SyncRoot, relative: String) = ignoredPath(root.excluded, relative)

    /** The run under way, as started: its id, files and their size. */
    fun openRun(rootId: String): PendingRun? = journal.get<PendingRun>("backup-run:$rootId")
    /** Files handled so far in the run under way, of all those in it. */
    fun run(rootId: String): BackupRunProgress? {
        val run = openRun(rootId) ?: return null
        run.jobs?.let { val total = maxOf(run.total ?: 0, it.size); return BackupRunProgress(total - it.size, total) }
        return BackupRunProgress(minOf(run.next ?: 0, run.total ?: 0), run.total ?: 0)
    }
    fun request(root: SyncRoot) {
        if (root.paused) throw IllegalStateException("Resume this backup folder before backing up now.")
        if (journal.get<PendingRun>("backup-run:${root.id}") != null) throw IllegalStateException("A backup is already running for this folder.")
        // Persist intent before scanning; an interrupted scan is repeated by the next pass.
        journal.set("backup-now:${root.id}", true)
    }
    private fun scan(root: SyncRoot) {
        val local = tree(root)
        local.check()
        // A photo library is large and its edits are already queued by the scans: only what was never saved is added,
        // rather than every photo being read again to find nothing changed.
        val saved = if (root.isPhotos) journal.files(root.id).mapTo(HashSet()) { it.relativePath } else emptySet()
        scanTree(local, root.excluded)?.forEach { (path, stat) -> if (!stat.folder && path !in saved) journal.enqueue(root.id, path, "upsert") }
    }
    /**
     * Brings a folder's backup in line with the account's encryption (desktop `convert`). Only this phone may change its
     * backups, so as encryption is turned on it re-encrypts what was saved before, and as it is turned off it decrypts
     * what was saved since: each file's current version is downloaded, uploaded again the other way and its old
     * versions deleted, then names switch form. Earlier versions go, as when the web app converts the rest of the
     * drive. Runs once per change, inside a backup run of its own with its planned totals.
     */
    private suspend fun convert(root: SyncRoot, url: String, stopped: () -> Boolean) {
        val conversion = conversion ?: return
        val want = conversion.form() ?: return
        val remoteId = root.remoteId ?: return
        val doneKey = "backup-converted:${root.id}"
        if (journal.get<String>(doneKey) == want) return
        val encrypting = want == "encrypted"
        val items = mutableListOf<DriveItem>()
        val folders = ArrayDeque(listOf(remoteId))
        while (folders.isNotEmpty()) {
            for (item in conversion.raw.all<DriveItem>("/v1/drive/folders/${HarborApi.segment(folders.removeLast())}/children")) {
                items += item
                if (item.isFolder) folders += item.id
            }
        }
        val content = items.filter { !it.isFolder }
        val names = items.filter { E2ee.isEncryptedName(it.name) != encrypting }
        if (content.isEmpty() && names.isEmpty()) return journal.set(doneKey, want)
        val runId = UUID.randomUUID().toString()
        val runUrl = "$url/runs/${HarborApi.segment(runId)}"
        api.request("$url/runs", "POST", buildJsonObject {
            put("id", runId); put("trigger", "MANUAL"); put("plannedFileCount", content.size); put("plannedSizeBytes", content.sumOf { it.sizeBytes })
        })
        val folder = java.io.File(conversion.scratch, runId).apply { mkdirs() }
        val scratch = FileTree(folder)
        val revisions = mutableMapOf<String, Int?>()
        var failed = 0
        /** Keeps the journal's record of a saved file current, so it isn't saved again. */
        fun saved(item: DriveItem) { journal.fileByItem(root.id, item.id)?.let { journal.putFile(it.copy(revision = item.revision ?: it.revision)) } }
        try {
            for (item in content) {
                if (stopped()) return
                try {
                    var current = item
                    if (item.encrypted != encrypting) {
                        transfers.download(scratch, buildJsonObject { put("driveItemId", item.id) }, item.id)
                        current = transfers.upload(scratch, item.id, "file", item.parentId, UploadState(UUID.randomUUID().toString()), {},
                            LocalFile(root.id, item.id, item.id, item.revision ?: 1, null, "FILE"), backup = root.backupId!! to runId)
                        runCatching { scratch.delete(item.id) }
                        saved(current)
                    }
                    // Versions stored the other way, including ones an interrupted pass left behind.
                    api.request("/v1/e2ee/items/${HarborApi.segment(item.id)}/${if (encrypting) "purge-plaintext" else "purge-encrypted"}", "POST")
                    revisions[item.id] = current.revision
                } catch (e: CancellationException) { throw e } catch (_: Exception) { failed++ }
            }
            for (item in names) {
                if (stopped()) return
                try {
                    // The decrypted name, read through the transport; a plain one goes up as is and is encrypted on the way.
                    val name = if (encrypting) item.name else api.get<ItemResponse>("/v1/drive/items/${HarborApi.segment(item.id)}").item.name
                    val renamed = api.request("$runUrl/items/${HarborApi.segment(item.id)}/name", "POST", buildJsonObject {
                        put("operationId", UUID.randomUUID().toString()); put("baseRevision", revisions[item.id] ?: item.revision); put("name", name)
                    })["item"]?.let { harborJson.decodeFromJsonElement<DriveItem>(it) }
                    renamed?.let(::saved)
                } catch (e: CancellationException) { throw e } catch (_: Exception) { failed++ }
            }
        } finally {
            folder.deleteRecursively()
            try {
                api.request("$runUrl/complete", "POST", buildJsonObject { if (failed > 0) put("error", "$failed files couldn’t be converted. Retrying.") })
            } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
        if (failed == 0) journal.set(doneKey, want)
    }
    /** Record whether the files waiting need more cloud storage than is free. */
    fun storageShort(root: SyncRoot, short: BackupStorage?) {
        if (short != null) onStorageFull(StorageNeed(short.requiredBytes, short.availableBytes))
        val current = journal.root(root.id) ?: return
        if (current.backupStorage != short) journal.root(current.copy(backupStorage = short))
    }
    private fun stat(local: LocalTree, relative: String) = try { local.ensureParents(relative, create = false); local.stat(relative) }
        catch (e: LocalFsException) { if (e.code != "ENOENT" && e.code != "ENOTDIR") throw e; null }
    /**
     * Settle this folder's queue: drop entries with nothing to save (missing, excluded, or content identical to the
     * last saved version) and return the jobs due for a backup now, with their sizes.
     */
    private fun changed(root: SyncRoot, manual: Boolean): List<Pair<String, Long>> {
        val local = tree(root)
        val ready = mutableListOf<Pair<String, Long>>()
        for (job in journal.jobs(root.id)) {
            if (ignored(root, job.relativePath)) { journal.finish(job.id); continue }
            if (!manual && System.currentTimeMillis() < (job.payload.retryAfter ?: 0)) continue
            val info = stat(local, job.relativePath)
            if (info == null || info.folder) { journal.finish(job.id); continue }
            if (job.kind == "delete") { journal.finish(job.id); journal.enqueue(root.id, job.relativePath, "upsert"); continue }
            if (!manual && !backupReady(root, info.mtime, job.payload.observedAt ?: 0)) continue
            // Rescans and touched-but-identical files queue entries too; they are already saved.
            val known = journal.file(root.id, job.relativePath)
            if (job.payload.upload?.uploadId == null && job.payload.backupEntry == null && known?.type == "FILE" &&
                known.hash != null && local.hash(job.relativePath) == known.hash) { journal.finish(job.id); continue }
            ready += job.id to info.size
        }
        return ready
    }

    suspend fun process(input: SyncRoot, perform: suspend (SyncRoot, LocalJob) -> Unit, stopped: () -> Boolean) {
        var root = input
        if (root.backupId == null) {
            val match = api.all<BackupRoot>("/v1/backups").firstOrNull { it.remoteRootDriveItemId == root.remoteId }
                ?: throw IllegalStateException("Backup folder registration was not found. Add this folder again.")
            root = root.copy(backupId = match.id); journal.root(root)
        }
        val url = "/v1/backups/${HarborApi.segment(root.backupId!!)}"
        // Removed on the server (from another device, or by forgetting it): this phone stops backing it up too.
        val connection = try { api.request(url)["root"]?.jsonObject } catch (e: ApiException) {
            if (e.status == 404) throw ApiException(409, "Backup folder disconnected.", "BACKUP_DISCONNECTED") else throw e
        }
        if (connection?.get("state")?.jsonPrimitive?.contentOrNull == "REMOVED") throw ApiException(409, "Backup folder disconnected.", "BACKUP_DISCONNECTED")
        val local = tree(root)
        restores.run(root, local, url)
        if (held(root)) return
        val key = "backup-run:${root.id}"
        val jobsKey = "backup-run-jobs:${root.id}"
        if (journal.get<PendingRun>(key) == null) convert(root, url, stopped)
        var pending: PendingRun? = journal.get<PendingRun>(key)
        val legacy = pending?.jobs
        if (pending != null && legacy != null) {
            journal.set(jobsKey, legacy)
            pending = pending.copy(jobs = null, total = legacy.size, next = 0)
            journal.set(key, pending)
        }
        if (pending == null) {
            val manual = journal.flag("backup-now:${root.id}")
            // Storage is full and files are already waiting for it: checking each of them again cannot make any fit.
            val waiting = journal.root(root.id)?.backupStorage
            if (waiting != null && journal.pendingCount(root.id) > 0) {
                val storage = storage()
                if (storage != null && storage.availableBytes < fullBelow(storage.quotaBytes)) {
                    if (manual) journal.set("backup-now:${root.id}", false)
                    storageShort(root, waiting.copy(availableBytes = storage.availableBytes))
                    return
                }
            }
            if (manual) scan(root)
            val ready = changed(root, manual)
            // Nothing changed: no backup is recorded, and a Back up now request is settled.
            if (ready.isEmpty()) {
                if (manual) journal.set("backup-now:${root.id}", false)
                // Files still waiting (to be unchanged for the wait) still need the storage they needed.
                if (journal.pendingCount(root.id) == 0) storageShort(root, null)
                upToDate(root, url)
                return
            }
            // Compare what is waiting with the free cloud storage before uploading any of it. Offline, or against an
            // older server, the run goes ahead and uploads are refused one by one.
            val required = ready.sumOf { it.second }
            val storage = storage()
            val available = storage?.availableBytes
            val short = available != null && required > available
            storageShort(root, if (short) BackupStorage(required, available!!, ready.count { it.second > available }) else null)
            if (short && (available!! < fullBelow(storage?.quotaBytes) || ready.none { it.second <= available })) {
                // Storage is full, or nothing fits: no run to record. The files wait until space is freed.
                if (manual) journal.set("backup-now:${root.id}", false)
                return
            }
            val created = PendingRun(UUID.randomUUID().toString(), if (manual) "MANUAL" else "AUTOMATIC", total = ready.size, bytes = required, next = 0,
                room = if (short) available else null, quota = if (short) storage?.quotaBytes else null)
            journal.transaction {
                journal.set(jobsKey, ready.map { it.first })
                journal.set(key, created)
                journal.set("backup-now:${root.id}", false)
            }
            pending = created
        }
        var current: PendingRun = pending
        val cached = runJobs[root.id]?.takeIf { it.first == current.id }?.second
            ?: journal.get<List<String>>(jobsKey).orEmpty().also { runJobs[root.id] = current.id to it }
        api.request("$url/runs", "POST", buildJsonObject {
            put("id", current.id); put("trigger", current.trigger)
            // Runs left from older versions don't know their size.
            current.bytes?.let { put("plannedFileCount", current.total ?: cached.size); put("plannedSizeBytes", it) }
        })
        val manual = current.trigger == "MANUAL"
        // A file just saved: changes made to it while it was sent must survive its queue entry.
        fun saved(job: LocalJob, entry: BackupEntry, before: LocalEntry?) {
            journal.finish(job.id)
            current.room?.let { current = current.copy(room = maxOf(0, it - entry.sizeBytes)) }
            val after = runCatching { local.stat(job.relativePath) }.getOrNull()
            if (after != null && (before == null || after.mtime != before.mtime || after.size != before.size || modifiedAt(after.mtime) != entry.modifiedAt.ifEmpty { null })) {
                journal.enqueue(root.id, job.relativePath, "upsert", observedAt = System.currentTimeMillis())
            }
        }
        // A file that could not be saved this run: it waits to be retried, and the run says why.
        suspend fun failed(job: LocalJob, e: Exception, before: LocalEntry?) {
            // Retain upload state and the run for network retries, including acknowledgement loss.
            if (transient(e)) throw e
            if (e is ApiException && e.code == "BACKUP_DISCONNECTED") throw e
            // Too many requests, or the account busy with another change: the pass pauses and this file is tried again.
            if (e is ApiException && (e.status == 429 || e.code == "CONCURRENT_UPDATE")) throw e
            val failing = journal.job(job.id) ?: job
            failing.payload.upload?.uploadId?.let { runCatching { api.request("/v1/uploads/${HarborApi.segment(it)}", "DELETE") } }
            // The retry belongs to a new run, so it needs a new operation id even when no upload was recorded.
            if (failing.payload.upload != null) failing.payload.upload = UploadState(UUID.randomUUID().toString())
            // Written to while it was being saved: no fault of the file, so it is skipped, not failed, and waits to be
            // unchanged again before a later backup saves it.
            if (skippedAsChanged(e)) {
                failing.payload.observedAt = System.currentTimeMillis()
                journal.saveJob(failing)
                return
            }
            failing.attempts++
            // A path harbor0 never accepts waits a day; retrying each minute cannot help.
            failing.payload.retryAfter = System.currentTimeMillis() + if (e is ApiException && e.code == "PATH_TOO_DEEP") 24 * 3600_000L else 60_000L
            failing.error = e.message ?: "This file could not be backed up."
            journal.saveJob(failing)
            if (storageRefusal(e)) {
                // From here on only files that fit in what is left are tried.
                val room = storageNeed(e, false).availableBytes ?: 0
                current = current.copy(room = room, tooBig = (current.tooBig ?: 0) + 1)
                val waiting = journal.root(root.id)?.backupStorage
                storageShort(root, BackupStorage(maxOf(waiting?.requiredBytes ?: 0, before?.size ?: 0), room, maxOf(waiting?.files ?: 0, current.tooBig ?: 0)))
            } else current = current.copy(error = "${job.relativePath}: ${failing.error}".take(2000))
        }
        var index = current.next ?: 0
        while (index < cached.size) {
            if (stopped()) { journal.set(key, current); return }
            val room = current.room
            if (room != null && room < fullBelow(current.quota)) {
                // Storage is full: the rest wait for space to be freed rather than each being looked at.
                current = current.copy(tooBig = (current.tooBig ?: 0) + cached.size - index, next = cached.size)
                journal.set(key, current)
                break
            }
            // Small new files go several at a time; the rest, one by one below.
            val group = if (batch != null) batchGroup(root, current, cached, index) else emptyList()
            if (group.size > 1) {
                val outcomes = batch!!(root, group.map { it.job }, current.id)
                // An older server: save files one at a time from now on.
                if (outcomes == null) { batch = null; continue }
                var resume = group.last().at + 1
                for (g in group) when (val outcome = outcomes[g.job.id] ?: BatchOutcome.NotReached) {
                    is BatchOutcome.Saved -> { val entry = outcome.entry; if (entry != null) saved(g.job, entry, g.before) else resume = minOf(resume, g.at) }
                    is BatchOutcome.Failed -> failed(g.job, outcome.error, g.before)
                    BatchOutcome.Alone -> { g.job.payload.alone = true; journal.saveJob(g.job); resume = minOf(resume, g.at) }
                    BatchOutcome.NotReached, BatchOutcome.Done -> resume = minOf(resume, g.at)
                }
                index = resume
                current = current.copy(next = index); journal.set(key, current)
                continue
            }
            val job = journal.job(cached[index])
            if (job != null) {
                val before = stat(local, job.relativePath)
                val limit = current.room
                // Too big for the storage left: it waits for a later run instead of being refused.
                if (job.payload.backupEntry == null && before != null && limit != null && before.size > limit) current = current.copy(tooBig = (current.tooBig ?: 0) + 1)
                else if (job.payload.backupEntry != null || (before != null && !before.folder && (manual || backupReady(root, before.mtime, job.payload.observedAt ?: 0)))) {
                    try {
                        if (job.payload.backupEntry == null) {
                            job.payload.backupManual = manual
                            job.payload.backupRunId = current.id
                            perform(root, job)
                        }
                        var entry = journal.job(job.id)?.payload?.backupEntry ?: job.payload.backupEntry
                        if (entry == null) {
                            val known = journal.file(root.id, job.relativePath) ?: throw IllegalStateException("File changed before it could be backed up.")
                            val item = api.get<ItemResponse>("/v1/drive/items/${HarborApi.segment(known.itemId)}").item
                            entry = BackupEntry(job.relativePath, item.id, item.currentVersionId ?: "", item.sizeBytes, modifiedAt(before!!.mtime) ?: Instant.EPOCH.toString(), Instant.now().toString())
                            job.payload.backupEntry = entry
                            journal.saveJob(job)
                        }
                        api.request("$url/runs/${HarborApi.segment(current.id)}/files", "POST", harborJson.encodeToJsonElement(entry).jsonObject)
                        saved(job, entry, before)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { failed(job, e, before) }
                }
            }
            index++
            current = current.copy(next = index)
            journal.set(key, current)
        }
        val tooBig = current.tooBig ?: 0
        val storage = if (tooBig > 0) "${String.format(Locale.US, "%,d", tooBig)} ${if (tooBig == 1) "file was" else "files were"} not backed up: there is not enough cloud storage (${bytesLabel(current.room ?: 0)} free)." else null
        val reported = listOfNotNull(storage, current.error).joinToString(" ")
        api.request("$url/runs/${HarborApi.segment(current.id)}/complete", "POST", buildJsonObject { if (reported.isNotEmpty()) put("error", reported.take(2000)) })
        journal.transaction { journal.remove(key); journal.remove(jobsKey) }
        runJobs.remove(root.id)
    }
    private class Grouped(val job: LocalJob, val before: LocalEntry, val at: Int)
    /**
     * The run's next files that can go in one batch: new (nothing saved for them yet, no upload of their own under
     * way), small, settled, and fitting the storage left. Stops at the first that can't, which then goes alone.
     */
    private fun batchGroup(root: SyncRoot, run: PendingRun, ids: List<String>, start: Int): List<Grouped> {
        val local = tree(root)
        val group = mutableListOf<Grouped>()
        // The storage left once the files already in the group are saved.
        var left = run.room
        var at = start
        while (at < ids.size && group.size < BATCH_UPLOAD_FILES) {
            // Storage full once these are saved: the run stops there.
            if (left != null && left < fullBelow(run.quota)) break
            val job = journal.job(ids[at])
            if (job == null) { at++; continue }
            if (job.kind != "upsert" || job.payload.backupEntry != null || job.payload.upload?.uploadId != null || job.payload.alone == true ||
                journal.file(root.id, job.relativePath) != null) break
            val before = runCatching { local.stat(job.relativePath) }.getOrNull()
            if (before == null || before.folder || before.size > BATCH_UPLOAD_MAX_BYTES || (left != null && before.size > left) ||
                (run.trigger != "MANUAL" && !backupReady(root, before.mtime, job.payload.observedAt ?: 0))) break
            job.payload.backupManual = run.trigger == "MANUAL"
            job.payload.backupRunId = run.id
            group += Grouped(job, before, at)
            if (left != null) left -= before.size
            at++
        }
        return group
    }

    /** Files whose current content is exactly what the cloud copy holds; everything else is left alone. */
    private fun matching(root: SyncRoot): Pair<List<Pair<String, LocalEntry>>, List<String>> {
        val local = tree(root)
        val saved = mutableListOf<Pair<String, LocalEntry>>()
        val changed = mutableListOf<String>()
        val entries = scanTree(local, root.excluded) ?: return saved to listOf(".")
        for ((path, stat) in entries) {
            if (stat.folder) continue
            val known = journal.file(root.id, path)
            val info = local.stat(path) ?: continue
            if (known?.type == "FILE" && known.hash != null && local.hash(path) == known.hash) saved += path to info else changed += path
        }
        return saved to changed
    }
    /** Removes emptied folders, deepest first. The chosen folder itself stays so access to it is kept for a restore. */
    private fun prune(root: SyncRoot) {
        val local = tree(root)
        val folders = (scanTree(local, emptyList()) ?: return).filterValues { it.folder }.keys.filter { !ignored(root, it) }
            .sortedByDescending { it.count { c -> c == '/' } }
        for (folder in folders) {
            val rest = runCatching { local.list(folder) }.getOrNull() ?: continue
            // File-manager bookkeeping alone must not keep an emptied folder around.
            if (rest.all { !it.folder && metadataSegment(it.name) }) runCatching { local.delete(folder) }
        }
    }

    /** Finishes a requested archive once a full backup has saved everything, removing only verified local files. */
    suspend fun archive(input: SyncRoot, stopped: () -> Boolean) {
        var root = input
        val url = "/v1/backups/${HarborApi.segment(root.backupId!!)}"
        val attemptsKey = "backup-archive:${root.id}"
        // Nothing local has been removed yet, so a failed archive simply keeps backing up.
        fun cancel(reason: String) { journal.root(root.copy(archive = null, archiveError = reason)); journal.set(attemptsKey, 0) }
        if (root.archive == "pending") {
            if (journal.get<PendingRun>("backup-run:${root.id}") != null || journal.flag("backup-now:${root.id}")) return
            val jobs = journal.jobs(root.id)
            jobs.firstOrNull { it.error != null }?.let { return cancel("${it.relativePath} could not be backed up: ${it.error}") }
            val changed = if (jobs.isNotEmpty()) listOf(".") else matching(root).second
            if (stopped()) return
            if (changed.isNotEmpty()) {
                val attempts = (journal.get<Int>(attemptsKey) ?: 0) + 1
                if (attempts >= ARCHIVE_ATTEMPTS) return cancel("Its files keep changing. Try again when nothing is editing them.")
                // Edits made during the final backup need one more pass before anything is removed.
                journal.set(attemptsKey, attempts)
                changed.filter { it != "." }.forEach { journal.enqueue(root.id, it, "upsert") }
                journal.set("backup-now:${root.id}", true)
                return
            }
            try { api.request("$url/archive", "POST") } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val reason = refused(e) ?: throw e
                return cancel(reason)
            }
            root = root.copy(archive = "removing"); journal.root(root)
        }
        val local = tree(root)
        if (runCatching { local.check(); true }.getOrDefault(false)) {
            for ((path, before) in matching(root).first) {
                val info = local.stat(path)
                if (info != null && !info.folder && info.size == before.size && info.mtime == before.mtime) local.delete(path)
            }
            prune(root)
        }
        journal.resetRootFiles(root.id)
        journal.set(attemptsKey, 0)
        journal.root(root.copy(archive = "archived"))
    }

    /** Brings an archived folder back: resumes the backup and downloads the latest saved files. */
    suspend fun unarchive(root: SyncRoot, stopped: () -> Boolean) {
        try { api.request("/v1/backups/${HarborApi.segment(root.backupId!!)}/unarchive", "POST") } catch (e: CancellationException) { throw e } catch (e: Exception) {
            val reason = refused(e) ?: throw e
            journal.root(root.copy(archive = "archived", archiveError = reason)); return
        }
        val local = tree(root)
        suspend fun walk(parentId: String, relative: String): Boolean {
            for (item in api.all<DriveItem>("/v1/drive/folders/${HarborApi.segment(parentId)}/children")) {
                if (stopped()) return false
                val path = joinPath(relative, safeSegment(item.name, item.id))
                if (ignored(root, path)) continue
                local.ensureParents(path)
                if (item.isFolder) { local.mkdirs(path); journal.snapshotPut(root.id, path, LocalStat(true, 0, 0)); if (!walk(item.id, path)) return false }
                // Anything already in the folder is newer local work; the next backup saves it.
                else if (local.stat(path) == null) {
                    // A cloud copy damaged while it was uploaded can't come back; the rest of the folder still does.
                    try { transfers.download(local, buildJsonObject { put("driveItemId", item.id) }, path, modifiedAt = item.modifiedAt) }
                    catch (_: UploadMismatchException) { continue }
                    journal.snapshotPut(root.id, path, local.stat(path)?.stat)
                }
            }
            return true
        }
        if (walk(root.remoteId!!, "")) journal.root(root.copy(archive = null, archiveError = null))
    }

    /**
     * Nothing new records no run, so harbor0 is told the folder is still backed up; otherwise an
     * untouched folder would get a stale-backup reminder. Waiting changes don't count.
     */
    private suspend fun upToDate(root: SyncRoot, url: String) {
        if (journal.jobs(root.id).isNotEmpty()) return
        val key = "backup-checked:${root.id}"
        val now = System.currentTimeMillis()
        if (now - (journal.get<Long>(key) ?: 0L) < 12 * 3600_000L) return
        api.request("$url/checked", "POST")
        journal.set(key, now)
    }
}
