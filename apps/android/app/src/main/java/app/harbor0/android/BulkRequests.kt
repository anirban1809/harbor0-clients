package app.harbor0.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

// A bulk action that fires every request at once has most of them throttled, so bulk actions run
// a few at a time and retry failures unrelated to the request (apps/web/lib/bulk-requests.ts).
const val BULK_CONCURRENCY = 4
val RETRY_DELAYS_MS = listOf(500L, 1500L, 4000L)

/** A failure retrying may fix: the backend is busy or unavailable. */
fun isTransient(e: Throwable) = e is ApiException && (e.code == "BACKEND_UNAVAILABLE" || e.code == "RATE_LIMITED" || e.status >= 500)

/**
 * Retries a request that failed for a reason unrelated to the request itself, after 500, 1500 and
 * 4000 ms (each ±25%). Only for calls that are safe to repeat: a mutation needs a baseRevision or
 * the same operationId on every attempt, so build its body outside [call].
 */
suspend fun <T> retryTransient(wait: suspend (Long) -> Unit = { delay(it) }, jitter: () -> Double = { .75 + Math.random() / 2 }, call: suspend () -> T): T {
    var attempt = 0
    while (true) {
        try { return call() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (attempt >= RETRY_DELAYS_MS.size || !isTransient(e)) throw e
            wait((RETRY_DELAYS_MS[attempt] * jitter()).toLong())
            attempt++
        }
    }
}

/** Runs [run] for every item with at most [limit] in flight; each result is kept in order, as Promise.allSettled. */
suspend fun <T, R> settleBounded(items: List<T>, limit: Int = BULK_CONCURRENCY, run: suspend (T) -> R): List<Result<R>> {
    val results = arrayOfNulls<Result<R>>(items.size)
    val next = AtomicInteger(0)
    coroutineScope {
        repeat(minOf(limit, items.size)) {
            launch {
                while (true) {
                    val index = next.getAndIncrement()
                    if (index >= items.size) break
                    results[index] = try { Result.success(run(items[index])) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
                }
            }
        }
    }
    @Suppress("UNCHECKED_CAST")
    return results.toList() as List<Result<R>>
}

/**
 * One sentence per distinct reason, so a bulk action that failed for one cause reads once:
 * “Could not save “a.txt”. Reason” or “Could not save 3 items. Reason”. Null when nothing failed.
 */
fun <T> failureSummary(targets: List<T>, results: List<Result<*>>, name: (T) -> String, reason: (Throwable) -> String,
    action: (String) -> String = { "save $it" }): String? {
    val failures = linkedMapOf<String, MutableList<T>>()
    results.forEachIndexed { index, result -> result.exceptionOrNull()?.let { failures.getOrPut(reason(it)) { mutableListOf() } += targets[index] } }
    if (failures.isEmpty()) return null
    return failures.entries.joinToString(" ") { (why, failed) ->
        "Could not ${action(if (failed.size == 1) "“${name(failed[0])}”" else "${failed.size} items")}. $why"
    }
}

/** Applies [save] to every target, four at a time with retries, and returns the failures as one message. */
suspend fun <T> bulk(targets: List<T>, name: (T) -> String, reason: (Throwable) -> String, action: (String) -> String = { "save $it" },
    save: suspend (T) -> Unit): String? {
    val results = settleBounded(targets) { item -> retryTransient { save(item) } }
    return failureSummary(targets, results, name, reason, action)
}
