package app.harbor0.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class BackendIntegrationTest {
    @Test fun realBackendMultipartRoundTripAppearanceTrashAndRevocation() = runBlocking {
        assumeTrue("Run with test-android.mjs for isolated backend coverage", System.getenv("HARBOR_ANDROID_INTEGRATION") == "true")
        val base = "http://127.0.0.1:18990"
        val marker = UUID.randomUUID().toString().replace("-", "")
        val email = "android-$marker@example.test"
        val password = "Development-only-123!"
        val client = OkHttpClient()
        fun post(path: String, fields: Map<String, String>) {
            val payload = harborJson.encodeToString(fields).toRequestBody("application/json".toMediaType())
            client.newCall(Request.Builder().url(base + path).post(payload).build()).execute().use {
                assertTrue(it.body?.string(), it.isSuccessful)
            }
        }
        post("/v1/auth/signup", mapOf("email" to email, "password" to password, "username" to "android_${marker.take(20)}", "displayName" to "Android integration"))
        post("/v1/auth/confirm", mapOf("email" to email, "code" to "123456"))
        val credentials = MemoryCredentials()
        val api = HarborApi(base, credentials)
        api.login(email, password, "Android integration")
        assertEquals(email, api.get<Account>("/v1/users/me").user.email)
        api.createFolder("Android uploads", null)
        val folder = api.list(null).items.first { it.name == "Android uploads" }
        val directory = Files.createTempDirectory("harbor-android-integration").toFile()
        try {
            val source = File(directory, "Android-roundtrip.bin")
            source.outputStream().use { output -> repeat(65) { output.write(ByteArray(1024 * 1024) { 0x41 }) } }
            val transfers = FileTransfers(api, allowLocal = true)
            val item = transfers.upload(source, source.name, "application/octet-stream", folder.id) { _, _ -> }
            assertEquals(65L * 1024 * 1024, item.sizeBytes)
            val download = transfers.download(item, File(directory, "previews")) { _, _ -> }
            assertEquals(FileTransfers.digest(source), FileTransfers.digest(download))
            val appearance = Appearance("dark", "forest", Palettes(dark = mapOf("sidebar" to "#123456")))
            api.request("/v1/users/me", "PATCH", buildJsonObject {
                operation().forEach { (k, v) -> put(k, v) }; put("appearance", harborJson.encodeToJsonElement(appearance))
            })
            assertEquals(appearance, api.get<Account>("/v1/users/me").user.appearance)
            api.get<DrivePage>("/v1/sync/folders"); api.get<Backups>("/v1/backups")
            api.trash(item, "trash")
            val deleted = api.get<DrivePage>("/v1/search?trash=true").items.first { it.id == item.id }
            assertNotNull(deleted.deletedAt)
            api.trash(deleted, "restore")
            val restored = api.list(folder.id).items.first { it.id == item.id }
            assertNull(restored.deletedAt)
            api.trash(restored, "trash"); api.emptyTrash()
            assertTrue(api.get<DrivePage>("/v1/search?trash=true").items.isEmpty())
            val second = HarborApi(base, credentials)
            second.restore(); assertTrue(second.signedIn.value)
            second.logout(); assertNull(credentials.value)
            assertTrue(runCatching { api.get<Account>("/v1/users/me") }.isFailure)
            assertFalse(api.signedIn.value)
        } finally { directory.deleteRecursively() }
    }

    /** The sync engine's newer routes against the real backend: batches, run totals, folder restores, activity and problem reports. */
    @Test fun realBackendBatchesRunTotalsFolderRestoresActivityAndProblemReports() = runBlocking {
        assumeTrue("Run with test-android.mjs for isolated backend coverage", System.getenv("HARBOR_ANDROID_INTEGRATION") == "true")
        val base = "http://127.0.0.1:18990"
        val marker = UUID.randomUUID().toString().replace("-", "")
        val email = "android-sync-$marker@example.test"
        val password = "Development-only-123!"
        val client = OkHttpClient()
        fun post(path: String, fields: Map<String, String>) {
            val payload = harborJson.encodeToString(fields).toRequestBody("application/json".toMediaType())
            client.newCall(Request.Builder().url(base + path).post(payload).build()).execute().use { assertTrue(it.body?.string(), it.isSuccessful) }
        }
        post("/v1/auth/signup", mapOf("email" to email, "password" to password, "username" to "sync_${marker.take(20)}", "displayName" to "Android sync"))
        post("/v1/auth/confirm", mapOf("email" to email, "code" to "123456"))
        val api = HarborApi(base, MemoryCredentials())
        api.login(email, password, "Android sync")
        val engine = EngineApi(api)
        val transfers = SyncTransfers(engine, allowLocal = true)
        val device = harborJson.decodeFromJsonElement<DeviceResponse>(engine.request("/v1/sync/devices/register", "POST", buildJsonObject {
            put("name", "Android sync"); put("platform", "ANDROID") })).device
        val source = Files.createTempDirectory("harbor-sync-source").toFile()
        val target = Files.createTempDirectory("harbor-sync-target").toFile()
        try {
            val times = listOf(1_700_000_000_000L, 1_700_000_100_000L, 1_700_000_200_000L)
            val files = times.mapIndexed { n, time -> File(source, "file-$n.txt").apply { writeText("content $n"); setLastModified(time) } }
            fun batchFiles(parentId: String) = files.map { BatchFile("job-${it.name}", it.name, it.name, it.length(), it.lastModified(),
                kotlinx.coroutines.runBlocking { FileTransfers.digest(it) }, parentId, UUID.randomUUID().toString()) }
            // A synced folder's small files, in one batch.
            val folder = api.createFolder("Android batch", null)
            val synced = transfers.batch(FileTree(source), BatchRoutes.SYNC, batchFiles(folder.id), { _, _ -> })!!
            assertTrue(synced.values.toString(), synced.values.all { it is BatchOutcome.Saved })
            val listed = api.list(folder.id).items.associateBy { it.name }
            assertEquals(3, listed.size)
            assertEquals(java.time.Instant.ofEpochMilli(times[1]).toString(), listed["file-1.txt"]!!.modifiedAt?.let { java.time.Instant.parse(it).toString() })
            // A backup run that says its size before it starts, with its files in one batch.
            val backup = engine.request("/v1/backups", "POST", buildJsonObject {
                put("operationId", UUID.randomUUID().toString()); put("deviceId", device.id); put("name", "Android backup") })["root"]!!.jsonObject
            val backupId = backup["id"]!!.jsonPrimitive.content
            val remoteRoot = backup["remoteRootDriveItemId"]!!.jsonPrimitive.content
            val runId = UUID.randomUUID().toString()
            engine.request("/v1/backups/$backupId/runs", "POST", buildJsonObject {
                put("id", runId); put("trigger", "MANUAL"); put("plannedFileCount", 3); put("plannedSizeBytes", files.sumOf { it.length() }) })
            val saved = transfers.batch(FileTree(source), BatchRoutes.backup(backupId, runId), batchFiles(remoteRoot), { _, _ -> })!!
            assertTrue(saved.values.toString(), saved.values.all { it is BatchOutcome.Saved && it.entry != null })
            engine.request("/v1/backups/$backupId/runs/$runId/complete", "POST", buildJsonObject {})
            val run = engine.all<BackupRun>("/v1/backups/$backupId/runs").first { it.id == runId }
            assertEquals(3, run.plannedFileCount); assertEquals(3, run.fileCount)
            // Restoring the whole folder puts every file back, with the time it was saved at.
            engine.request("/v1/backups/$backupId/restores", "POST", buildJsonObject { put("id", UUID.randomUUID().toString()); put("itemId", remoteRoot) })
            var summary: RestoreSummary? = null
            val restores = BackupRestores(engine, transfers, MemoryValues(), { _, _ -> null }, { _, _, _ -> }) { _, progress, done -> if (progress == null) summary = done }
            restores.run(SyncRoot("root", "content://x", "Android backup", remoteRoot, "backup", backupId = backupId), FileTree(target), "/v1/backups/$backupId")
            assertEquals(3, summary!!.restored)
            files.forEach { assertEquals(it.readText(), File(target, it.name).readText()); assertEquals(it.lastModified(), File(target, it.name).lastModified()) }
            assertTrue(engine.all<BackupRestore>("/v1/backups/$backupId/pending-restores").isEmpty())
            // Activity and problem reports are accepted as sent.
            val reporter = ActivityReporter({ engine.request("/v1/activity", "POST", it) }, MemoryValues(), this)
            reporter.observe(listOf(ActivitySubject(ActivityScope.ENGINE, status = ActivityStatus.ACTIVE),
                ActivitySubject(ActivityScope.BACKUP, backupId, ActivityStatus.PAUSED, "USER")))
            reporter.flush(); reporter.stop()
            assertTrue(reporter.queued.isEmpty())
            assertEquals("PAUSED", engine.get<Backups>("/v1/backups").items.first { it.id == backupId }.state)
            var reported = false
            val reports = ProblemReports({ engine.request("/v1/client-events", "POST", it); reported = true }, this)
            reports.report("UPLOAD_FAILED", "storage", java.io.IOException("reset"), "upload", 10)
            reports.flush()
            assertTrue(reported)
        } finally { source.deleteRecursively(); target.deleteRecursively() }
    }

    /**
     * An end-to-end encrypted account from the Android app against the real backend: turning encryption on from the
     * phone, encrypted folders and uploads (manual, sync multipart and batch) stored with h0e1 names and encrypted
     * sizes, downloads decrypted back, a remembered unlock reopening, and file requests refused while locked.
     */
    @Test fun realBackendEncryptedAccountFromAndroid() = runBlocking {
        assumeTrue("Run with test-android.mjs for isolated backend coverage", System.getenv("HARBOR_ANDROID_INTEGRATION") == "true")
        val base = "http://127.0.0.1:18990"
        val marker = UUID.randomUUID().toString().replace("-", "")
        val email = "android-e2ee-$marker@example.test"
        val password = "Development-only-123!"
        val client = OkHttpClient()
        fun post(path: String, fields: Map<String, String>) {
            val payload = harborJson.encodeToString(fields).toRequestBody("application/json".toMediaType())
            client.newCall(Request.Builder().url(base + path).post(payload).build()).execute().use { assertTrue(it.body?.string(), it.isSuccessful) }
        }
        post("/v1/auth/signup", mapOf("email" to email, "password" to password, "username" to "e2ee_${marker.take(20)}", "displayName" to "Android E2EE"))
        post("/v1/auth/confirm", mapOf("email" to email, "code" to "123456"))
        // The session proves this installation's key and says which app it is: harbor0 for Android 0.1.16, which encrypts.
        val pair = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val key = DeviceKey(pair.public.encoded, pair.private)
        val api = HarborApi(base, MemoryCredentials(), deviceKeys = object: DeviceKeyStore { override fun key(account: String) = key }, appVersion = "0.1.16")
        val copies = MemoryE2eeCopies()
        val vault = E2eeVault(copies)
        api.transport = vault.sealed(api.direct)
        api.login(email, password, "Android E2EE")
        assertNull(api.get<Account>("/v1/users/me").user.e2ee)
        val passphrase = "harbor lantern quartz meadow"
        vault.enroll(api.e2eeApi(), passphrase, E2ee.newRecoveryKey(), remember = true)
        assertNotNull(api.get<Account>("/v1/users/me").user.e2ee)
        assertNotNull(vault.uploadKeys())
        val engine = EngineApi(api, transport = vault.sealed(api.direct, strict = true))
        engine.request("/v1/sync/devices/register", "POST", buildJsonObject { put("name", "Android E2EE"); put("platform", "ANDROID"); put("appVersion", "0.1.16") })
        val transfers = FileTransfers(api, allowLocal = true, keys = vault.transferKeys())
        val sync = SyncTransfers(engine, allowLocal = true, keys = vault.transferKeys())
        val directory = Files.createTempDirectory("harbor-android-e2ee").toFile()
        try {
            val folder = api.createFolder("Private papers", null)
            assertEquals("Private papers", folder.name)
            // A manual upload of a few chunks, a synced multipart one and two small files in a batch.
            val photo = File(directory, "Holiday photo.jpg").apply { writeBytes(ByteArray(9_500_000) { (it * 31).toByte() }) }
            val uploaded = transfers.upload(photo, photo.name, "image/jpeg", folder.id) { _, _ -> }
            assertEquals(photo.name, uploaded.name); assertEquals(photo.length(), uploaded.sizeBytes)
            File(directory, "notes.txt").writeText("Only this phone can read this.")
            val notes = sync.upload(FileTree(directory), "notes.txt", "notes.txt", folder.id, UploadState(UUID.randomUUID().toString()), {})
            val small = listOf("a.txt" to "first", "b.txt" to "second").map { (name, text) ->
                val file = File(directory, name).apply { writeText(text) }
                BatchFile("job-$name", name, name, file.length(), file.lastModified(), FileTransfers.digest(file), folder.id, UUID.randomUUID().toString())
            }
            val batch = sync.batch(FileTree(directory), BatchRoutes.SYNC, small, { _, _ -> })!!
            assertTrue(batch.values.toString(), batch.values.all { it is BatchOutcome.Saved })
            // The server keeps only h0e1 names, encrypted sizes and encrypted bytes.
            val raw = api.direct.call("/v1/drive/folders/${HarborApi.segment(folder.id)}/children", "GET", null)["items"]!!.jsonArray.map { it.jsonObject }
            assertEquals(4, raw.size)
            assertTrue(raw.toString(), raw.all { E2ee.isEncryptedName(it["name"]!!.jsonPrimitive.content) && it["encrypted"]!!.jsonPrimitive.boolean })
            assertEquals(E2ee.encryptedSize(photo.length()), raw.first { it["id"]!!.jsonPrimitive.content == uploaded.id }["sizeBytes"]!!.jsonPrimitive.long)
            val root = api.direct.call("/v1/drive/folders/root/children", "GET", null)["items"]!!.jsonArray.map { it.jsonObject }
            assertTrue(root.all { E2ee.isEncryptedName(it["name"]!!.jsonPrimitive.content) })
            // Through the transport everything reads as plaintext, and downloads decrypt.
            assertEquals(setOf(photo.name, "notes.txt", "a.txt", "b.txt"), api.list(folder.id).items.map { it.name }.toSet())
            assertEquals(FileTransfers.digest(photo), FileTransfers.digest(transfers.download(uploaded, File(directory, "previews")) { _, _ -> }))
            val target = Files.createTempDirectory("harbor-android-e2ee-sync").toFile()
            try {
                val hash = sync.download(FileTree(target), buildJsonObject { put("driveItemId", notes.id) }, "notes.txt")
                assertEquals("Only this phone can read this.", File(target, "notes.txt").readText())
                assertEquals(FileTransfers.digest(File(directory, "notes.txt")), hash)
            } finally { target.deleteRecursively() }
            assertTrue(api.get<Page<DriveItem>>("/v1/search?q=holiday").items.any { it.id == uploaded.id })
            // A remembered unlock opens again on the next launch with the server's half.
            val next = E2eeVault(copies).apply { setAccount(vault.current().userId!!, true, false) }
            assertEquals(E2eeRestore.UNLOCKED, next.restore(api.e2eeApi()))
            // Locked, sync's requests are refused before they reach the server; the passphrase opens it again.
            val userId = vault.current().userId!!
            val kept = copies.load(userId)!!
            vault.lock(api.e2eeApi())
            assertNull(copies.load(userId))
            assertEquals("E2EE_LOCKED", (runCatching { engine.request("/v1/sync/changes?cursor=0") }.exceptionOrNull() as ApiException).code)
            // A copy whose record was removed (here by locking; in the web app by Remove) no longer opens.
            copies.save(kept)
            assertEquals(E2eeRestore.REMOVED, E2eeVault(copies).apply { setAccount(userId, true, false) }.restore(api.e2eeApi()))
            vault.unlockWithPassphrase(api.e2eeApi(), passphrase, remember = false)
            assertEquals(setOf(photo.name, "notes.txt", "a.txt", "b.txt"), engine.list(folder.id).items.map { it.name }.toSet())
        } finally { directory.deleteRecursively() }
    }

    /**
     * A photo backup against the real backend, with a library in memory: the backup is named "Photos", photos go into
     * one folder per album with their times, setting up again reuses the same backup, and what it holds counts as saved.
     */
    @Test fun realBackendPhotoBackupFromAFakeLibrary() = runBlocking {
        assumeTrue("Run with test-android.mjs for isolated backend coverage", System.getenv("HARBOR_ANDROID_INTEGRATION") == "true")
        val base = "http://127.0.0.1:18990"
        val marker = UUID.randomUUID().toString().replace("-", "")
        val email = "android-photos-$marker@example.test"
        val password = "Development-only-123!"
        val client = OkHttpClient()
        fun post(path: String, fields: Map<String, String>) {
            val payload = harborJson.encodeToString(fields).toRequestBody("application/json".toMediaType())
            client.newCall(Request.Builder().url(base + path).post(payload).build()).execute().use { assertTrue(it.body?.string(), it.isSuccessful) }
        }
        post("/v1/auth/signup", mapOf("email" to email, "password" to password, "username" to "photos_${marker.take(20)}", "displayName" to "Android photos"))
        post("/v1/auth/confirm", mapOf("email" to email, "code" to "123456"))
        val api = HarborApi(base, MemoryCredentials())
        api.login(email, password, "Android photos")
        val engine = EngineApi(api)
        val transfers = SyncTransfers(engine, allowLocal = true)
        val device = harborJson.decodeFromJsonElement<DeviceResponse>(engine.request("/v1/sync/devices/register", "POST", buildJsonObject {
            put("name", "Android photos"); put("platform", "ANDROID") })).device
        val scratch = Files.createTempDirectory("harbor-photos").toFile()
        try {
            val library = FakeMediaSource()
            library.add(1, "Camera", "IMG_0001.jpg", "first photo".toByteArray(), modified = 1_700_000_000_000L)
            library.add(2, "Camera", "img_0001.JPG", "same name, other photo".toByteArray(), modified = 1_700_000_100_000L)
            library.add(3, "Screenshots", "Screenshot 1.png", "a screenshot".toByteArray(), modified = 1_700_000_200_000L)
            library.add(4, "Camera", "VID_0001.mp4", "a video".toByteArray(), modified = 1_700_000_300_000L, video = true)
            val tree = MediaStoreTree(library, { true }, scratch)
            val (backup, reused) = photoBackupRoot(api, device.id, null, emptySet())
            assertFalse(reused)
            assertEquals(PHOTOS_NAME, backup.localPathDisplayName)
            assertEquals(PHOTOS_NAME, api.get<Backups>("/v1/backups").items.single().localPathDisplayName)
            // One run: the album folders, then the photos in one batch, each into its album.
            val local = scanTree(tree, emptyList())!!
            val runId = UUID.randomUUID().toString()
            val files = local.filterValues { !it.folder }
            engine.request("/v1/backups/${backup.id}/runs", "POST", buildJsonObject {
                put("id", runId); put("trigger", "MANUAL"); put("plannedFileCount", files.size); put("plannedSizeBytes", files.values.sumOf { it.size }) })
            val albums = local.filterValues { it.folder }.keys.associateWith { album ->
                harborJson.decodeFromJsonElement<ItemResponse>(engine.request("/v1/backups/${backup.id}/runs/$runId/folders", "POST", buildJsonObject {
                    put("name", album); put("parentId", backup.remoteRootDriveItemId); put("operationId", UUID.randomUUID().toString()) })).item.id
            }
            val batch = files.map { (path, stat) -> BatchFile("job-$path", path, baseName(path), stat.size, stat.mtime, tree.hash(path), albums.getValue(parentPath(path)), UUID.randomUUID().toString()) }
            val saved = transfers.batch(tree, BatchRoutes.backup(backup.id, runId), batch, { _, _ -> })!!
            assertTrue(saved.values.toString(), saved.values.all { it is BatchOutcome.Saved && it.entry != null })
            engine.request("/v1/backups/${backup.id}/runs/$runId/complete", "POST", buildJsonObject {})
            assertEquals(4, engine.all<BackupRun>("/v1/backups/${backup.id}/runs").single().fileCount)
            val remote = api.list(backup.remoteRootDriveItemId).items.filter { it.isFolder }.associate { folder -> folder.name to api.list(folder.id).items.map { it.name }.toSet() }
            assertEquals(mapOf("Camera" to setOf("IMG_0001.jpg", "img_0001 (2).JPG", "VID_0001.mp4"), "Screenshots" to setOf("Screenshot 1.png")), remote)
            // Set up again on this phone: the same backup, and everything in it already counts as saved.
            val (again, reusedAgain) = photoBackupRoot(api, device.id, null, emptySet())
            assertTrue(reusedAgain); assertEquals(backup.id, again.id)
            val seed = photoSeed(api, "root", backup.remoteRootDriveItemId, local)
            assertEquals(local.keys, seed.map { it.first.relativePath }.toSet())
            // A phone that already has it starts a second one instead.
            assertFalse(photoBackupRoot(api, device.id, null, setOf(backup.id)).second)
        } finally { scratch.deleteRecursively() }
    }
}
