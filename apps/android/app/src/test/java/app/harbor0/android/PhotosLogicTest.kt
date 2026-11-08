package app.harbor0.android

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.decodeFromString
import java.time.ZoneId
import java.util.Locale

/** The Photos page's rules (PhotosPage.kt), as apps/web/components/photos-page.tsx and contracts `mediaKind`. */
class PhotosLogicTest {
    private fun file(id: String, name: String, mime: String? = null, created: String = "2026-10-01T10:00:00Z", modified: String? = null,
        backup: String? = null, sync: String? = null, size: Long = 1000, encrypted: Boolean = false) =
        DriveItem(id, name, "FILE", mimeType = mime, createdAt = created, modifiedAt = modified, backupRootId = backup, syncFolderId = sync,
            sizeBytes = size, encrypted = encrypted)

    @Test fun mediaKindByTypeThenExtension() {
        assertEquals(MediaKind.Photo, mediaKind(file("1", "a.bin", "image/png")))
        assertEquals(MediaKind.Video, mediaKind(file("1", "a.bin", "video/mp4; codecs=x")))
        assertEquals(MediaKind.Photo, mediaKind(file("1", "IMG_1.HEIC", "application/octet-stream")))
        assertEquals(MediaKind.Photo, mediaKind(file("1", "raw.CR3")))
        assertEquals(MediaKind.Video, mediaKind(file("1", "clip.mkv", "")))
        assertNull(mediaKind(file("1", "photo.jpg", "application/pdf")))
        assertNull(mediaKind(file("1", "notes.txt")))
        assertNull(mediaKind(DriveItem("1", "Pictures.jpg", "FOLDER")))
    }
    @Test fun listIsDedupedFilteredAndNewestFirstByTakenTime() {
        val pages = listOf(
            listOf(file("a", "a.jpg", created = "2026-09-01T00:00:00Z"), file("b", "b.mp4", created = "2026-10-05T00:00:00Z", modified = "2026-08-01T00:00:00Z")),
            listOf(file("a", "a.jpg", created = "2026-09-01T00:00:00Z"), file("c", "c.txt"), file("d", "d.png", created = "2026-10-20T00:00:00Z")))
        assertEquals(listOf("d", "a", "b"), photoList(pages).map { it.id })
    }
    @Test fun monthsInOrder() {
        val items = photoList(listOf(listOf(file("a", "a.jpg", created = "2026-10-20T00:00:00Z"), file("b", "b.jpg", created = "2026-10-02T00:00:00Z"),
            file("c", "c.jpg", created = "2026-09-30T23:00:00Z"))))
        val sections = monthSections(items, ZoneId.of("UTC"), Locale.US)
        assertEquals(listOf("October 2026", "September 2026"), sections.map { it.name })
        assertEquals(listOf(2, 1), sections.map { it.items.size })
        // Local time decides the month.
        assertEquals("October 2026", monthName("2026-09-30T23:00:00Z", ZoneId.of("Asia/Kolkata"), Locale.US))
    }
    @Test fun devicesComputersFirstThenPhonesThenGoneThenMyDrive() {
        val laptop = Device("dev-laptop", "Work laptop", "MACOS", devicePublicId = "key-laptop")
        val phone = Device("dev-phone", "Pixel", "ANDROID", devicePublicId = "key-phone")
        val browser = Device("dev-web", "Browser", "WEB")
        val backups = listOf(BackupRoot("b1", "Camera", "r1", "ACTIVE", deviceName = "Pixel", devicePublicId = "key-phone"),
            BackupRoot("b2", "Old", "r2", "ACTIVE", deviceName = "Old PC", devicePublicId = "key-old"))
        val synced = listOf(DriveItem("s1", "Shared", "FOLDER", syncDevices = listOf(SyncDevice("dev-laptop", "Work laptop"), SyncDevice("dev-phone", "Pixel"))),
            DriveItem("s2", "Orphan", "FOLDER"))
        val items = listOf(file("up", "up.jpg"), file("cam", "cam.jpg", backup = "b1"), file("old", "old.jpg", backup = "b2"),
            file("both", "both.jpg", sync = "s1"), file("orphan", "orphan.jpg", sync = "s2"))
        val sections = deviceSections(items, listOf(phone, browser, laptop), backups, synced)
        assertEquals(listOf("Work laptop", "Pixel", "Old PC", "Synced folders", "Uploaded to My Drive"), sections.map { it.name })
        // A photo in a folder synced on two devices shows under both.
        assertEquals(listOf("both"), sections[0].items.map { it.id })
        assertEquals(listOf("cam", "both"), sections[1].items.map { it.id })
        assertEquals(phone, sections[1].device)
        assertNull(sections[2].device)
        assertEquals("My Drive", photoPlace(items[0], listOf(phone, laptop), backups, synced))
        assertEquals("Backup from Pixel", photoPlace(items[1], listOf(phone, laptop), backups, synced))
        assertEquals("Synced on Work laptop, Pixel", photoPlace(items[3], listOf(phone, laptop), backups, synced))
        assertEquals("Backup from Other device", photoPlace(file("x", "x.jpg", backup = "gone"), emptyList(), emptyList(), emptyList()))
    }
    @Test fun thumbnailsOnlyForFormatsAndSizesThePhoneCanShow() {
        assertTrue(canShowThumbnail(file("1", "a.jpg", size = MAX_THUMBNAIL)))
        assertFalse(canShowThumbnail(file("1", "a.jpg", size = MAX_THUMBNAIL + 1)))
        assertTrue("videos read only a frame", canShowThumbnail(file("1", "a.mp4", size = 5_000_000_000)))
        assertFalse(canShowThumbnail(file("1", "a.cr3")))
        assertFalse(canShowThumbnail(file("1", "a.svg")))
        assertTrue(canShowThumbnail(file("1", "a.png", size = MAX_ENCRYPTED_THUMBNAIL, encrypted = true)))
        assertFalse(canShowThumbnail(file("1", "a.png", size = MAX_ENCRYPTED_THUMBNAIL + 1, encrypted = true)))
        assertTrue("encrypted videos are read by range, whatever their size", canShowThumbnail(file("1", "a.mp4", size = 5_000_000_000, encrypted = true)))
    }

    @Test fun savedThumbnailsAreCroppedToAtMostThreeToOne() {
        assertEquals(4000 to 3000, thumbnailCrop(4000, 3000))
        assertEquals(3000 to 1000, thumbnailCrop(9000, 1000))
        assertEquals(500 to 1500, thumbnailCrop(500, 4000))
    }

    @Test fun photosPagesSayWhetherTheyAreInOrder() {
        val page = harborJson.decodeFromString<PhotoPage>("""{"items":[],"nextCursor":null,"ordered":true}""")
        assertTrue(page.ordered)
        assertFalse(harborJson.decodeFromString<PhotoPage>("""{"items":[],"nextCursor":"x"}""").ordered)
        val link = harborJson.decodeFromString<PhotoLinks>("""{"items":[{"id":"a","url":"u","sizeBytes":1,"thumbnailUrl":"t"}],"expiresAt":"2026-01-01T00:00:00Z"}""")
        assertEquals("t", link.items[0].thumbnailUrl)
    }
}
