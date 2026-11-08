package app.harbor0.android

import androidx.work.NetworkType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest

/** A photo library in memory: rows with their bytes; rows the app didn't save can't be deleted, as on Android 10 and later. */
class FakeMediaSource(var access: MediaAccess = MediaAccess.FULL): MediaSource {
    val rows = mutableListOf<MediaRow>()
    val bytes = mutableMapOf<Long, ByteArray>()
    val owned = mutableSetOf<Long>()
    val inserted = mutableListOf<Pair<String, MediaRow>>()
    var opened = 0
    var queries = 0
    private var next = 1000L
    fun add(id: Long, album: String?, name: String, content: ByteArray, modified: Long = 1_700_000_000_000L + id, video: Boolean = false, relativePath: String? = null) {
        rows += MediaRow(id, video, name, album, relativePath, content.size.toLong(), modified, if (video) "video/mp4" else "image/jpeg")
        bytes[id] = content
    }
    fun remove(id: Long) { rows.removeAll { it.id == id }; bytes.remove(id) }
    override fun access() = access
    override fun rows(videos: Boolean): List<MediaRow> {
        if (access == MediaAccess.NONE) throw SecurityException("Permission denied")
        queries++
        return rows.filter { videos || !it.video }
    }
    override fun open(row: MediaRow): InputStream { opened++; return ByteArrayInputStream(bytes[row.id] ?: throw FileNotFoundException(row.name)) }
    override fun insert(album: String, name: String, mime: String, video: Boolean, write: (OutputStream) -> Unit): MediaRow {
        val out = ByteArrayOutputStream().also(write)
        val id = next++
        val row = MediaRow(id, video, name, album, photoRestoreFolder(album), out.size().toLong(), 1_800_000_000_000L, mime)
        rows += row; bytes[id] = out.toByteArray(); owned += id; inserted += photoRestoreFolder(album) to row
        return row
    }
    override fun delete(row: MediaRow) { if (row.id !in owned) throw SecurityException("Not owned"); remove(row.id) }
}

private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun json(text: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(text)

class PhotoBackupTest {
    private lateinit var dir: File
    @Before fun setup() { dir = Files.createTempDirectory("photo-backup").toFile() }
    @After fun cleanup() { dir.deleteRecursively() }
    private fun tree(source: MediaSource, videos: Boolean = true) = MediaStoreTree(source, { videos }, File(dir, "scratch"))
    private fun row(id: Long, name: String, album: String? = "Camera", relativePath: String? = null, video: Boolean = false) =
        MediaRow(id, video, name, album, relativePath, 10, 1000, null)

    @Test fun pathsUseAlbumsWithFallbacksAndSafeNames() {
        val index = photoIndex(listOf(row(1, "IMG_1.jpg"), row(2, "shot.png", album = null, relativePath = "Pictures/Screenshots/"), row(3, "x.jpg", album = " ", relativePath = null),
            row(4, "a/b\u0001c.jpg", album = "Trip/2026"), row(5, "", album = "Camera", video = true), row(6, "._hidden.jpg"), row(7, "edit.harbor-part")))
        assertEquals(setOf("Camera/IMG_1.jpg", "Screenshots/shot.png", "Other/x.jpg", "Trip_2026/a_b_c.jpg", "Camera/Video 5", "Camera/_._hidden.jpg", "Camera/edit.harbor-part_"),
            index.files.keys)
        assertEquals(listOf("Camera", "Screenshots", "Other", "Trip_2026"), index.albums.keys.toList())
        index.files.keys.forEach { path -> assertFalse(path, internalPath(path)); baseName(path).let { safeName(it) } }
        // Long names keep their extension within 240 characters, and a surrogate pair is never split.
        val long = mediaSegment("😀".repeat(150) + ".heic", "x")
        assertTrue(long.length in 239..240)
        assertTrue(long.endsWith(".heic")); assertFalse(long.dropLast(5).last().isHighSurrogate())
        assertEquals("Photo 9.jpg", photoIndex(listOf(MediaRow(9, false, " ", "A", null, 1, 1, "image/jpeg"))).files.keys.single().substringAfter('/'))
    }

    @Test fun collisionsAreNumberedByIdIgnoringCaseAndAreStable() {
        val rows = listOf(row(5, "IMG.jpg"), row(3, "img.JPG"), row(9, "IMG (2).jpg"), row(4, "IMG.jpg", album = "camera"), row(8, "IMG.jpg", album = "Other"))
        val index = photoIndex(rows)
        // Albums differing only in case are one folder, named as the oldest item has it.
        assertEquals(mapOf("Camera/img.JPG" to 3L, "Camera/IMG (2).jpg" to 4L, "Camera/IMG (3).jpg" to 5L, "Other/IMG.jpg" to 8L, "Camera/IMG (2) (2).jpg" to 9L),
            index.files.mapValues { it.value.id })
        repeat(5) { assertEquals(index.files.keys, photoIndex(rows.shuffled()).files.keys) }
        assertEquals("a".repeat(231) + " (12).jpg", photoNumbered("a".repeat(236) + ".jpg", 12))
    }

    @Test fun scansDiffFromTheLibraryWithoutReadingFiles() {
        val source = FakeMediaSource()
        source.add(1, "Camera", "IMG_1.jpg", "one".toByteArray())
        source.add(2, "Camera", "IMG_2.jpg", "two".toByteArray())
        source.add(3, "Screenshots", "s.png", "shot".toByteArray())
        source.add(4, "Camera", "VID_1.mp4", "video".toByteArray(), video = true)
        val local = tree(source)
        val first = scanTree(local, emptyList())!!
        assertEquals(setOf("Camera", "Screenshots", "Camera/IMG_1.jpg", "Camera/IMG_2.jpg", "Camera/VID_1.mp4", "Screenshots/s.png"), first.keys)
        assertEquals(LocalStat(false, 3, 1_700_000_000_001L), first["Camera/IMG_1.jpg"])
        assertTrue(diffSnapshot(first, scanTree(local, emptyList())!!).isEmpty())
        // An edit (new size and time), a new photo and a deleted one; excluded albums are left out.
        source.remove(2)
        source.rows.replaceAll { if (it.id == 1L) it.copy(size = 5, modified = 1_750_000_000_000L) else it }
        source.add(5, "Camera", "IMG_3.jpg", "three".toByteArray())
        val changes = diffSnapshot(first, scanTree(local, listOf("Screenshots"))!!)
        assertEquals(listOf("Camera/IMG_1.jpg" to "upsert", "Camera/IMG_3.jpg" to "upsert", "Camera/IMG_2.jpg" to "delete", "Screenshots/s.png" to "delete", "Screenshots" to "delete"),
            changes.map { it.relativePath to it.kind })
        assertTrue(changes.first().changed)
        assertEquals("Nothing is read or hashed while scanning", 0, source.opened)
        // Files are read only when they are uploaded.
        assertEquals(sha("three".toByteArray()), local.hash("Camera/IMG_3.jpg"))
        assertEquals(1, source.opened)
        // Without videos, they are simply not part of the backup.
        assertEquals(setOf("Camera/IMG_1.jpg", "Camera/IMG_3.jpg"), scanTree(tree(source, videos = false), listOf("Screenshots"))!!.keys.filter { !it.endsWith("Camera") }.toSet())
    }

    @Test fun accessAndUnsupportedChangesAreClearErrors() {
        val source = FakeMediaSource(MediaAccess.NONE)
        val local = tree(source)
        val error = runCatching { local.check() }.exceptionOrNull() as LocalFsException
        assertEquals("EACCES", error.code)
        assertEquals(IssueCode.PERMISSION_DENIED, syncIssueCode(error))
        assertEquals(PHOTOS_ACCESS, issueText(SyncIssue("i", "r", IssueCode.PERMISSION_DENIED, error.message!!)))
        assertEquals("EACCES", (runCatching { local.list("") }.exceptionOrNull() as LocalFsException).code)
        source.access = MediaAccess.PARTIAL
        local.check()
        source.add(1, "Camera", "IMG_1.jpg", "one".toByteArray())
        assertEquals(1, local.list("").size)
        assertEquals("EIO", (runCatching { local.delete("Camera") }.exceptionOrNull() as LocalFsException).code)
        assertEquals("EIO", (runCatching { local.rename("Camera/IMG_1.jpg", "Camera/b.jpg") }.exceptionOrNull() as LocalFsException).code)
        assertEquals("EIO", (runCatching { local.createFile("Camera/new.jpg") }.exceptionOrNull() as LocalFsException).code)
        assertEquals("EACCES", (runCatching { local.delete("Camera/IMG_1.jpg") }.exceptionOrNull() as LocalFsException).code)
        assertEquals(1, source.rows.size)
    }

    @Test fun optionsPermissionsAndWifiGating() {
        val plain = harborJson.decodeFromString<SyncRoot>("""{"id":"r","treeUri":"content://t","localName":"Docs","remoteId":"x","mode":"backup"}""")
        assertNull(plain.photos); assertFalse(plain.isPhotos)
        val photos = plain.copy(treeUri = PHOTOS_URI, localName = PHOTOS_NAME, photos = PhotoOptions())
        val decoded = harborJson.decodeFromString<SyncRoot>(harborJson.encodeToString(SyncRoot.serializer(), photos))
        assertEquals(PhotoOptions(videos = true, mobileData = false), decoded.photos)
        assertTrue(decoded.isPhotos); assertEquals(PHOTOS_LOCATION, decoded.localLocation); assertEquals("Docs", plain.localLocation)
        // Photos skip the hour of quiet folders wait for.
        val now = System.currentTimeMillis()
        assertTrue(backupReady(decoded, now, now)); assertFalse(backupReady(plain, now, now))
        assertTrue(photoUploadsHeld(PhotoOptions(), metered = true))
        assertFalse(photoUploadsHeld(PhotoOptions(), metered = false))
        assertFalse(photoUploadsHeld(PhotoOptions(mobileData = true), metered = true))
        // The engine decides about mobile data; the trigger only needs a connection.
        assertEquals(NetworkType.CONNECTED, photoNetwork(PhotoOptions()))
        assertEquals(NetworkType.CONNECTED, photoNetwork(PhotoOptions(mobileData = true)))
        val wifi = NetworkKind(vpn = false, notMetered = true); val cell = NetworkKind(vpn = false, notMetered = false)
        val vpn = NetworkKind(vpn = true, notMetered = false)
        assertEquals(false, metered(wifi, listOf(wifi)))
        assertEquals(true, metered(cell, listOf(cell)))
        // A VPN is as metered as the network under it.
        assertEquals(false, metered(vpn, listOf(vpn, wifi)))
        assertEquals(true, metered(vpn, listOf(vpn, cell)))
        assertEquals(false, metered(null, emptyList()))
        assertEquals(listOf("android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE"), photoPermissions(28))
        assertEquals(listOf("android.permission.READ_EXTERNAL_STORAGE", "android.permission.ACCESS_MEDIA_LOCATION"), photoPermissions(32))
        assertFalse("android.permission.READ_MEDIA_VISUAL_USER_SELECTED" in photoPermissions(33))
        assertTrue(photoPermissions(36).containsAll(listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")))
        assertEquals(MediaAccess.PARTIAL, photoAccess(34) { it == "android.permission.READ_MEDIA_VISUAL_USER_SELECTED" })
        assertEquals(MediaAccess.FULL, photoAccess(36) { true })
        assertEquals(MediaAccess.NONE, photoAccess(33) { it == "android.permission.READ_EXTERNAL_STORAGE" })
        assertEquals(MediaAccess.FULL, photoAccess(30) { it == "android.permission.READ_EXTERNAL_STORAGE" })
        assertEquals("image/heic", mediaMime("IMG.HEIC")); assertEquals("video/quicktime", mediaMime("a.mov")); assertNull(mediaMime("notes.txt"))
        assertEquals("DCIM/Camera/", photoRestoreFolder("Camera")); assertEquals("Pictures/Trip/", photoRestoreFolder("Trip"))
    }

    @Test fun anEarlierPhotoBackupOfThisPhoneIsReused() {
        fun root(id: String, name: String = PHOTOS_NAME, state: String = "ACTIVE", device: String = "old", key: String? = "key", created: String = "2026-10-0${id.last()}") =
            BackupRoot(id, name, "folder-$id", state, deviceId = device, devicePublicId = key, createdAt = created)
        val roots = listOf(root("a1", state = "REMOVED"), root("a2", state = "ARCHIVED"), root("a3", name = "Docs"), root("a4", key = "other", device = "x"),
            root("a5"), root("a6", state = "PAUSED"))
        assertEquals("a6", reusablePhotoRoot(roots, "session", "key", emptySet())?.id)
        assertEquals("a5", reusablePhotoRoot(roots, "session", "key", setOf("a6"))?.id)
        assertEquals("a4", reusablePhotoRoot(roots, "x", null, emptySet())?.id)
        assertNull(reusablePhotoRoot(roots, "session", "else", emptySet()))
        // What the cloud folder holds already counts as saved when path, size and time match.
        val local = mapOf("Camera" to LocalStat(true, 0, 0), "Camera/a.jpg" to LocalStat(false, 3, 1_700_000_000_000L), "Camera/b.jpg" to LocalStat(false, 4, 1_700_000_000_000L))
        val album = DriveItem("al", "Camera", "FOLDER", revision = 2)
        val seed = photoSeed("r", local, mapOf(album to listOf(DriveItem("fa", "a.jpg", "FILE", sizeBytes = 3, revision = 4, modifiedAt = "2023-11-14T22:13:20Z"),
            DriveItem("fb", "b.jpg", "FILE", sizeBytes = 5, modifiedAt = "2023-11-14T22:13:20Z")), DriveItem("gone", "Gone", "FOLDER") to emptyList()))
        assertEquals(listOf(LocalFile("r", "Camera", "al", 2, null, "FOLDER"), LocalFile("r", "Camera/a.jpg", "fa", 4, null, "FILE")), seed.map { it.first })
    }

    @Test fun restoresGoIntoTheLibraryAndNeverOverwritePhotosHarborDidNotSave() = runBlocking {
        val kept = "still here".toByteArray()
        val gone = "deleted photo".toByteArray()
        val saved = "saved version".toByteArray()
        val source = FakeMediaSource()
        source.add(1, "Camera", "kept.jpg", kept)
        val server = MockWebServer()
        server.dispatcher = object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                return when {
                    path == "/v1/auth/login" -> json("""{"accessToken":"a","refreshToken":"r","expiresIn":3600}""")
                    path == "/v1/backups/b1/pending-restores" -> json("""{"items":[{"id":"r1","itemId":"gone","folder":false,"relativePath":"Camera/gone.jpg","state":"PENDING","versionId":"v1"},
                        {"id":"r2","itemId":"kept","folder":false,"relativePath":"Camera/kept.jpg","state":"PENDING","versionId":"v2"}]}""")
                    path == "/v1/downloads" -> {
                        val item = harborJson.parseToJsonElement(request.body.readUtf8()).jsonObject["driveItemId"]!!.jsonPrimitive.content
                        val content = if (item == "gone") gone else saved
                        json("""{"downloadUrl":"${server.url("/blob/$item")}","sizeBytes":${content.size},"contentHash":"${sha(content)}"}""")
                    }
                    path == "/blob/gone" -> MockResponse().setBody(String(gone))
                    path == "/blob/kept" -> MockResponse().setBody(String(saved))
                    path == "/v1/backups/b1" -> json("""{"root":{"state":"ACTIVE"}}""")
                    path.startsWith("/v1/backups/b1/restores/") -> json("{}")
                    else -> json("""{"error":{"code":"NOT_FOUND","message":"Not found"}}""", 404)
                }
            }
        }
        server.start()
        try {
            val api = HarborApi(server.url("/").toString(), MemoryCredentials()).also { it.login("a@b.test", "secret", "Test") }
            val engine = EngineApi(api, pause = {})
            var summary: RestoreSummary? = null
            val written = mutableListOf<String>()
            val local = tree(source)
            BackupRestores(engine, SyncTransfers(engine, allowLocal = true), MemoryValues(), { _, _ -> null }, { _, path, stat -> if (stat != null) written += path }) { _, p, s ->
                if (p == null) summary = s
            }.run(SyncRoot("root", PHOTOS_URI, PHOTOS_NAME, "remote", "backup", backupId = "b1", photos = PhotoOptions()), local, "/v1/backups/b1")
            // The deleted photo is back in its album, saved where the camera keeps photos, and is known at once.
            val restored = source.inserted.single()
            assertEquals("DCIM/Camera/" to "gone.jpg", restored.first to restored.second.name)
            assertArrayEquals(gone, source.bytes[restored.second.id])
            assertEquals(listOf("Camera/gone.jpg"), written)
            assertEquals(gone.size.toLong(), local.stat("Camera/gone.jpg")?.size)
            // The photo still on the phone was not harbor0's to replace: it stays as it was, and the restore says why.
            assertArrayEquals(kept, source.bytes[1])
            assertEquals(1, summary!!.restored); assertEquals(1, summary!!.failed)
            assertTrue(summary!!.error!!, summary!!.error!!.contains("still on this phone"))
            // Part files stayed in private space and are gone.
            assertTrue(File(dir, "scratch").walk().none { it.isFile })
        } finally { server.shutdown() }
    }
}
