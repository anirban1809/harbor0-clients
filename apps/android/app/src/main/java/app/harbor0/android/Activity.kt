package app.harbor0.android

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

// What the sync and backup engine is doing, reported as it changes (docs/specs/device-activity.md, desktop
// activity.ts, packages/contracts/src/activity.ts). Counts only: never file or folder names.

/** What one subject is doing now: the engine (no [folderId]), a sync folder (its cloud id) or a backup (its id). */
data class ActivitySubject(val scope: String, val folderId: String? = null, val status: String, val reason: String? = null,
    val files: Int? = null, val bytes: Long? = null, val failed: Int? = null, val runId: String? = null)
@Serializable data class ActivityEvent(val id: String, val at: String, val scope: String, val folderId: String? = null, val action: String,
    val status: String, val reason: String? = null, val files: Int? = null, val bytes: Long? = null, val failed: Int? = null, val runId: String? = null)
@Serializable data class ActivityReported(val status: String, val reason: String? = null)

object ActivityScope { const val ENGINE = "ENGINE"; const val SYNC = "SYNC"; const val BACKUP = "BACKUP" }
object ActivityStatus { const val PAUSED = "PAUSED"; const val BLOCKED = "BLOCKED"; const val ACTIVE = "ACTIVE"; const val IDLE = "IDLE" }

const val ACTIVITY_BATCH = 50
/** Unsent events kept at most; the oldest go first. */
const val ACTIVITY_QUEUE_MAX = 500
/** A sync folder counts as idle only after this long with nothing to do. */
const val SYNC_IDLE_MS = 30_000L
/** How soon after a change its events are sent. */
private const val SEND_DELAY_MS = 3_000L
private const val QUEUE = "activity-queue"
private const val REPORTED = "activity-reported"
// The contract's fields are optional, not nullable: absent values are left out.
private val activityJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

private fun keyOf(scope: String, folderId: String?) = "$scope#${folderId ?: "-"}"
/** What happened, going from the status last reported to the new one. */
fun activityAction(previous: ActivityReported?, next: ActivitySubject): String = when {
    next.status == ActivityStatus.PAUSED -> "PAUSED"
    previous?.status == ActivityStatus.PAUSED -> "RESUMED"
    next.status == ActivityStatus.BLOCKED -> "BLOCKED"
    previous?.status == ActivityStatus.BLOCKED -> "UNBLOCKED"
    next.status == ActivityStatus.ACTIVE -> "STARTED"
    else -> if (previous != null) "FINISHED" else "IDLE"
}

/**
 * Tells the server what the engine is doing. The engine describes every subject after each change; an event is
 * queued only when one's status or reason differs from what was last reported, and queued events go in batches to
 * `POST /v1/activity`, surviving restarts and time offline. Runs on the engine's single thread ([scope]).
 */
class ActivityReporter(private val send: suspend (JsonObject) -> Unit, private val store: KeyValues, private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis, private val sendDelay: Long = SEND_DELAY_MS) {
    private val reported: MutableMap<String, ActivityReported> = store.raw(REPORTED)?.let {
        runCatching { activityJson.decodeFromString<Map<String, ActivityReported>>(it) }.getOrNull() }.orEmpty().toMutableMap()
    private val queue: MutableList<ActivityEvent> = store.raw(QUEUE)?.let {
        runCatching { activityJson.decodeFromString<List<ActivityEvent>>(it) }.getOrNull() }.orEmpty().toMutableList()
    /** When each sync folder last had work, so a short pause between edits isn't "finished". */
    private val busyAt = mutableMapOf<String, Long>()
    /** Files a sync folder moved since it started. */
    private val moved = mutableMapOf<String, Int>()
    private var timer: Job? = null
    private var sending: Job? = null
    /** An older server without the route: stop until the app restarts. */
    private var unsupported = false
    /** The engine stopped: its journal may be closed, so nothing more is read or written. */
    private var stopped = false
    val queued: List<ActivityEvent> get() = queue.toList()

    /** A sync folder finished moving one file. */
    fun fileMoved(folderId: String) { moved[folderId] = (moved[folderId] ?: 0) + 1 }

    /** Compares every subject with what was last reported and queues an event for each change. */
    fun observe(subjects: List<ActivitySubject>) {
        if (unsupported || stopped) return
        val time = now()
        val seen = mutableSetOf<String>()
        var changed = false
        for (subject in subjects) {
            val key = keyOf(subject.scope, subject.folderId)
            seen += key
            val previous = reported[key]
            var next = subject
            if (subject.scope == ActivityScope.SYNC) {
                if (subject.status == ActivityStatus.ACTIVE) busyAt[key] = time
                // Idle only once it has had nothing to do for a while.
                else if (subject.status == ActivityStatus.IDLE && previous?.status == ActivityStatus.ACTIVE && time - (busyAt[key] ?: 0) < SYNC_IDLE_MS) continue
            }
            if (previous != null && previous.status == next.status && previous.reason == next.reason) continue
            val action = activityAction(previous, next)
            if (subject.scope == ActivityScope.SYNC && subject.folderId != null) {
                if (action == "STARTED") moved[subject.folderId] = 0
                if (action == "FINISHED") next = next.copy(files = moved[subject.folderId] ?: 0)
            }
            queue += ActivityEvent(UUID.randomUUID().toString(), Instant.ofEpochMilli(time).toString(), next.scope, next.folderId, action, next.status,
                next.reason, next.files, next.bytes, next.failed, next.runId)
            reported[key] = ActivityReported(next.status, next.reason)
            changed = true
        }
        // A folder no longer set up is forgotten, so setting it up again reports it afresh.
        val forgotten = reported.keys.filter { it !in seen }
        forgotten.forEach { reported.remove(it); busyAt.remove(it) }
        if (!changed && forgotten.isEmpty()) return
        if (queue.size > ACTIVITY_QUEUE_MAX) repeat(queue.size - ACTIVITY_QUEUE_MAX) { queue.removeAt(0) }
        save()
        if (changed) soon()
    }

    /** Sends what is queued; what fails waits for the next try. Never fails the caller. */
    suspend fun flush() {
        timer?.cancel(); timer = null
        val running = sending?.takeIf { it.isActive } ?: scope.launch { try { sendAll() } catch (e: CancellationException) { throw e } catch (_: Exception) {} }.also { sending = it }
        running.join()
    }
    /** The engine started again (after a settings change, say): report and send as before. */
    fun start() { stopped = false }
    fun stop() { stopped = true; timer?.cancel(); timer = null }

    private suspend fun sendAll() {
        while (queue.isNotEmpty() && !unsupported && !stopped) {
            val batch = queue.take(ACTIVITY_BATCH)
            val ids = batch.map { it.id }.toSet()
            try {
                send(buildJsonObject { put("events", activityJson.encodeToJsonElement(batch)) })
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (e is ApiException && e.status == 404) { unsupported = true; queue.clear(); save() }
                // Refused for its content: dropped rather than sent again forever.
                else if (e is ApiException && e.status == 400) { queue.removeAll { it.id in ids }; save(); continue }
                return
            }
            queue.removeAll { it.id in ids }
            save()
        }
    }
    private fun soon() {
        if (timer?.isActive == true) return
        timer = scope.launch { delay(sendDelay); timer = null; flush() }
    }
    private fun save() {
        if (stopped) return
        store.setRaw(QUEUE, activityJson.encodeToString(queue.toList()))
        store.setRaw(REPORTED, activityJson.encodeToString(reported.toMap()))
    }
}
