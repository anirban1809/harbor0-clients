package app.harbor0.android

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DriveParityTest {
    private val root = BackupRoot("r", "Photos", "item-r", "ACTIVE", deviceName = "Studio Mac")
    private fun restore(id: String, state: String, at: String) = BackupRestore(id, state = state, requestedAt = at)
    private fun run(state: String, files: Int = 3, bytes: Long = 3_000_000, planned: Int? = null, plannedBytes: Long? = null) =
        BackupRun("run", state, "AUTOMATIC", "2026-10-20T10:00:00Z", files, bytes, plannedFileCount = planned, plannedSizeBytes = plannedBytes)
    private val phoneRoot = SyncRoot("local", "content://tree", "Photos", "item-r", "backup", backupId = "r")

    @Test fun restoreProgressCountsTheCurrentBatch() {
        assertNull(restoreProgress(listOf(restore("old", "COMPLETED", "2026-10-19T00:00:00Z"))))
        val batch = listOf(restore("old", "COMPLETED", "2026-10-19T00:00:00Z"), restore("a", "COMPLETED", "2026-10-20T10:00:00Z"),
            restore("b", "PENDING", "2026-10-20T10:00:00Z"), restore("c", "PENDING", "2026-10-20T10:00:01Z"))
        assertEquals(RestoreBatch(1, 3), restoreProgress(batch))
        assertEquals("Restored 1 of 3 files…", RestoreBatch(1, 3).detail)
        assertEquals("Getting ready to restore 2 files…", RestoreBatch(0, 2).detail)
        assertEquals("Restoring 1 file…", RestoreBatch(1, 1).detail)
    }
    @Test fun backupStatusFollowsTheWebPanel() {
        assertEquals("Waiting" to "Checking backup status…", backupStatus(root, null, emptyList()).let { it.label to it.detail })
        assertEquals("Saved 1,200 of 3000 files · 1.0 GB of 2.0 GB",
            backupStatus(root, listOf(run("RUNNING", 1200, 1_000_000_000, 3000, 2_000_000_000)), emptyList()).detail)
        assertEquals("Saving 3 files so far…", backupStatus(root, listOf(run("RUNNING")), emptyList()).detail)
        assertEquals("Stopped", backupStatus(root.copy(state = "REMOVED"), emptyList(), emptyList()).label)
        assertEquals("Backups from Studio Mac are stopped. Every backed-up file and version is kept here, read-only.",
            backupStatus(root.copy(state = "ARCHIVED"), emptyList(), emptyList()).detail)
        assertEquals("Needs attention", backupStatus(root.copy(state = "ERROR"), emptyList(), emptyList()).label)
        // Restores on this phone show their progress; another device's restores only show as waiting in History.
        val pending = listOf(restore("a", "PENDING", "2026-10-20T10:00:00Z"))
        assertEquals("Getting ready to restore 1 file…", backupStatus(root, emptyList(), pending, phoneRoot).detail)
        assertEquals("Waiting", backupStatus(root, emptyList(), pending).label)
        assertEquals("Paused", backupStatus(root, emptyList(), pending, phoneRoot.copy(paused = true)).label)
        // The engine's own progress, file by file, is preferred for a backup on this phone.
        val live = SyncRuntime(restoring = mapOf(phoneRoot.id to RestoreProgress(1, 4, "Docs/b.txt", 50, 100)))
        backupStatus(root, emptyList(), pending, phoneRoot, runtime = live).let {
            assertEquals("Restoring 2 of 4 files…", it.detail); assertEquals(.375f, it.fraction!!, .001f)
        }
        assertEquals("Restoring b.txt…", backupStatus(root, emptyList(), pending, phoneRoot,
            runtime = SyncRuntime(restoring = mapOf(phoneRoot.id to RestoreProgress(0, 1, "Docs/b.txt", 0, 0)))).detail)
        // Waiting for cloud storage is the folder's status.
        val full = backupStatus(root, emptyList(), emptyList(), phoneRoot.copy(backupStorage = BackupStorage(500, 100, 2)),
            runtime = SyncRuntime(storage = Storage(10_000, 9_000, 0)))
        assertEquals(NEEDS_STORAGE to "2 files won’t fit in your remaining storage.", full.label to full.detail)
    }
    @Test fun backupOnThisPhoneShowsTheBytesOnTheirWay() {
        // The server's run counts nothing until a batch is recorded; the engine's upload under way is added in.
        val sending = SyncRuntime(active = SyncProgress(phoneRoot.id, "upload", "Camera/a.jpg", 6_000_000, 20_000_000),
            backupRuns = mapOf(phoneRoot.id to BackupRunProgress(0, 3)))
        backupStatus(root, listOf(run("RUNNING", 0, 0, 3, 20_000_000)), emptyList(), phoneRoot, runtime = sending).let {
            assertEquals("Backing up", it.label)
            assertEquals("Saving 1 of 3 files · 6.0 MB of 20.0 MB", it.detail)
            assertEquals(.3f, it.fraction!!, .001f)
        }
        // Before the server's run is read, the upload's own size is shown.
        assertEquals("Saving 1 of 3 files · 6.0 MB of 20.0 MB sending", backupStatus(root, emptyList(), emptyList(), phoneRoot, runtime = sending).detail)
        // Recorded files count once the engine has moved past them, and the bar never reads full before the run ends.
        val later = SyncRuntime(active = SyncProgress(phoneRoot.id, "upload", "Camera/c.jpg", 2_000_000, 2_000_000),
            backupRuns = mapOf(phoneRoot.id to BackupRunProgress(2, 3)))
        backupStatus(root, listOf(run("RUNNING", 2, 18_000_000, 3, 20_000_000)), emptyList(), phoneRoot, runtime = later).let {
            assertEquals("Saving 3 of 3 files · 20.0 MB of 20.0 MB", it.detail); assertEquals(.99f, it.fraction!!, .001f)
        }
        // The next file starting before the server has recorded the last one doesn't take the total back.
        val next = SyncRuntime(active = SyncProgress(phoneRoot.id, "upload", "Camera/c.jpg", 0, 2_000_000), backupRuns = mapOf(phoneRoot.id to BackupRunProgress(2, 3)))
        assertEquals("Saving 3 of 3 files · 12.0 MB of 20.0 MB", backupStatus(root, listOf(run("RUNNING", 1, 6_000_000, 3, 20_000_000)), emptyList(), phoneRoot,
            runtime = next, shown = "run" to 12_000_000).detail)
        assertEquals("run" to 6_000_000L, backupStatus(root, listOf(run("RUNNING", 0, 0, 3, 20_000_000)), emptyList(), phoneRoot, runtime = sending).saved)
        // Another folder's upload doesn't move this one.
        val elsewhere = sending.copy(active = sending.active!!.copy(rootId = "other"))
        assertEquals("Saving 1 of 3 files · 0 B of 20.0 MB", backupStatus(root, listOf(run("RUNNING", 0, 0, 3, 20_000_000)), emptyList(), phoneRoot, runtime = elsewhere).detail)
    }
    @Test fun syncBadgeLabelsAndDetails() {
        val status = SyncStatus("f", "PENDING", 3, 1, cloudState = "RELEASED", pendingItems = 4)
        assertEquals("Pending", status.label)
        assertEquals("Synced", status.copy(state = "SYNCED").label)
        assertEquals("Status unavailable", status.copy(state = "UNKNOWN").label)
        assertEquals("4 items waiting for linked devices.", syncDetail(DriveItem("f", "Docs", "FOLDER"), status))
        assertEquals("All contents confirmed on linked devices.", syncDetail(DriveItem("f", "Docs", "FOLDER"), status.copy(state = "SYNCED")))
        assertEquals("1 of 3 linked devices confirmed. Stored only on linked devices.", syncDetail(DriveItem("a", "a.txt", "FILE"), status))
        assertEquals("1 of 3 linked devices confirmed. Stored in the cloud.", syncDetail(DriveItem("a", "a.txt", "FILE"), status.copy(cloudState = null)))
    }
    @Test fun uploadConflictsOfferReplaceOrKeepBoth() {
        assertEquals("Report (2).pdf", numberedName("Report.pdf", 2))
        assertEquals(".env (3)", numberedName(".env", 3))
        assertEquals("notes (2)", numberedName("notes", 2))
        assertEquals("A file with this name is already here.", uploadErrorMessage(ApiException(409, "Name taken", "NAME_CONFLICT")))
        assertEquals("Other", uploadErrorMessage(ApiException(400, "Other", "X")))
        val entries = listOf(UploadEntry("1", "a.txt", 1, phase = UploadPhase.Failed, conflict = "file", groupKey = "g", groupName = "Folder"),
            UploadEntry("2", "b", 1, phase = UploadPhase.Failed, conflict = "folder", groupKey = "g", groupName = "Folder"),
            UploadEntry("3", "c.txt", 1, phase = UploadPhase.Done, groupKey = "g", groupName = "Folder"))
        val activity = summarizeUploads(entries).single()
        assertEquals(listOf("1", "2"), activity.conflictKeys)
        assertFalse(activity.replaceable)
        assertTrue(summarizeUploads(entries.take(1)).single().replaceable)
    }
    @Test fun publicLinkTexts() {
        val now = Instant.parse("2026-10-20T12:00:00Z").toEpochMilli()
        assertEquals("Never expires", linkEnds(null, now))
        assertTrue(linkEnds("2026-10-26T12:00:00Z", now).startsWith("Expires in 6 days · "))
        assertTrue(linkEnds("2026-10-21T11:00:00Z", now).startsWith("Expires tomorrow · "))
        assertTrue(linkEnds("2026-10-20T11:00:00Z", now).startsWith("Expires today · "))
        assertEquals("harbor0content.online/l/abc", shownLink("https://harbor0content.online/l/abc"))
        assertTrue(expiresSoon(PublicLink("l", "i", url = "u", expiresAt = "2026-10-21T12:00:00Z"), now))
        assertFalse(expiresSoon(PublicLink("l", "i", url = "u", expiresAt = null), now))
    }
    @Test fun storageAuditCsvMatchesTheWeb() {
        val rows = listOf(
            StorageAuditRow("i1", "v1", "=sum.txt", "MY_DRIVE", null, "CURRENT", 1, 1500, 1500, "h1", "2026-10-01T00:00:00Z", "Mac"),
            StorageAuditRow("i2", "v2", "a,b.txt", "TRASH", "Deleted", "PREVIOUS_VERSION", 2, 2_500_000, 2_500_000, "h2", "2026-10-02T00:00:00Z", null))
        val csv = storageAuditCsv(rows, Storage(10_000_000, 3_000_000, 0), Instant.parse("2026-10-20T00:00:00Z"))
        val lines = csv.split("\r\n")
        assertEquals("harbor0 storage audit,2026-10-20T00:00:00Z", lines[0])
        assertEquals("My Drive,1500,1.50 KB", lines[2])
        assertEquals("Trash,2500000,2.50 MB", lines[3])
        assertEquals("  of which previous versions,2500000,2.50 MB", lines[4])
        assertEquals("Unaccounted difference,498500,498.50 KB", lines[7])
        assertEquals("\"a,b.txt\",Trash,Deleted,Previous version,2,2500000,2500000,2.50 MB,2026-10-02T00:00:00Z,,h2,i2,v2", lines[13])
        assertEquals("'=sum.txt,My Drive,,Current version,1,1500,1500,1.50 KB,2026-10-01T00:00:00Z,Mac,h1,i1,v1", lines[14])
        assertTrue(csv.endsWith("\r\n"))
    }
}
