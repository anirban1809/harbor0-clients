package app.harbor0.android

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class ReceiptItem(val id: String, val type: String, val revision: Int, val currentVersionId: String? = null)
@Serializable data class Receipt(val rootId: String, val remoteId: String, val relative: String, val item: ReceiptItem, val hash: String?,
    /** An encrypted version, confirmed by the SHA-256 of its stored bytes ([storedHash], or read from its versions). */
    val encrypted: Boolean = false, val storedHash: String? = null)
private data class Candidate(val rootId: String, val itemId: String, val isRoot: Boolean)

private const val AUDIT_RETRY = 15_000L
// A pass that finds every copy confirmed waits an hour, or until nudge() reports new activity.
private const val AUDIT_IDLE = 3_600_000L
private const val AUDIT_BATCH = 25

/**
 * Durable acknowledgements (desktop `sync-receipts.ts`): every verified local copy is confirmed to the server so
 * sync status can show which linked devices are up to date. Synced files stay in the cloud. The outbox survives restarts, and audits recover
 * copies that were never confirmed.
 */
class SyncReceipts(private val api: EngineApi, private val journal: SyncJournal, private val tree: (SyncRoot) -> LocalTree) {
    private var candidates = mutableListOf<Candidate>()
    private var nextAuditAt = 0L
    private var passFound = false
    // Off once the server turns out to have no batch route; copies are then confirmed one at a time.
    private var batches = true
    private class Ready(val id: String, val root: SyncRoot, val receipt: Receipt)
    private fun pending(): MutableMap<String, Receipt> = journal.get<Map<String, Receipt>>("syncReceipts")?.toMutableMap() ?: mutableMapOf()
    fun pendingRoots() = pending().values.map { it.rootId }.distinct()
    fun queue(root: SyncRoot, relative: String, item: DriveItem, hash: String?) {
        val revision = item.revision ?: return
        if (root.mode != "sync" || root.remoteId == null || item.id.isEmpty() || revision <= 0 || (item.type == "FILE" && (item.currentVersionId == null || hash == null))) return
        val pending = pending()
        pending[item.id] = Receipt(root.id, root.remoteId, relative, ReceiptItem(item.id, item.type, revision, item.currentVersionId), hash,
            item.encrypted, item.contentHash.takeIf { item.encrypted })
        journal.set<Map<String, Receipt>>("syncReceipts", pending)
    }
    private fun remove(id: String, receipt: Receipt, retry: Boolean = false) {
        // Transfers may queue a newer revision while the network request is outstanding.
        val current = pending()
        if (current[id] != receipt) return
        current.remove(id)
        if (retry) current[id] = receipt // A failing item must not starve other confirmations.
        journal.set<Map<String, Receipt>>("syncReceipts", current)
    }
    private fun reconcile(root: SyncRoot) { journal.root(root.id)?.let { journal.root(it.copy(needsReconcile = true)) } }
    /** Sync activity may have left copies unconfirmed; audit again soon. */
    fun nudge() { nextAuditAt = minOf(nextAuditAt, System.currentTimeMillis() + AUDIT_RETRY) }
    private fun passEnded() { nextAuditAt = System.currentTimeMillis() + if (passFound) AUDIT_RETRY else AUDIT_IDLE }

    suspend fun audit() {
        if (candidates.isEmpty()) {
            if (System.currentTimeMillis() < nextAuditAt) return
            passFound = false
            candidates = journal.roots().filter { it.mode == "sync" && it.remoteId != null && !it.paused }.flatMap { root ->
                listOf(Candidate(root.id, root.remoteId!!, true)) + journal.files(root.id).map { Candidate(root.id, it.itemId, false) }
            }.toMutableList()
        }
        val batch = candidates.take(AUDIT_BATCH)
        if (batch.isEmpty()) { passEnded(); return }
        val statuses = try {
            api.request("/v1/sync/status?recursive=false&ids=" + batch.joinToString(",") { HarborApi.segment(it.itemId) })["items"]?.jsonArray?.map { it.jsonObject } ?: return
        } catch (e: CancellationException) { throw e } catch (e: ApiException) {
            if (e.code in listOf("ITEM_NOT_FOUND", "PARENT_NOT_FOUND", "ITEM_DELETING", "SYNC_REMOVED")) {
                passFound = true
                batch.mapNotNull { journal.root(it.rootId) }.distinctBy { it.id }.forEach(::reconcile)
                candidates = candidates.drop(batch.size).toMutableList()
            }
            return
        } catch (e: Exception) { return }
        for (candidate in batch) {
            val root = journal.root(candidate.rootId) ?: continue
            if (root.paused || root.mode != "sync") continue
            val known = if (candidate.isRoot) null else journal.fileByItem(root.id, candidate.itemId)
            if (!candidate.isRoot && known == null) continue
            val relative = if (candidate.isRoot) "." else known!!.relativePath
            if (excludedPath(root.excluded, relative)) continue
            val status = statuses.firstOrNull { it["itemId"]?.jsonPrimitive?.contentOrNull == candidate.itemId } ?: continue
            val confirmed = status["deviceConfirmed"]?.jsonPrimitive?.booleanOrNull == true
            if (confirmed && (candidate.isRoot || status["revision"]?.jsonPrimitive?.intOrNull == known?.revision)) continue
            passFound = true
            try {
                val item = api.get<ItemResponse>("/v1/drive/items/${HarborApi.segment(candidate.itemId)}").item
                if (item.deletedAt != null) { reconcile(root); continue }
                if (known != null && (item.revision != known.revision || (item.type == "FILE" && known.hash == null))) { reconcile(root); continue }
                queue(root, relative, item, known?.hash)
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                // Revisit this copy on the next audit; other files can still be confirmed.
            }
        }
        candidates = candidates.drop(batch.size).toMutableList()
        if (candidates.isEmpty()) passEnded()
    }

    suspend fun flush() {
        val deadline = System.currentTimeMillis() + 5000
        val ready = mutableListOf<Ready>()
        for ((id, receipt) in pending()) {
            if (System.currentTimeMillis() >= deadline) break
            val root = journal.root(receipt.rootId)
            if (root == null || root.mode != "sync" || root.remoteId != receipt.remoteId || excludedPath(root.excluded, receipt.relative)) { remove(id, receipt); continue }
            if (root.paused) continue
            try {
                val local = tree(root)
                val path = if (receipt.relative == ".") "" else receipt.relative
                val stat = local.stat(path) ?: throw LocalFsException("ENOENT", "Missing", path)
                if (receipt.item.type == "FILE") {
                    if (stat.folder || local.hash(path) != receipt.hash) { remove(id, receipt); continue }
                    val verified = local.stat(path)
                    if (verified == null || verified.size != stat.size || verified.mtime != stat.mtime) { remove(id, receipt); continue }
                } else if (!stat.folder) { remove(id, receipt); continue }
                ready += Ready(id, root, receipt)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { failed(id, root, receipt, e) }
            if (ready.size >= SYNC_ACKNOWLEDGE_ITEMS) { confirm(ready); ready.clear() }
        }
        if (ready.isNotEmpty()) confirm(ready)
    }
    private suspend fun body(receipt: Receipt): JsonObject {
        // An encrypted version is confirmed by its stored hash, read from its versions when the item didn't carry it.
        val hash = if (!receipt.encrypted) receipt.hash else receipt.storedHash
            ?: api.request("/v1/drive/items/${HarborApi.segment(receipt.item.id)}/versions")["items"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == receipt.item.currentVersionId }?.get("contentHash")?.jsonPrimitive?.contentOrNull
        return buildJsonObject { put("versionId", receipt.item.currentVersionId); put("revision", receipt.item.revision); put("contentHash", hash) }
    }
    /** Confirms checked copies: up to [SYNC_ACKNOWLEDGE_ITEMS] in one request, or one by one on an older server. */
    private suspend fun confirm(ready: List<Ready>) {
        if (batches && ready.size > 1) {
            try {
                val results = api.request("/v1/sync/items/acknowledge", "POST", buildJsonObject {
                    put("items", JsonArray(ready.map { r -> buildJsonObject { put("itemId", r.id); body(r.receipt).forEach { (k, v) -> put(k, v) } } }))
                })["results"]?.jsonArray
                ready.forEachIndexed { n, r ->
                    val result = results?.getOrNull(n) as? JsonObject
                    val error = result?.get("error") as? JsonObject
                    if (error != null) failed(r.id, r.root, r.receipt, ApiException(409, error["message"]?.jsonPrimitive?.contentOrNull ?: "Not confirmed.",
                        error["code"]?.jsonPrimitive?.contentOrNull))
                    else confirmed(r.id, r.receipt, result?.get("ok")?.jsonPrimitive?.booleanOrNull == true)
                }
                return
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                // An older server confirms one copy at a time.
                if (e is ApiException && e.status == 404 && (e.code == null || e.code == "NOT_FOUND")) batches = false
                else { ready.forEach { remove(it.id, it.receipt, retry = true) }; return }
            }
        }
        for (r in ready) {
            try { confirmed(r.id, r.receipt, api.request("/v1/sync/items/${HarborApi.segment(r.id)}/acknowledge", "POST", body(r.receipt))["ok"]?.jsonPrimitive?.booleanOrNull == true) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { failed(r.id, r.root, r.receipt, e) }
        }
    }
    private fun confirmed(id: String, receipt: Receipt, ok: Boolean) {
        if (!ok) return remove(id, receipt, retry = true)
        remove(id, receipt)
        journal.set("syncConfirmedAt", java.time.Instant.now().toString())
    }
    /** A copy that could not be confirmed: looked at again, or retried after the others. */
    private fun failed(id: String, root: SyncRoot, receipt: Receipt, e: Exception) {
        if ((e is LocalFsException && e.code == "ENOENT") || (e is ApiException && e.code in listOf("REVISION_CONFLICT", "ITEM_NOT_FOUND", "PARENT_NOT_FOUND"))) {
            reconcile(root); remove(id, receipt)
        } else remove(id, receipt, retry = true)
    }
}
