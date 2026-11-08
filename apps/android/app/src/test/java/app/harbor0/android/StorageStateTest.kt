package app.harbor0.android

import org.junit.Assert.*
import org.junit.Test

class StorageStateTest {
    private fun storage(quota: Long, used: Long, reserved: Long = 0) = Storage(quota, used, reserved)

    @Test fun fullBelowIsOnePercentAtMostOneMegabyte() {
        assertEquals(1_000_000L, fullBelow(10_000_000_000))
        assertEquals(500_000L, fullBelow(50_000_000))
        assertEquals(1_000_000L, fullBelow(0))
    }
    @Test fun statesFollowTheWebRule() {
        val gb = 1_000_000_000L
        assertEquals("ok", storageState(storage(10 * gb, 9 * gb)))
        assertEquals("low", storageState(storage(10 * gb, 9_400_000_000, 100_000_000)))
        assertEquals("full", storageState(storage(10 * gb, 10 * gb - 999_999)))
        assertEquals("full", storageState(storage(10 * gb, 10 * gb)))
        assertEquals("over", storageState(storage(10 * gb, 10 * gb + 1)))
    }
    @Test fun bannerTexts() {
        val gb = 1_000_000_000L
        assertNull(storageWarning(storage(10 * gb, gb)))
        assertNull(storageWarning(storage(0, 0)))
        assertEquals("500.0 MB of your 10.0 GB is left. You’re running low on storage.", storageWarning(storage(10 * gb, 9_500_000_000)))
        assertEquals("Your storage is full (10.0 GB). New files can’t be uploaded or synced until you free up space or get more storage.", storageWarning(storage(10 * gb, 10 * gb)))
        assertEquals("You’re using 12.0 GB, more than your 10.0 GB of storage. New files can’t be uploaded or synced until you free up space or get more storage.",
            storageWarning(storage(10 * gb, 12 * gb)))
    }
}
