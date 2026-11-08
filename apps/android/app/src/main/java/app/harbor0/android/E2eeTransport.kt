package app.harbor0.android

import kotlinx.serialization.json.*
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/** The server, as the end-to-end encryption code needs it. HarborApi.request has this shape. */
fun interface E2eeApi { suspend fun call(path: String, method: String, body: JsonObject?): JsonObject }
fun HarborApi.e2eeApi() = direct

/**
 * The keys transfers use, as desktop `transferKeys`: [upload] while new files must be encrypted, [read] while stored
 * encrypted files can be decrypted (also while encryption is being turned off). [locked]: the account is encrypted and
 * new files must be encrypted, but this phone hasn't been unlocked, so nothing may be uploaded.
 */
class TransferKeys(val upload: () -> AccountKeys? = { null }, val read: () -> AccountKeys? = { null }, val locked: () -> Boolean = { false }) {
    /** Keys for a new upload, or null for an unencrypted one; refuses while locked. */
    fun forUpload(): AccountKeys? = upload() ?: if (locked()) throw e2eeLocked() else null
    /** Keys to decrypt a stored encrypted file. */
    fun forRead(): AccountKeys = read() ?: throw e2eeLocked()
    companion object { val NONE = TransferKeys() }
}
fun E2eeVault.transferKeys() = TransferKeys(::uploadKeys, ::readKeys) { current().let { it.enabled && !it.decrypting && it.keys == null } }
/**
 * The vault's transport, as the desktop's API wrapper: when the server says encryption was turned on or off elsewhere
 * (E2EE_OFF, E2EE_REQUIRED), the account is read again so the next attempt goes up the right way.
 */
fun E2eeVault.sealed(inner: E2eeApi, strict: Boolean = false): E2eeApi {
    val sealed = transport(inner, strict)
    return E2eeApi { path, method, body ->
        try { sealed.call(path, method, body) } catch (e: ApiException) {
            if (e.code == "E2EE_OFF" || e.code == "E2EE_REQUIRED") try { sealed.call("/v1/users/me", "GET", null) }
                catch (refresh: kotlinx.coroutines.CancellationException) { throw refresh } catch (_: Exception) {}
            throw e
        }
    }
}

/** What the transport needs to know about the signed-in account's encryption. */
data class E2eeView(val userId: String? = null,
    /** The account is encrypted. */ val enabled: Boolean = false,
    /** Being turned off: new names go up unencrypted, stored ones are still read. */ val decrypting: Boolean = false,
    /** Present once this app has unlocked the account. */ val keys: AccountKeys? = null)

fun e2eeLocked() = ApiException(423, "Unlock your files to continue.", "E2EE_LOCKED")
/** The server's normalizedName: NFC and lower case. */
fun normalizeName(name: String) = Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(java.util.Locale.ROOT)

private val mimes = mapOf("png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
    "avif" to "image/avif", "heic" to "image/heic", "svg" to "image/svg+xml", "bmp" to "image/bmp", "pdf" to "application/pdf",
    "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv", "json" to "application/json", "html" to "text/html",
    "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "webm" to "video/webm", "mp3" to "audio/mpeg",
    "m4a" to "audio/mp4", "wav" to "audio/wav", "ogg" to "audio/ogg", "flac" to "audio/flac", "zip" to "application/zip",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation")
/** The MIME type a browser would give a file of this name; uploads of encrypted files hide it. */
fun guessMime(name: String) = mimes[name.lowercase(java.util.Locale.ROOT).substringAfterLast('.')] ?: "application/octet-stream"

/**
 * Makes end-to-end encryption invisible to the rest of the app, as e2ee-transport.ts does. On an
 * encrypted account, names going to the server for the account's own items are encrypted, and
 * answers come back with names decrypted, sizes as the original files and a MIME type guessed from
 * the name. Name search runs here, as the server can't read names. Signed download links keep the
 * stored size and hash: they describe the bytes as stored, which the app checks before decrypting.
 * `strict` refuses file requests while locked, for code that acts on names by itself, like sync.
 */
class E2eeTransport(private val inner: E2eeApi, private val vault: () -> E2eeView,
    private val onAccount: (userId: String, enabled: Boolean, decrypting: Boolean) -> Unit, private val strict: Boolean = false,
    private val now: () -> Long = System::currentTimeMillis): E2eeApi {
    private val owners = ConcurrentHashMap<String, String>()
    private val names = ConcurrentHashMap<String, String>()
    @Volatile private var checkedAt = 0L

    private suspend fun ownerOf(id: String): String = owners[id] ?: run {
        val item = inner.call("/v1/drive/items/${HarborApi.segment(id)}", "GET", null)["item"]!!.jsonObject
        item["ownerUserId"]!!.jsonPrimitive.content.also { owners[id] = it }
    }
    /** Whether a name under `id` (an item, or a folder for new items) is the account's own. */
    private suspend fun mine(id: String?) = id == null || id == "root" || ownerOf(id) == vault().userId
    private fun keys() = vault().keys ?: throw e2eeLocked()
    private fun sealName(name: JsonElement?): JsonElement? {
        val text = (name as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return name
        return if (E2ee.isEncryptedName(text)) name else JsonPrimitive(E2ee.encryptName(keys(), text))
    }
    private fun sealPath(path: String) = path.split('/').joinToString("/") { (sealName(JsonPrimitive(it)) as JsonPrimitive).content }

    /** The request as it goes to the server: the path (a lookup's name is in its query) and body. */
    suspend fun outgoing(path: String, method: String, body: JsonObject?): Pair<String, JsonObject?> {
        val verb = method.uppercase()
        val route = path.substringBefore('?')
        val query = path.substringAfter('?', "")
        val name = body?.get("name")
        if (body != null && name is JsonPrimitive && name.isString) {
            val item = Regex("^/v1/drive/items/([^/]+)(?:/move)?$").find(route)?.groupValues?.get(1)
            val backupRun = Regex("^/v1/backups/[^/]+/runs/[^/]+/(folders|uploads|items/[^/]+/name)$").find(route)?.groupValues?.get(1)
            val encrypted = body["encrypted"]?.jsonPrimitive?.booleanOrNull == true
            val encrypt = (verb == "POST" && route == "/v1/drive/folders" && mine(body["parentId"]?.jsonPrimitive?.contentOrNull)) ||
                (item != null && verb in setOf("PATCH", "POST") && mine(decode(item))) ||
                (verb == "POST" && route == "/v1/uploads" && encrypted) ||
                // Backup folders always belong to the account backing up.
                (verb == "POST" && (backupRun == "folders" || backupRun?.startsWith("items/") == true)) ||
                (verb == "POST" && backupRun == "uploads" && encrypted)
            if (encrypt) return path to body.with("name", sealName(name)!!)
        }
        // A backed up file's path, which the server keeps with the backup run.
        val relativePath = (body?.get("relativePath") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (verb == "POST" && Regex("^/v1/backups/[^/]+/runs/[^/]+/files$").matches(route) && relativePath != null)
            return path to body.with("relativePath", JsonPrimitive(sealPath(relativePath)))
        // A batch of small files from a synced folder or a backup run: each encrypted upload's name, and each path a backup records.
        val batch = if (route == "/v1/uploads/batch") "uploads" else Regex("^/v1/backups/[^/]+/runs/[^/]+/(uploads|files)/batch$").find(route)?.groupValues?.get(1)
        val uploads = body?.get("uploads") as? JsonArray
        if (verb == "POST" && batch == "uploads" && uploads != null)
            return path to body.with("uploads", JsonArray(uploads.map { upload ->
                val o = upload as? JsonObject
                if (o != null && (o["encrypted"] as? JsonPrimitive)?.booleanOrNull == true) o.with("name", sealName(o["name"]) ?: JsonNull) else upload
            }))
        val files = body?.get("files") as? JsonArray
        if (verb == "POST" && batch == "files" && files != null)
            return path to body.with("files", JsonArray(files.map { file ->
                val o = file as? JsonObject
                val p = (o?.get("relativePath") as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (o != null && p != null) o.with("relativePath", JsonPrimitive(sealPath(p))) else file
            }))
        val operations = body?.get("operations") as? JsonArray
        if (verb == "POST" && route == "/v1/sync/operations" && operations != null)
            return path to body.with("operations", JsonArray(operations.map { operation ->
                val o = operation as? JsonObject
                val payload = o?.get("payload") as? JsonObject
                val payloadName = payload?.get("name") as? JsonPrimitive
                if (o != null && payloadName != null && payloadName.isString && mine(o["entityId"]?.jsonPrimitive?.contentOrNull))
                    o.with("payload", payload.with("name", sealName(payloadName)!!)) else operation
            }))
        val lookup = Regex("^/v1/drive/folders/([^/]+)/lookup$").find(route)?.groupValues?.get(1)
        if (lookup != null && mine(decode(lookup))) {
            val params = parseQuery(query).toMutableList()
            val sealed = (sealName(params.firstOrNull { it.first == "name" }?.second?.let(::JsonPrimitive) ?: JsonNull) as? JsonPrimitive)?.contentOrNull ?: "null"
            params.removeAll { it.first == "name" }; params.add("name" to sealed)
            return "$route?${formatQuery(params)}" to body
        }
        return path to body
    }

    private fun reveal(k: AccountKeys, token: String): String = names[token] ?: try {
        E2ee.decryptName(k, token).also { if (names.size > 50_000) names.clear(); names[token] = it }
    } catch (e: E2eeException) {
        // Lower-cased copies (normalizedName) and damaged names stay as they are.
        token
    } catch (e: IllegalArgumentException) { token }
    /** An answer with its names decrypted, sizes as the original files and MIME types from the names. */
    fun incoming(value: JsonElement, k: AccountKeys): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map { incoming(it, k) })
        is JsonPrimitive -> {
            val text = value.contentOrNull
            if (!value.isString || text == null || !text.contains("h0e1.") || Regex("^https?://").containsMatchIn(text)) value
            else if (E2ee.isEncryptedName(text)) JsonPrimitive(reveal(k, text))
            // Paths and messages that name encrypted items.
            else JsonPrimitive(Regex("h0e1\\.[A-Za-z0-9_-]+").findAll(text).map { it.value }.toSet().fold(text) { out, t -> out.split(t).joinToString(reveal(k, t)) })
        }
        is JsonObject -> {
            val id = value.string("id"); val owner = value.string("ownerUserId")
            if (id != null && owner != null) owners[id] = owner
            val out = value.mapValues { incoming(it.value, k) }.toMutableMap()
            val name = value.string("name")
            if (name != null && E2ee.isEncryptedName(name) && "normalizedName" in value)
                out["normalizedName"] = JsonPrimitive(normalizeName((out["name"] as JsonPrimitive).content))
            val size = (value["sizeBytes"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            if ((value["encrypted"] as? JsonPrimitive)?.booleanOrNull == true && size != null && "downloadUrl" !in value) {
                out["sizeBytes"] = JsonPrimitive(E2ee.plainSize(size))
                val shown = (out["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val mime = (value["mimeType"] as? JsonPrimitive)?.contentOrNull
                if (shown != null && (mime.isNullOrEmpty() || mime == "application/octet-stream")) out["mimeType"] = JsonPrimitive(guessMime(shown))
            }
            JsonObject(out)
        }
    }

    /** Name search and type filters, applied to decrypted names a page at a time. */
    private suspend fun search(path: String, method: String, body: JsonObject?, k: AccountKeys): JsonObject {
        val params = parseQuery(path.substringAfter('?', "")).toMutableList()
        fun take(key: String) = params.firstOrNull { it.first == key }?.second
        val q = normalizeName(take("q") ?: "")
        val extension = take("extension")?.lowercase(java.util.Locale.ROOT)
        val mimeType = take("mimeType")
        params.removeAll { it.first in setOf("q", "extension", "mimeType") }
        val limit = take("limit")?.toDoubleOrNull() ?: 100.0
        val items = mutableListOf<JsonElement>()
        var cursor = take("cursor")
        for (pages in 0 until 20) {
            if (cursor != null) { params.removeAll { it.first == "cursor" }; params.add("cursor" to cursor) }
            val page = incoming(inner.call("/v1/search?${formatQuery(params)}", method, body), k).jsonObject
            items += (page["items"] as? JsonArray).orEmpty().filter { item ->
                val o = item.jsonObject
                val normalized = o.string("normalizedName") ?: ""
                (q.isEmpty() || normalized.contains(q)) && (extension.isNullOrEmpty() || normalized.endsWith(".$extension")) &&
                    (mimeType.isNullOrEmpty() || o.string("mimeType") == mimeType)
            }
            cursor = page.string("nextCursor")
            if (cursor == null || items.size >= limit) break
        }
        return buildJsonObject { put("items", JsonArray(items)); put("nextCursor", cursor) }
    }

    override suspend fun call(path: String, method: String, body: JsonObject?): JsonObject {
        val before = vault()
        val files = fileRoute.containsMatchIn(path)
        if (strict && files && before.enabled && before.keys == null) throw e2eeLocked()
        var target = path
        var payload = body
        if (before.enabled && before.keys != null) {
            if (path.startsWith("/v1/search?") && Regex("[?&](q|extension|mimeType)=").containsMatchIn(path)) return search(path, method, body, before.keys)
            // While turning off, new names go up unencrypted; stored ones are still read here.
            if (!before.decrypting) outgoing(path, method, body).let { target = it.first; payload = it.second }
        } else if (before.enabled && !before.decrypting) outgoing(path, method, body) // Throws if a name would need encrypting.
        val result = inner.call(target, method, payload)
        if (path == "/v1/users/me") account(result)
        val after = vault()
        if (after.enabled && after.keys != null) return incoming(result, after.keys).jsonObject
        if (strict && files && !after.enabled && now() - checkedAt > 60_000) {
            // Encryption may have been turned on elsewhere: find out before anything acts on encrypted names or bytes.
            val text = result.toString()
            if (text.contains("\"h0e1.") || text.contains("\"encrypted\":true")) {
                checkedAt = now()
                runCatching { inner.call("/v1/users/me", "GET", null) }.getOrNull()?.let(::account)
                if (vault().enabled) throw e2eeLocked()
            }
        }
        return result
    }
    /** Tells the vault about the account in a /v1/users/me answer. */
    fun account(me: JsonObject) {
        val user = me["user"] as? JsonObject ?: return
        val id = user.string("id") ?: return
        val e2ee = user["e2ee"] as? JsonObject
        onAccount(id, e2ee != null, !e2ee?.string("decryptingSince").isNullOrEmpty())
    }

    companion object {
        /** Requests whose answers name the account's files. */
        private val fileRoute = Regex("^/v1/(drive|uploads|downloads|sync|backups|search)(/|\\?|$)")
        private fun decode(segment: String) = URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
        private fun parseQuery(query: String) = if (query.isEmpty()) emptyList() else query.split('&').filter { it.isNotEmpty() }.map {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        private fun formatQuery(params: List<Pair<String, String>>) = params.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }
        private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))
        private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
