package app.harbor0.android

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

// How full the account's cloud storage is (apps/web/lib/storage-state.ts), and what the sync engine does about it
// (desktop sync-state.ts, storage-text.ts). Pure, so the Drive screens can use the same rule.

/**
 * Less free than this and storage is full: hardly any file fits (1% of the limit, at most 1 MB). The apps, the
 * drive's banner and the sync engine all use this one rule.
 */
fun fullBelow(quotaBytes: Long?): Long = minOf(1_000_000L, if (quotaBytes != null && quotaBytes > 0) quotaBytes / 100 else 1_000_000L)

/** "over" its (lowered) limit, "full", "low" (95% taken) or "ok". */
fun storageState(storage: Storage): String {
    val taken = storage.usedBytes + storage.reservedBytes
    if (storage.usedBytes > storage.quotaBytes) return "over"
    if (storage.quotaBytes - taken < fullBelow(storage.quotaBytes)) return "full"
    if (storage.quotaBytes > 0 && taken.toDouble() / storage.quotaBytes >= 0.95) return "low"
    return "ok"
}

/** What a refused upload needed and what was free; [owner]: a shared folder whose owner is out of storage. */
@Serializable data class StorageNeed(val requiredBytes: Long? = null, val availableBytes: Long? = null, val owner: Boolean = false)
/** Uploads refused for lack of storage: [availableBytes] was free when it was last refused or read. */
@Serializable data class StorageFull(val availableBytes: Long, val requiredBytes: Long? = null, val at: String = "")
/** A backup folder's waiting files need more storage than is free: their size, what is free, and how many don't fit. */
@Serializable data class BackupStorage(val requiredBytes: Long, val availableBytes: Long, val files: Int)

const val STORAGE_FULL = "Sync paused · storage full"
const val NEEDS_STORAGE = "Needs storage"
const val BACKUP_STORAGE_FULL = "Backup paused · storage full"
/** Every file has moved; what is left is confirming the copies or comparing with the cloud. */
const val CHECKING = "Checking sync"

/** Nothing new can be uploaded: storage was refused, and less than [fullBelow] is free. */
fun storageFullNow(full: StorageFull?, storage: Storage?) = full != null && full.availableBytes < fullBelow(storage?.quotaBytes)

/** STORAGE_QUOTA_EXCEEDED and OWNER_STORAGE_FULL refusals (409). */
fun storageRefusal(error: Throwable) = error is ApiException && error.code in listOf(IssueCode.STORAGE_QUOTA_EXCEEDED, "OWNER_STORAGE_FULL")
/** What a refused upload needed; a shared folder's owner's free space is not theirs to see. */
fun storageNeed(error: Throwable, shared: Boolean): StorageNeed {
    if (shared || (error as? ApiException)?.code == "OWNER_STORAGE_FULL") return StorageNeed(owner = true)
    val details: JsonObject? = (error as? ApiException)?.details
    fun bytes(name: String) = (details?.get(name) as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
    return StorageNeed(bytes("requiredBytes"), bytes("availableBytes"))
}

/** Whose storage refused the upload, and by how much when the server said (desktop storage-text.ts). */
fun storageText(issue: SyncIssue, root: SyncRoot? = null): String {
    if (issue.storage?.owner == true || root?.shareId != null)
        return "The owner of this shared folder is out of cloud storage, so new changes cannot be uploaded. Syncing resumes once they free up space."
    val required = issue.storage?.requiredBytes
    val available = issue.storage?.availableBytes
    if (required == null || available == null) return "There is not enough cloud storage left to upload this change. Syncing resumes once space is freed."
    return if (available > 0) "This file is too big for your remaining cloud storage (needs ${bytesLabel(required)}, ${bytesLabel(available)} free). Smaller files keep syncing."
    else "Your cloud storage is full (this file needs ${bytesLabel(required)}). Syncing resumes once space is freed."
}

/**
 * A backup folder on this phone waiting for storage, as the web backup panel words it: a label
 * ([BACKUP_STORAGE_FULL] or [NEEDS_STORAGE]) and a detail line. Null when it is not waiting for storage.
 */
fun backupStorageLabel(root: SyncRoot, runtime: SyncRuntime): Pair<String, String>? {
    val waiting = root.backupStorage
    val full = storageFullNow(runtime.storageFull, runtime.storage)
    if (waiting == null && !full) return null
    return if (full || (waiting != null && waiting.availableBytes < fullBelow(runtime.storage?.quotaBytes)))
        BACKUP_STORAGE_FULL to "New and changed files back up once space is freed."
    else NEEDS_STORAGE to "${plural(waiting!!.files, "file")} won’t fit in your remaining storage."
}

/** The My Drive storage banner (drive-workspace.tsx), or null when there is room. */
fun storageWarning(storage: Storage): String? {
    if (storage.quotaBytes <= 0) return null
    return when (storageState(storage)) {
        "over" -> "You’re using ${bytesLabel(storage.usedBytes)}, more than your ${bytesLabel(storage.quotaBytes)} of storage. New files can’t be uploaded or synced until you free up space or get more storage."
        "full" -> "Your storage is full (${bytesLabel(storage.quotaBytes)}). New files can’t be uploaded or synced until you free up space or get more storage."
        "low" -> "${bytesLabel(storage.quotaBytes - storage.usedBytes - storage.reservedBytes)} of your ${bytesLabel(storage.quotaBytes)} is left. You’re running low on storage."
        else -> null
    }
}
