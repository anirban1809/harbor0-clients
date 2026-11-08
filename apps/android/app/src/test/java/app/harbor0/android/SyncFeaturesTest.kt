package app.harbor0.android

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Settings in memory, standing in for the journal. */
class MemoryValues: KeyValues {
    val values = mutableMapOf<String, String>()
    override fun raw(key: String) = values[key]
    override fun setRaw(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}

private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun json(text: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(text)
private fun body(request: RecordedRequest) = harborJson.parseToJsonElement(request.body.clone().readUtf8().ifEmpty { "{}" }).jsonObject

/** Sync engine features against a mock server: restores, damaged uploads, batches, activity and problem reports. */
class SyncFeaturesTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val requests = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    private var routes: (RecordedRequest) -> MockResponse? = { null }
    @Before fun setup() {
        server = MockWebServer()
        server.dispatcher = object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                if (path == "/v1/auth/login") return json("""{"accessToken":"a","refreshToken":"r","expiresIn":3600}""")
                if (!path.startsWith("/blob")) requests += path to (runCatching { body(request) }.getOrNull() ?: buildJsonObject {})
                return routes(request) ?: json("""{"error":{"code":"NOT_FOUND","message":"Not found"}}""", 404)
            }
        }
        server.start()
        dir = Files.createTempDirectory("sync-features").toFile()
    }
    @After fun cleanup() { server.shutdown(); dir.deleteRecursively() }
    private suspend fun engineApi(): EngineApi {
        val api = HarborApi(server.url("/").toString(), MemoryCredentials())
        api.login("a@b.test", "secret", "Test")
        return EngineApi(api, pause = {})
    }
    private fun paths() = requests.map { it.first }

    @Test fun folderRestoreSkipsUnchangedFilesRestoresTheRestAndSetsTheirTime() = runBlocking {
        val api = engineApi()
        val same = "already here".toByteArray()
        val changed = "saved version".toByteArray()
        val fresh = "new file".toByteArray()
        File(dir, "Docs").mkdirs()
        File(dir, "Docs/same.txt").writeBytes(same)
        File(dir, "Docs/changed.txt").writeText("edited locally")
        val savedAt = "2026-09-01T10:00:00Z"
        routes = { request ->
            val path = request.path!!
            when {
                path == "/v1/backups/b1/pending-restores" -> json("""{"items":[{"id":"r1","itemId":"folder","folder":true,"relativePath":"Docs","state":"PENDING"}]}""")
                path.startsWith("/v1/drive/folders/folder/children") -> json("""{"items":[
                    {"id":"same","name":"same.txt","type":"FILE","revision":2,"currentVersionId":"vs"},
                    {"id":"changed","name":"changed.txt","type":"FILE","revision":3,"currentVersionId":"vc","modifiedAt":"$savedAt"},
                    {"id":"sub","name":"Sub","type":"FOLDER","revision":1}]}""")
                path.startsWith("/v1/drive/folders/sub/children") -> json("""{"items":[{"id":"fresh","name":"fresh.txt","type":"FILE","revision":1,"currentVersionId":"vf"}]}""")
                path == "/v1/downloads" -> {
                    val item = body(request)["driveItemId"]!!.jsonPrimitive.content
                    val content = if (item == "changed") changed else fresh
                    json("""{"downloadUrl":"${server.url("/blob/$item")}","sizeBytes":${content.size},"contentHash":"${sha(content)}"}""")
                }
                path == "/blob/changed" -> MockResponse().setBody(String(changed))
                path == "/blob/fresh" -> MockResponse().setBody(String(fresh))
                path == "/v1/backups/b1" -> json("""{"root":{"state":"ACTIVE"}}""")
                path == "/v1/backups/b1/restores/r1/complete" -> json("{}")
                else -> null
            }
        }
        val store = MemoryValues()
        val known = mapOf("same" to LocalFile("root", "Docs/same.txt", "same", 2, sha(same), "FILE"))
        val written = mutableListOf<String>()
        val progress = mutableListOf<RestoreProgress>()
        var summary: RestoreSummary? = null
        val transfers = SyncTransfers(api, allowLocal = true)
        val restores = BackupRestores(api, transfers, store, { _, id -> known[id] }, { _, path, _ -> written += path }) { _, p, s ->
            if (p != null) progress += p else summary = s
        }
        val root = SyncRoot("root", "content://x", "Backup", "remote", "backup", backupId = "b1")
        restores.run(root, FileTree(dir), "/v1/backups/b1")
        assertArrayEquals(same, File(dir, "Docs/same.txt").readBytes())
        assertArrayEquals(changed, File(dir, "Docs/changed.txt").readBytes())
        assertArrayEquals(fresh, File(dir, "Docs/Sub/fresh.txt").readBytes())
        assertEquals(Instant.parse(savedAt).toEpochMilli(), File(dir, "Docs/changed.txt").lastModified())
        assertEquals(RestoreSummary(2, 1, 0, null, summary!!.at), summary)
        assertTrue(written.containsAll(listOf("Docs", "Docs/Sub", "Docs/changed.txt", "Docs/Sub/fresh.txt")))
        assertEquals(3, progress.last().total)
        // Only the two changed files were downloaded, each at the item's latest version.
        val downloads = requests.filter { it.first == "/v1/downloads" }.map { it.second }
        assertEquals(listOf("vc", "vf"), downloads.map { it["versionId"]!!.jsonPrimitive.content })
        val complete = requests.single { it.first == "/v1/backups/b1/restores/r1/complete" }.second
        assertTrue(complete.isEmpty())
        // The receipt is cleared once acknowledged.
        assertNull(store.raw("backup-restore:r1"))
    }

    @Test fun failedFolderFilesAreSummarisedAndTheRestoreCompletesWithAnError() = runBlocking {
        val api = engineApi()
        routes = { request ->
            when (request.path) {
                "/v1/backups/b1/pending-restores" -> json("""{"items":[{"id":"r1","itemId":"remote","folder":true,"relativePath":"","state":"PENDING"}]}""")
                "/v1/drive/folders/remote/children" -> json("""{"items":[{"id":"bad","name":"bad.txt","type":"FILE","revision":1,"currentVersionId":"v"}]}""")
                "/v1/downloads" -> json("""{"downloadUrl":"${server.url("/blob")}","sizeBytes":3,"contentHash":"abc","uploadMismatch":true}""")
                "/v1/backups/b1/restores/r1/complete" -> json("{}")
                else -> null
            }
        }
        var summary: RestoreSummary? = null
        val restores = BackupRestores(api, SyncTransfers(api, allowLocal = true), MemoryValues(), { _, _ -> null }, { _, _, _ -> }) { _, p, s -> if (p == null) summary = s }
        restores.run(SyncRoot("root", "content://x", "Backup", "remote", "backup", backupId = "b1"), FileTree(dir), "/v1/backups/b1")
        val error = requests.single { it.first == "/v1/backups/b1/restores/r1/complete" }.second["error"]!!.jsonPrimitive.content
        assertEquals("1 file was not restored. bad.txt: $UPLOAD_MISMATCH_LOCAL", error)
        assertEquals(1, summary!!.failed)
        assertFalse(File(dir, "bad.txt").exists())
    }

    @Test fun damagedUploadsFailAtOnceAndKeepTheLocalFile() = runBlocking {
        val api = engineApi()
        File(dir, "a.txt").writeText("keep me")
        routes = { request -> if (request.path == "/v1/downloads") json("""{"downloadUrl":"${server.url("/blob")}","sizeBytes":3,"contentHash":"abc","uploadMismatch":true}""") else null }
        val error = runCatching { SyncTransfers(api, allowLocal = true).download(FileTree(dir), buildJsonObject { put("driveItemId", "item") }, "a.txt") }.exceptionOrNull()
        assertTrue(error is UploadMismatchException)
        assertEquals(UPLOAD_MISMATCH_LOCAL, error!!.message)
        assertEquals("keep me", File(dir, "a.txt").readText())
        val manual = runCatching { FileTransfers(api.api, allowLocal = true).download(DriveItem("item", "a.txt", "FILE"), dir) { _, _ -> } }.exceptionOrNull()
        assertEquals(UPLOAD_MISMATCH_MESSAGE, manual!!.message)
        // Neither went to storage.
        assertEquals(0, server.requestCount - requests.size - 1)
    }

    @Test fun storageRulesMatchTheWebAndDesktop() {
        val gb = 1_000_000_000L
        assertEquals(1_000_000L, fullBelow(10 * gb)); assertEquals(500L, fullBelow(50_000)); assertEquals(1_000_000L, fullBelow(null))
        assertEquals("over", storageState(Storage(10 * gb, 11 * gb, 0)))
        assertEquals("full", storageState(Storage(10 * gb, 10 * gb - 999_999, 0)))
        assertEquals("low", storageState(Storage(10 * gb, 9 * gb + 600_000_000, 0)))
        assertEquals("ok", storageState(Storage(10 * gb, gb, gb)))
        val root = SyncRoot("r", "content://tree", "Docs", "remote", "sync")
        val storage = Storage(10 * gb, 10 * gb, 0)
        val job = SyncJobView("j", "r", "a", "upsert", null, 0)
        assertEquals(STORAGE_FULL, folderState(root, SyncRuntime(storageFull = StorageFull(0), storage = storage), listOf(job)))
        // A shared folder is the owner's storage, not this account's.
        assertEquals("Syncing", folderState(root.copy(shareId = "s"), SyncRuntime(storageFull = StorageFull(0), storage = storage), listOf(job)))
        assertEquals(NEEDS_STORAGE, folderState(root, SyncRuntime(storageFull = StorageFull(5 * gb), storage = storage), listOf(job)))
        assertEquals("Up to date", folderState(root, SyncRuntime(storageFull = StorageFull(5 * gb), storage = storage), emptyList()))
        assertEquals(CHECKING, globalSyncState(listOf(root), SyncRuntime(confirmationPendingRoots = listOf("r")), emptyList()))
        val refusal = ApiException(409, "Full", "STORAGE_QUOTA_EXCEEDED", buildJsonObject { put("requiredBytes", 2_000_000); put("availableBytes", 500_000) })
        assertTrue(storageRefusal(refusal)); assertTrue(storageRefusal(ApiException(409, "Owner full", "OWNER_STORAGE_FULL")))
        assertEquals(StorageNeed(2_000_000, 500_000), storageNeed(refusal, false))
        assertEquals(StorageNeed(owner = true), storageNeed(refusal, true))
        assertEquals(IssueCode.STORAGE_QUOTA_EXCEEDED, syncIssueCode(ApiException(409, "Owner full", "OWNER_STORAGE_FULL")))
        val issue = SyncIssue("i", "r", IssueCode.STORAGE_QUOTA_EXCEEDED, "Full", storage = storageNeed(refusal, false))
        assertEquals("This file is too big for your remaining cloud storage (needs 2.0 MB, 500.0 KB free). Smaller files keep syncing.", storageText(issue))
        assertEquals("Your cloud storage is full (this file needs 2.0 MB). Syncing resumes once space is freed.", storageText(issue.copy(storage = StorageNeed(2_000_000, 0))))
        assertTrue(storageText(issue.copy(storage = StorageNeed(owner = true))).startsWith("The owner of this shared folder"))
        val backup = SyncRoot("b", "content://b", "Photos", "remote", "backup", backupStorage = BackupStorage(5 * gb, 2 * gb, 3))
        assertEquals(NEEDS_STORAGE to "3 files won’t fit in your remaining storage.", backupStorageLabel(backup, SyncRuntime(storage = storage)))
        assertEquals(BACKUP_STORAGE_FULL, backupStorageLabel(backup.copy(backupStorage = BackupStorage(5 * gb, 10, 3)), SyncRuntime(storage = storage))!!.first)
        assertNull(backupStorageLabel(backup.copy(backupStorage = null), SyncRuntime()))
    }

    @Test fun batchUploadsSettleEachFileAndFallBackOnOlderServers() = runBlocking {
        val api = engineApi()
        val files = (1..3).map { n -> File(dir, "f$n.txt").apply { writeText("file $n") } }
        val batch = files.mapIndexed { n, f -> BatchFile("job$n", f.name, f.name, f.length(), f.lastModified(), sha(f.readBytes()), "parent", "op$n") }
        val tree = FileTree(dir)
        // An older server has no batch routes.
        assertNull(SyncTransfers(api, allowLocal = true).batch(tree, BatchRoutes.SYNC, batch, { _, _ -> }))
        routes = { request ->
            when (request.path) {
                "/v1/uploads/batch" -> json("""{"results":[
                    {"upload":{"id":"u0","partSizeBytes":8388608},"uploadUrl":"${server.url("/blob/0")}"},
                    {"error":{"code":"NAME_CONFLICT","message":"Exists"}},
                    {"upload":{"id":"u2","partSizeBytes":8388608},"uploadUrl":"${server.url("/blob/2")}"}]}""")
                "/blob/0", "/blob/2" -> MockResponse().setHeader("ETag", "etag-${request.path!!.last()}")
                "/v1/uploads/batch/finish" -> json("""{"results":[
                    {"item":{"id":"i0","name":"f1.txt","type":"FILE","revision":1,"currentVersionId":"v0"}},
                    {"error":{"code":"BATCH_NOT_REACHED","message":"Send it again."}}]}""")
                else -> null
            }
        }
        val persisted = mutableMapOf<String, UploadState>()
        val outcomes = SyncTransfers(api, allowLocal = true).batch(tree, BatchRoutes.SYNC, batch, { file, state -> persisted[file.jobId] = state })!!
        assertEquals("i0", (outcomes["job0"] as BatchOutcome.Saved).item.id)
        assertEquals(BatchOutcome.Alone, outcomes["job1"])
        assertEquals(BatchOutcome.NotReached, outcomes["job2"])
        // Each created upload is kept with its job, so one the batch doesn't finish resumes alone.
        assertEquals(setOf("job0", "job2"), persisted.keys); assertEquals("u2", persisted["job2"]!!.uploadId)
        val created = requests.last { it.first == "/v1/uploads/batch" }.second["uploads"]!!.jsonArray.map { it.jsonObject }
        assertEquals(Instant.ofEpochMilli(files[0].lastModified()).toString(), created[0]["modifiedAt"]!!.jsonPrimitive.content)
        assertEquals(batch[0].hash, created[0]["contentHash"]!!.jsonPrimitive.content)
        val finished = requests.single { it.first == "/v1/uploads/batch/finish" }.second["files"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("u0", "u2"), finished.map { it["uploadId"]!!.jsonPrimitive.content })
        assertEquals(listOf("etag-0", "etag-2"), finished.map { it["etag"]!!.jsonPrimitive.content })
    }

    @Test fun batchFailuresAreClassifiedAsDesktopDoes() {
        fun outcome(code: String) = batchFailure(buildJsonObject { put("code", code); put("message", "m") })
        assertEquals(BatchOutcome.NotReached, outcome("BATCH_NOT_REACHED")); assertEquals(BatchOutcome.NotReached, outcome("CONCURRENT_UPDATE"))
        listOf("NAME_CONFLICT", "REVISION_CONFLICT", "INVALID_UPLOAD_STATE", "IDEMPOTENCY_KEY_REUSED").forEach { assertEquals(BatchOutcome.Alone, outcome(it)) }
        val failed = outcome("STORAGE_QUOTA_EXCEEDED") as BatchOutcome.Failed
        assertTrue(storageRefusal(failed.error))
    }

    @Test fun uploadsSendTheFilesModifiedTime() = runBlocking {
        val api = engineApi()
        val file = File(dir, "a.txt").apply { writeText("abc"); setLastModified(1_700_000_000_000) }
        routes = { request ->
            when (request.path) {
                "/v1/uploads" -> json("""{"upload":{"id":"u","partSizeBytes":10}}""")
                "/v1/uploads/u/parts" -> json("""{"parts":[{"partNumber":1,"uploadUrl":"${server.url("/blob")}"}]}""")
                "/blob" -> MockResponse().setHeader("ETag", "e")
                "/v1/uploads/u/complete" -> json("""{"item":{"id":"i","name":"a.txt","type":"FILE","revision":1}}""")
                else -> null
            }
        }
        SyncTransfers(api, allowLocal = true).upload(FileTree(dir), "a.txt", "a.txt", null, UploadState("op"), {})
        assertEquals("2023-11-14T22:13:20Z", requests.first { it.first == "/v1/uploads" }.second["modifiedAt"]!!.jsonPrimitive.content)
        assertEquals(3L, file.length())
    }

    @Test fun aFileWrittenToWhileUploadingFailsAsChanged() = runBlocking {
        val api = engineApi()
        val file = File(dir, "a.txt").apply { writeText("abc"); setLastModified(1_700_000_000_000) }
        routes = { request ->
            when (request.path) {
                "/v1/uploads" -> json("""{"upload":{"id":"u","partSizeBytes":10}}""")
                "/v1/uploads/u/parts" -> json("""{"parts":[{"partNumber":1,"uploadUrl":"${server.url("/blob")}"}]}""")
                "/blob" -> MockResponse().setHeader("ETag", "e")
                "/v1/uploads/u/complete" -> json("""{"item":{"id":"i","name":"a.txt","type":"FILE","revision":1}}""")
                else -> null
            }
        }
        // Edited once its only part is in storage, before the upload is finished.
        val error = runCatching {
            SyncTransfers(api, allowLocal = true).upload(FileTree(dir), "a.txt", "a.txt", null, UploadState("op"), {},
                progress = { loaded, _ -> if (loaded > 0) file.apply { writeText("abcd"); setLastModified(1_700_000_060_000) } })
        }.exceptionOrNull()
        assertTrue(error is FileChangedException)
        assertTrue(skippedAsChanged(error!!))
        assertFalse(paths().contains("/v1/uploads/u/complete"))
    }

    @Test fun engineRequestsRetryThrottlingAndStayInsideTheirBudget() = runBlocking {
        var calls = 0
        routes = { request -> if (request.path == "/v1/thing") { calls++; if (calls < 3) json("""{"error":{"code":"RATE_LIMITED","message":"Slow down"}}""", 429) else json("""{"ok":true}""") } else null }
        val api = engineApi()
        assertEquals(true, api.request("/v1/thing")["ok"]!!.jsonPrimitive.boolean)
        assertEquals(3, calls)
        assertTrue(shouldRetry(ApiException(503, "Down"), "POST", null))
        assertFalse(shouldRetry(ApiException(500, "Oops"), "POST", buildJsonObject { put("name", "x") }))
        assertTrue(shouldRetry(ApiException(500, "Oops"), "POST", buildJsonObject { put("operationId", "op") }))
        assertTrue(shouldRetry(java.io.IOException("reset"), "GET", null))
        assertFalse(shouldRetry(ApiException(409, "Conflict", "NAME_CONFLICT"), "GET", null))
        assertTrue(bulkTransferPath("/v1/uploads/batch")); assertTrue(bulkTransferPath("/v1/backups/b/runs/r/files")); assertFalse(bulkTransferPath("/v1/backups/b"))
        // The third request in a window of two waits until the first ages out.
        var clock = 0L
        val slept = mutableListOf<Long>()
        val budget = RequestBudget(2, { clock }) { slept += it; clock += it }
        budget.take(); clock = 1000; budget.take(); budget.take()
        assertEquals(listOf(59_000L), slept)
    }

    @Test fun activityIsQueuedOnChangesSentInBatchesAndDroppedByOlderServers() = runBlocking {
        var clock = 1_000_000L
        val sent = mutableListOf<JsonObject>()
        var status = 200
        val store = MemoryValues()
        val reporter = ActivityReporter({ if (status != 200) throw ApiException(status, "No"); sent += it }, store, this, { clock }, sendDelay = 10_000_000)
        val engine = ActivitySubject(ActivityScope.ENGINE, status = ActivityStatus.ACTIVE)
        reporter.observe(listOf(engine, ActivitySubject(ActivityScope.SYNC, "f1", ActivityStatus.ACTIVE, files = 4)))
        // Unchanged: nothing new.
        reporter.observe(listOf(engine, ActivitySubject(ActivityScope.SYNC, "f1", ActivityStatus.ACTIVE, files = 3)))
        assertEquals(listOf("STARTED", "STARTED"), reporter.queued.map { it.action })
        // Idle only after 30 s without work.
        clock += 10_000
        reporter.observe(listOf(engine, ActivitySubject(ActivityScope.SYNC, "f1", ActivityStatus.IDLE)))
        assertEquals(2, reporter.queued.size)
        reporter.fileMoved("f1"); reporter.fileMoved("f1")
        clock += SYNC_IDLE_MS
        reporter.observe(listOf(engine, ActivitySubject(ActivityScope.SYNC, "f1", ActivityStatus.IDLE)))
        val finished = reporter.queued.last()
        assertEquals("FINISHED", finished.action); assertEquals(2, finished.files)
        // Pausing a backup is reported as PAUSED by the person, and resuming as RESUMED.
        reporter.observe(listOf(engine, ActivitySubject(ActivityScope.BACKUP, "b1", ActivityStatus.PAUSED, "USER")))
        reporter.observe(listOf(engine, ActivitySubject(ActivityScope.BACKUP, "b1", ActivityStatus.IDLE)))
        assertEquals(listOf("PAUSED", "RESUMED"), reporter.queued.takeLast(2).map { it.action })
        // The queue survives a restart; what was reported is not reported again.
        val restarted = ActivityReporter({ sent += it }, store, this, { clock }, sendDelay = 10_000_000)
        assertEquals(reporter.queued, restarted.queued)
        restarted.flush()
        assertTrue(restarted.queued.isEmpty())
        val events = sent.single()["events"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("STARTED", "STARTED", "FINISHED", "PAUSED", "RESUMED"), events.map { it["action"]!!.jsonPrimitive.content })
        assertFalse("engine events have no folderId", events[0].containsKey("folderId"))
        assertFalse(events[0].containsKey("reason"))
        assertEquals("USER", events[3]["reason"]!!.jsonPrimitive.content)
        // A server without the route: the queue is dropped and nothing more is reported.
        status = 404
        reporter.observe(listOf(engine.copy(status = ActivityStatus.PAUSED, reason = "USER")))
        reporter.flush()
        assertTrue(reporter.queued.isEmpty())
        reporter.observe(listOf(engine))
        assertTrue(reporter.queued.isEmpty())
        // At most 500 unsent events are kept, the oldest dropped.
        val busy = ActivityReporter({ throw java.io.IOException("offline") }, MemoryValues(), this, { clock }, sendDelay = 10_000_000)
        repeat(600) { busy.observe(listOf(ActivitySubject(ActivityScope.ENGINE, status = if (it % 2 == 0) ActivityStatus.PAUSED else ActivityStatus.ACTIVE))) }
        assertEquals(ACTIVITY_QUEUE_MAX, busy.queued.size)
        busy.flush(); assertEquals(ACTIVITY_QUEUE_MAX, busy.queued.size)
        busy.stop(); reporter.stop()
    }

    @Test fun problemReportsAreDedupedBatchedAndNeverNameFiles() = runBlocking {
        val sent = mutableListOf<JsonObject>()
        var clock = 0L
        val reports = ProblemReports({ sent += it }, this, { clock }, delayMs = 10_000_000)
        val local = LocalReadException(LocalFsException("EIO", "“secret.txt” could not be read.", "secret.txt"))
        reports.report("UPLOAD_PART_RETRY", "storage", local, "u1", 10, 1, 0)
        reports.report("UPLOAD_PART_RETRY", "storage", local, "u1", 10, 1, 1)
        clock += 60_001
        reports.report("UPLOAD_PART_RETRY", "storage", local, "u1", 10, 1, 2)
        reports.report("UPLOAD_FAILED", "create", ApiException(409, "Storage full", "STORAGE_QUOTA_EXCEEDED"), sizeBytes = 10)
        assertEquals(3, reports.pending)
        reports.flush()
        val events = sent.single()["events"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("EIO", "EIO", "STORAGE_QUOTA_EXCEEDED"), events.map { it["code"]!!.jsonPrimitive.content })
        assertFalse(events.any { it.toString().contains("secret") })
        assertEquals(409, events[2]["httpStatus"]!!.jsonPrimitive.int)
        // A failed send is dropped silently.
        val failing = ProblemReports({ throw java.io.IOException("offline") }, this, { clock }, delayMs = 10_000_000)
        failing.report("UPLOAD_FAILED", "other", null); failing.flush()
        assertEquals(0, failing.pending)
    }

    @Test fun namesLongerThanTheServerAllowsAreRefused() {
        assertEquals("a".repeat(240), safeName("a".repeat(240)))
        assertTrue(runCatching { safeName("a".repeat(241)) }.isFailure)
    }
}
