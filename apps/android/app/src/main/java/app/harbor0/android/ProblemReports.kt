package app.harbor0.android

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.IOException
import java.time.Instant
import java.util.WeakHashMap

private const val REPORT_BATCH = 20
private const val REPORT_DELAY_MS = 5_000L
// The same problem again within this window is dropped, so a stuck retry loop can't flood.
private const val REPORT_REPEAT_MS = 60_000L

/** The code, HTTP status and message of an error for a problem report. Local file errors carry only their code. */
fun describeProblem(error: Throwable?): JsonObject = buildJsonObject {
    when (error) {
        is ApiException -> { error.code?.let { put("code", it.take(64)) }; put("httpStatus", error.status); put("message", error.message.take(300)) }
        // Their messages name the file.
        is LocalReadException -> put("code", error.error.code)
        is LocalFsException -> put("code", error.code)
        // The connection failed before any status; messages can name hosts, so only the kind is sent.
        is IOException -> { put("code", (error.javaClass.simpleName.ifEmpty { "IOException" }).take(64)); put("httpStatus", 0) }
        null -> {}
        else -> put("code", (error.javaClass.simpleName.ifEmpty { "Error" }).take(64))
    }
}

/**
 * Problems the API can't see (a failed storage PUT, an upload that gave up), reported to `POST /v1/client-events`
 * for the console, as `ApiClient.reportProblem` in packages/api-client. Reports go out in batches a few seconds
 * later; if sending fails they are dropped. File names and URLs are never sent.
 */
class ProblemReports(private val send: suspend (JsonObject) -> Unit, private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis, private val delayMs: Long = REPORT_DELAY_MS) {
    private val problems = mutableListOf<JsonObject>()
    private val reported = HashMap<String, Long>()
    private var timer: Job? = null

    /** [kind] UPLOAD_PART_RETRY or UPLOAD_FAILED; [stage] hash, create, parts, storage, complete or other. */
    fun report(kind: String, stage: String, error: Throwable?, uploadId: String? = null, sizeBytes: Long? = null, partNumber: Int? = null, attempt: Int? = null,
        code: String? = null, httpStatus: Int? = null) {
        val described = buildJsonObject {
            describeProblem(error).forEach { (k, v) -> put(k, v) }
            code?.let { put("code", it.take(64)) }; httpStatus?.let { put("httpStatus", it) }
        }
        val time = now()
        synchronized(this) {
            val key = listOf(kind, stage, described["code"]?.jsonPrimitive?.contentOrNull, uploadId, partNumber).joinToString("|")
            if (time - (reported[key] ?: Long.MIN_VALUE / 2) < REPORT_REPEAT_MS) return
            if (reported.size > 500) reported.clear()
            reported[key] = time
            if (problems.size >= 100) return
            problems += buildJsonObject {
                put("kind", kind); put("at", Instant.ofEpochMilli(time).toString()); put("stage", stage)
                uploadId?.let { put("uploadId", it.take(128)) }; sizeBytes?.let { put("sizeBytes", maxOf(0, it)) }
                partNumber?.let { put("partNumber", it) }; attempt?.let { put("attempt", it) }
                described.forEach { (k, v) -> put(k, v) }
            }
            if (problems.size >= REPORT_BATCH) scope.launch { flush() }
            else if (timer?.isActive != true) timer = scope.launch { delay(delayMs); synchronized(this@ProblemReports) { timer = null }; flush() }
        }
    }
    suspend fun flush() {
        synchronized(this) { timer?.cancel(); timer = null }
        while (true) {
            val events = synchronized(this) { problems.take(REPORT_BATCH).also { repeat(it.size) { problems.removeAt(0) } } }
            if (events.isEmpty()) return
            try { send(buildJsonObject { put("events", JsonArray(events)) }) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
    }
    val pending get() = synchronized(this) { problems.size }

    companion object {
        private val byApi = WeakHashMap<HarborApi, ProblemReports>()
        /** One reporter per signed-in API client, shared by manual transfers and the sync engine. */
        fun of(api: HarborApi): ProblemReports = synchronized(byApi) {
            byApi.getOrPut(api) { ProblemReports({ api.request("/v1/client-events", "POST", it) }) }
        }
    }
}
