package app.harbor0.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun answer(text: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(text)

/**
 * End-to-end encryption on the phone against a mock server that keeps what it is sent, as the real one does: every
 * request through the transport, encrypted uploads (manual, resumed sync multipart, batches) and decrypting downloads.
 */
class E2eeTransfersTest {
    private val keys = E2ee.accountKeys(ByteArray(32) { (it * 7).toByte() })
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val requests = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    /** What the fake server stores: uploads by id and their parts, and the finished files' stored bytes. */
    private class Upload(val body: JsonObject, val partSize: Long, val parts: ConcurrentHashMap<Int, ByteArray> = ConcurrentHashMap(), var item: JsonObject? = null)
    private val uploads = ConcurrentHashMap<String, Upload>()
    private val stored = ConcurrentHashMap<String, ByteArray>()
    /** Part numbers storage refuses (each refusal counted down). */
    private val refuse = ConcurrentHashMap<Int, Int>()
    private var partSize = 3_000_000L
    private var nextId = 0

    @Before fun setup() {
        server = MockWebServer()
        server.dispatcher = object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@E2eeTransfersTest) { route(request) }
        }
        server.start()
        dir = Files.createTempDirectory("e2ee-transfers").toFile()
    }
    @After fun cleanup() { server.shutdown(); dir.deleteRecursively() }

    private fun route(request: RecordedRequest): MockResponse {
        val path = request.path!!
        val method = request.method!!
        if (path == "/v1/auth/login") return answer("""{"accessToken":"a","refreshToken":"r","expiresIn":3600}""")
        if (path.startsWith("/storage/")) return storage(request)
        val body = runCatching { harborJson.parseToJsonElement(request.body.clone().readUtf8().ifEmpty { "{}" }).jsonObject }.getOrDefault(buildJsonObject {})
        requests += "$method $path" to body
        Regex("^/v1/uploads/([^/]+)/parts$").find(path)?.let { m ->
            val numbers = body["partNumbers"]!!.jsonArray.map { it.jsonPrimitive.int }
            return answer(buildJsonObject { put("parts", JsonArray(numbers.map { n -> buildJsonObject { put("partNumber", n); put("uploadUrl", server.url("/storage/${m.groupValues[1]}/$n").toString()) } })) }.toString())
        }
        Regex("^/v1/uploads/([^/]+)/complete$").find(path)?.let { m ->
            val upload = uploads.getValue(m.groupValues[1])
            val bytes = body["parts"]!!.jsonArray.map { it.jsonObject["partNumber"]!!.jsonPrimitive.int }.flatMap { upload.parts.getValue(it).toList() }.toByteArray()
            if (sha(bytes) != body["contentHash"]!!.jsonPrimitive.content) return answer("""{"error":{"code":"HASH_MISMATCH","message":"The uploaded file did not match."}}""", 409)
            return answer("""{"item":${finish(m.groupValues[1], upload, bytes)}}""")
        }
        Regex("^/v1/uploads/([^/]+)$").find(path)?.takeIf { it.groupValues[1] != "batch" }?.let { m ->
            val upload = uploads[m.groupValues[1]] ?: return answer("""{"error":{"code":"NOT_FOUND","message":"Gone"}}""", 404)
            if (method == "DELETE") { uploads.remove(m.groupValues[1]); return answer("{}") }
            return answer(buildJsonObject {
                put("upload", buildJsonObject { put("id", m.groupValues[1]); put("state", if (upload.item != null) "COMPLETED" else "UPLOADING"); upload.item?.let { put("item", it) } })
                put("parts", JsonArray(upload.parts.keys.sorted().map { n -> buildJsonObject { put("partNumber", n); put("etag", "e$n") } }))
            }.toString())
        }
        if (path == "/v1/uploads" && method == "POST") {
            val id = "up${++nextId}"
            uploads[id] = Upload(body, partSize)
            return answer("""{"upload":{"id":"$id","partSizeBytes":$partSize,"encrypted":${body["encrypted"] ?: false},"sizeBytes":${body["sizeBytes"]}}}""")
        }
        if (path == "/v1/uploads/batch") {
            return answer(buildJsonObject { put("results", JsonArray(body["uploads"]!!.jsonArray.map { u ->
                val id = "up${++nextId}"
                uploads[id] = Upload(u.jsonObject, u.jsonObject["sizeBytes"]!!.jsonPrimitive.long)
                buildJsonObject { put("upload", buildJsonObject { put("id", id); put("partSizeBytes", u.jsonObject["sizeBytes"]!!.jsonPrimitive.long) }); put("uploadUrl", server.url("/storage/$id/1").toString()) }
            })) }.toString())
        }
        if (path == "/v1/uploads/batch/finish") {
            return answer(buildJsonObject { put("results", JsonArray(body["files"]!!.jsonArray.map { f ->
                val id = f.jsonObject["uploadId"]!!.jsonPrimitive.content
                val upload = uploads.getValue(id)
                val bytes = upload.parts.getValue(1)
                if (sha(bytes) != f.jsonObject["contentHash"]!!.jsonPrimitive.content) buildJsonObject { put("error", buildJsonObject { put("code", "HASH_MISMATCH"); put("message", "No") }) }
                else buildJsonObject { put("item", harborJson.parseToJsonElement(finish(id, upload, bytes))) }
            })) }.toString())
        }
        if (path == "/v1/downloads") {
            val id = body["driveItemId"]!!.jsonPrimitive.content
            val bytes = stored[id] ?: return answer("""{"error":{"code":"ITEM_NOT_FOUND","message":"Gone"}}""", 404)
            return answer("""{"downloadUrl":"${server.url("/storage/blob/$id")}","sizeBytes":${bytes.size},"contentHash":"${sha(bytes)}","encrypted":${E2ee.isEncryptedName(nameOf(id))}}""")
        }
        Regex("^/v1/drive/items/([^/]+)$").find(path)?.let { m -> return answer("""{"item":{"id":"${m.groupValues[1]}","name":"x","type":"FOLDER","ownerUserId":"u1"}}""") }
        if (path.startsWith("/v1/drive/folders/") && path.contains("/children")) {
            return answer(buildJsonObject { put("items", JsonArray(stored.keys.map { id -> harborJson.parseToJsonElement(item(id, stored.getValue(id).size.toLong())) })) }.toString())
        }
        if (path == "/v1/drive/folders") return answer("""{"item":{"id":"f${++nextId}","name":${body["name"]},"type":"FOLDER","ownerUserId":"u1","revision":1}}""")
        if (path == "/v1/users/me") return answer("""{"user":{"id":"u1","email":"a@b.test","displayName":"A","e2ee":{"enabledAt":"2026-10-20T00:00:00Z"}},"storage":{"quotaBytes":100,"usedBytes":0,"reservedBytes":0}}""")
        return answer("""{"error":{"code":"NOT_FOUND","message":"Not found"}}""", 404)
    }
    private val names = ConcurrentHashMap<String, String>()
    private fun nameOf(id: String) = names[id] ?: ""
    private fun item(id: String, size: Long) = """{"id":"$id","name":"${nameOf(id)}","normalizedName":"${nameOf(id).lowercase()}","type":"FILE","sizeBytes":$size,"revision":1,
        "currentVersionId":"v-$id","ownerUserId":"u1","encrypted":${E2ee.isEncryptedName(nameOf(id))},"mimeType":"application/octet-stream","contentHash":"${sha(stored[id] ?: ByteArray(0))}"}"""
    private fun finish(uploadId: String, upload: Upload, bytes: ByteArray): String {
        val id = upload.body["driveItemId"]?.jsonPrimitive?.contentOrNull ?: "item-$uploadId"
        names[id] = upload.body["name"]!!.jsonPrimitive.content
        stored[id] = bytes
        return item(id, bytes.size.toLong()).also { upload.item = harborJson.parseToJsonElement(it).jsonObject }
    }
    private fun storage(request: RecordedRequest): MockResponse {
        val segments = request.path!!.removePrefix("/storage/").split('/')
        if (segments[0] == "blob") {
            val bytes = stored.getValue(segments[1])
            val range = request.getHeader("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
            if (range == null) return MockResponse().setBody(Buffer().write(bytes))
            val start = range.groupValues[1].toInt()
            val end = range.groupValues[2].ifEmpty { "${bytes.size - 1}" }.toInt()
            return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${bytes.size}").setBody(Buffer().write(bytes.copyOfRange(start, end + 1)))
        }
        val number = segments[1].toInt()
        val left = refuse[number] ?: 0
        if (left > 0) { refuse[number] = left - 1; return MockResponse().setResponseCode(500) }
        uploads.getValue(segments[0]).parts[number] = request.body.readByteArray()
        return MockResponse().setHeader("ETag", "e$number")
    }

    private suspend fun signedIn(): HarborApi = HarborApi(server.url("/").toString(), MemoryCredentials()).also { it.login("a@b.test", "secret", "Test") }
    private fun view() = E2eeView("u1", true, false, keys)
    private fun unlocked() = TransferKeys({ keys }, { keys })
    private fun random(size: Int) = ByteArray(size).also { java.util.Random(size.toLong()).nextBytes(it) }
    private fun paths() = requests.map { it.first }

    @Test fun everyRequestGoesThroughTheTransportAndUsersMeTellsTheVault() = runBlocking {
        val api = signedIn()
        val vault = E2eeVault(MemoryE2eeCopies())
        api.transport = vault.sealed(api.direct)
        // Encryption on, as /v1/users/me says; locked, a name can't go up.
        assertTrue(api.get<Account>("/v1/users/me").user.encrypted)
        assertTrue(vault.locked)
        assertEquals("E2EE_LOCKED", (runCatching { api.createFolder("Taxes", null) }.exceptionOrNull() as ApiException).code)
        // With the key, names are encrypted on the way out and come back plaintext; sizes are the files' own.
        api.transport = E2eeTransport(api.direct, ::view, { _, _, _ -> })
        val folder = api.createFolder("Taxes", null)
        assertEquals("Taxes", folder.name)
        val sent = requests.last { it.first == "POST /v1/drive/folders" }.second["name"]!!.jsonPrimitive.content
        assertTrue(E2ee.isEncryptedName(sent)); assertEquals("Taxes", E2ee.decryptName(keys, sent))
        names["i1"] = E2ee.encryptName(keys, "Report.pdf"); stored["i1"] = ByteArray(E2ee.encryptedSize(1000).toInt())
        val listed = api.list(null).items.single()
        assertEquals("Report.pdf", listed.name); assertEquals("report.pdf", listed.normalizedName)
        assertEquals(1000L, listed.sizeBytes); assertEquals("application/pdf", listed.mimeType)
        assertEquals(listed, api.all<DriveItem>("/v1/drive/folders/root/children").single())
        // The vault's own calls skip the transport.
        assertSame(api.direct, api.e2eeApi())
    }

    @Test fun strictEngineTransportRefusesFileRequestsWhileLocked() = runBlocking {
        val api = signedIn()
        val engine = EngineApi(api, pause = {}, transport = E2eeTransport(api.direct, { E2eeView("u1", true, false, null) }, { _, _, _ -> }, strict = true))
        val before = requests.size
        assertEquals("E2EE_LOCKED", (runCatching { engine.request("/v1/sync/changes?cursor=0") }.exceptionOrNull() as ApiException).code)
        assertEquals(before, requests.size)
        // Sync says why it waits, for every folder.
        val runtime = SyncRuntime(locked = true)
        assertEquals(LOCKED_LABEL, globalSyncState(emptyList(), runtime, emptyList()))
        assertEquals(LOCKED_LABEL, folderState(SyncRoot("r", "content://x", "Docs", "f", "sync"), runtime, emptyList()))
    }

    @Test fun manualUploadIsEncryptedAndItsDownloadDecrypted() = runBlocking {
        val api = signedIn()
        api.transport = E2eeTransport(api.direct, ::view, { _, _, _ -> })
        val transfers = FileTransfers(api, allowLocal = true, keys = unlocked())
        val plain = random(7_500_000)
        val source = File(dir, "source.bin").apply { writeBytes(plain) }
        // A part storage refuses once is sent again; the stored hash counts it once.
        refuse[2] = 1
        val item = transfers.upload(source, "Holiday.mov", "video/quicktime", null) { _, _ -> }
        val create = requests.first { it.first == "POST /v1/uploads" }.second
        assertTrue(create["encrypted"]!!.jsonPrimitive.boolean)
        assertEquals(E2ee.encryptedSize(plain.size.toLong()), create["sizeBytes"]!!.jsonPrimitive.long)
        assertNull(create["contentHash"]); assertEquals("application/octet-stream", create["mimeType"]!!.jsonPrimitive.content)
        assertEquals("Holiday.mov", E2ee.decryptName(keys, create["name"]!!.jsonPrimitive.content))
        assertEquals("Holiday.mov", item.name); assertEquals(plain.size.toLong(), item.sizeBytes)
        val bytes = stored.getValue(item.id)
        assertEquals(E2ee.encryptedSize(plain.size.toLong()), bytes.size.toLong())
        val back = transfers.download(item, File(dir, "previews")) { _, _ -> }
        assertEquals("Holiday.mov", back.name)
        assertArrayEquals(plain, back.readBytes())
        assertEquals(listOf("Holiday.mov"), back.parentFile!!.list()!!.toList())
        // Without the key nothing is downloaded or uploaded.
        val locked = FileTransfers(api, allowLocal = true, keys = TransferKeys(locked = { true }))
        assertEquals("E2EE_LOCKED", (runCatching { locked.download(item, File(dir, "previews")) { _, _ -> } }.exceptionOrNull() as ApiException).code)
        assertEquals("E2EE_LOCKED", (runCatching { locked.upload(source, "a", "text/plain", null) { _, _ -> } }.exceptionOrNull() as ApiException).code)
    }

    @Test fun syncUploadResumesWithItsSavedKeyAndDownloadResumesThenDecrypts() = runBlocking {
        val engine = EngineApi(signedIn().also { it.transport = E2eeTransport(it.direct, ::view, { _, _, _ -> }) }, pause = {})
        val transfers = SyncTransfers(engine, allowLocal = true, keys = unlocked())
        val plain = random(9_000_000)
        val tree = FileTree(dir)
        File(dir, "clip.mp4").writeBytes(plain)
        val state = UploadState("op1")
        // Storage keeps refusing part 2: the upload stops with part 1 stored, its key kept with the job.
        refuse[2] = 3
        assertNotNull(runCatching { transfers.upload(tree, "clip.mp4", "clip.mp4", "parent", state, {}) }.exceptionOrNull())
        val header = state.header!!
        assertEquals(listOf(1), state.parts!!.map { it.partNumber })
        assertNull(state.storedHash)
        // The next pass resumes: part 1 is sealed again only for the hash, the rest go up with the same key.
        val item = transfers.upload(tree, "clip.mp4", "clip.mp4", "parent", state, {})
        assertEquals(header, state.header)
        assertEquals(1, requests.count { it.first == "POST /v1/uploads" })
        val bytes = stored.getValue(item.id)
        assertEquals(sha(bytes), state.storedHash)
        val out = java.io.ByteArrayOutputStream()
        FileCipher.decrypt(keys, bytes.inputStream(), bytes.size.toLong(), out)
        assertArrayEquals(plain, out.toByteArray())
        // A download interrupted after some stored bytes resumes from the part file, then is decrypted in place.
        File(dir, "copy.mp4.harbor-part").writeBytes(bytes.copyOfRange(0, 5_000_000))
        val hash = transfers.download(tree, buildJsonObject { put("driveItemId", item.id) }, "copy.mp4")
        assertArrayEquals(plain, File(dir, "copy.mp4").readBytes())
        assertEquals(sha(plain), hash)
        assertFalse(File(dir, "copy.mp4.harbor-part").exists()); assertFalse(File(dir, "copy.mp4.harbor-plain").exists())
        // Damaged stored bytes fail before anything replaces the local file, and come down again next time.
        stored[item.id] = bytes.copyOf().also { it[200] = (it[200] + 1).toByte() }
        File(dir, "copy.mp4").writeBytes("local".toByteArray())
        assertNotNull(runCatching { transfers.download(tree, buildJsonObject { put("driveItemId", item.id) }, "copy.mp4") }.exceptionOrNull())
        assertEquals("local", File(dir, "copy.mp4").readText())
        assertFalse(File(dir, "copy.mp4.harbor-part").exists())
    }

    @Test fun anUploadStartedEncryptedStartsOverWhenEncryptionIsOff() = runBlocking {
        val engine = EngineApi(signedIn(), pause = {})
        File(dir, "a.txt").writeText("hello")
        val state = UploadState("op", uploadId = "old", partSize = 100, header = "x", size = 5, mtime = File(dir, "a.txt").lastModified())
        uploads["old"] = Upload(buildJsonObject {}, 100)
        val item = SyncTransfers(engine, allowLocal = true).upload(FileTree(dir), "a.txt", "a.txt", null, state, {})
        assertTrue("DELETE /v1/uploads/old" in paths())
        val create = requests.last { it.first == "POST /v1/uploads" }.second
        assertNull(create["encrypted"]); assertEquals(sha("hello".toByteArray()), create["contentHash"]!!.jsonPrimitive.content)
        assertNull(state.header)
        assertEquals("hello", String(stored.getValue(item.id)))
    }

    @Test fun smallFilesGoInEncryptedBatches() = runBlocking {
        val engine = EngineApi(signedIn().also { it.transport = E2eeTransport(it.direct, ::view, { _, _, _ -> }) }, pause = {})
        val tree = FileTree(dir)
        val files = listOf("one.txt" to "first file", "two.txt" to "second").mapIndexed { n, (name, text) ->
            File(dir, name).writeText(text)
            val f = File(dir, name)
            BatchFile("job$n", name, name, f.length(), f.lastModified(), sha(f.readBytes()), null, "op$n")
        }
        val persisted = mutableMapOf<String, UploadState>()
        val outcomes = SyncTransfers(engine, allowLocal = true, keys = unlocked()).batch(tree, BatchRoutes.SYNC, files, { file, state -> persisted[file.jobId] = state.copy() })!!
        val created = requests.first { it.first == "POST /v1/uploads/batch" }.second["uploads"]!!.jsonArray.map { it.jsonObject }
        assertTrue(created.all { it["encrypted"]!!.jsonPrimitive.boolean && it["contentHash"] == null && E2ee.isEncryptedName(it["name"]!!.jsonPrimitive.content) })
        assertEquals(files.map { E2ee.encryptedSize(it.size) }, created.map { it["sizeBytes"]!!.jsonPrimitive.long })
        for ((n, file) in files.withIndex()) {
            val saved = outcomes.getValue(file.jobId) as BatchOutcome.Saved
            assertEquals(file.name, saved.item.name)
            val bytes = stored.getValue(saved.item.id)
            val out = java.io.ByteArrayOutputStream()
            FileCipher.decrypt(keys, bytes.inputStream(), bytes.size.toLong(), out)
            assertEquals(File(dir, file.name).readText(), String(out.toByteArray()))
            // Kept with the job, so a file the batch doesn't finish resumes alone with the same key.
            assertEquals(sha(bytes), persisted.getValue("job$n").storedHash)
            assertArrayEquals(bytes.copyOf(E2ee.HEADER_SIZE), E2ee.fromB64url(persisted.getValue("job$n").header!!))
        }
    }

    @Test fun sealedRangesArriveInTheSamePiecesAndHashOnce() {
        val cipher = FileCipher.create(keys)
        val plain = random(9_000_000)
        val total = cipher.storedSize(plain.size.toLong())
        val read = { b: Long, e: Long -> plain.copyOfRange(b.toInt(), e.toInt()) }
        val whole = cipher.encryptedRange(plain.size.toLong(), 0, total, read)
        val hash = StoredHash()
        val pieces = mutableListOf<Pair<Long, Int>>()
        for (start in 0 until total step 3_000_000L) {
            val end = minOf(total, start + 3_000_000)
            // Each part is offered twice, as a retried upload would.
            repeat(2) { cipher.sealRange(plain.size.toLong(), start, end, read) { at, piece -> hash.offer(at, piece); if (it == 0) pieces += at to piece.size } }
        }
        assertEquals(total, hash.length)
        assertEquals(sha(whole), hash.hex())
        assertTrue(pieces.all { it.second <= E2ee.CHUNK_SIZE + E2ee.TAG })
        // Content URIs read forward only; a read going back opens the stream again.
        var opened = 0
        PlainReader { opened++; plain.inputStream() }.use { reader ->
            assertArrayEquals(plain.copyOfRange(10, 20), reader.read(10, 20))
            assertArrayEquals(plain.copyOfRange(30, 40), reader.read(30, 40))
            assertArrayEquals(plain.copyOfRange(0, 5), reader.read(0, 5))
        }
        assertEquals(2, opened)
    }
}
