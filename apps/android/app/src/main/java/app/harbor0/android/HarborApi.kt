package app.harbor0.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resumeWithException

suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

/** The second step of a sign-in for accounts with two-step verification (`twoFactorChallengeSchema`). */
@Serializable data class TwoFactorChallenge(val session: String, val methods: List<String> = emptyList(), val method: String? = null,
    val destination: String? = null)

class HarborApi(val baseUrl: String, private val credentials: CredentialStore,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).followRedirects(false).build(),
    private val now: () -> Long = System::currentTimeMillis, private val deviceKeys: DeviceKeyStore? = null,
    private val appVersion: String = BuildConfig.VERSION_NAME,
    /** Milliseconds before retry [attempt] (0-based) of a throttled request; [retryAfter] is the server's hint in seconds. */
    private val retryDelay: (attempt: Int, retryAfter: Long?) -> Long = { attempt, after -> backoff(attempt, after) }) {
    private val sessionLock = Mutex()
    // Read by requests on many threads; written only under [sessionLock].
    @Volatile private var saved: SavedSession? = null
    private val _signedIn = MutableStateFlow(false)
    val signedIn = _signedIn.asStateFlow()
    var canRestore = false; private set
    private val _ended = MutableStateFlow<ApiException?>(null)
    /** Why the session ended when this app did not sign out itself (revoked, signed out elsewhere, expired). */
    val ended = _ended.asStateFlow()
    private val _encryptionBlocked = MutableStateFlow(false)
    /** The account is end-to-end encrypted, which this version can't open: file routes answer 426 E2EE_UPDATE_REQUIRED. */
    val encryptionBlocked = _encryptionBlocked.asStateFlow()
    fun clearEncryptionBlock() { _encryptionBlocked.value = false }

    private var deviceName: String? = null
    private suspend fun persist(tokens: Tokens, deviceProven: Boolean = saved?.deviceProven ?: false) {
        val next = SavedSession(tokens, now() + (tokens.expiresIn * 1000).toLong(), deviceProven)
        withContext(Dispatchers.IO) { credentials.save(next) }
        saved = next; canRestore = true
    }
    private suspend fun clear(reason: ApiException? = null) {
        // Invalidate memory even when storage fails; the caller can retry removing local data.
        // Remote sign-outs are explained even when found while restoring at launch; plain expiry only mid-session.
        if (reason != null && (_signedIn.value || reason.code == "DEVICE_REVOKED" || reason.message.contains("signed out", true))) _ended.value = reason
        saved = null; _signedIn.value = false; _encryptionBlocked.value = false
        withContext(Dispatchers.IO) { credentials.clear() }
        canRestore = false
    }
    suspend fun forget() = sessionLock.withLock { clear() }
    suspend fun restore() = sessionLock.withLock {
        canRestore = true
        saved = withContext(Dispatchers.IO) { credentials.load() }
        canRestore = saved != null
        if (saved != null) {
            refresh()
            // Sessions from before signed device identity are upgraded once; a failure retries next launch.
            saved?.takeIf { !it.deviceProven }?.let { current ->
                if (runCatching { proveDevice(current.tokens.accessToken) }.isSuccess) {
                    val next = current.copy(deviceProven = true)
                    runCatching { withContext(Dispatchers.IO) { credentials.save(next) } }.onSuccess { saved = next }
                }
            }
            _signedIn.value = true
        }
    }
    /**
     * Signs in with the password. Accounts with two-step verification get a [TwoFactorChallenge] instead of tokens;
     * finish with [loginVerify]. Returns null once signed in.
     */
    suspend fun login(email: String, password: String, deviceName: String): TwoFactorChallenge? = sessionLock.withLock {
        val result = raw("/v1/auth/login", "POST", buildJsonObject {
            put("email", email.trim().lowercase()); put("password", password)
            put("platform", "ANDROID"); put("deviceName", deviceName)
            // This app can ask for a second-step code, so the server sends a challenge rather than TWO_FACTOR_UNSUPPORTED.
            put("twoFactor", true)
        })
        result["twoFactor"]?.let { return@withLock harborJson.decodeFromJsonElement<TwoFactorChallenge>(it) }
        signIn(harborJson.decodeFromJsonElement(result), deviceName)
        null
    }
    /** Chooses how to finish a two-step sign-in; EMAIL sends a code now. The returned session replaces the old one. */
    suspend fun loginMethod(email: String, session: String, method: String): TwoFactorChallenge {
        require(method in listOf("TOTP", "EMAIL"))
        val result = raw("/v1/auth/login/method", "POST", buildJsonObject {
            put("email", email.trim().lowercase()); put("session", session); put("method", method)
        })
        return harborJson.decodeFromJsonElement(result["twoFactor"]!!)
    }
    /**
     * Finishes a two-step sign-in with the code. A wrong code throws 400 AUTH_INVALID whose details carry the
     * session to keep; AUTH_EXPIRED means starting again from the password.
     */
    suspend fun loginVerify(email: String, session: String, method: String, code: String, deviceName: String) = sessionLock.withLock {
        require(method in listOf("TOTP", "EMAIL"))
        require(Regex("^\\d{6,8}$").matches(code)) { "Enter the code." }
        val tokens = harborJson.decodeFromJsonElement<Tokens>(raw("/v1/auth/login/verify", "POST", buildJsonObject {
            put("email", email.trim().lowercase()); put("session", session); put("method", method); put("code", code)
            put("platform", "ANDROID"); put("deviceName", deviceName)
        }))
        signIn(tokens, deviceName)
    }
    private suspend fun signIn(tokens: Tokens, deviceName: String) {
        this.deviceName = deviceName
        // Servers without signed device identity don't offer the challenge; restore() upgrades later.
        val proven = try { proveDevice(tokens.accessToken); true }
            catch (e: ApiException) { if (e.status != 404) throw e; false }
        persist(tokens, deviceProven = proven); _ended.value = null; _encryptionBlocked.value = false; _signedIn.value = true
    }
    /** Binds this session to the installation's key so the server can tell devices apart. */
    private suspend fun proveDevice(token: String) {
        val keys = deviceKeys ?: return
        val challenge = raw("/v1/auth/session/challenge", "POST", null, token)
        val nonce = challenge["challenge"]!!.jsonPrimitive.content
        val userId = challenge["userId"]!!.jsonPrimitive.content
        val key = withContext(Dispatchers.IO) { keys.key(userId) }
        raw("/v1/auth/session", "POST", buildJsonObject {
            put("name", deviceName ?: "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"); put("platform", "ANDROID"); put("devicePublicId", key.fingerprint)
            put("appVersion", appVersion.take(32)); put("proof", key.proof(nonce, userId))
        }, token)
    }
    private suspend fun refresh() {
        val token = saved?.tokens?.refreshToken ?: throw ApiException(401, "Sign in to continue.")
        try {
            persist(harborJson.decodeFromJsonElement(raw("/v1/auth/refresh", "POST", buildJsonObject { put("refreshToken", token) })))
        } catch (e: ApiException) { if (e.status == 401 || e.status == 403) clear(e); throw e }
    }
    suspend fun logout() {
        if (saved != null) {
            try {
                authorized("/v1/auth/logout", "POST") { buildJsonObject { put("refreshToken", saved?.tokens?.refreshToken) } }
            } catch (e: ApiException) { if (_signedIn.value && e.code != "DEVICE_REVOKED") throw e }
        }
        sessionLock.withLock { clear() }
    }
    /** Unauthenticated account flows: sign-up, email confirmation, resend and password reset. */
    suspend fun anonymous(path: String, body: JsonObject): JsonObject {
        require(path.startsWith("/v1/auth/"))
        return raw(path, "POST", body)
    }
    /** Every signed-in request exactly as the server sends and receives it, without [transport]. */
    val direct = E2eeApi { path, method, body -> authorized(path, method) { body } }
    /**
     * Wraps every signed-in request when set: on an end-to-end encrypted account the vault's transport encrypts names
     * going out and decrypts names and sizes coming back, so the rest of the app only ever sees plaintext.
     */
    @Volatile var transport: E2eeApi? = null
    suspend fun request(path: String, method: String = "GET", body: JsonObject? = null): JsonObject = (transport ?: direct).call(path, method, body)
    suspend inline fun <reified T> get(path: String): T = harborJson.decodeFromJsonElement(request(path))
    /**
     * The access token to send, renewed first when it is about to expire or the server [refused] it. Only reading and
     * renewing the token holds the lock, so requests run side by side; requests that find the same token refused wait
     * here, and all but the first use the token it renewed rather than rotating the refresh token again.
     */
    private suspend fun token(refused: String? = null): String = sessionLock.withLock {
        val current = saved?.tokens?.accessToken
        val renew = if (refused != null) current == refused else (saved?.expiresAt ?: 0) - now() < 60_000
        if (renew) refresh()
        saved?.tokens?.accessToken ?: throw ApiException(401, "Sign in to continue.")
    }
    /** Signs out after a refusal, unless another request has renewed the session since [token] was sent. */
    private suspend fun ended(e: ApiException, token: String) = sessionLock.withLock {
        if (e.code == "DEVICE_REVOKED" || saved?.tokens?.accessToken == token) clear(e)
    }
    private suspend fun authorized(path: String, method: String, body: () -> JsonObject?): JsonObject {
        val token = token()
        try { return raw(path, method, body(), token) }
        catch (e: ApiException) {
            if (e.code == "DEVICE_REVOKED") { ended(e, token); throw e }
            if (e.status != 401) throw e
            val renewed = token(refused = token)
            try { return raw(path, method, body(), renewed) }
            catch (retry: ApiException) { if (retry.status == 401 || retry.code == "DEVICE_REVOKED") ended(retry, renewed); throw retry }
        }
    }
    /**
     * One API call. Signed-in calls refused by throttling (429 RATE_LIMITED) or an unavailable server (503) are tried
     * up to 3 times, honoring Retry-After: both are refused before the handler runs, so repeating is safe.
     * Sign-in calls are not repeated; they have their own limits (desktop retry.ts).
     */
    private suspend fun raw(path: String, method: String, body: JsonObject?, token: String? = null): JsonObject = withContext(Dispatchers.IO) {
        require(path.startsWith("/v1/"))
        val request = Request.Builder().url(baseUrl.trimEnd('/') + path)
        if (token != null) request.header("Authorization", "Bearer $token")
        if (coroutineContext[BackgroundRequest.Key] != null) request.header(BACKGROUND_HEADER, "1")
        val payload = body?.toString()?.toRequestBody("application/json".toMediaType())
        request.method(method, payload ?: if (method == "POST" || method == "PATCH") "{}".toRequestBody("application/json".toMediaType()) else null)
        send(request.build(), retry = token != null)
    }
    private suspend fun send(request: Request, retry: Boolean): JsonObject {
        var attempt = 0
        var retryAfter: Long? = null
        while (true) {
            client.newCall(request).awaitResponse().use { response ->
                val text = response.body?.string().orEmpty()
                val json = runCatching { harborJson.parseToJsonElement(text).jsonObject }.getOrNull()
                if (response.isSuccessful) return json ?: if (text.isBlank()) buildJsonObject {} else throw IOException("The server returned an unreadable response.")
                val error = json?.get("error") as? JsonObject
                val code = error?.get("code")?.jsonPrimitive?.contentOrNull
                // Only throttling: other 429s, like USERNAME_CHANGE_LIMIT, are answers.
                val throttled = response.code == 503 || (response.code == 429 && (code == null || code == "RATE_LIMITED"))
                if (!retry || !throttled || attempt >= 2) {
                    if (code == "E2EE_UPDATE_REQUIRED") _encryptionBlocked.value = true
                    throw ApiException(response.code, error?.get("message")?.jsonPrimitive?.contentOrNull ?: "The server could not complete this request (${response.code}). Try again.",
                        code, error?.get("details") as? JsonObject)
                }
                retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()
            }
            delay(retryDelay(attempt++, retryAfter))
        }
    }
    companion object {
        /** Exponential backoff with jitter (0.5 s, 1 s), or the server's Retry-After up to 10 s. */
        fun backoff(attempt: Int, retryAfter: Long?): Long {
            if (retryAfter != null) return retryAfter.coerceIn(0, 10) * 1000
            val step = 500L shl attempt
            return step / 2 + (Math.random() * step / 2).toLong()
        }
        fun pagePath(path: String, cursor: String?): String {
            val url = ("https://harbor.invalid" + path).toHttpUrl().newBuilder()
            if (cursor != null) url.addQueryParameter("cursor", cursor)
            val built = url.build()
            return built.encodedPath + (built.encodedQuery?.let { "?$it" } ?: "")
        }
        fun segment(value: String): String = "https://harbor.invalid".toHttpUrl().newBuilder().addPathSegment(value).build().encodedPath.drop(1)
    }
    suspend fun list(parent: String?, cursor: String? = null): DrivePage = get(pagePath("/v1/drive/folders/${segment(parent ?: "root")}/children", cursor))
    suspend fun createFolder(name: String, parent: String?): DriveItem =
        harborJson.decodeFromJsonElement<ItemResponse>(request("/v1/drive/folders", "POST", operation("name" to safeName(name.trim()), "parentId" to parent))).item
    /** Follows every page of a list endpoint, stopping if the server repeats a cursor. */
    suspend inline fun <reified T> all(path: String): List<T> {
        val result = mutableListOf<T>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = get<Page<T>>(pagePath(path, cursor))
            result += page.items
            cursor = page.nextCursor
            check(cursor == null || seen.add(cursor)) { "Could not load the remaining files. Please retry." }
        } while (cursor != null)
        return result
    }
    /** A mutation body: a fresh operation ID, the item's revision, and any extra fields. */
    fun mutation(item: DriveItem, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        operation().forEach { (k, v) -> put(k, v) }
        item.revision?.let { put("baseRevision", it) }
        extra()
    }
    suspend fun trash(item: DriveItem, action: String) {
        val revision = item.revision ?: throw IllegalStateException("Refresh this list before changing the file.")
        require(revision > 0)
        require(action in listOf("trash", "restore", "permanent"))
        val suffix = if (action == "trash") "" else "/$action"
        request("/v1/drive/items/${segment(item.id)}$suffix", if (action == "restore") "POST" else "DELETE", buildJsonObject {
            operation().forEach { (k, v) -> put(k, v) }; put("baseRevision", revision)
        })
    }
    suspend fun emptyTrash() {
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        do {
            val result = request("/v1/drive/trash/empty", "POST", buildJsonObject {
                operation().forEach { (k, v) -> put(k, v) }; cursor?.let { put("cursor", it) }
            })
            cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull
            check(cursor == null || seen.add(cursor)) { "Trash cleanup stopped unexpectedly. Refresh and try again." }
        } while (cursor != null)
    }
}

/** Header (contracts `BACKGROUND_HEADER`) on requests the app makes by itself, so the console doesn't count them as a person. */
const val BACKGROUND_HEADER = "X-Harbor-Background"
/** In a coroutine's context, marks its requests as the app's own work (the sync and backup engine), not a person's tap. */
object BackgroundRequest : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<BackgroundRequest>
}
