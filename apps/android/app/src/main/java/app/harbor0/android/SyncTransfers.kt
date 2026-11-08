package app.harbor0.android

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A local read that failed while a request body was being sent: a problem with the file, not the network. */
class LocalReadException(val error: LocalFsException): IOException(error.message, error)

/** A file's modification time as uploads and backup entries record it; none when the provider doesn't say. */
fun modifiedAt(mtime: Long): String? = if (mtime > 0) Instant.ofEpochMilli(mtime).toString() else null

/** One small file of a batch, ready to go: its cloud folder exists and its content is hashed. */
data class BatchFile(val jobId: String, val relativePath: String, val name: String, val size: Long, val mtime: Long, val hash: String,
    val parentId: String?, val operationId: String, val known: LocalFile? = null)
/** Where a batch goes: a backup run's routes, or a synced folder's. */
data class BatchRoutes(val create: String, val finish: String, val backup: Boolean) {
    companion object {
        val SYNC = BatchRoutes("/v1/uploads/batch", "/v1/uploads/batch/finish", false)
        fun backup(rootId: String, runId: String) = "/v1/backups/${HarborApi.segment(rootId)}/runs/${HarborApi.segment(runId)}".let {
            BatchRoutes("$it/uploads/batch", "$it/files/batch", true)
        }
    }
}
/** What happened to one file sent in a batch. */
sealed interface BatchOutcome {
    /** Uploaded; a backup batch also recorded it in the run as [entry]. */
    data class Saved(val item: DriveItem, val entry: BackupEntry? = null): BatchOutcome
    data class Failed(val error: Exception): BatchOutcome
    /** Needs the single-file path: it already exists in the cloud, or changed while being sent. Its upload id, if any, is kept. */
    data object Alone: BatchOutcome
    /** The server ran out of time before it; it is sent again. */
    data object NotReached: BatchOutcome
    /** Nothing to upload after all: unchanged since it was last synced, or gone. */
    data object Done: BatchOutcome
}
/** Files of a batch sent to storage at once. */
private const val BATCH_PUT_CONCURRENCY = 8
/**
 * Plain-HTTP storage links are accepted only from a local backend: in a debug build, or a profile build pointed at
 * one with -PharborApiUrl (a release build's API is always HTTPS).
 */
val LOCAL_STORAGE_ALLOWED = BuildConfig.DEBUG || BuildConfig.API_URL.startsWith("http://")
/** A batched file's refusal: retried alone, sent again, or failed like a single upload. */
fun batchFailure(error: JsonObject?): BatchOutcome {
    val code = error?.get("code")?.jsonPrimitive?.contentOrNull
    if (code == "BATCH_NOT_REACHED" || code == "CONCURRENT_UPDATE") return BatchOutcome.NotReached
    if (code in listOf("NAME_CONFLICT", "REVISION_CONFLICT", "INVALID_UPLOAD_STATE", "IDEMPOTENCY_KEY_REUSED")) return BatchOutcome.Alone
    return BatchOutcome.Failed(ApiException(409, error?.get("message")?.jsonPrimitive?.contentOrNull ?: "The file could not be uploaded.", code ?: "UPLOAD_FAILED",
        error?.get("details") as? JsonObject))
}
/** Errors that end the whole pass rather than one file: lost connections, throttling and server errors. */
private fun passing(error: Throwable) = (error is IOException && error !is LocalReadException) ||
    (error is ApiException && (error.status >= 500 || error.status == 429 || error.code == "CONCURRENT_UPDATE"))

/**
 * Resumable transfers between a local folder and the cloud (desktop `transfers.ts`): multipart uploads whose
 * state is persisted in the job, and downloads to a `.harbor-part` file that is verified by size and SHA-256
 * before it replaces the destination.
 */
class SyncTransfers(private val api: EngineApi, private val storage: OkHttpClient = OkHttpClient.Builder()
    .callTimeout(10, TimeUnit.MINUTES).readTimeout(60, TimeUnit.SECONDS).followRedirects(false).build(), private val allowLocal: Boolean = LOCAL_STORAGE_ALLOWED,
    /** On an end-to-end encrypted account: uploads are encrypted and downloads decrypted (desktop `transferKeys`). */
    private val keys: TransferKeys = TransferKeys.NONE) {
    private val reports get() = ProblemReports.of(api.api)

    /** Uploads one file, resuming the upload in [state]. A failure is reported (without names) for the staff console. */
    suspend fun upload(tree: LocalTree, relative: String, name: String, parentId: String?, state: UploadState, persist: () -> Unit,
        existing: LocalFile? = null, progress: (Long, Long) -> Unit = { _, _ -> }, beforeComplete: suspend () -> Unit = {},
        backup: Pair<String, String>? = null): DriveItem {
        val stage = arrayOf("hash")
        return try { send(tree, relative, name, parentId, state, persist, existing, progress, beforeComplete, backup, stage) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            reports.report("UPLOAD_FAILED", stage[0], e, state.uploadId, state.size); throw e
        }
    }

    private suspend fun send(tree: LocalTree, relative: String, name: String, parentId: String?, state: UploadState, persist: () -> Unit,
        existing: LocalFile?, progress: (Long, Long) -> Unit, beforeComplete: suspend () -> Unit, backup: Pair<String, String>?, stage: Array<String>): DriveItem {
        stage[0] = "hash"
        val info = tree.stat(relative) ?: throw LocalFsException("ENOENT", "“$name” could not be found.", relative)
        val keys = keys.forUpload()
        // A changed file, or encryption turned on or off since the upload started, starts over.
        if ((state.mtime != null && (state.mtime != info.mtime || state.size != info.size)) || (state.uploadId != null && (state.header != null) != (keys != null))) {
            state.uploadId?.let { runCatching { api.request("/v1/uploads/${HarborApi.segment(it)}", "DELETE") } }
            state.operationId = UUID.randomUUID().toString(); state.uploadId = null; state.partSize = null; state.hash = null; state.parts = null
            state.header = null; state.storedHash = null
        }
        state.mtime = info.mtime; state.size = info.size
        if (state.hash == null) state.hash = tree.hash(relative)
        persist()
        stage[0] = "create"
        // A freshly created upload has no parts yet; only a resumed one needs its server status.
        var created = false
        var cipher: FileCipher? = null
        if (state.uploadId == null) {
            cipher = keys?.let { FileCipher.create(it) }
            state.header = cipher?.header?.let(E2ee::b64url); state.storedHash = null
            val input = buildJsonObject {
                put("operationId", state.operationId); put("parentId", parentId); put("name", name)
                // The server only ever sees the encrypted size and hash of an encrypted file.
                if (cipher != null) { put("sizeBytes", cipher.storedSize(info.size)); put("encrypted", true) }
                else { put("sizeBytes", info.size); put("contentHash", state.hash) }
                put("mimeType", "application/octet-stream")
                // Kept with the version, so a restore can give the file back its time.
                modifiedAt(info.mtime)?.let { put("modifiedAt", it) }
                if (existing != null) { put("driveItemId", existing.itemId); put("baseRevision", existing.revision) }
            }
            val result = if (backup != null) api.request("/v1/backups/${HarborApi.segment(backup.first)}/runs/${HarborApi.segment(backup.second)}/uploads", "POST", input)
                else api.request("/v1/uploads", "POST", input)
            val upload = result["upload"]!!.jsonObject
            state.uploadId = upload["id"]!!.jsonPrimitive.content
            state.partSize = upload["partSizeBytes"]!!.jsonPrimitive.long
            persist()
            created = true
        }
        val route = "/v1/uploads/${HarborApi.segment(state.uploadId!!)}"
        val status = if (created) null else api.request(route)
        val upload = status?.get("upload")?.jsonObject
        val uploadState = upload?.get("state")?.jsonPrimitive?.contentOrNull
        if (uploadState == "COMPLETED") return harborJson.decodeFromJsonElement(upload["item"]!!)
        if (uploadState in listOf("ABORTED", "FAILED", "EXPIRED")) {
            state.uploadId = null; state.operationId = UUID.randomUUID().toString(); state.header = null; state.storedHash = null; persist()
            return send(tree, relative, name, parentId, state, persist, existing, progress, beforeComplete, backup, stage)
        }
        val partSize = state.partSize ?: throw IllegalStateException("The server returned an invalid upload size.")
        require(partSize > 0) { "The server returned an invalid upload size." }
        val parts = (if (uploadState == "COMPLETING") state.parts.orEmpty() else status?.get("parts")?.let { harborJson.decodeFromJsonElement<List<CompletedPart>>(it) }.orEmpty()).toMutableList()
        state.header?.let { header ->
            val sealing = cipher ?: FileCipher.open(keys ?: throw e2eeLocked(), E2ee.fromB64url(header))
            return sendEncrypted(tree, relative, info, state, persist, parts, sealing, partSize, progress, beforeComplete, stage)
        }
        val count = maxOf(1L, (info.size + partSize - 1) / partSize).toInt()
        val done = parts.map { it.partNumber }.toSet()
        fun bytes(n: Int) = minOf(partSize, info.size - (n - 1) * partSize)
        var loaded = parts.sumOf { bytes(it.partNumber) }
        progress(loaded, info.size)
        stage[0] = "storage"
        for (number in (1..count).filter { it !in done }) {
            currentCoroutineContext().ensureActive()
            val offset = (number - 1) * partSize
            val length = bytes(number)
            var etag: String? = null
            val sent = AtomicLong()
            reporting(sent, { progress(loaded + it, info.size) }) { for (attempt in 0..2) {
                try {
                    val urls = api.request("$route/parts", "POST", buildJsonObject { put("partNumbers", JsonArray(listOf(JsonPrimitive(number)))) })
                    val url = urls["parts"]!!.jsonArray.map { it.jsonObject }.first { it["partNumber"]!!.jsonPrimitive.int == number }["uploadUrl"]!!.jsonPrimitive.content
                    FileTransfers.validateUrl(url, api.baseUrl, allowLocal)
                    val body = object: RequestBody() {
                        override fun contentType(): MediaType? = null
                        override fun contentLength() = length
                        override fun writeTo(sink: BufferedSink) {
                            // Failures reading the local file are reported as that file's problem, not a network error.
                            fun <T> local(block: () -> T): T = try { block() } catch (e: LocalFsException) { throw LocalReadException(e) }
                                catch (e: IOException) { throw LocalReadException(localError(e, relative)) }
                            sent.set(0)
                            val input = try { tree.openRead(relative) } catch (e: LocalFsException) { throw LocalReadException(e) }
                            input.use {
                                var skipped = 0L
                                while (skipped < offset) {
                                    val n = local { input.skip(offset - skipped) }
                                    if (n <= 0) throw LocalReadException(FileChangedException("File changed during upload. Retry to preserve the latest content.", relative))
                                    skipped += n
                                }
                                var remaining = length
                                val buffer = ByteArray(256 * 1024)
                                while (remaining > 0) {
                                    val read = local { input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()) }
                                    if (read <= 0) throw LocalReadException(FileChangedException("File changed during upload. Retry to preserve the latest content.", relative))
                                    sink.write(buffer, 0, read); remaining -= read
                                    sent.set(length - remaining)
                                }
                            }
                        }
                    }
                    storage.newCall(Request.Builder().url(url).put(body).build()).awaitResponse().use { response ->
                        etag = if (response.isSuccessful) response.header("ETag") else null
                    }
                    if (etag.isNullOrBlank()) throw IOException("Storage upload failed. It will resume automatically.")
                    return@reporting
                } catch (e: LocalReadException) { throw e.error }
                catch (e: IOException) {
                    reports.report("UPLOAD_PART_RETRY", "storage", e, state.uploadId, info.size, number, attempt)
                    if (attempt == 2) throw e
                } catch (e: ApiException) { reports.report("UPLOAD_PART_RETRY", "parts", e, state.uploadId, info.size, number, attempt); throw e }
            } }
            parts += CompletedPart(number, etag!!)
            state.parts = parts.toList(); persist()
            loaded += length
            progress(loaded, info.size)
        }
        val after = tree.stat(relative)
        if (after == null || after.mtime != state.mtime || after.size != state.size) throw FileChangedException("File changed while uploading. The next attempt will restart safely.", relative)
        beforeComplete()
        stage[0] = "complete"
        return harborJson.decodeFromJsonElement<ItemResponse>(api.request("$route/complete", "POST", buildJsonObject {
            put("parts", harborJson.encodeToJsonElement(parts.sortedBy { it.partNumber })); put("contentHash", state.hash)
        })).item
    }

    /** PUTs one part to storage, signing a new link for each attempt; returns its ETag. Local read failures are the file's problem. */
    /** Runs [block] while reporting the bytes [sent] so far every 250 ms: a part can take minutes on a slow connection. */
    private suspend fun <T> reporting(sent: AtomicLong, report: (Long) -> Unit, block: suspend () -> T): T = coroutineScope {
        val ticker = launch { while (true) { delay(250); report(sent.get()) } }
        try { block() } finally { ticker.cancel() }
    }
    private suspend fun putPart(route: String, state: UploadState, number: Int, length: Long, fileSize: Long, sent: AtomicLong = AtomicLong(),
        write: (BufferedSink) -> Unit): String {
        for (attempt in 0..2) {
            try {
                val urls = api.request("$route/parts", "POST", buildJsonObject { put("partNumbers", JsonArray(listOf(JsonPrimitive(number)))) })
                val url = urls["parts"]!!.jsonArray.map { it.jsonObject }.first { it["partNumber"]!!.jsonPrimitive.int == number }["uploadUrl"]!!.jsonPrimitive.content
                FileTransfers.validateUrl(url, api.baseUrl, allowLocal)
                val body = object: RequestBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = length
                    override fun writeTo(sink: BufferedSink) {
                        sent.set(0)
                        val counted = object: ForwardingSink(sink) {
                            override fun write(source: Buffer, byteCount: Long) { super.write(source, byteCount); sent.addAndGet(byteCount) }
                        }.buffer()
                        write(counted); counted.emit()
                    }
                }
                val etag = storage.newCall(Request.Builder().url(url).put(body).build()).awaitResponse().use { if (it.isSuccessful) it.header("ETag") else null }
                if (!etag.isNullOrBlank()) return etag
                throw IOException("Storage upload failed. It will resume automatically.")
            } catch (e: LocalReadException) { throw e.error }
            catch (e: IOException) {
                reports.report("UPLOAD_PART_RETRY", "storage", e, state.uploadId, fileSize, number, attempt)
                if (attempt == 2) throw e
            } catch (e: ApiException) { reports.report("UPLOAD_PART_RETRY", "parts", e, state.uploadId, fileSize, number, attempt); throw e }
        }
        throw IOException("Storage upload failed. It will resume automatically.")
    }

    /**
     * The parts of an encrypted upload (desktop `uploadEncrypted`). They are sealed in order, so the stored bytes'
     * SHA-256 is known at the end without reading the file twice. Sealing is deterministic for the saved header, so a
     * resumed upload re-seals the parts storage already has only to hash them.
     */
    private suspend fun sendEncrypted(tree: LocalTree, relative: String, info: LocalEntry, state: UploadState, persist: () -> Unit,
        parts: MutableList<CompletedPart>, cipher: FileCipher, partSize: Long, progress: (Long, Long) -> Unit, beforeComplete: suspend () -> Unit,
        stage: Array<String>): DriveItem {
        val route = "/v1/uploads/${HarborApi.segment(state.uploadId!!)}"
        val stored = cipher.storedSize(info.size)
        val count = maxOf(1L, (stored + partSize - 1) / partSize).toInt()
        fun bytes(n: Int) = minOf(partSize, stored - (n - 1) * partSize)
        // Progress is shown against the file's own size.
        fun shown(loaded: Long) = if (stored == 0L) 0 else loaded * info.size / stored
        val done = parts.map { it.partNumber }.toSet()
        var loaded = parts.sumOf { bytes(it.partNumber) }
        progress(shown(loaded), info.size)
        fun <T> local(block: () -> T): T = try { block() } catch (e: LocalFsException) { throw LocalReadException(e) }
            catch (e: IOException) { throw LocalReadException(localError(e, relative)) }
        if (state.storedHash == null || parts.size < count) {
            stage[0] = "storage"
            val hash = StoredHash()
            for (number in 1..count) {
                currentCoroutineContext().ensureActive()
                val start = (number - 1) * partSize
                val end = start + bytes(number)
                if (number in done) {
                    // Already in storage: sealed again only for the hash.
                    try { PlainReader { tree.openRead(relative) }.use { reader -> local { cipher.sealRange(info.size, start, end, reader::read, hash::offer) } } }
                    catch (e: LocalReadException) { throw e.error }
                    continue
                }
                val sent = AtomicLong()
                val etag = reporting(sent, { progress(shown(loaded + it), info.size) }) { putPart(route, state, number, end - start, stored, sent) { sink ->
                    PlainReader { local { tree.openRead(relative) } }.use { reader ->
                        cipher.sealRange(info.size, start, end, { b, e -> local { reader.read(b, e) } }) { at, piece -> hash.offer(at, piece); sink.write(piece) }
                    }
                } }
                parts += CompletedPart(number, etag)
                state.parts = parts.toList(); persist()
                loaded += end - start
                progress(shown(loaded), info.size)
            }
            if (hash.length != stored) throw FileChangedException("File changed while uploading. The next attempt will restart safely.", relative)
            state.storedHash = hash.hex(); persist()
        }
        val after = tree.stat(relative)
        if (after == null || after.mtime != state.mtime || after.size != state.size) throw FileChangedException("File changed while uploading. The next attempt will restart safely.", relative)
        beforeComplete()
        stage[0] = "complete"
        return harborJson.decodeFromJsonElement<ItemResponse>(api.request("$route/complete", "POST", buildJsonObject {
            put("parts", harborJson.encodeToJsonElement(parts.sortedBy { it.partNumber })); put("contentHash", state.storedHash)
        })).item
    }

    /**
     * Downloads into `name.harbor-part` (resuming a partial one), verifies it, then replaces [destination]. Returns its
     * hash. [modifiedAt], when known (a restored backup version), is set back on the file where the folder allows it.
     * A file damaged while it was uploaded fails at once with [UploadMismatchException], leaving everything local alone.
     */
    suspend fun download(tree: LocalTree, input: JsonObject, destination: String, progress: (Long, Long) -> Unit = { _, _ -> },
        beforeReplace: suspend () -> Unit = {}, modifiedAt: String? = null): String {
        val part = "$destination.harbor-part"
        val existing = tree.stat(part)
        var offset = if (existing != null && !existing.folder) existing.size else 0L
        if (existing?.folder == true) throw LocalFsException("EEXIST", "A folder blocks this download.", part)
        val signed = harborJson.decodeFromJsonElement<Download>(api.request("/v1/downloads", "POST", input))
        if (signed.uploadMismatch) throw UploadMismatchException()
        // Known before anything is downloaded: an encrypted file can't be put in place without the key.
        val readKeys = if (signed.encrypted) keys.forRead() else null
        FileTransfers.validateUrl(signed.downloadUrl, api.baseUrl, allowLocal)
        if (offset > signed.sizeBytes) { tree.delete(part); offset = 0 }
        var digest = MessageDigest.getInstance("SHA-256")
        if (offset > 0) tree.openRead(part).use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val n = try { stream.read(buffer) } catch (e: IOException) { throw localError(e, part) }; if (n < 0) break; digest.update(buffer, 0, n) }
        }
        if (offset < signed.sizeBytes || signed.sizeBytes == 0L) {
            val request = Request.Builder().url(signed.downloadUrl).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
            storage.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) throw IOException("Download interrupted. It will resume automatically.")
                if (response.code == 206 && response.header("Content-Range") != "bytes $offset-${signed.sizeBytes - 1}/${signed.sizeBytes}")
                    throw IOException("Storage returned an unexpected download range.")
                // Servers that ignore Range send the complete file; start over.
                if (offset > 0 && response.code != 206) { offset = 0; digest = MessageDigest.getInstance("SHA-256"); tree.delete(part) }
                if (offset == 0L && tree.stat(part) == null) tree.createFile(part)
                val body = response.body ?: throw IOException("Download interrupted. It will resume automatically.")
                tree.openWrite(part, append = offset > 0).use { output ->
                    body.byteStream().use { stream ->
                        val buffer = ByteArray(256 * 1024)
                        var loaded = offset
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = stream.read(buffer)
                            if (n < 0) break
                            if (loaded + n > signed.sizeBytes) throw IOException("Download exceeded its expected size.")
                            try { output.write(buffer, 0, n) } catch (e: IOException) { throw localError(e, part) }
                            digest.update(buffer, 0, n)
                            loaded += n
                            progress(loaded, signed.sizeBytes)
                        }
                    }
                }
            }
        }
        val downloaded = tree.stat(part)
        if (downloaded?.size != signed.sizeBytes || !digest.digest().joinToString("") { "%02x".format(it) }.equals(signed.contentHash, ignoreCase = true)) {
            runCatching { tree.delete(part) }
            throw LocalFsException("EIO", "Download integrity check failed. The original local file was kept.", destination)
        }
        // An encrypted download is decrypted beside it first; the hash returned is the file's as written.
        var written = part
        var localHash = signed.contentHash.lowercase()
        if (readKeys != null) {
            written = "$destination.harbor-plain"
            localHash = try { decrypt(tree, part, written, signed.sizeBytes, readKeys) } catch (e: Exception) {
                runCatching { tree.delete(written) }
                // Damaged bytes come down again next time rather than failing for good.
                runCatching { tree.delete(part) }
                throw e
            }
        }
        beforeReplace()
        if (tree.stat(destination) != null) tree.delete(destination)
        tree.rename(written, destination)
        if (written != part) runCatching { tree.delete(part) }
        modifiedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }?.let { tree.setModified(destination, it) }
        return localHash
    }
    /** Decrypts a checked encrypted download into [target], chunk by chunk; returns the SHA-256 of the decrypted file. */
    private fun decrypt(tree: LocalTree, source: String, target: String, storedSize: Long, keys: AccountKeys): String {
        if (tree.stat(target) != null) tree.delete(target)
        tree.createFile(target)
        val digest = MessageDigest.getInstance("SHA-256")
        tree.openRead(source).use { input ->
            tree.openWrite(target, append = false).use { output ->
                val hashing = object: java.io.FilterOutputStream(output) {
                    override fun write(b: ByteArray, off: Int, len: Int) { digest.update(b, off, len); out.write(b, off, len) }
                }
                val sink = DecryptingOutputStream(keys, storedSize, hashing)
                val buffer = ByteArray(256 * 1024)
                while (true) { val n = try { input.read(buffer) } catch (e: IOException) { throw localError(e, source) }; if (n < 0) break; sink.write(buffer, 0, n) }
                sink.finish()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Uploads several small files together (desktop `sendBatch`): one request creates their uploads, each with one
     * storage link, they go to storage [BATCH_PUT_CONCURRENCY] at a time, and one request finishes them (and, for a
     * backup, records them in the run). A file's upload is handed to [persist] as soon as it exists, so one the batch
     * doesn't finish resumes alone instead of starting over. Null when the server has no batch routes.
     */
    suspend fun batch(tree: LocalTree, routes: BatchRoutes, files: List<BatchFile>, persist: (BatchFile, UploadState) -> Unit,
        progress: (Long, Long) -> Unit = { _, _ -> }): Map<String, BatchOutcome>? {
        val outcomes = linkedMapOf<String, BatchOutcome>()
        fun alone(list: List<BatchFile>) = outcomes.also { list.forEach { outcomes[it.jobId] = BatchOutcome.Alone } }
        // An encrypted account's files each get their own key and are encrypted before they go; their names are
        // encrypted on the way by the transport. Encryption adds a little to every file; one that then outgrows a batch goes alone.
        val keys = keys.forUpload()
        val (fits, large) = files.partition { keys == null || E2ee.encryptedSize(it.size) <= BATCH_UPLOAD_MAX_BYTES }
        alone(large)
        if (fits.isEmpty()) return outcomes
        val ciphers = fits.associate { it.jobId to keys?.let { k -> FileCipher.create(k) } }
        val created = try {
            api.request(routes.create, "POST", buildJsonObject {
                put("uploads", JsonArray(fits.map { file -> buildJsonObject {
                    put("operationId", file.operationId); put("parentId", file.parentId); put("name", file.name)
                    // The server only ever sees the encrypted size and hash of an encrypted file.
                    val cipher = ciphers[file.jobId]
                    if (cipher != null) { put("sizeBytes", cipher.storedSize(file.size)); put("encrypted", true) }
                    else { put("sizeBytes", file.size); put("contentHash", file.hash) }
                    put("mimeType", "application/octet-stream")
                    modifiedAt(file.mtime)?.let { put("modifiedAt", it) }
                    file.known?.let { put("driveItemId", it.itemId); put("baseRevision", it.revision) }
                } }))
            })["results"]?.jsonArray.orEmpty()
        } catch (e: ApiException) {
            // An older server has no batch routes.
            if (e.status == 404 && (e.code == null || e.code == "NOT_FOUND")) return null
            if (passing(e)) throw e
            return alone(fits)
        }
        data class Open(val file: BatchFile, val uploadId: String, val url: String, val state: UploadState)
        val open = mutableListOf<Open>()
        fits.forEachIndexed { n, file ->
            val result = created.getOrNull(n) as? JsonObject
            val upload = result?.get("upload") as? JsonObject
            val url = result?.get("uploadUrl")?.jsonPrimitive?.contentOrNull
            val id = upload?.get("id")?.jsonPrimitive?.contentOrNull
            if (upload == null || url == null || id == null) { outcomes[file.jobId] = batchFailure(result?.get("error") as? JsonObject); return@forEachIndexed }
            // Kept so a file the batch doesn't finish resumes alone with the same upload and key.
            val state = UploadState(file.operationId, id, upload["partSizeBytes"]?.jsonPrimitive?.longOrNull ?: file.size, file.hash, file.size, file.mtime,
                header = ciphers[file.jobId]?.header?.let(E2ee::b64url))
            persist(file, state)
            open += Open(file, id, url, state)
        }
        // To storage side by side; a file that changed meanwhile, or that storage refused, goes alone and resumes the upload it already has.
        val total = open.sumOf { it.file.size }
        // Bytes on their way, counted as storage takes them (from OkHttp's threads) and reported from here every 250 ms,
        // so a batch of large photos shows movement long before its first file finishes.
        val sending = open.map { AtomicLong() }
        val gate = Semaphore(BATCH_PUT_CONCURRENCY)
        val etags = coroutineScope {
            val ticker = launch { while (true) { progress(sending.sumOf { it.get() }, total); delay(250) } }
            open.mapIndexed { n, item -> async { gate.withPermit {
                val etag = try { putOne(tree, item.file, item.uploadId, item.url, ciphers[item.file.jobId], sending[n]) { storedHash ->
                    item.state.storedHash = storedHash; persist(item.file, item.state)
                } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    if (passing(e)) throw e
                    null
                }
                if (etag == null) outcomes[item.file.jobId] = BatchOutcome.Alone
                sending[n].set(item.file.size)
                etag
            } } }.awaitAll().also { ticker.cancel(); progress(total, total) }
        }
        val sent = open.zip(etags).mapNotNull { (item, etag) -> etag?.let { item to it } }
        if (sent.isEmpty()) return outcomes
        val finished = try {
            api.request(routes.finish, "POST", buildJsonObject {
                put("files", JsonArray(sent.map { (item, etag) -> buildJsonObject {
                    // An encrypted file is finished with the hash of what was stored.
                    put("uploadId", item.uploadId); put("etag", etag); put("contentHash", item.state.storedHash ?: item.file.hash)
                    // What the backup run records of each file.
                    if (routes.backup) { put("relativePath", item.file.relativePath); put("modifiedAt", modifiedAt(item.file.mtime) ?: Instant.EPOCH.toString()); put("savedAt", Instant.now().toString()) }
                } }))
            })["results"]?.jsonArray.orEmpty()
        } catch (e: ApiException) { if (passing(e)) throw e; return alone(sent.map { it.first.file }) }
        sent.forEachIndexed { n, (item, _) ->
            val result = finished.getOrNull(n) as? JsonObject
            val saved = result?.get("item")?.let { runCatching { harborJson.decodeFromJsonElement<DriveItem>(it) }.getOrNull() }
            val entry = result?.get("entry")?.let { runCatching { harborJson.decodeFromJsonElement<BackupEntry>(it) }.getOrNull() }
            outcomes[item.file.jobId] = if (saved == null || (routes.backup && entry == null)) batchFailure(result?.get("error") as? JsonObject)
                else BatchOutcome.Saved(saved, entry)
        }
        return outcomes
    }
    /** PUTs a batched file's one part, signing a new link for each retry. Null when it changed or storage kept refusing it. */
    private suspend fun putOne(tree: LocalTree, file: BatchFile, uploadId: String, firstUrl: String, cipher: FileCipher?, sent: AtomicLong, sealed: (String) -> Unit): String? {
        val plain = try { tree.openRead(file.relativePath).use { it.readBytes() } } catch (e: LocalFsException) { return null } catch (e: IOException) { return null }
        if (plain.size.toLong() != file.size) return null
        val bytes = if (cipher == null) plain else cipher.encryptedRange(file.size, 0, cipher.storedSize(file.size)) { b, e -> plain.copyOfRange(b.toInt(), e.toInt()) }
        if (cipher != null) sealed(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        var url = firstUrl
        for (attempt in 0..2) {
            try {
                if (attempt > 0) url = api.request("/v1/uploads/${HarborApi.segment(uploadId)}/parts", "POST", buildJsonObject { put("partNumbers", JsonArray(listOf(JsonPrimitive(1)))) })["parts"]!!
                    .jsonArray.first().jsonObject["uploadUrl"]!!.jsonPrimitive.content
                FileTransfers.validateUrl(url, api.baseUrl, allowLocal)
                var status = 0
                val etag = storage.newCall(Request.Builder().url(url).put(object: RequestBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = bytes.size.toLong()
                    // Written a piece at a time so [sent] follows the upload, against the file's own size (an encrypted file is a little larger).
                    override fun writeTo(sink: BufferedSink) {
                        sent.set(0)
                        var at = 0
                        while (at < bytes.size) {
                            val end = minOf(bytes.size, at + 64 * 1024)
                            sink.write(bytes, at, end - at); sink.flush()
                            at = end
                            sent.set(at.toLong() * file.size / maxOf(1, bytes.size))
                        }
                    }
                }).build()).awaitResponse().use { status = it.code; if (it.isSuccessful) it.header("ETag") else null }
                // Refused by storage: tried again with a new link, then the file goes alone.
                if (etag.isNullOrBlank()) {
                    reports.report("UPLOAD_PART_RETRY", "storage", null, uploadId, file.size, 1, attempt, if (status in 200..299) "NO_ETAG" else "STORAGE_REJECTED", status)
                    continue
                }
                val after = tree.stat(file.relativePath)
                return if (after == null || after.size != file.size || after.mtime != file.mtime) null else etag
            } catch (e: IOException) {
                reports.report("UPLOAD_PART_RETRY", "storage", e, uploadId, file.size, 1, attempt)
                if (attempt == 2) throw e
            } catch (e: ApiException) { reports.report("UPLOAD_PART_RETRY", "parts", e, uploadId, file.size, 1, attempt); throw e }
        }
        return null
    }
}
