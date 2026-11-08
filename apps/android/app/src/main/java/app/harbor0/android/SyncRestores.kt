package app.harbor0.android

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.IOException

/**
 * A restore under way in one backup folder: [done] files restored (or found already restored) of the [total] known
 * so far, and the file downloading now ([relativePath], [loaded] of [size] bytes). A folder's files are added to
 * [total] as it is listed. UI: "Restored $done of $total files…".
 */
data class RestoreProgress(val done: Int, val total: Int, val relativePath: String, val loaded: Long, val size: Long)
/** How the last restores in a backup folder went. [unchanged]: files in a restored folder that already matched their saved copy. */
@Serializable data class RestoreSummary(val restored: Int, val unchanged: Int, val failed: Int, val error: String? = null, val at: String = "")

/** Lost connections, throttling and server errors: the restore is tried again on the next pass. */
private fun retryLater(error: Throwable) = (error is IOException && error !is LocalReadException) ||
    (error is ApiException && (error.status >= 500 || error.status == 429))

/**
 * Restores requested from other devices, applied to a backup folder on this phone (desktop `backups.ts` restores):
 * one version of a file, or everything saved in a folder, each file at its latest version. Files that already match
 * are left alone, and restored files get back the time they were saved at where the folder allows it.
 */
class BackupRestores(private val api: EngineApi, private val transfers: SyncTransfers, private val store: KeyValues,
    /** The tracked file for a cloud item in a backup folder, if any. */
    private val known: (rootId: String, itemId: String) -> LocalFile?,
    /** Records what the engine wrote, so the next scan does not report it as a local edit. */
    private val written: (rootId: String, relative: String, stat: LocalStat?) -> Unit,
    /** Progress while restores run (null when they are done, with a summary of what was restored, if anything was). */
    private val onRestore: (rootId: String, progress: RestoreProgress?, summary: RestoreSummary?) -> Unit = { _, _, _ -> }) {
    private val nextCheck = mutableMapOf<String, Long>()
    private class Restoring(val root: SyncRoot, val local: LocalTree, val url: String, var done: Int, var total: Int,
        var restored: Int = 0, var unchanged: Int = 0, var failed: Int = 0, var error: String? = null)
    private fun ignored(root: SyncRoot, relative: String) = ignoredPath(root.excluded, relative)

    /** Looks for restore requests on the next pass instead of waiting for the usual check. */
    fun soon() = nextCheck.clear()

    suspend fun run(root: SyncRoot, local: LocalTree, url: String) {
        if (System.currentTimeMillis() < (nextCheck[root.id] ?: 0)) return
        nextCheck[root.id] = System.currentTimeMillis() + 10_000
        // Pending entries are removed on acknowledgement; always read from the beginning.
        val items = api.all<BackupRestore>("$url/pending-restores")
        // Requests already restored, waiting only to be acknowledged, are not counted again.
        val requests = items.filter { store.raw("backup-restore:${it.id}") == null }
        val restoring = Restoring(root, local, url, 0, requests.count { !it.folder })
        try {
            for (request in items) request(restoring, request)
        } finally {
            if (requests.isNotEmpty()) onRestore(root.id, null, (restoring.restored + restoring.unchanged + restoring.failed).takeIf { it > 0 }?.let {
                RestoreSummary(restoring.restored, restoring.unchanged, restoring.failed, restoring.error, java.time.Instant.now().toString())
            })
        }
    }

    private suspend fun request(restoring: Restoring, request: BackupRestore) {
        val root = restoring.root
        val key = "backup-restore:${request.id}"
        var receipt = store.raw(key)?.let { runCatching { harborJson.parseToJsonElement(it).jsonObject }.getOrNull() }
        if (receipt == null) {
            val relative = if (request.itemId == root.remoteId) "" else known(root.id, request.itemId)?.relativePath ?: request.relativePath
            receipt = try {
                val error = if (request.folder) folder(restoring, request.itemId, relative)
                    else file(restoring, request.itemId, request.versionId, relative, request.modifiedAt)
                buildJsonObject { error?.let { put("error", it) } }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (retryLater(e)) throw e
                buildJsonObject { put("error", (e.message ?: "The file could not be restored.").take(2000)) }
            }
            // Recorded before acknowledging so an offline retry cannot overwrite newer edits.
            store.setRaw(key, receipt.toString())
        }
        api.request("${restoring.url}/restores/${HarborApi.segment(request.id)}/complete", "POST", receipt)
        store.remove(key)
    }

    /**
     * Everything saved in a folder, each file at its latest version. Files that already match it are left alone.
     * Returns why some files could not be restored, if any could not.
     */
    private suspend fun folder(restoring: Restoring, folderId: String, relative: String): String? {
        val root = restoring.root
        val local = restoring.local
        if (relative.isNotEmpty() && !validRelative(relative)) throw IllegalStateException("Invalid restore destination.")
        val files = mutableListOf<Pair<DriveItem, String>>()
        suspend fun walk(parentId: String, folder: String) {
            var cursor: String? = null
            do {
                val page = api.list(parentId, cursor)
                for (item in page.items) {
                    val path = joinPath(folder, safeSegment(item.name, item.id))
                    if (ignored(root, path)) continue
                    if (item.isFolder) {
                        local.mkdirs(path); written(root.id, path, LocalStat(true, 0, 0))
                        walk(item.id, path)
                    } else files += item to path
                }
                onRestore(root.id, RestoreProgress(restoring.done, restoring.total + files.size, folder.ifEmpty { root.localName }, 0, 0), null)
                cursor = page.nextCursor
            } while (cursor != null)
        }
        if (relative.isNotEmpty()) { local.mkdirs(relative); written(root.id, relative, LocalStat(true, 0, 0)) }
        walk(folderId, relative)
        restoring.total += files.size
        val failures = mutableListOf<String>()
        for ((item, path) in files) {
            val saved = known(root.id, item.id)
            val info = local.stat(path)
            if (info != null && !info.folder && saved?.hash != null && saved.revision == item.revision && local.hash(path) == saved.hash) {
                restoring.done++; restoring.unchanged++
                continue
            }
            file(restoring, item.id, item.currentVersionId, path, item.modifiedAt)?.let { failures += it }
        }
        if (failures.isEmpty()) return null
        return "${if (failures.size == 1) "1 file was" else "${failures.size} files were"} not restored. ${failures[0]}".take(2000)
    }

    /**
     * One version of a file, written over the local copy unless that changes while it downloads. Returns why it could
     * not be restored; a lost connection or server error is thrown instead.
     */
    private suspend fun file(restoring: Restoring, itemId: String, versionId: String?, relative: String, modifiedAt: String?): String? {
        val root = restoring.root
        val local = restoring.local
        fun progress(loaded: Long, size: Long) = onRestore(root.id, RestoreProgress(restoring.done, restoring.total, relative, loaded, size), null)
        progress(0, 0)
        try {
            if (!validRelative(relative)) throw IllegalStateException("Invalid restore destination.")
            local.ensureParents(relative)
            val before = local.stat(relative)
            // A part file from a different selected version must not be resumed.
            if (local.stat("$relative.harbor-part") != null) local.delete("$relative.harbor-part")
            transfers.download(local, buildJsonObject { put("driveItemId", itemId); versionId?.let { put("versionId", it) } }, relative, ::progress, beforeReplace = {
                val connection = api.request(restoring.url)["root"]?.jsonObject
                if (connection?.get("state")?.jsonPrimitive?.contentOrNull == "REMOVED") throw ApiException(409, "Backup folder disconnected.", "BACKUP_DISCONNECTED")
                val current = local.stat(relative)
                if ((before == null) != (current == null) || (before != null && current != null && (before.mtime != current.mtime || before.size != current.size)))
                    throw IllegalStateException("The local file changed during restore. Retry after editing is finished.")
            }, modifiedAt = modifiedAt)
            written(root.id, relative, local.stat(relative)?.stat)
            restoring.restored++
            return null
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (retryLater(e)) throw e
            val reason = "${baseName(relative)}: ${e.message ?: "The file could not be restored."}"
            restoring.failed++
            if (restoring.error == null) restoring.error = reason
            return reason.take(2000)
        } finally { restoring.done++ }
    }
}
