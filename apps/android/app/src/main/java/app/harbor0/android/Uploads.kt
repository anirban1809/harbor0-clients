package app.harbor0.android

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.put

enum class UploadPhase { Queued, Hashing, Uploading, Paused, Done, Failed }
/** How a file lands: under its own name, as a new version of an item, or beside a same-named one. */
sealed interface UploadTarget {
    data object New: UploadTarget
    data class Replace(val item: DriveItem): UploadTarget
    data object KeepBoth: UploadTarget
}
/** One file's upload. Files from a chosen folder share a group, shown as one row. [conflict] is "file" or "folder" when the name is taken. */
data class UploadEntry(val key: String, val name: String, val size: Long, val loaded: Long = 0, val phase: UploadPhase = UploadPhase.Queued,
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
    val temporary: java.io.File? = null)

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

/** Uploads one file at a time; paused, cancelled or already running files are skipped. Pausing restarts that file when resumed. */
class UploadQueue(private val m: WorkspaceModel) {
    var entries by mutableStateOf<List<UploadEntry>>(emptyList()); private set
    private class Job(val file: PickedFile, val batch: String, val base: String?, var stopped: Boolean = false, var target: UploadTarget = UploadTarget.New) {
        /** The folder the file goes into, once its folders exist. */
        var parentId: String? = base
    }
    private val jobs = mutableMapOf<String, Job>()
    // Folders created for each batch, by path, so files in a folder share it.
    private val created = mutableMapOf<String, MutableMap<String, String>>()
    private var runner: kotlinx.coroutines.Job? = null
    private var current: Pair<String, kotlinx.coroutines.Job>? = null
    val active get() = entries.any { it.phase == UploadPhase.Queued || it.phase == UploadPhase.Hashing || it.phase == UploadPhase.Uploading }
    private fun update(key: String, change: (UploadEntry) -> UploadEntry) { entries = entries.map { if (it.key == key) change(it) else it } }

    fun clear() { runner?.cancel(); current?.second?.cancel(); jobs.clear(); created.clear(); entries = emptyList() }
    fun addUris(uris: List<Uri>) {
        m.viewModelScope.launch {
            try { add(withContext(Dispatchers.IO) { uris.map { describe(it) } }) } catch (e: CancellationException) { throw e } catch (e: Exception) { m.report(e) }
        }
    }
    /** Walks a folder chosen with the system picker and uploads every file inside it, recreating its subfolders. */
    fun addTree(tree: Uri) {
        m.viewModelScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    val resolver = m.context.contentResolver
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
                                else out += PickedFile(DocumentsContract.buildDocumentUriUsingTree(tree, child), name, if (c.isNull(3)) 0 else c.getLong(3), mime, path)
                            }
                        }
                    }
                    walk(rootId, listOf(rootName))
                    out
                }
                if (files.isEmpty()) m.notify("This folder has no files to upload.") else add(files)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { m.report(e) }
        }
    }
    private fun describe(uri: Uri): PickedFile {
        val resolver = m.context.contentResolver
        var name = "Upload"; var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { c.getString(0)?.let { name = it }; if (!c.isNull(1)) size = c.getLong(1) }
        }
        return PickedFile(uri, name, size, resolver.getType(uri) ?: "application/octet-stream")
    }
    /** Uploads a file chosen on this phone as a new version of [item], keeping its name. */
    fun addVersion(uri: Uri, item: DriveItem) {
        m.viewModelScope.launch {
            try {
                val picked = withContext(Dispatchers.IO) { describe(uri) }.copy(name = item.name)
                add(listOf(picked), UploadTarget.Replace(item), item.parentId)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { m.report(e) }
        }
    }
    fun add(files: List<PickedFile>, target: UploadTarget = UploadTarget.New, into: String? = null) {
        val drive = m.drive
        if (target is UploadTarget.Replace) { if (!drive.canEdit(target.item)) { m.error = "You can’t upload to this folder."; return } }
        else if (!drive.canWriteHere) { m.error = "Uploads are unavailable in this location"; return }
        enqueue(files, target, if (target is UploadTarget.Replace) into else drive.folderId)
    }
    /** Uploads files unpacked on this phone (from an archive) into [parent]; null is the top of My Drive. */
    fun addCopies(files: List<PickedFile>, parent: String?) = enqueue(files, UploadTarget.New, parent)
    private fun enqueue(files: List<PickedFile>, target: UploadTarget, base: String?) {
        val batch = UUID.randomUUID().toString()
        val queued = files.mapIndexed { index, file ->
            val key = "$batch/$index"
            jobs[key] = Job(file, batch, base, target = target)
            UploadEntry(key, file.name, file.size, groupKey = file.folders.firstOrNull()?.let { "$batch/$it" }, groupName = file.folders.firstOrNull())
        }
        entries = entries + queued
        drain()
    }
    private fun drain() {
        if (runner?.isActive == true) return
        runner = m.viewModelScope.launch {
            while (true) {
                val key = entries.firstOrNull { it.phase == UploadPhase.Queued && jobs[it.key]?.stopped == false }?.key ?: break
                val job = launch { run(key) }
                current = key to job
                job.join()
                current = null
            }
        }
    }
    private suspend fun run(key: String) {
        val job = jobs[key] ?: return
        val scratch = File(m.context.cacheDir, "transfers/${UUID.randomUUID()}")
        try {
            update(key) { it.copy(phase = UploadPhase.Hashing, error = null, loaded = 0, conflict = null) }
            val parent = parentFor(job)
            val name = safeName(job.file.name)
            val target = job.target
            withContext(Dispatchers.IO) {
                check(scratch.parentFile!!.mkdirs() || scratch.parentFile!!.isDirectory)
                m.context.contentResolver.openInputStream(job.file.uri)?.use { input -> scratch.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) { ensureActive(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
                } } ?: error("The selected file could not be opened. Select it again.")
            }
            val size = scratch.length()
            update(key) { it.copy(size = size) }
            val progress = { label: String, fraction: Float? ->
                m.viewModelScope.launch {
                    update(key) { it.copy(phase = if (label.startsWith("Uploading")) UploadPhase.Uploading else UploadPhase.Hashing,
                        loaded = ((fraction ?: 0f) * size).toLong()) }
                }
                Unit
            }
            when (target) {
                is UploadTarget.Replace -> m.transfers.upload(scratch, name, job.file.mime, parent, {
                    put("driveItemId", target.item.id); target.item.revision?.let { put("baseRevision", it) }
                }, progress = progress)
                // Keeping both tries “name (2)”, “name (3)” and so on until a name is free.
                UploadTarget.KeepBoth -> for (n in 1..50) {
                    try { m.transfers.upload(scratch, if (n == 1) name else numberedName(name, n), job.file.mime, parent, progress = progress); break }
                    catch (e: ApiException) { if (e.code != "NAME_CONFLICT" || n == 50) throw e }
                }
                UploadTarget.New -> m.transfers.upload(scratch, name, job.file.mime, parent, progress = progress)
            }
            update(key) { it.copy(phase = UploadPhase.Done, loaded = it.size) }
            jobs.remove(key)
            withContext(Dispatchers.IO) { job.file.temporary?.delete() }
            m.drive.refresh(); m.refreshAccount()
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
            update(key) { it.copy(phase = UploadPhase.Failed, error = uploadErrorMessage(e, m::message), conflict = conflict) }
        } finally { withContext(NonCancellable + Dispatchers.IO) { scratch.delete() } }
    }
    /** The item in [parent] whose name matches, the way the server compares names. */
    private suspend fun findByName(parent: String?, name: String): DriveItem? {
        val wanted = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC).lowercase()
        return m.api.all<DriveItem>("/v1/drive/folders/${HarborApi.segment(parent ?: "root")}/children")
            .firstOrNull { java.text.Normalizer.normalize(it.name, java.text.Normalizer.Form.NFC).lowercase() == wanted }
    }
    /** Uploads files whose name is taken as new versions of the existing files. */
    fun replace(keys: List<String>) {
        m.viewModelScope.launch {
            for (key in keys) {
                val job = jobs[key] ?: continue
                // Always the latest revision, so a replace that lost a race can simply be tried again.
                val existing = runCatching {
                    (job.target as? UploadTarget.Replace)?.let { m.api.get<ItemResponse>("/v1/drive/items/${HarborApi.segment(it.item.id)}").item }
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
            parent = folders[path] ?: m.api.createFolder(name, parent).id.also { folders[path] = it }
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
    }
    fun resume(keys: List<String>) {
        for (key in keys) { jobs[key]?.stopped = false; if (jobs.containsKey(key)) update(key) { it.copy(phase = UploadPhase.Queued, error = null) } }
        drain()
    }
    fun cancel(keys: List<String>) {
        for (key in keys) {
            val job = jobs.remove(key) ?: continue
            job.stopped = true; job.file.temporary?.delete()
            if (current?.first == key) current?.second?.cancel()
        }
        entries = entries.filter { it.key !in keys }
    }
    fun dismiss() {
        val finished = summarizeUploads(entries).filter { it.phase == UploadPhase.Done || it.phase == UploadPhase.Failed }.flatMap { it.keys }.toSet()
        finished.forEach { jobs.remove(it) }
        entries = entries.filter { it.key !in finished }
    }
}
