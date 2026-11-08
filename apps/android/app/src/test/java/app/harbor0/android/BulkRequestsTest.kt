package app.harbor0.android

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BulkRequestsTest {
    @Test fun atMostFourRunAtOnceAndResultsKeepTheirOrder() = runTest {
        var running = 0
        var most = 0
        val results = settleBounded((1..10).toList()) { n ->
            running++; most = maxOf(most, running)
            delay(100)
            running--
            if (n == 3) error("three") else n * 2
        }
        assertEquals(4, most)
        assertEquals(listOf(2, 4, null, 8, 10, 12, 14, 16, 18, 20), results.map { it.getOrNull() })
        assertEquals("three", results[2].exceptionOrNull()?.message)
    }
    @Test fun transientFailuresRetryAfter500And1500And4000() = runTest {
        val waits = mutableListOf<Long>()
        var calls = 0
        val value = retryTransient({ waits += it }, { 1.0 }) {
            calls++
            if (calls < 4) throw ApiException(503, "busy", if (calls == 2) "RATE_LIMITED" else null)
            "done"
        }
        assertEquals("done", value)
        assertEquals(listOf(500L, 1500L, 4000L), waits)
    }
    @Test fun givesUpAfterThreeRetriesAndNeverRetriesOtherErrors() = runTest {
        var calls = 0
        val busy = runCatching { retryTransient({}, { 1.0 }) { calls++; throw ApiException(429, "slow down", "RATE_LIMITED") } }
        assertEquals(4, calls)
        assertEquals("slow down", busy.exceptionOrNull()?.message)
        calls = 0
        runCatching { retryTransient({}, { 1.0 }) { calls++; throw ApiException(409, "conflict", "REVISION_CONFLICT") } }
        assertEquals(1, calls)
        assertTrue(isTransient(ApiException(400, "x", "BACKEND_UNAVAILABLE")))
        assertFalse(isTransient(IllegalStateException("x")))
    }
    @Test fun failuresAreGroupedByReason() {
        val targets = listOf("a.txt", "b.txt", "c.txt", "d.txt")
        val results = listOf(Result.failure<Unit>(Exception("Gone.")), Result.success(Unit), Result.failure(Exception("Gone.")), Result.failure(Exception("Locked.")))
        assertEquals("Could not save 2 items. Gone. Could not save “d.txt”. Locked.", failureSummary(targets, results, { it }, { it.message!! }))
        assertNull(failureSummary(targets, List(4) { Result.success(Unit) }, { it }, { it.message!! }))
    }
}
