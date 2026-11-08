package app.harbor0.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** This phone's copy of an account key: it opens only with the half the server keeps. */
@Serializable data class E2eeDeviceCopy(val userId: String, val recordId: String, val local: String, val envelope: DeviceEnvelope)
interface E2eeCopyStore {
    fun load(userId: String): E2eeDeviceCopy?
    fun save(copy: E2eeDeviceCopy)
    fun clear(userId: String)
}
class MemoryE2eeCopies: E2eeCopyStore {
    private val copies = java.util.concurrent.ConcurrentHashMap<String, E2eeDeviceCopy>()
    override fun load(userId: String) = copies[userId]
    override fun save(copy: E2eeDeviceCopy) { copies[copy.userId] = copy }
    override fun clear(userId: String) { copies.remove(userId) }
}

/** Saved copies sealed by an Android Keystore key, one per account and API realm, excluded from backup like sign-ins. */
class KeystoreE2eeCopies(context: Context, realm: String = BuildConfig.API_URL): E2eeCopyStore {
    private val scope = E2ee.sha256Hex(realm.trimEnd('/').toByteArray())
    private val preferences = context.getSharedPreferences("e2ee_$scope", Context.MODE_PRIVATE)
    private val alias = context.packageName + ".e2ee." + scope
    private fun entry(userId: String) = "copy_" + E2ee.sha256Hex(userId.toByteArray())
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun load(userId: String): E2eeDeviceCopy? {
        val encoded = preferences.getString(entry(userId), null) ?: return null
        return runCatching {
            val bytes = E2ee.fromB64url(encoded)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(entry(userId).toByteArray())
            harborJson.decodeFromString<E2eeDeviceCopy>(String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8))
        }.getOrNull()?.takeIf { it.userId == userId }
    }
    override fun save(copy: E2eeDeviceCopy) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(entry(copy.userId).toByteArray())
        val sealed = cipher.iv + cipher.doFinal(harborJson.encodeToString(E2eeDeviceCopy.serializer(), copy).toByteArray())
        check(preferences.edit().putString(entry(copy.userId), E2ee.b64url(sealed)).commit()) { "Could not keep this phone unlocked securely. Try again." }
    }
    override fun clear(userId: String) {
        check(preferences.edit().remove(entry(userId)).commit()) { "Could not remove this phone's copy of your key. Try again." }
    }
}

/** How opening the saved copy went. */
enum class E2eeRestore { UNLOCKED, NO_COPY, REMOVED, FAILED }

/**
 * The signed-in account's end-to-end encryption on this phone, as the desktop vault. The keys live
 * in memory only. A remembered unlock saves a copy sealed by the Keystore that also needs the
 * server's half, so removing this phone in the web app's encryption settings locks it for good;
 * one not remembered keeps its copy in memory, so the app locks when its process ends, and the
 * server forgets its half after 7 days unused.
 */
class E2eeVault(private val store: E2eeCopyStore) {
    private val _view = MutableStateFlow(E2eeView())
    val view: StateFlow<E2eeView> = _view.asStateFlow()
    private var deviceRecordId: String? = null
    private var sessionCopy: E2eeDeviceCopy? = null
    fun current() = _view.value
    /** Uploads must be encrypted: the account is encrypted, unlocked here and not turning off. */
    fun uploadKeys() = current().takeIf { it.enabled && !it.decrypting }?.keys
    /** Stored encrypted files can be read. */
    fun readKeys() = current().takeIf { it.enabled }?.keys
    /** This phone's record among the account's unlocked devices, while unlocked. */
    val deviceRecord get() = if (current().keys != null) deviceRecordId else null
    val locked get() = current().let { it.enabled && it.keys == null }
    /** A transport that keeps this vault in step with the account. */
    fun transport(api: E2eeApi, strict: Boolean = false) = E2eeTransport(api, ::current, ::setAccount, strict)

    /** Called with each /v1/users/me answer; a different account starts locked. */
    fun setAccount(userId: String, enabled: Boolean, decrypting: Boolean) {
        val before = current()
        if (before.userId != userId) {
            deviceRecordId = null; sessionCopy = null
            _view.value = E2eeView(userId, enabled, decrypting, null); return
        }
        if (before.enabled && !enabled) {
            // Turned off: the saved copy opens nothing any more.
            runCatching { store.clear(userId) }
            deviceRecordId = null; sessionCopy = null
            _view.value = before.copy(enabled = false, decrypting = false, keys = null); return
        }
        _view.update { it.copy(enabled = enabled, decrypting = decrypting) }
    }
    /** Signed out: forget the keys, keeping the saved copy for the next sign-in. */
    fun reset() { deviceRecordId = null; sessionCopy = null; _view.value = E2eeView() }

    /** Opens this phone's saved copy, if the server still recognises it. */
    suspend fun restore(api: E2eeApi): E2eeRestore {
        val userId = current().userId ?: return E2eeRestore.NO_COPY
        if (current().keys != null) return E2eeRestore.UNLOCKED
        val saved = sessionCopy?.takeIf { it.userId == userId } ?: withContext(Dispatchers.IO) { runCatching { store.load(userId) }.getOrNull() }
            ?: return E2eeRestore.NO_COPY
        return try {
            val secret = api.call("/v1/e2ee/devices/${HarborApi.segment(saved.recordId)}/secret", "POST", null)["serverSecret"]!!.jsonPrimitive.content
            val keys = withContext(Dispatchers.Default) {
                E2ee.accountKeys(E2ee.unwrapForDevice(saved.envelope, E2ee.fromB64url(saved.local), E2ee.fromB64url(secret)))
            }
            if (current().userId != userId) return E2eeRestore.FAILED
            deviceRecordId = saved.recordId
            _view.update { it.copy(keys = keys) }
            E2eeRestore.UNLOCKED
        } catch (e: Exception) {
            // Removed from the web app (E2EE_DEVICE_REMOVED), or damaged: the passphrase unlocks it again.
            if ((e is ApiException && e.status == 404) || e is E2eeException) {
                forgetCopy(userId); E2eeRestore.REMOVED
            } else E2eeRestore.FAILED
        }
    }
    private suspend fun forgetCopy(userId: String) {
        sessionCopy = null
        withContext(Dispatchers.IO) { runCatching { store.clear(userId) } }
    }

    private suspend fun keep(api: E2eeApi, keys: AccountKeys, remember: Boolean) {
        val userId = current().userId ?: throw IllegalStateException("Sign in to continue.")
        val local = E2ee.random(32)
        val server = E2ee.random(32)
        val envelope = E2ee.wrapForDevice(keys.master, local, server)
        val id = api.call("/v1/e2ee/devices", "POST", buildJsonObject {
            put("proof", keys.proof); put("serverSecret", E2ee.b64url(server)); put("remember", remember)
        })["id"]!!.jsonPrimitive.content
        val copy = E2eeDeviceCopy(userId, id, E2ee.b64url(local), envelope)
        if (remember) { withContext(Dispatchers.IO) { store.save(copy) }; sessionCopy = null }
        else { sessionCopy = copy; withContext(Dispatchers.IO) { runCatching { store.clear(userId) } } }
        deviceRecordId = id
        _view.update { it.copy(keys = keys) }
    }
    private suspend fun state(api: E2eeApi) = api.call("/v1/e2ee", "GET", null)["e2ee"] as? JsonObject
        ?: throw IllegalStateException("Encryption is off for this account.")
    /** Unlocks with the passphrase; a wrong one throws E2eeException WRONG_KEY. Argon2id takes a moment. */
    suspend fun unlockWithPassphrase(api: E2eeApi, passphrase: String, remember: Boolean = true) {
        val envelope = state(api)["passphrase"] as? JsonObject ?: throw IllegalStateException("Encryption is off for this account.")
        val keys = withContext(Dispatchers.Default) {
            E2ee.accountKeys(E2ee.unwrapWithPassphrase(harborJson.decodeFromJsonElement(PassphraseEnvelope.serializer(), envelope), passphrase))
        }
        keep(api, keys, remember)
    }
    /** Unlocks with the recovery key and sets a new passphrase, as after a forgotten one. */
    suspend fun unlockWithRecovery(api: E2eeApi, recovery: ByteArray, newPassphrase: String, remember: Boolean = true) {
        val envelope = state(api)["recovery"] as? JsonObject ?: throw IllegalStateException("Encryption is off for this account.")
        val (keys, passphrase) = withContext(Dispatchers.Default) {
            val master = E2ee.unwrapWithRecovery(harborJson.decodeFromJsonElement(RecoveryEnvelope.serializer(), envelope), recovery)
            E2ee.accountKeys(master) to E2ee.wrapWithPassphrase(master, newPassphrase)
        }
        api.call("/v1/e2ee/passphrase", "PUT", buildJsonObject {
            put("passphrase", harborJson.encodeToJsonElement(PassphraseEnvelope.serializer(), passphrase)); put("proof", keys.proof)
        })
        keep(api, keys, remember)
    }
    /** The keys, while unlocked on this phone. */
    fun requireKeys() = current().keys ?: throw e2eeLocked()
    /**
     * Turns encryption on for the account (web `enroll`): a new account key, wrapped by the passphrase and the
     * recovery key; this phone then keeps its own copy. Every later upload and name must be encrypted.
     */
    suspend fun enroll(api: E2eeApi, passphrase: String, recovery: ByteArray, remember: Boolean = true): JsonObject {
        val master = E2ee.newMasterKey()
        val (keys, body) = withContext(Dispatchers.Default) {
            val keys = E2ee.accountKeys(master)
            keys to buildJsonObject {
                put("passphrase", harborJson.encodeToJsonElement(PassphraseEnvelope.serializer(), E2ee.wrapWithPassphrase(master, passphrase)))
                put("recovery", harborJson.encodeToJsonElement(RecoveryEnvelope.serializer(), E2ee.wrapWithRecovery(master, recovery)))
                put("proof", keys.proof)
            }
        }
        val state = api.call("/v1/e2ee/enroll", "POST", body)
        _view.update { it.copy(enabled = true, decrypting = false) }
        keep(api, keys, remember)
        return state
    }
    /** A new passphrase for the same key: files stay as they are, unlocked devices stay unlocked. Returns the new state. */
    suspend fun changePassphrase(api: E2eeApi, passphrase: String): JsonObject {
        val keys = requireKeys()
        val envelope = withContext(Dispatchers.Default) { E2ee.wrapWithPassphrase(keys.master, passphrase) }
        return api.call("/v1/e2ee/passphrase", "PUT", buildJsonObject {
            put("passphrase", harborJson.encodeToJsonElement(PassphraseEnvelope.serializer(), envelope)); put("proof", keys.proof)
        })
    }
    /** A new recovery key; the old one stops working. Returns the new state. */
    suspend fun replaceRecovery(api: E2eeApi, recovery: ByteArray): JsonObject {
        val keys = requireKeys()
        return api.call("/v1/e2ee/recovery", "PUT", buildJsonObject {
            put("recovery", harborJson.encodeToJsonElement(RecoveryEnvelope.serializer(), E2ee.wrapWithRecovery(keys.master, recovery))); put("proof", keys.proof)
        })
    }
    /** Keeps this phone unlocked after the app closes: a remembered copy replaces this session's. */
    suspend fun rememberPhone(api: E2eeApi) {
        val keys = requireKeys()
        val previous = deviceRecordId
        keep(api, keys, true)
        if (previous != null) try { api.call("/v1/e2ee/devices/${HarborApi.segment(previous)}", "DELETE", null) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
    }
    /** Whether this phone's copy is remembered (survives the app closing). */
    fun remembered() = deviceRecordId != null && sessionCopy == null
    /** Forgets the key on this phone and removes its copy; it needs the passphrase again. */
    suspend fun lock(api: E2eeApi? = null) {
        val userId = current().userId
        val record = deviceRecordId ?: sessionCopy?.recordId ?: userId?.let { withContext(Dispatchers.IO) { runCatching { store.load(it) }.getOrNull() } }?.recordId
        if (userId != null) forgetCopy(userId)
        deviceRecordId = null
        _view.update { it.copy(keys = null) }
        if (api != null && record != null) runCatching { api.call("/v1/e2ee/devices/${HarborApi.segment(record)}", "DELETE", null) }
    }
}
