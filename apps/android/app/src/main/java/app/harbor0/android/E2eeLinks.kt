package app.harbor0.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import javax.crypto.SecretKey

/*
 * Public links of an end-to-end encrypted account (docs/specs/public-folder-links.md), byte for byte as
 * packages/api-client/src/e2ee.ts and apps/web/lib/e2ee-links.ts. A link's random 32-byte secret is only in its URL
 * fragment (`/s#<token>.<secret>`); keys derived from it seal each shared item's name and content key. The account
 * keeps the secret wrapped by its master key, so its apps can show the link again.
 */

/** The keys a link's secret gives: one seals names, the other files' content keys. */
class LinkKeys internal constructor(internal val names: SecretKey, internal val files: SecretKey)
/** A version's content key sealed for a link, and the first 20 bytes of its header (base64url). */
data class LinkFileWrap(val keyWrap: String, val head: String)

object E2eeLinks {
    /** Bytes of a header a link wrap is bound to and hands out: magic, chunk size, nonce prefix. */
    const val HEAD_SIZE = 20
    private val SECRET_AAD = "harbor0/e2ee/v1/link-secret".toByteArray()
    private val FILE_AAD = "harbor0/e2ee/v1/link-file".toByteArray()

    fun newSecret(rng: (Int) -> ByteArray = E2ee::random) = rng(32)
    fun keys(secret: ByteArray) = LinkKeys(E2ee.aesKey(E2ee.hkdf(secret, "harbor0/e2ee/v1/link-names")), E2ee.aesKey(E2ee.hkdf(secret, "harbor0/e2ee/v1/link-files")))
    private fun sealed(key: SecretKey, data: ByteArray, aad: ByteArray, rng: (Int) -> ByteArray): String {
        val iv = rng(12)
        return E2ee.b64url(iv + E2ee.seal(key, iv, data, aad))
    }
    private fun opened(key: SecretKey, wrapped: String, aad: ByteArray): ByteArray {
        val bytes = E2ee.fromB64url(wrapped)
        if (bytes.size < 12 + E2ee.TAG) throw E2eeException("CORRUPT", "The link’s data is damaged.")
        return E2ee.open(key, bytes.copyOf(12), bytes, aad, 12, bytes.size - 12)
    }
    private fun secretKey(keys: AccountKeys) = E2ee.aesKey(E2ee.hkdf(keys.master, "harbor0/e2ee/v1/link-secrets"))
    /** The link's secret wrapped by the account, for the server to keep for the owner. */
    fun wrapSecret(keys: AccountKeys, secret: ByteArray, rng: (Int) -> ByteArray = E2ee::random) = sealed(secretKey(keys), secret, SECRET_AAD, rng)
    fun unwrapSecret(keys: AccountKeys, secretWrap: String) = opened(secretKey(keys), secretWrap, SECRET_AAD)
    /** The link's address with its secret: the server's URL plus `.<secret>` in the fragment. */
    fun url(url: String, secret: ByteArray) = "$url.${E2ee.b64url(secret)}"

    // The item id is bound in, so the server can't move a name to another item.
    private fun nameAad(itemId: String) = "harbor0/e2ee/v1/link-name:$itemId".toByteArray()
    fun wrapName(link: LinkKeys, itemId: String, name: String, rng: (Int) -> ByteArray = E2ee::random) =
        sealed(link.names, Normalizer.normalize(name, Normalizer.Form.NFC).toByteArray(), nameAad(itemId), rng)
    fun unwrapName(link: LinkKeys, itemId: String, nameWrap: String): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(opened(link.names, nameWrap, nameAad(itemId)))).toString()
    } catch (e: java.nio.charset.CharacterCodingException) { throw E2eeException("CORRUPT", "The link’s data is damaged.") }

    /** A stored version's random content key, unwrapped from its 80-byte header. */
    fun contentKey(keys: AccountKeys, header: ByteArray): ByteArray {
        if (header.size < E2ee.HEADER_SIZE || E2ee.MAGIC.indices.any { header[it] != E2ee.MAGIC[it] } || header[4].toInt() != 1)
            throw E2eeException("CORRUPT", "This file isn’t encrypted the way harbor0 expects.")
        val chunk = ByteBuffer.wrap(header).getInt(8).toLong() and 0xffffffffL
        if (chunk < 4096 || chunk > 64L * 1024 * 1024) throw E2eeException("CORRUPT", "This file’s header is damaged.")
        return E2ee.open(keys.fileWrap, header.copyOfRange(20, 32), header.copyOfRange(32, 80), header.copyOf(20))
    }
    // The version's header is bound in: it is random per version, so a wrap opens no other file.
    private fun fileAad(head: ByteArray) = FILE_AAD + head.copyOf(HEAD_SIZE)
    /** A stored version's content key, re-sealed for a link, from the 80-byte header the server keeps. */
    fun wrapFileKey(keys: AccountKeys, link: LinkKeys, header: ByteArray, rng: (Int) -> ByteArray = E2ee::random): LinkFileWrap {
        val content = contentKey(keys, header)
        val head = header.copyOf(HEAD_SIZE)
        return LinkFileWrap(sealed(link.files, content, fileAad(head), rng), E2ee.b64url(head))
    }
    /** A visitor's content key for a file shared by a link (what the share site does). */
    fun openFileKey(link: LinkKeys, wrap: LinkFileWrap): ByteArray = opened(link.files, wrap.keyWrap, fileAad(E2ee.fromB64url(wrap.head)))
}

/** `PublicLink.e2ee`: the link's secret, wrapped by the account's master key. */
@Serializable data class LinkE2ee(val secretWrap: String)

/** How wrapping a link's items is going, as the web's `WrapProgress`. */
data class WrapProgress(
    /** Items looked at so far. */
    val scanned: Int = 0,
    /** Items wrapped now; the rest were current already. */
    val wrapped: Int = 0,
    /** Encrypted files whose key can't be read yet (still uploading, or not in the cloud). */
    val waiting: Int = 0, val done: Boolean = false)

/** How often an unlocked phone brings its account's encrypted links up to date. */
const val LINK_SYNC_MS = 10 * 60_000L

/**
 * Keeps encrypted links current, as apps/web/lib/e2ee-links.ts: wraps whatever under a link isn't wrapped for its
 * current name and version yet, the item itself and, for a folder, everything inside it. [api] must be the vault's
 * transport, so names arrive decrypted: that's what the visitor sees. One run per link at a time; a second caller
 * joins it and hears its progress too.
 */
class LinkWraps(private val api: E2eeApi, private val rng: (Int) -> ByteArray = E2ee::random) {
    private class Run(val result: CompletableDeferred<WrapProgress>, val listeners: MutableSet<(WrapProgress) -> Unit>)
    private val running = mutableMapOf<String, Run>()

    suspend fun sync(keys: AccountKeys, link: PublicLink, onProgress: ((WrapProgress) -> Unit)? = null): WrapProgress {
        val e2ee = link.e2ee ?: return WrapProgress(done = true)
        var mine = false
        val run = synchronized(running) {
            running[link.id] ?: Run(CompletableDeferred(), mutableSetOf()).also { running[link.id] = it; mine = true }
        }
        if (onProgress != null) synchronized(run.listeners) { run.listeners += onProgress }
        if (!mine) return run.result.await()
        try {
            val progress = wrapAll(keys, link, e2ee) { p -> synchronized(run.listeners) { run.listeners.toList() }.forEach { it(p) } }
            run.result.complete(progress)
            return progress
        } catch (e: Throwable) {
            run.result.completeExceptionally(if (e is CancellationException) IllegalStateException("Stopped.") else e)
            throw e
        } finally { synchronized(running) { running.remove(link.id) } }
    }

    private suspend fun wrapAll(keys: AccountKeys, link: PublicLink, e2ee: LinkE2ee, onProgress: (WrapProgress) -> Unit): WrapProgress {
        val sealed = withContext(Dispatchers.Default) { E2eeLinks.keys(E2eeLinks.unwrapSecret(keys, e2ee.secretWrap)) }
        var progress = WrapProgress()
        val pending = mutableListOf<JsonObject>()
        val route = "/v1/links/${HarborApi.segment(link.id)}/e2ee"
        suspend fun save(all: Boolean) {
            while (pending.size >= 100 || (all && pending.isNotEmpty())) {
                val wraps = pending.take(100)
                repeat(wraps.size) { pending.removeAt(0) }
                val result = api.call("$route/wraps", "PUT", buildJsonObject { put("wraps", JsonArray(wraps)) })
                // Items changed since the scan are wrapped on the next run.
                progress = progress.copy(wrapped = progress.wrapped + (result["saved"]?.jsonPrimitive?.intOrNull ?: 0))
            }
        }
        suspend fun consider(item: JsonObject) {
            progress = progress.copy(scanned = progress.scanned + 1)
            if (item.bool("current")) return
            val id = item.text("id") ?: return
            var file: LinkFileWrap? = null
            if (item.bool("encrypted")) {
                val header = item.text("keyHeader") ?: run { progress = progress.copy(waiting = progress.waiting + 1); return }
                file = withContext(Dispatchers.Default) { E2eeLinks.wrapFileKey(keys, sealed, E2ee.fromB64url(header), rng) }
            }
            // The transport has decrypted the name already: that's what the visitor sees.
            val nameWrap = E2eeLinks.wrapName(sealed, id, item.text("name").orEmpty(), rng)
            pending += buildJsonObject {
                put("itemId", id); put("revision", item["revision"]?.jsonPrimitive?.intOrNull ?: 0); put("nameWrap", nameWrap)
                put("keyWrap", file?.keyWrap); put("head", file?.head)
            }
        }
        var queue: MutableList<Pair<String?, String?>> = if (link.itemType == "FOLDER") mutableListOf(null to null) else mutableListOf()
        var first = true
        while (first || queue.isNotEmpty()) {
            val folders = queue.take(25)
            queue = queue.drop(folders.size).toMutableList()
            val scan = api.call("$route/scan", "POST", buildJsonObject {
                put("folders", JsonArray(folders.map { (folderId, cursor) -> buildJsonObject { folderId?.let { put("folderId", it) }; cursor?.let { put("cursor", it) } } }))
            })
            if (first) (scan["root"] as? JsonObject)?.let { consider(it) }
            first = false
            for (folder in (scan["folders"] as? JsonArray).orEmpty().map { it.jsonObject }) {
                for (item in (folder["items"] as? JsonArray).orEmpty().map { it.jsonObject }) {
                    consider(item)
                    if (item.text("type") == "FOLDER") queue += item.text("id") to null
                }
                folder.text("nextCursor")?.let { queue += folder.text("folderId") to it }
            }
            save(false)
            onProgress(progress)
        }
        save(true)
        progress = progress.copy(done = true)
        onProgress(progress)
        return progress
    }

    /** Brings every encrypted link of the account up to date, one at a time; errors wait for the next run. */
    suspend fun syncAll(keys: AccountKeys, links: List<PublicLink>) {
        for (link in links.filter { it.e2ee != null && it.disabledAt == null })
            try { sync(keys, link) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
    }
    companion object {
        private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true
    }
}
