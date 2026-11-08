package app.harbor0.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.IOException

// Server limits shared with the other apps (packages/contracts/src/rate-limits.ts).
/** API requests one account may make a minute, across all its devices. */
const val RATE_LIMIT = 600
/** Uploads and backup runs have a minute's allowance of their own; see [bulkTransferPath]. */
const val BULK_RATE_LIMIT = 1800
/** Small files sent in one batch request; see the batch upload routes. */
const val BATCH_UPLOAD_FILES = 25
/** Files up to this size go in batches: one storage part each. Larger ones upload alone. */
const val BATCH_UPLOAD_MAX_BYTES = 8L * 1024 * 1024
/** Synced copies confirmed in one request; see POST /v1/sync/items/acknowledge. */
const val SYNC_ACKNOWLEDGE_ITEMS = 50
/** Whether a request counts against [BULK_RATE_LIMIT]. */
fun bulkTransferPath(path: String) = Regex("^/v1/(uploads(/|$|\\?)|backups/[^/]+/runs/)").containsMatchIn(path)

// Below the server's limits with room to spare, as desktop's request-budget.ts (560 a minute), keeping a share for
// what the person does in the app so a large backup never makes the app itself fail with "Too many requests".
const val REQUEST_LIMIT = 560
const val FOREGROUND_RESERVE = 80
private const val WINDOW_MS = 60_000L

/**
 * Requests sent in the last minute. A request over its share waits for the oldest to age out instead of being
 * refused by the server. Counting over any 60 seconds keeps every calendar minute under the limit too.
 */
class RequestBudget(private val limit: Int, private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) }) {
    private val sent = ArrayDeque<Long>()
    private val lock = Mutex()
    suspend fun take() {
        while (true) {
            val wait = lock.withLock {
                val time = now()
                while (sent.isNotEmpty() && sent.first() <= time - WINDOW_MS) sent.removeFirst()
                if (sent.size < limit) { sent.addLast(time); return }
                // The request that has to age out before this one fits.
                maxOf(10L, sent[sent.size - limit] + WINDOW_MS - time)
            }
            sleep(wait)
        }
    }
}

private const val RETRIES = 4
/** Exponential backoff with jitter (desktop retry.ts): half the step is fixed, half is random. */
fun backoffDelay(retry: Int, random: () -> Double = Math::random): Long {
    val step = minOf(8_000.0, 500.0 * (1L shl minOf(retry, 10)))
    return (step / 2 + random() * step / 2).toLong()
}
/**
 * Throttling and unavailability were refused before the handler ran, so even a non-repeatable request did nothing.
 * Other server errors and dropped connections repeat only reads and requests whose operation id makes the server
 * return the first result instead of applying it twice.
 */
fun shouldRetry(error: Throwable, method: String, body: JsonObject?): Boolean {
    val repeatable = method == "GET" || (body?.get("operationId") as? JsonPrimitive)?.isString == true
    if (error is ApiException) {
        if (error.status == 429 || error.status == 503) return true
        return repeatable && (error.status >= 500 || error.code == "CONCURRENT_UPDATE")
    }
    return repeatable && error is IOException
}

/**
 * The API as the sync and backup engine uses it: every attempt waits for room in the engine's share of the
 * request budget (normal and bulk routes counted apart), and transient failures are retried with backoff.
 * Requests the person makes in the app go through [HarborApi] directly and use the reserve.
 */
class EngineApi(val api: HarborApi, private val normal: RequestBudget = RequestBudget(REQUEST_LIMIT - FOREGROUND_RESERVE),
    private val bulk: RequestBudget = RequestBudget(BULK_RATE_LIMIT * REQUEST_LIMIT / RATE_LIMIT - FOREGROUND_RESERVE),
    private val pause: suspend (Long) -> Unit = { delay(it) },
    /**
     * How requests reach the server: on an account that may be end-to-end encrypted, the vault's strict transport,
     * which refuses file requests while locked so the engine never acts on encrypted names. Defaults to [HarborApi.request].
     */
    private val transport: E2eeApi? = null) {
    val baseUrl get() = api.baseUrl
    /** The same budget, with names and sizes exactly as stored: for converting backups, which must see both forms. */
    fun raw() = EngineApi(api, normal, bulk, pause, api.direct)
    /** The engine's requests are the app at work, not a person, so they go out marked as [BackgroundRequest]. */
    suspend fun request(path: String, method: String = "GET", body: JsonObject? = null): JsonObject =
        withContext(BackgroundRequest) { send(path, method, body) }
    private suspend fun send(path: String, method: String, body: JsonObject?): JsonObject {
        var retry = 0
        while (true) {
            (if (bulkTransferPath(path)) bulk else normal).take()
            try { return transport?.call(path, method, body) ?: api.request(path, method, body) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (retry >= RETRIES || !shouldRetry(e, method, body)) throw e
                pause(backoffDelay(retry++))
            }
        }
    }
    suspend inline fun <reified T> get(path: String): T = harborJson.decodeFromJsonElement(request(path))
    suspend fun list(parent: String?, cursor: String? = null): DrivePage = get(HarborApi.pagePath("/v1/drive/folders/${HarborApi.segment(parent ?: "root")}/children", cursor))
    /** Follows every page of a list endpoint, stopping if the server repeats a cursor. */
    suspend inline fun <reified T> all(path: String): List<T> {
        val result = mutableListOf<T>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = get<Page<T>>(HarborApi.pagePath(path, cursor))
            result += page.items
            cursor = page.nextCursor
            check(cursor == null || seen.add(cursor)) { "Could not load the remaining files. Please retry." }
        } while (cursor != null)
        return result
    }
    /** `GET /v1/users/me`'s storage, or null when it can't be read now (offline, an older server). */
    suspend fun storage(): Storage? = try { get<Account>("/v1/users/me").storage } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
}
