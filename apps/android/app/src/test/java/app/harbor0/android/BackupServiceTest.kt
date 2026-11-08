package app.harbor0.android

import org.junit.Assert.*
import org.junit.Test

class BackupServiceTest {
    private val photos = SyncRoot("p", PHOTOS_URI, "Photos", "item-p", "backup", backupId = "b", photos = PhotoOptions())
    private val docs = SyncRoot("d", "content://tree", "Docs", "item-d", "backup", backupId = "c")
    private val music = SyncRoot("s", "content://tree/music", "Music", "item-s", "sync")
    private fun view(runtime: SyncRuntime, vararg roots: SyncRoot) = SyncView(runtime, roots.toList())
    private fun jobs(count: Int, root: String = "s") = List(count) { SyncJobView("j$it", root, "file-$it.mp3", "upload", null, 0) }

    @Test fun theServiceRunsOnlyWhileABackupHasFilesLeftThatCanMove() {
        val running = SyncRuntime(backupRuns = mapOf("p" to BackupRunProgress(12, 120)))
        val work = backgroundWork(view(running, photos))!!
        assertEquals("Backing up photos", work.title)
        assertEquals("Saved 12 of 120 files", work.detail)
        assertEquals("Backing up Docs", backgroundWork(view(SyncRuntime(backupRuns = mapOf("d" to BackupRunProgress(0, 3))), docs))!!.title)
        // Nothing open, or a run that has handled everything.
        assertNull(backgroundWork(view(SyncRuntime(), photos)))
        assertNull(backgroundWork(view(SyncRuntime(backupRuns = mapOf("p" to BackupRunProgress(120, 120))), photos)))
        assertNull(backgroundWork(null))
        // Waiting for something the service can't change: no notification sits there doing nothing.
        assertNull(backgroundWork(view(running.copy(paused = true), photos)))
        assertNull(backgroundWork(view(running.copy(locked = true), photos)))
        assertNull(backgroundWork(view(running.copy(waitingForWifi = setOf("p")), photos)))
        assertNull(backgroundWork(view(running, photos.copy(paused = true))))
        assertNull(backgroundWork(view(running.copy(storageFull = StorageFull(0), storage = Storage(1_000_000_000, 1_000_000_000, 0)), photos)))
    }

    @Test fun aLargeSyncKeepsTheServiceGoingButASmallOneDoesNotStartIt() {
        val big = SyncView(SyncRuntime(), listOf(music), jobs(LARGE_SYNC_FILES))
        val work = backgroundWork(big, minSync = LARGE_SYNC_FILES)!!
        assertEquals("Syncing Music", work.title)
        assertEquals("20 files left", work.detail)
        // A few files: not worth a notification when the app is left, but the service carries on until they're done.
        val small = SyncView(SyncRuntime(), listOf(music), jobs(2))
        assertNull(backgroundWork(small, minSync = LARGE_SYNC_FILES))
        assertEquals("2 files left", backgroundWork(small)!!.detail)
        // One large file under way counts as large.
        val video = SyncView(SyncRuntime(active = SyncProgress("s", "upload", "film.mp4", 0, LARGE_SYNC_BYTES)), listOf(music), jobs(1))
        assertNotNull(backgroundWork(video, minSync = LARGE_SYNC_FILES))
        // Nothing to move, or held up by something the service can't change.
        assertNull(backgroundWork(SyncView(SyncRuntime(), listOf(music))))
        assertNull(backgroundWork(big.copy(runtime = SyncRuntime(paused = true))))
        assertNull(backgroundWork(big.copy(runtime = SyncRuntime(online = false))))
        assertNull(backgroundWork(big.copy(roots = listOf(music.copy(paused = true)))))
        assertNull(backgroundWork(big.copy(jobs = jobs(20).map { it.copy(error = "Failed") })))
        // A backup run comes first.
        val both = SyncView(SyncRuntime(backupRuns = mapOf("d" to BackupRunProgress(0, 3))), listOf(music, docs), jobs(30))
        assertEquals("Backing up Docs", backgroundWork(both)!!.title)
    }
}
