package app.harbor0.android

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.BufferedSink
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.withLock

/** How long a signed storage link is reused for ranged reads before a new one is asked for (web `LINK_REUSE_MS`). */
const val LINK_REUSE_MS = 4 * 60_000L

class FileTransfers(private val api: HarborApi, private val storage: OkHttpClient = OkHttpClient.Builder()
    .callTimeout(5, TimeUnit.MINUTES).followRedirects(false).build(), private val allowLocal: Boolean = LOCAL_STORAGE_ALLOWED,
    /** On an end-to-end encrypted account: files are encrypted before upload and decrypted after download. */
    private val keys: TransferKeys = TransferKeys.NONE) {
    companion object {
        suspend fun digest(file: File): String = withContext(Dispatchers.IO) {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) { ensureActive(); val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
        fun validateUrl(value: String, base: String, allowLocal: Boolean) {
            val url = value.toHttpUrl()
            val apiUrl = base.toHttpUrl()
            val local = setOf("localhost", "127.0.0.1", "10.0.2.2")
            require(url.username.isEmpty() && url.password.isEmpty() && (url.isHttps ||
                (allowLocal && !apiUrl.isHttps && apiUrl.host in local && url.host in local))) { "The file server did not provide a secure transfer address." }
        }
    }
    private val reports get() = ProblemReports.of(api)
    /**
     * Uploads a file to My Drive. [modifiedAt] (ISO 8601), when known, is kept with the version so a restore can give
     * the file back its time. [extra] adds fields to the upload request, such as `driveItemId` and `baseRevision` for a
     * new version of a file. A failure is reported, without names, for the staff console.
     *
     * On an end-to-end encrypted account the file gets its own key and is sealed part by part as it goes: the server
     * only sees the encrypted size and, at the end, the SHA-256 of the stored bytes; its name is encrypted on the way.
     */
    suspend fun upload(source: File, name: String, mime: String, parent: String?, extra: JsonObjectBuilder.() -> Unit = {}, modifiedAt: String? = null,
        progress: (String, Float?) -> Unit): DriveItem = withContext(Dispatchers.IO) {
        safeName(name)
        progress("Checking $name…", null)
        val cipher = keys.forUpload()?.let { FileCipher.create(it) }
        val hash = if (cipher == null) digest(source) else null
        val plainSize = source.length()
        val size = cipher?.storedSize(plainSize) ?: plainSize
        val stored = StoredHash()
        val result = try {
            harborJson.decodeFromJsonElement<UploadResponse>(api.request("/v1/uploads", "POST", buildJsonObject {
                operation("name" to name, "parentId" to parent).forEach { (k, v) -> put(k, v) }
                put("sizeBytes", size)
                // The server only ever sees the encrypted size and hash of an encrypted file.
                if (cipher != null) { put("mimeType", "application/octet-stream"); put("encrypted", true) } else { put("mimeType", mime); put("contentHash", hash) }
                modifiedAt?.let { put("modifiedAt", it) }
                extra()
            }))
        } catch (e: CancellationException) { throw e } catch (e: Exception) { reports.report("UPLOAD_FAILED", "create", e, sizeBytes = size); throw e }
        val upload = result.upload
        val route = "/v1/uploads/${HarborApi.segment(upload.id)}"
        var stage = "storage"
        try {
            require(upload.partSizeBytes > 0) { "The server returned an invalid upload size." }
            val count = if (size == 0L) 1 else (size - 1) / upload.partSizeBytes + 1
            require(count <= 10_000) { "This file is too large to upload." }
            val parts = mutableListOf<CompletedPart>()
            for (number in 1..count.toInt()) {
                ensureActive()
                val offset = (number - 1L) * upload.partSizeBytes
                val length = minOf(upload.partSizeBytes, size - offset)
                var completed: CompletedPart? = null
                for (attempt in 0..2) {
                    try {
                        val urls = harborJson.decodeFromJsonElement<Parts>(api.request("$route/parts", "POST", buildJsonObject { put("partNumbers", JsonArray(listOf(JsonPrimitive(number)))) }))
                        val url = urls.parts.first { it.partNumber == number }.uploadUrl
                        validateUrl(url, api.baseUrl, allowLocal)
                        val body = object : RequestBody() {
                            override fun contentType(): MediaType? = null
                            override fun contentLength() = length
                            override fun writeTo(sink: BufferedSink) {
                                if (cipher != null) return RandomAccessFile(source, "r").use { input ->
                                    val read = { begin: Long, end: Long -> ByteArray((end - begin).toInt()).also { input.seek(begin); input.readFully(it) } }
                                    cipher.sealRange(plainSize, offset, offset + length, read) { at, piece -> stored.offer(at, piece); sink.write(piece) }
                                }
                                RandomAccessFile(source, "r").use { input ->
                                    input.seek(offset)
                                    var remaining = length
                                    val buffer = ByteArray(256 * 1024)
                                    while (remaining > 0) {
                                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                        check(read > 0) { "The selected file could not be read." }
                                        sink.write(buffer, 0, read); remaining -= read
                                    }
                                }
                            }
                        }
                        storage.newCall(Request.Builder().url(url).put(body).build()).awaitResponse().use { response ->
                            check(response.isSuccessful) { "File upload failed (${response.code}). Try again." }
                            val etag = response.header("ETag")
                            check(!etag.isNullOrBlank()) { "File storage did not confirm the upload." }
                            completed = CompletedPart(number, etag)
                        }
                        break
                    } catch (e: Exception) {
                        ensureActive()
                        reports.report("UPLOAD_PART_RETRY", if (e is ApiException) "parts" else "storage", e, upload.id, size, number, attempt)
                        if (attempt == 2) throw e
                        delay(250L * (attempt + 1))
                    }
                }
                parts += checkNotNull(completed)
                progress("Uploading $name…", if (size == 0L) 1f else (offset + length).toFloat() / size)
            }
            progress("Finishing $name…", null)
            stage = "complete"
            if (cipher != null) check(stored.length == size) { "File changed during upload. Retry to preserve the latest content." }
            harborJson.decodeFromJsonElement<ItemResponse>(api.request("$route/complete", "POST", buildJsonObject {
                put("parts", harborJson.encodeToJsonElement(parts)); put("contentHash", if (cipher != null) stored.hex() else hash)
            })).item
        } catch (e: Exception) {
            if (e !is CancellationException) reports.report("UPLOAD_FAILED", stage, e, upload.id, size)
            withContext(NonCancellable) { runCatching { withTimeout(15_000) { api.request(route, "DELETE") } } }
            throw e
        }
    }
    suspend fun download(item: DriveItem, previewDirectory: File, progress: (String, Float?) -> Unit): File =
        download(item.name, buildJsonObject { put("driveItemId", item.id) }, previewDirectory, progress)
    /** Downloads through `/v1/downloads`: a file, an earlier version, or a file received in a transfer. */
    suspend fun download(name: String, body: JsonObject, previewDirectory: File, progress: (String, Float?) -> Unit): File = withContext(Dispatchers.IO) {
        safeName(name)
        val authorization = harborJson.decodeFromJsonElement<Download>(api.request("/v1/downloads", "POST", body))
        fetch(authorization.downloadUrl, name, authorization.sizeBytes, authorization.contentHash, previewDirectory, authorization.uploadMismatch,
            authorization.encrypted, progress)
    }
    /**
     * One GET of a signed storage URL, of bytes [begin, end) when given, refusing bodies over [maxBytes]. Returns the
     * status and the body; refusals come back with an empty body for the caller to decide (an expired link is 403).
     */
    suspend fun storageGet(url: String, begin: Long? = null, end: Long? = null, maxBytes: Long = Int.MAX_VALUE.toLong()): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
        validateUrl(url, api.baseUrl, allowLocal)
        val request = Request.Builder().url(url)
        if (begin != null && end != null) request.header("Range", "bytes=$begin-${end - 1}")
        storage.newCall(request.build()).awaitResponse().use { response ->
            if (!response.isSuccessful) return@withContext response.code to ByteArray(0)
            val body = checkNotNull(response.body)
            check(body.contentLength() <= maxBytes) { "This file is too large to show here." }
            val out = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    ensureActive(); val n = input.read(buffer); if (n < 0) break
                    out.write(buffer, 0, n)
                    check(out.size() <= maxBytes) { "This file is too large to show here." }
                }
            }
            response.code to out.toByteArray()
        }
    }
    /** Decrypts a whole stored encrypted file held in memory, such as a thumbnail's original. */
    fun decryptStored(stored: ByteArray): ByteArray = java.io.ByteArrayOutputStream().also { out ->
        FileCipher.decrypt(keys.forRead(), stored.inputStream(), stored.size.toLong(), out)
    }.toByteArray()
    /** The key of a stored encrypted file, from its header, for reading it by range ([EncryptedRangeReader]). */
    fun openCipher(header: ByteArray): FileCipher = FileCipher.open(keys.forRead(), header)
    /** A small file encrypted whole, as any file is, for the account to open with [decryptStored] (a Photos thumbnail). */
    fun sealSmall(plain: ByteArray): ByteArray = java.io.ByteArrayOutputStream().also { out ->
        FileCipher.create(keys.forRead()).encrypt(plain.inputStream(), plain.size.toLong(), out)
    }.toByteArray()
    /**
     * A drive file read in place by range, as the web's `rangeFetcher` and `openEncrypted`: one signed link, renewed
     * every few minutes and when storage refuses it. An encrypted file reads as its plaintext, a chunk at a time.
     */
    suspend fun openRange(itemId: String): RangeSource {
        val body = buildJsonObject { put("driveItemId", itemId); put("preview", true) }
        suspend fun sign() = harborJson.decodeFromJsonElement<Download>(api.request("/v1/downloads", "POST", body))
        val first = sign()
        check(!first.uploadMismatch) { UPLOAD_MISMATCH_MESSAGE }
        val stored = first.sizeBytes
        val lock = kotlinx.coroutines.sync.Mutex()
        var url = first.downloadUrl
        var signedAt = System.currentTimeMillis()
        suspend fun renew(seen: String) = lock.withLock { if (url == seen) { url = sign().downloadUrl; signedAt = System.currentTimeMillis() } }
        val fetch: suspend (Long, Long) -> ByteArray = fetch@{ begin, until ->
            val end = minOf(until, stored)
            if (end <= begin) return@fetch ByteArray(0)
            for (attempt in 0..1) {
                if (System.currentTimeMillis() - signedAt >= LINK_REUSE_MS) renew(url)
                val current = url
                val (status, bytes) = try { storageGet(current, begin, end) } catch (e: java.io.IOException) {
                    if (attempt > 0) throw e; renew(current); continue
                }
                if ((status == 403 || status == 400) && attempt == 0) { renew(current); continue }
                check(status in 200..299) { "The download failed ($status). Try again." }
                // Storage that ignores Range sends the whole file.
                val part = if (status == 200) bytes.copyOfRange(begin.toInt().coerceAtMost(bytes.size), end.toInt().coerceAtMost(bytes.size)) else bytes
                check(part.size.toLong() == end - begin) { "The download was cut short. Try again." }
                return@fetch part
            }
            error("The download failed. Try again.")
        }
        if (!first.encrypted) return object : RangeSource {
            override val size = stored
            override suspend fun read(begin: Long, end: Long) = fetch(begin, end)
        }
        val reader = EncryptedReader.open(keys.forRead(), stored, fetch)
        return object : RangeSource {
            override val size = reader.size
            override suspend fun read(begin: Long, end: Long) = reader.read(begin, end)
        }
    }
    /**
     * Streams a signed storage URL to a private file, checking its size and SHA-256 when they are known. A file the
     * server says was damaged while it was uploaded ([uploadMismatch]) fails at once with [UPLOAD_MISMATCH_MESSAGE]:
     * its bytes would never pass the check. An [encrypted] file is checked as stored, then decrypted chunk by chunk;
     * any damaged or swapped chunk fails the whole file.
     */
    suspend fun fetch(url: String, fileName: String, sizeBytes: Long?, contentHash: String?, previewDirectory: File,
        uploadMismatch: Boolean = false, encrypted: Boolean = false, progress: (String, Float?) -> Unit): File = withContext(Dispatchers.IO) {
        check(!uploadMismatch) { UPLOAD_MISMATCH_MESSAGE }
        val name = safeName(fileName)
        val readKeys = if (encrypted) keys.forRead() else null
        validateUrl(url, api.baseUrl, allowLocal)
        val folder = File(previewDirectory, UUID.randomUUID().toString()).apply { check(mkdirs()) }
        val plain = File(folder, name)
        val file = if (readKeys != null) File(folder, ".stored") else plain
        try {
            progress("Downloading $name…", null)
            storage.newCall(Request.Builder().url(url).build()).awaitResponse().use { response ->
                check(response.isSuccessful) { "Download failed (${response.code}). Try again." }
                val body = checkNotNull(response.body)
                val total = sizeBytes ?: body.contentLength().takeIf { it >= 0 }
                body.byteStream().use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var received = 0L
                    while (true) {
                        ensureActive(); val n = input.read(buffer); if (n < 0) break
                        received += n
                        check(sizeBytes == null || received <= sizeBytes) { "The downloaded file failed its integrity check." }
                        output.write(buffer, 0, n)
                        progress("Downloading $name…", total?.let { if (it == 0L) 1f else received.toFloat() / it })
                    }
                } }
            }
            progress("Checking $name…", null)
            check(sizeBytes == null || file.length() == sizeBytes) { "The downloaded file failed its integrity check. Try downloading it again." }
            check(contentHash == null || digest(file).equals(contentHash, ignoreCase = true)) { "The downloaded file failed its integrity check. Try downloading it again." }
            if (readKeys != null) {
                file.inputStream().use { input -> plain.outputStream().use { output -> FileCipher.decrypt(readKeys, input, file.length(), output) } }
                file.delete()
            }
            plain
        } catch (e: Exception) { folder.deleteRecursively(); throw e }
    }
}
