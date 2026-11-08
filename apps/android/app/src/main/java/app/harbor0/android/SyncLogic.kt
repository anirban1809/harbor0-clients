package app.harbor0.android

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDateTime

// Pure sync rules shared with the desktop engine (apps/desktop/src/paths.ts, sync-state.ts, backups.ts).
// Nothing here touches Android APIs, so it is covered by JVM unit tests.

/** A remote name as a safe local file name; unsafe-but-valid names get the item's id appended. */
fun safeSegment(name: String, id: String): String {
    require(name.isNotEmpty() && name != "." && name != ".." && name.none { it == '\\' || it == '/' || it == '\u0000' }) { "Unsafe remote filename." }
    val reserved = Regex("^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\.|$)", RegexOption.IGNORE_CASE)
    if (Regex("[<>:\"|?*\\u0000-\\u001f]").containsMatchIn(name) || Regex("[. ]$").containsMatchIn(name) || reserved.containsMatchIn(name)) {
        val cleaned = name.replace(Regex("[^a-zA-Z0-9._ -]"), "_").replace(Regex("[. ]+$"), "").ifEmpty { "file" }
        return "$cleaned~${id.take(8)}"
    }
    return name
}

// OS and file-manager bookkeeping that should never be synced or backed up.
private val metadataNames = setOf(".ds_store", ".appledouble", ".lsoverride", ".spotlight-v100", ".trashes", ".fseventsd", ".temporaryitems",
    ".documentrevisions-v100", ".apdisk", "icon\r", "thumbs.db", "ehthumbs.db", "desktop.ini", "\$recycle.bin", ".directory",
    // Android: generated gallery thumbnails. MediaStore trash and in-progress files are matched by prefix below.
    ".thumbnails")
fun metadataSegment(name: String) = name.startsWith("._") || name.lowercase() in metadataNames || name.startsWith(".trashed-") || name.startsWith(".pending-")

private val recoveredPattern = Regex(" \\(Recovered by harbor0 \\d{4}-\\d{2}-\\d{2} \\d{2}\\.\\d{2}\\.\\d{2}\\)$")
/** Local copies kept after a remote folder deletion: visible to the user, but never synced again. */
fun recoveredName(name: String, at: LocalDateTime): String {
    fun two(value: Int) = value.toString().padStart(2, '0')
    return "$name (Recovered by harbor0 ${at.year}-${two(at.monthValue)}-${two(at.dayOfMonth)} ${two(at.hour)}.${two(at.minute)}.${two(at.second)})"
}
fun internalPath(relative: String) = relative.split('/').any { p ->
    p.startsWith(".harbor-") || p.endsWith(".harbor-part") || recoveredPattern.containsMatchIn(p) || metadataSegment(p)
}
/** Node's path.extname: the last ".ext", or nothing for names like ".profile". */
fun extension(name: String): String { val dot = name.lastIndexOf('.'); return if (dot <= 0) "" else name.substring(dot) }
fun conflictName(name: String, device: String, operationId: String): String {
    val ext = extension(name)
    val label = device.replace(Regex("\\.local$", RegexOption.IGNORE_CASE), "").replace(Regex("[^\\p{L}\\p{N} '-]"), "_").trim().take(40).ifEmpty { "another device" }
    return "${name.dropLast(ext.length)} (Conflict - $label - ${operationId.take(8)})$ext"
}
fun excludedPath(excluded: List<String>, relative: String) = excluded.any { relative == it || relative.startsWith("$it/") }
fun ignoredPath(excluded: List<String>, relative: String) = internalPath(relative) || excludedPath(excluded, relative)
fun parentPath(relative: String) = relative.substringBeforeLast('/', "")
fun baseName(relative: String) = relative.substringAfterLast('/')
fun joinPath(parent: String, name: String) = if (parent.isEmpty() || parent == ".") name else "$parent/$name"
/** A relative path from the engine or user input: no empty, "." or ".." segments and no absolute paths. */
fun validRelative(relative: String) = relative.isNotEmpty() && !relative.startsWith("/") && relative.split('/').none { it.isEmpty() || it == "." || it == ".." }
fun normalizedName(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC).lowercase()

// Failed files and folders retry with growing delays (4s up to 5 minutes).
fun retryDelay(attempts: Int): Long = minOf(300_000L, 2000L * (1L shl minOf(attempts, 10)))
const val REMOTE_POLL_MIN = 2_000L
/** How long a backup run works before it yields to local edits in synced folders. */
const val RUN_MIN_MS = 30_000L
const val REMOTE_POLL_MAX = 30_000L
/** How long a changed file waits, unchanged, before an automatic backup saves it, by default. */
const val BACKUP_QUIET_MS = 10 * 60 * 1000L
val BACKUP_WAIT_CHOICES = listOf(5, 10, 30, 60)
/** This phone's choices for automatic backups, set in Settings (desktop `BackupSettings`). */
data class BackupSettings(val waitMinutes: Int = (BACKUP_QUIET_MS / 60_000).toInt(),
    // A file written to while it is being saved is left for the next backup, not failed.
    val skipChanging: Boolean = true)
/** The settings in use; set from what was saved before any backup runs (SyncController), and again when changed. */
@Volatile var backupSettings = BackupSettings()
fun backupReady(mtimeMs: Long, observedAt: Long = 0, now: Long = System.currentTimeMillis()) = now - maxOf(mtimeMs, observedAt) >= backupSettings.waitMinutes * 60_000L
/** A backup skips this file for now rather than failing it: it was written to while being saved, and Settings say to skip such files. */
fun skippedAsChanged(error: Throwable) = error is FileChangedException && backupSettings.skipChanging
/** The wait as text, e.g. "10 minutes" or "1 hour". */
fun backupWaitLabel(minutes: Int = backupSettings.waitMinutes) = if (minutes % 60 == 0) plural(minutes / 60, "hour") else plural(minutes, "minute")
/** Photos are saved at once: files still being written aren't in the library yet, so there is nothing to wait for. */
fun backupReady(root: SyncRoot, mtimeMs: Long, observedAt: Long = 0) = root.isPhotos || backupReady(mtimeMs, observedAt)

/** A stable UUID-shaped operation id for a numbered fallback folder, as desktop derives it. */
fun numberedOperationId(operationId: String, number: Int): String {
    val hash = MessageDigest.getInstance("SHA-256").digest("sync-folder:$operationId:$number".toByteArray()).joinToString("") { "%02x".format(it) }
    return "${hash.take(8)}-${hash.substring(8, 12)}-4${hash.substring(13, 16)}-a${hash.substring(17, 20)}-${hash.substring(20, 32)}"
}

// Local snapshots ----------------------------------------------------------------------------
/** What a scan knows about one local entry. Folder sizes and times are not compared. */
@Serializable data class LocalStat(val folder: Boolean, val size: Long, val mtime: Long)
data class LocalChange(val relativePath: String, val kind: String, val stat: LocalStat?, val changed: Boolean)
/**
 * Android has no file watcher for document trees, so each scan is compared with the last one:
 * new entries are upserts, edited files are upserts marked as changes, and missing entries are deletes.
 * Parents come before children for upserts; children before parents for deletes.
 */
fun diffSnapshot(previous: Map<String, LocalStat>, current: Map<String, LocalStat>): List<LocalChange> {
    val upserts = current.entries.mapNotNull { (path, stat) ->
        val before = previous[path]
        when {
            before == null || before.folder != stat.folder -> LocalChange(path, "upsert", stat, false)
            !stat.folder && (before.size != stat.size || before.mtime != stat.mtime) -> LocalChange(path, "upsert", stat, true)
            else -> null
        }
    }.sortedWith(compareBy({ it.relativePath.count { c -> c == '/' } }, { it.relativePath }))
    val deletes = previous.keys.filter { it !in current }.map { LocalChange(it, "delete", null, false) }
        .sortedWith(compareByDescending<LocalChange> { it.relativePath.count { c -> c == '/' } }.thenBy { it.relativePath })
    return upserts + deletes
}

// Engine state ---------------------------------------------------------------------------------
object IssueCode {
    const val MAPPING_REQUIRED = "MAPPING_REQUIRED"; const val FOLDER_MISSING = "FOLDER_MISSING"; const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val STORAGE_QUOTA_EXCEEDED = "STORAGE_QUOTA_EXCEEDED"; const val DISK_FULL = "DISK_FULL"; const val AUTH_INVALID = "AUTH_INVALID"
    const val CONFLICT = "CONFLICT"; const val FOLDER_RECOVERED = "FOLDER_RECOVERED"; const val SYNC_ERROR = "SYNC_ERROR"
    /** A cloud file whose stored bytes were damaged while it was uploaded; it is not downloaded until uploaded again. */
    const val UPLOAD_MISMATCH = "UPLOAD_MISMATCH"
}
@Serializable data class SyncIssue(val id: String, val rootId: String, val code: String, val message: String, val relativePath: String? = null,
    val conflictPath: String? = null, val jobId: String? = null, val scope: String? = null, val at: String = "",
    /** A refused upload: its size and the free space, or that the shared folder's owner is full. */
    val storage: StorageNeed? = null)
/** Kept until the user dismisses them (or, for a damaged upload, until the file is uploaded again); a later successful sync does not clear these. */
fun stickyIssue(issue: SyncIssue) = issue.code == IssueCode.CONFLICT || issue.code == IssueCode.FOLDER_RECOVERED || issue.code == IssueCode.UPLOAD_MISMATCH
data class SyncProgress(val rootId: String, val direction: String, val relativePath: String, val loaded: Long, val total: Long)
@Serializable data class SyncActivityItem(val id: String, val rootId: String, val direction: String, val relativePath: String, val at: String,
    val itemId: String? = null, val name: String? = null)
@Serializable data class WaitingItem(val rootId: String, val relativePath: String)
data class SyncRuntime(val running: Boolean = false, val paused: Boolean = false, val online: Boolean = true, val message: String = "Ready",
    val queued: Int = 0, val lastSync: String? = null, val active: SyncProgress? = null, val issues: List<SyncIssue> = emptyList(),
    val recent: List<SyncActivityItem> = emptyList(), val waiting: List<WaitingItem> = emptyList(), val confirmationPendingRoots: List<String> = emptyList(),
    /** The account's cloud storage refused uploads: they wait, everything else carries on, until space is freed. */
    val storageFull: StorageFull? = null,
    /** The account's storage as last read: on start and whenever the change feed shows something that moves it. */
    val storage: Storage? = null,
    /** By backup root id: backed-up files being put back on this phone, and how the last restores went. */
    val restoring: Map<String, RestoreProgress> = emptyMap(), val restored: Map<String, RestoreSummary> = emptyMap(),
    /** By backup root id: files handled so far in the backup run under way, of all those in it. */
    val backupRuns: Map<String, BackupRunProgress> = emptyMap(),
    /** The account is end-to-end encrypted and this phone is locked: nothing syncs or backs up until it is unlocked. */
    val locked: Boolean = false,
    /** Photo backups whose uploads wait for Wi-Fi (the network is metered and mobile data is off for them). */
    val waitingForWifi: Set<String> = emptySet())
/** A backup run under way on this phone: [done] of [total] files handled. */
data class BackupRunProgress(val done: Int, val total: Int)
data class SyncJobView(val id: String, val rootId: String, val relativePath: String, val kind: String, val error: String?, val attempts: Int)

const val WAITING = "Waiting for another device"
/** Sync and backups wait while an encrypted account is locked on this phone, as desktop's folder status. */
const val LOCKED_LABEL = "Locked · unlock to back up"
/** One folder's status label, as desktop `folderState`. */
fun folderState(root: SyncRoot, state: SyncRuntime, jobs: List<SyncJobView>): String {
    val full = storageFullNow(state.storageFull, state.storage)
    val issues = state.issues.filter { it.rootId == root.id && it.code != IssueCode.FOLDER_RECOVERED }
    if (issues.any { it.code == IssueCode.FOLDER_MISSING }) return "Folder unavailable"
    if (issues.any { it.code == IssueCode.CONFLICT }) return "Conflict"
    if (state.locked) return LOCKED_LABEL
    if (issues.isNotEmpty()) return "Action required"
    if (state.paused || root.paused) return "Paused"
    if (!state.online) return "Offline"
    if (jobs.any { it.rootId == root.id && it.error != null }) return "Action required"
    // Nothing new can be uploaded; see SyncRuntime.storageFull.
    if (full && root.shareId == null && state.active == null) return STORAGE_FULL
    // Some waiting files are bigger than what is left; smaller ones keep uploading.
    if (state.storageFull != null && root.shareId == null && state.active == null && jobs.any { it.rootId == root.id }) return NEEDS_STORAGE
    if (state.active?.rootId == root.id || jobs.any { it.rootId == root.id }) return "Syncing"
    if (state.waiting.any { it.rootId == root.id }) return WAITING
    return if (root.needsReconcile || root.id in state.confirmationPendingRoots) CHECKING else "Up to date"
}
/** The status of every folder together, as desktop `globalSyncState`. */
fun globalSyncState(roots: List<SyncRoot>, state: SyncRuntime, jobs: List<SyncJobView>): String {
    val ids = roots.map { it.id }.toSet()
    if (state.locked) return LOCKED_LABEL
    if (state.issues.any { (it.rootId in ids || it.rootId.isEmpty()) && it.code != IssueCode.FOLDER_RECOVERED }) return "Action required"
    if (state.paused) return "Paused"
    if (!state.online) return "Offline"
    if (jobs.any { it.rootId in ids && it.error != null }) return "Action required"
    if ((state.active != null && state.active.rootId in ids) || roots.any { !it.paused && jobs.any { job -> job.rootId == it.id } }) return "Syncing"
    if (state.waiting.any { it.rootId in ids }) return WAITING
    return if (state.confirmationPendingRoots.any { it in ids } || roots.any { !it.paused && it.needsReconcile }) CHECKING else "Up to date"
}
/** Problems derived from current engine state, so resolved ones disappear at once. */
fun syncRequirements(roots: List<SyncRoot>, state: SyncRuntime, jobs: List<SyncJobView>): List<SyncIssue> {
    val ids = roots.filter { it.mode == "sync" }.map { it.id }.toSet()
    val issues = state.issues.filter { it.rootId in ids || it.rootId.isEmpty() }.toMutableList()
    for (job in jobs) {
        if (job.rootId !in ids || !state.online || job.error == null) continue
        if (issues.any { it.rootId == job.rootId && !stickyIssue(it) }) continue
        issues += SyncIssue(job.id, job.rootId, IssueCode.SYNC_ERROR, job.error, job.relativePath, jobId = job.id)
    }
    return issues
}
/** Local folder errors carry POSIX-like codes so issues are classified as on desktop. */
open class LocalFsException(val code: String, message: String, val relativePath: String? = null, cause: Throwable? = null): Exception(message, cause)
/** A file found to have changed while it was being uploaded (desktop `FileChangedError`): a backup may skip it for now. */
class FileChangedException(message: String, relativePath: String? = null): LocalFsException("EIO", message, relativePath)
fun syncIssueCode(error: Throwable, item: Boolean = false): String {
    val code = (error as? LocalFsException)?.code ?: (error as? ApiException)?.code
    return when (code) {
        "ENOENT", "ENOTDIR" -> if (item) IssueCode.SYNC_ERROR else IssueCode.FOLDER_MISSING
        "EACCES", "EPERM" -> IssueCode.PERMISSION_DENIED
        "ENOSPC" -> IssueCode.DISK_FULL
        IssueCode.STORAGE_QUOTA_EXCEEDED, "OWNER_STORAGE_FULL" -> IssueCode.STORAGE_QUOTA_EXCEEDED
        "AUTH_INVALID", "DEVICE_REVOKED" -> IssueCode.AUTH_INVALID
        else -> IssueCode.SYNC_ERROR
    }
}
/** Copy for each issue code, as the desktop sync page words them. */
fun issueText(issue: SyncIssue): String = when (issue.code) {
    IssueCode.MAPPING_REQUIRED -> "Finish setting up this local folder to start syncing."
    IssueCode.FOLDER_MISSING -> "The local folder can no longer be found."
    IssueCode.PERMISSION_DENIED -> if (issue.message == PHOTOS_ACCESS) PHOTOS_ACCESS else "harbor0 no longer has access to this folder. Choose it again to continue."
    IssueCode.STORAGE_QUOTA_EXCEEDED -> storageText(issue)
    IssueCode.DISK_FULL -> "This phone does not have enough free space to download new changes."
    IssueCode.AUTH_INVALID -> "Your session ended. Sign in again to continue syncing."
    IssueCode.CONFLICT -> "This file was changed in more than one place. The local version has been preserved separately for review."
    IssueCode.FOLDER_RECOVERED -> "This folder was removed from sync elsewhere. The copy on this phone was kept under a new name and no longer syncs."
    else -> issue.message.ifBlank { "A change could not be synchronized." }
}
/** Shown for a cloud file that was damaged while it was uploaded (desktop transfers.ts). */
const val UPLOAD_MISMATCH_LOCAL = "This file was damaged while it was uploaded. Upload it again from the original. The local file was kept."
/** A download refused because the cloud copy was damaged while it was uploaded: never retried, nothing local is touched. */
class UploadMismatchException(message: String = UPLOAD_MISMATCH_LOCAL): IllegalStateException(message)

// Journal records ---------------------------------------------------------------------------------
/** A local folder on this phone, kept in step with a cloud folder (sync) or saved to the cloud (backup). */
@Serializable data class SyncRoot(
    val id: String, val treeUri: String, val localName: String, val remoteId: String?, val mode: String,
    val backupId: String? = null,
    // Backup folders only: set while the local copy is being removed, is gone, or is coming back.
    val archive: String? = null, val archiveError: String? = null,
    val paused: Boolean = false, val excluded: List<String> = emptyList(), val cloudPath: String? = null, val needsReconcile: Boolean = false,
    val shareId: String? = null, val sharedBy: String? = null, val sharedSequence: Long? = null, val lastSyncedAt: String? = null,
    /** Backup folders only: the waiting files need more cloud storage than is free. */
    val backupStorage: BackupStorage? = null,
    /** A photo backup ([PHOTOS_URI]): the phone's photo library rather than a folder, with its options. */
    val photos: PhotoOptions? = null,
)
@Serializable data class LocalFile(val rootId: String, val relativePath: String, val itemId: String, val revision: Int, val hash: String?, val type: String)
@Serializable data class JobEntry(val type: String, val sizeBytes: Long, val updatedAt: Long)
@Serializable data class UploadState(var operationId: String, var uploadId: String? = null, var partSize: Long? = null, var hash: String? = null,
    var size: Long? = null, var mtime: Long? = null, var parts: List<CompletedPart>? = null,
    /** An encrypted upload's H0E1 header (base64url): resuming seals the same bytes with it. */
    var header: String? = null,
    /** SHA-256 of the encrypted bytes as stored, once every part is sealed. */
    var storedHash: String? = null)
@Serializable data class JobPayload(var entry: JobEntry? = null, var retryAt: Long? = null, var observedAt: Long? = null, var relayVersion: String? = null,
    var upload: UploadState? = null, var backupRunId: String? = null, var backupManual: Boolean? = null, var backupEntry: BackupEntry? = null,
    var retryAfter: Long? = null,
    /** A backup file a batch could not settle: it goes on its own from now on. */
    var alone: Boolean? = null)
data class LocalJob(val id: String, val rootId: String, val relativePath: String, val kind: String, val payload: JobPayload, var attempts: Int, var error: String?)
/**
 * A backup run under way. Runs from older versions kept their job ids in [jobs], removing each as it was saved; now
 * they are stored once under `backup-run-jobs:` and [next] is how many have been handled.
 */
@Serializable data class PendingRun(val id: String, val trigger: String, val jobs: List<String>? = null, val error: String? = null,
    /** Files in the run when it started, and their combined size (sent with the run so its progress has an end). */
    val total: Int? = null, val bytes: Long? = null, val next: Int? = null,
    /** Cloud storage still free for this run, once it is known to be short; files bigger than this wait. Unset while everything fits. */
    val room: Long? = null, val quota: Long? = null,
    /** Files left for a later run because they did not fit. */
    val tooBig: Int? = null)
