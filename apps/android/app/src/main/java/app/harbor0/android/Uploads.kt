package app.harbor0.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.put

@Serializable enum class UploadPhase { Queued, Hashing, Uploading, Paused, Done, Failed }
/** How a file lands: under its own name, as a new version of an item, or beside a same-named one. */
sealed interface UploadTarget {
    data object New: UploadTarget
    data class Replace(val item: DriveItem): UploadTarget
    data object KeepBoth: UploadTarget
}
/** One file's upload. Files from a chosen folder share a group, shown as one row. [conflict] is "file" or "folder" when the name is taken. */
@Serializable data class UploadEntry(val key: String, val name: String, val size: Long, val loaded: Long = 0, val phase: UploadPhase = UploadPhase.Queued,
    val error: String? = null, val groupKey: String? = null, val groupName: String? = null, val conflict: String? = null)
data class UploadActivity(val key: String, val folder: Boolean, val name: String, val size: Long, val loaded: Long, val phase: UploadPhase,
    val error: String?, val files: Int, val filesDone: Int, val filesFailed: Int, val keys: List<String>,
    /** Failed files whose name is taken, which can be replaced or kept beside the existing ones. */
    val conflictKeys: List<String> = emptyList(),
    /** Every name conflict is with a file, so replacing is possible. */
    val replaceable: Boolean = true)
/** “Report.pdf” → “Report (2).pdf”; dotfiles and names without an extension get the suffix last. */
fun numberedName(name: String, n: Int): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
}
/** Upload failures in words, as apps/web/lib/upload.ts. */
fun uploadErrorMessage(e: Throwable, fallback: (Throwable) -> String = { it.message ?: "Upload failed" }): String =
    (e as? ApiException)?.code?.let { mapOf(
        "OWNER_STORAGE_FULL" to "The owner of this shared folder is out of storage.",
        "STORAGE_QUOTA_EXCEEDED" to "Your storage is full. Free up space, then retry.",
        "NAME_CONFLICT" to "A file with this name is already here.",
        "REVISION_CONFLICT" to "The file changed while uploading. Replace it again to upload over the latest version.",
        "FORBIDDEN" to "You can’t upload to this folder.")[it] } ?: fallback(e)
/** A file picked on this device, with the folders to create above it for folder uploads. */
data class PickedFile(val uri: Uri, val name: String, val size: Long, val mime: String, val folders: List<String> = emptyList(),
    /** A private file made only for this upload (a file unpacked from an archive), removed once it is uploaded or cancelled. */
    val temporary: java.io.File? = null,
    /** The picked document or folder whose read permission harbor0 kept, given back once its files are uploaded. */
    val grant: Uri? = null)

/** Rolls folder uploads into one row each, as the web upload tray does. */
fun summarizeUploads(entries: List<UploadEntry>): List<UploadActivity> = entries.groupBy { it.groupKey ?: it.key }.map { (key, members) ->
    val first = members.first()
    val phases = members.map { it.phase }
    val active = phases.any { it == UploadPhase.Hashing || it == UploadPhase.Uploading }
    val phase = if (first.groupKey == null) first.phase else when {
        active -> UploadPhase.Uploading
        UploadPhase.Paused in phases -> UploadPhase.Paused
        UploadPhase.Queued in phases -> UploadPhase.Queued
        UploadPhase.Failed in phases -> UploadPhase.Failed
        else -> UploadPhase.Done
    }
    UploadActivity(key, first.groupKey != null, first.groupName ?: first.name, members.sumOf { it.size },
        members.sumOf { if (it.phase == UploadPhase.Done) it.size else it.loaded }, phase, members.firstOrNull { it.phase == UploadPhase.Failed }?.error,
        members.size, phases.count { it == UploadPhase.Done }, phases.count { it == UploadPhase.Failed }, members.map { it.key },
        members.filter { it.phase == UploadPhase.Failed && it.conflict != null }.map { it.key },
        members.all { it.phase != UploadPhase.Failed || it.conflict != "folder" })
}
fun uploadDetail(a: UploadActivity): String {
    val amount = "${bytesLabel(a.loaded)} of ${bytesLabel(a.size)}"
    val count = { n: Int -> plural(n, "file") }
    if (!a.folder) return when (a.phase) {
        UploadPhase.Queued -> "Waiting…"; UploadPhase.Hashing -> "Preparing file…"; UploadPhase.Uploading -> amount
        UploadPhase.Paused -> "Paused · $amount"; UploadPhase.Done -> "Uploaded"; UploadPhase.Failed -> a.error ?: "Upload failed"
    }
    val failed = if (a.filesFailed > 0) " · ${a.filesFailed} failed" else ""
    val files = "${a.filesDone} of ${count(a.files)}"
    return when (a.phase) {
        UploadPhase.Queued -> "Waiting · ${count(a.files)}"
        UploadPhase.Hashing, UploadPhase.Uploading -> "$files · $amount$failed"
        UploadPhase.Paused -> "Paused · $files$failed"
        UploadPhase.Done -> "${count(a.files)} uploaded"
        UploadPhase.Failed -> "${a.filesFailed} of ${count(a.files)} failed${a.error?.let { " · $it" } ?: ""}"
    }
}

/** What the queue needs from the open app: where new uploads go, and how to show what happened. Null while no screen is open. */
interface UploadHost {
    val folderId: String?
    val canWriteHere: Boolean
    fun canEdit(item: DriveItem): Boolean
    fun refused(message: String)
    fun notify(message: String)
    fun report(e: Throwable)
    fun message(e: Throwable): String
    /** A file finished uploading: the open folder and the storage figures are refreshed. */
    fun uploaded()
}

/** One queued file as saved on this phone, so the queue carries on after Android ends the app. [grant] is the picked document or tree whose read permission was kept. */
@Serializable data class SavedUpload(val key: String, val batch: String, val base: String?, val uri: String, val name: String, val size: Long, val mime: String,
    val folders: List<String> = emptyList(), val temporary: String? = null, val grant: String? = null,
    /** "new", "replace" (over [item]) or "keep-both". */
    val target: String = "new", val item: DriveItem? = null, val stopped: Boolean = false)
@Serializable data class SavedUploads(val account: String? = null, val jobs: List<SavedUpload> = emptyList(), val entries: List<UploadEntry> = emptyList(),
    /** Folders already created for each batch, by path. */
    val created: Map<String, Map<String, String>> = emptyMap())

/** The saved queue as it comes back after the app was ended: finished files are dropped and the file under way starts again from the queue. */
fun resumedUploads(saved: SavedUploads): SavedUploads {
    val keys = saved.jobs.map { it.key }.toSet()
    val entries = saved.entries.filter { it.phase != UploadPhase.Done && it.key in keys }.map {
        if (it.phase == UploadPhase.Hashing || it.phase == UploadPhase.Uploading) it.copy(phase = UploadPhase.Queued, loaded = 0) else it
    }
    val left = entries.map { it.key }.toSet()
    val jobs = saved.jobs.filter { it.key in left }
    return saved.copy(jobs = jobs, entries = entries, created = saved.created.filterKeys { batch -> jobs.any { it.batch == batch } })
}

/** The "Uploading" notification's title, text and progress (in KB, as Android's progress is an int), or null when nothing is moving. */
data class UploadNotice(val title: String, val detail: String, val loaded: Int, val total: Int)
fun uploadNotice(entries: List<UploadEntry>): UploadNotice? {
    val moving = entries.filter { it.phase == UploadPhase.Queued || it.phase == UploadPhase.Hashing || it.phase == UploadPhase.Uploading }
    if (moving.isEmpty()) return null
    val size = moving.sumOf { it.size }; val loaded = moving.sumOf { it.loaded }
    val title = if (moving.size == 1) "Uploading ${moving.single().name}" else "Uploading ${plural(moving.size, "file")}"
    return UploadNotice(title, "${bytesLabel(loaded)} of ${bytesLabel(size)}", (loaded / 1024).toInt(), (size / 1024).toInt())
}

/**
 * Uploads one file at a time; paused, cancelled or already running files are skipped. Pausing restarts that file when
 * resumed. One queue serves the whole process (Harbor.uploads), so uploads keep going when the app is left or its
 * screen is closed: [UploadWorker] keeps the process running meanwhile, and the queue is saved on this phone, so a
 * queue Android ended carries on from its next file (the file under way starts over).
 */
class UploadQueue(context: Context, private val api: HarborApi, private val transfers: FileTransfers) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile var host: UploadHost? = null
    var entries by mutableStateOf<List<UploadEntry>>(emptyList()); private set
    private class Job(val file: PickedFile, val batch: String, val base: String?, var stopped: Boolean = false, var target: UploadTarget = UploadTarget.New,
        val grant: Uri? = null) {
        /** The folder the file goes into, once its folders exist. */
        var parentId: String? = base
    }
    private val jobs = mutableMapOf<String, Job>()
    // Folders created for each batch, by path, so files in a folder share it.
    private val created = mutableMapOf<String, MutableMap<String, String>>()
    private var runner: kotlinx.coroutines.Job? = null
    private var current: Pair<String, kotlinx.coroutines.Job>? = null
    /** The account the saved queue belongs to, and whether this process checked it is still the one signed in. */
    private var account: String? = null
    private var verified = false
    private val store = File(this.context.filesDir, "uploads.json")
    private val saves = MutableStateFlow<SavedUploads?>(null)
    val active get() = entries.any { it.phase == UploadPhase.Queued || it.phase == UploadPhase.Hashing || it.phase == UploadPhase.Uploading }
    private fun update(key: String, change: (UploadEntry) -> UploadEntry) {
        val before = entries.firstOrNull { it.key == key }?.phase
        entries = entries.map { if (it.key == key) change(it) else it }
        if (entries.firstOrNull { it.key == key }?.phase != before) save()
    }

    init {
        load()
        // The latest state only, written in order.
        scope.launch(Dispatchers.IO) {
            saves.collect { saved -> if (saved != null) runCatching {
                val next = File(store.path + ".tmp")
                next.writeText(harborJson.encodeToString(SavedUploads.serializer(), saved))
                check(next.renameTo(store))
            } }
        }
    }
    private fun load() {
        // Nothing was under way when the process started: copies of files from an earlier run are stale.
        File(context.cacheDir, "uploads").deleteRecursively()
        val saved = runCatching { resumedUploads(harborJson.decodeFromString(SavedUploads.serializer(), store.readText())) }.getOrNull()
        val kept = saved?.jobs?.mapNotNull { it.temporary }?.map { File(it).canonicalPath }?.toSet().orEmpty()
        File(context.cacheDir, "zip-copies").takeIf { it.exists() }?.walkBottomUp()?.forEach { if (it.isFile && it.canonicalPath !in kept || it.isDirectory && it.list()?.isEmpty() == true) it.delete() }
        if (saved == null) return
        account = saved.account
        for (s in saved.jobs) {
            val target = when (s.target) { "replace" -> s.item?.let { UploadTarget.Replace(it) } ?: UploadTarget.New; "keep-both" -> UploadTarget.KeepBoth; else -> UploadTarget.New }
            jobs[s.key] = Job(PickedFile(Uri.parse(s.uri), s.name, s.size, s.mime, s.folders, s.temporary?.let(::File)), s.batch, s.base, s.stopped, target, s.grant?.let(Uri::parse))
        }
        saved.created.forEach { (batch, folders) -> created[batch] = folders.toMutableMap() }
        entries = saved.entries
    }
    private fun save() {
        saves.value = SavedUploads(account, jobs.map { (key, job) ->
            val target = job.target
            SavedUpload(key, job.batch, job.base, job.file.uri.toString(), job.file.name, job.file.size, job.file.mime, job.file.folders, job.file.temporary?.path,
                job.grant?.toString(), when (target) { is UploadTarget.Replace -> "replace"; UploadTarget.KeepBoth -> "keep-both"; UploadTarget.New -> "new" },
                (target as? UploadTarget.Replace)?.item, job.stopped)
        }, entries, created.mapValues { it.value.toMap() })
    }
    /** Keeps reading a picked document or folder after the app is ended. Not every app's documents allow it; those upload only while harbor0 runs. */
    private fun keep(uri: Uri): Uri? = runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); uri
    }.getOrNull()
    private fun release(removed: Collection<Job>) {
        val used = jobs.values.mapNotNull { it.grant }.toSet()
        removed.forEach { job ->
            job.file.temporary?.delete()
            job.grant?.takeIf { it !in used }?.let { runCatching { context.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        }
    }

    /** Signed out: everything stops and the saved queue is forgotten. */
    fun clear() {
        runner?.cancel(); current?.second?.cancel()
        val removed = jobs.values.toList(); jobs.clear(); release(removed)
        created.clear(); entries = emptyList(); account = null; verified = false; save()
    }
    fun addUris(uris: List<Uri>) {
        scope.launch {
            try { add(withContext(Dispatchers.IO) { uris.map { describe(it).copy(grant = keep(it)) } }) } catch (e: CancellationException) { throw e } catch (e: Exception) { host?.report(e) }
        }
    }
    /** Walks a folder chosen with the system picker and uploads every file inside it, recreating its subfolders. */
    fun addTree(tree: Uri) {
        scope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    val grant = keep(tree)
                    val resolver = context.contentResolver
                    val rootId = DocumentsContract.getTreeDocumentId(tree)
                    val rootName = resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Folder"
                    val out = mutableListOf<PickedFile>()
                    fun walk(id: String, path: List<String>) {
                        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                            while (c.moveToNext()) {
                                val child = c.getString(0); val name = c.getString(1) ?: continue; val mime = c.getString(2) ?: "application/octet-stream"
                                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walk(child, path + name)
                                else out += PickedFile(DocumentsContract.buildDocumentUriUsingTree(tree, child), name, if (c.isNull(3)) 0 else c.getLong(3), mime, path, grant = grant)
                            }
                        }
                    }
                    walk(rootId, listOf(rootName))
                    out
                }
                if (files.isEmpty()) host?.notify("This folder has no files to upload.") else add(files)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host?.report(e) }
        }
    }
    private fun describe(uri: Uri): PickedFile {
        val resolver = context.contentResolver
        var name = "Upload"; var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { c.getString(0)?.let { name = it }; if (!c.isNull(1)) size = c.getLong(1) }
        }
        return PickedFile(uri, name, size, resolver.getType(uri) ?: "application/octet-stream")
    }
    /** Uploads a file chosen on this phone as a new version of [item], keeping its name. */
    fun addVersion(uri: Uri, item: DriveItem) {
        scope.launch {
            try {
                val picked = withContext(Dispatchers.IO) { describe(uri).copy(grant = keep(uri)) }.copy(name = item.name)
                add(listOf(picked), UploadTarget.Replace(item), item.parentId)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host?.report(e) }
        }
    }
    fun add(files: List<PickedFile>, target: UploadTarget = UploadTarget.New, into: String? = null) {
        val host = host ?: return release(files.map { Job(it, "", null, grant = it.grant) })
        if (target is UploadTarget.Replace) { if (!host.canEdit(target.item)) { host.refused("You can’t upload to this folder."); return } }
        else if (!host.canWriteHere) { host.refused("Uploads are unavailable in this location"); return }
        enqueue(files, target, if (target is UploadTarget.Replace) into else host.folderId)
    }
    /** Uploads files unpacked on this phone (from an archive) into [parent]; null is the top of My Drive. */
    fun addCopies(files: List<PickedFile>, parent: String?) = enqueue(files, UploadTarget.New, parent)
    private fun enqueue(files: List<PickedFile>, target: UploadTarget, base: String?) {
        val batch = UUID.randomUUID().toString()
        val queued = files.mapIndexed { index, file ->
            val key = "$batch/$index"
            jobs[key] = Job(file, batch, base, target = target, grant = file.grant)
            UploadEntry(key, file.name, file.size, groupKey = file.folders.firstOrNull()?.let { "$batch/$it" }, groupName = file.folders.firstOrNull())
        }
        entries = entries + queued
        save()
        start()
    }
    /** Uploads what is queued, with the "Uploading" service keeping it going when the app is left. Best called while the app is on screen. */
    fun start() {
        if (!active) return
        runCatching { UploadWorker.start(context) }
        if (runner?.isActive == true) return
        runner = scope.launch {
            while (true) {
                val key = entries.firstOrNull { it.phase == UploadPhase.Queued && jobs[it.key]?.stopped == false }?.key ?: break
                // A queue saved by an account that has since signed out is never uploaded to another one.
                if (!verified) {
                    val me = try { api.get<Account>("/v1/users/me").user.id } catch (e: CancellationException) { throw e } catch (e: ApiException) {
                        if (e.status == 401) { clear(); break } else null
                    } catch (_: Exception) { null }
                    if (me == null) { delay(RETRY_MS); continue }
                    if (account != null && account != me) { clear(); break }
                    if (account == null) { account = me; save() }
                    verified = true
                }
                val job = launch { run(key) }
                current = key to job
                job.join()
                current = null
            }
        }
    }
    private suspend fun run(key: String) {
        val job = jobs[key] ?: return
        val scratch = File(context.cacheDir, "uploads/${UUID.randomUUID()}")
        try {
            update(key) { it.copy(phase = UploadPhase.Hashing, error = null, loaded = 0, conflict = null) }
            val name = safeName(job.file.name)
            for (attempt in 0..NETWORK_RETRIES) try {
                upload(key, job, name, scratch)
                break
            } catch (e: java.io.IOException) {
                // Dropped connections are common on phones, more so in the background: the file starts again a little later.
                if (attempt == NETWORK_RETRIES) throw e
                update(key) { it.copy(phase = UploadPhase.Hashing, loaded = 0) }
                delay(minOf(60_000L, 5_000L shl attempt))
            }
            update(key) { it.copy(phase = UploadPhase.Done, loaded = it.size) }
            jobs.remove(key)?.let { release(listOf(it)) }
            save()
            host?.uploaded()
        } catch (e: CancellationException) {
            if (jobs[key]?.stopped == true) update(key) { it.copy(phase = UploadPhase.Paused) }
            if (!currentCoroutineContext().isActive && jobs[key] == null) return
        } catch (e: Exception) {
            // A taken name, or a file that changed under a replace, offers Replace or Keep both.
            val conflict = when {
                e !is ApiException -> null
                job.target is UploadTarget.Replace -> if (e.code == "REVISION_CONFLICT") "file" else null
                job.target == UploadTarget.New && e.code == "NAME_CONFLICT" ->
                    if (runCatching { findByName(job.parentId, job.file.name) }.getOrNull()?.isFolder == true) "folder" else "file"
                else -> null
            }
            update(key) { it.copy(phase = UploadPhase.Failed, error = uploadErrorMessage(e) { host?.message(it) ?: it.message ?: "Upload failed" }, conflict = conflict) }
        } finally { withContext(NonCancellable + Dispatchers.IO) { scratch.delete() } }
    }
    private suspend fun upload(key: String, job: Job, name: String, scratch: File) {
        val parent = parentFor(job)
        val target = job.target
        withContext(Dispatchers.IO) {
            check(scratch.parentFile!!.mkdirs() || scratch.parentFile!!.isDirectory)
            val input = try { context.contentResolver.openInputStream(job.file.uri) } catch (_: SecurityException) { null }
            input?.use { scratch.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) { ensureActive(); val n = it.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
            } } ?: error("The selected file could not be opened. Select it again.")
        }
        val size = scratch.length()
        update(key) { it.copy(size = size) }
        val progress = { label: String, fraction: Float? ->
            scope.launch {
                update(key) { it.copy(phase = if (label.startsWith("Uploading")) UploadPhase.Uploading else UploadPhase.Hashing,
                    loaded = ((fraction ?: 0f) * size).toLong()) }
            }
            Unit
        }
        when (target) {
            is UploadTarget.Replace -> transfers.upload(scratch, name, job.file.mime, parent, {
                put("driveItemId", target.item.id); target.item.revision?.let { put("baseRevision", it) }
            }, progress = progress)
            // Keeping both tries “name (2)”, “name (3)” and so on until a name is free.
            UploadTarget.KeepBoth -> for (n in 1..50) {
                try { transfers.upload(scratch, if (n == 1) name else numberedName(name, n), job.file.mime, parent, progress = progress); break }
                catch (e: ApiException) { if (e.code != "NAME_CONFLICT" || n == 50) throw e }
            }
            UploadTarget.New -> transfers.upload(scratch, name, job.file.mime, parent, progress = progress)
        }
    }
    /** The item in [parent] whose name matches, the way the server compares names. */
    private suspend fun findByName(parent: String?, name: String): DriveItem? {
        val wanted = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC).lowercase()
        return api.all<DriveItem>("/v1/drive/folders/${HarborApi.segment(parent ?: "root")}/children")
            .firstOrNull { java.text.Normalizer.normalize(it.name, java.text.Normalizer.Form.NFC).lowercase() == wanted }
    }
    /** Uploads files whose name is taken as new versions of the existing files. */
    fun replace(keys: List<String>) {
        scope.launch {
            for (key in keys) {
                val job = jobs[key] ?: continue
                // Always the latest revision, so a replace that lost a race can simply be tried again.
                val existing = runCatching {
                    (job.target as? UploadTarget.Replace)?.let { api.get<ItemResponse>("/v1/drive/items/${HarborApi.segment(it.item.id)}").item }
                        ?: findByName(job.parentId, job.file.name)
                }.getOrNull()
                if (existing == null || existing.isFolder) {
                    update(key) { it.copy(conflict = if (existing != null) "folder" else null,
                        error = if (existing != null) "A folder has this name, so it can’t be replaced. Keep both instead." else "The file to replace is gone. Retry to upload it.") }
                    if (existing == null) job.target = UploadTarget.New
                    continue
                }
                job.target = UploadTarget.Replace(existing)
                update(key) { it.copy(conflict = null) }
                resume(listOf(key))
            }
        }
    }
    /** Uploads files whose name is taken under a numbered name instead. */
    fun keepBoth(keys: List<String>) {
        for (key in keys) { jobs[key]?.target = UploadTarget.KeepBoth; update(key) { it.copy(conflict = null) } }
        resume(keys)
    }
    private suspend fun parentFor(job: Job): String? {
        val folders = created.getOrPut(job.batch) { mutableMapOf() }
        var parent = job.base
        var path = ""
        for (name in job.file.folders) {
            path += "/$name"
            parent = folders[path] ?: api.createFolder(name, parent).id.also { folders[path] = it; save() }
        }
        job.parentId = parent
        return parent
    }
    fun pause(keys: List<String>) {
        for (key in keys) {
            val job = jobs[key] ?: continue
            job.stopped = true
            if (current?.first == key) current?.second?.cancel() else update(key) { if (it.phase == UploadPhase.Queued) it.copy(phase = UploadPhase.Paused) else it }
        }
        save()
    }
    fun resume(keys: List<String>) {
        for (key in keys) { jobs[key]?.stopped = false; if (jobs.containsKey(key)) update(key) { it.copy(phase = UploadPhase.Queued, error = null) } }
        save()
        start()
    }
    fun cancel(keys: List<String>) {
        val removed = keys.mapNotNull { key -> jobs.remove(key)?.also { job -> job.stopped = true; if (current?.first == key) current?.second?.cancel() } }
        release(removed)
        entries = entries.filter { it.key !in keys }
        save()
    }
    fun dismiss() {
        val finished = summarizeUploads(entries).filter { it.phase == UploadPhase.Done || it.phase == UploadPhase.Failed }.flatMap { it.keys }.toSet()
        release(finished.mapNotNull { jobs.remove(it) })
        entries = entries.filter { it.key !in finished }
        save()
    }

    private companion object {
        const val NETWORK_RETRIES = 6
        const val RETRY_MS = 15_000L
    }
}
