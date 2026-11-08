package app.harbor0.android

import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * End-to-end encryption, byte for byte as packages/api-client/src/e2ee.ts does it, so a file or
 * name encrypted by any harbor0 app opens in every other one.
 *
 * Keys: one random 32-byte master key per account, stored only wrapped (AES-256-GCM) by a key from
 * the passphrase (Argon2id), from the recovery key (HKDF) and from each unlocked device's secret
 * (HKDF over a device half and a server half). Subkeys come from the master key with HKDF.
 * Files (H0E1): an 80-byte header, then 4 MiB chunks, each sealed with nonce prefix ‖ index and
 * the header plus a last-chunk flag as associated data. Sealing is deterministic for a header,
 * so a resumed upload re-seals the same bytes. Names: `h0e1.` + base64url(iv ‖ ciphertext) with
 * the IV an HMAC of the name, so equal names encrypt equally.
 */

/** WRONG_KEY, CORRUPT, NAME_TOO_LONG or LOCKED, as the TypeScript E2eeError. */
class E2eeException(val code: String, message: String): Exception(message)

@Serializable data class PassphraseEnvelope(val v: Int, val kdf: String, val salt: String, val memoryKiB: Int, val iterations: Int,
    val parallelism: Int, val iv: String, val ciphertext: String)
@Serializable data class RecoveryEnvelope(val v: Int, val kdf: String, val salt: String, val iv: String, val ciphertext: String)
/** A device's copy: the master key wrapped by both halves of the device secret. */
@Serializable data class DeviceEnvelope(val iv: String, val ciphertext: String)
data class Argon2Cost(val memoryKiB: Int, val iterations: Int, val parallelism: Int)

/** The keys an unlocked device works with, derived once from the master key. */
class AccountKeys internal constructor(val master: ByteArray, internal val fileWrap: SecretKey, internal val nameEnc: SecretKey,
    internal val nameMac: SecretKey, /** Sent to the server to show this device holds the key. */ val proof: String)

object E2ee {
    const val NAME_PREFIX = "h0e1."
    /** Longest name, in UTF-8 bytes, that fits the server's limit once encrypted. */
    const val MAX_NAME_BYTES = 255
    const val CHUNK_SIZE = 4 * 1024 * 1024
    const val HEADER_SIZE = 80
    const val TAG = 16
    /** Argon2id cost for new passphrases: OWASP's 64 MiB, 3 passes. */
    val ARGON2 = Argon2Cost(65_536, 3, 1)
    internal val MAGIC = byteArrayOf(0x48, 0x30, 0x45, 0x31) // "H0E1"
    private val MASTER_AAD = "harbor0/e2ee/v1/master".toByteArray()
    private val NAME_AAD = "harbor0/e2ee/v1/name".toByteArray()
    private val secure = SecureRandom()
    private val encryptedName = Regex("^h0e1\\.[A-Za-z0-9_-]+$")

    fun random(n: Int) = ByteArray(n).also { secure.nextBytes(it) }
    fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun fromB64url(s: String): ByteArray = try { Base64.getUrlDecoder().decode(s.replace('+', '-').replace('/', '_').trimEnd('=')) }
        catch (e: IllegalArgumentException) { throw E2eeException("CORRUPT", "The stored key is damaged.") }
    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** HKDF-SHA256 (RFC 5869); an empty salt is a block of zeros, as WebCrypto treats it. */
    fun hkdf(secret: ByteArray, info: String, length: Int = 32, salt: ByteArray = ByteArray(0)): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        mac.init(SecretKeySpec(mac.doFinal(secret), "HmacSHA256"))
        val out = ByteArray(length)
        var block = ByteArray(0); var at = 0; var counter = 1
        while (at < length) {
            mac.update(block); mac.update(info.toByteArray()); mac.update(counter++.toByte())
            block = mac.doFinal()
            block.copyInto(out, at, 0, minOf(block.size, length - at)); at += block.size
        }
        return out
    }
    internal fun aesKey(raw: ByteArray): SecretKey = SecretKeySpec(raw, "AES")
    internal fun hmac(key: SecretKey, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(key); doFinal(data) }
    internal fun seal(key: SecretKey, iv: ByteArray, data: ByteArray, aad: ByteArray, offset: Int = 0, length: Int = data.size): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)); updateAAD(aad); doFinal(data, offset, length)
        }
    internal fun open(key: SecretKey, iv: ByteArray, data: ByteArray, aad: ByteArray, offset: Int = 0, length: Int = data.size): ByteArray = try {
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)); updateAAD(aad); doFinal(data, offset, length)
        }
    } catch (e: AEADBadTagException) { throw E2eeException("WRONG_KEY", "The key doesn’t match.") }
    catch (e: java.security.GeneralSecurityException) { throw E2eeException("WRONG_KEY", "The key doesn’t match.") }

    private fun wrapMaster(kek: ByteArray, master: ByteArray, iv: ByteArray) = DeviceEnvelope(b64url(iv), b64url(seal(aesKey(kek), iv, master, MASTER_AAD)))
    private fun unwrapMaster(kek: ByteArray, iv: String, ciphertext: String): ByteArray {
        val master = open(aesKey(kek), fromB64url(iv), fromB64url(ciphertext), MASTER_AAD)
        if (master.size != 32) throw E2eeException("CORRUPT", "The stored key is damaged.")
        return master
    }

    /** Argon2id v1.3 over the NFKC passphrase's UTF-8 bytes, 32 bytes out, as hash-wasm. */
    fun passphraseKek(passphrase: String, salt: ByteArray, cost: Argon2Cost): ByteArray {
        // The server's schema bounds, so a damaged envelope can't ask for gigabytes.
        if (cost.memoryKiB !in 19_456..1_048_576 || cost.iterations !in 2..20 || cost.parallelism !in 1..8)
            throw E2eeException("CORRUPT", "The stored key is damaged.")
        val generator = Argon2BytesGenerator()
        generator.init(Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13).withSalt(salt)
            .withMemoryAsKB(cost.memoryKiB).withIterations(cost.iterations).withParallelism(cost.parallelism).build())
        return ByteArray(32).also { generator.generateBytes(Normalizer.normalize(passphrase, Normalizer.Form.NFKC).toByteArray(), it) }
    }
    fun wrapWithPassphrase(master: ByteArray, passphrase: String, cost: Argon2Cost = ARGON2, rng: (Int) -> ByteArray = ::random): PassphraseEnvelope {
        val salt = rng(16)
        val wrapped = wrapMaster(passphraseKek(passphrase, salt, cost), master, rng(12))
        return PassphraseEnvelope(1, "argon2id", b64url(salt), cost.memoryKiB, cost.iterations, cost.parallelism, wrapped.iv, wrapped.ciphertext)
    }
    fun unwrapWithPassphrase(e: PassphraseEnvelope, passphrase: String): ByteArray {
        if (e.v != 1 || e.kdf != "argon2id") throw E2eeException("CORRUPT", "The stored key is damaged.")
        return unwrapMaster(passphraseKek(passphrase, fromB64url(e.salt), Argon2Cost(e.memoryKiB, e.iterations, e.parallelism)), e.iv, e.ciphertext)
    }

    // Recovery keys: 20 random bytes as 32 Crockford base32 characters, shown in 8 groups of 4.
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val recoveryChars = Regex("^[0-9A-HJKMNP-TV-Z]{32}$")
    fun formatRecoveryKey(bytes: ByteArray): String {
        var bits = 0; var value = 0
        val out = StringBuilder()
        for (b in bytes) {
            value = (value shl 8) or (b.toInt() and 255); bits += 8
            while (bits >= 5) { out.append(CROCKFORD[(value ushr (bits - 5)) and 31]); bits -= 5 }
        }
        return out.chunked(4).joinToString("-")
    }
    /** Reads a typed recovery key, forgiving case, spaces, dashes and look-alike letters. */
    fun parseRecoveryKey(typed: String): ByteArray? {
        val chars = typed.uppercase(java.util.Locale.ROOT).replace(Regex("[\\s-]"), "")
            .replace('O', '0').replace('I', '1').replace('L', '1').replace('U', 'V')
        if (!recoveryChars.matches(chars)) return null
        val out = ByteArray(20)
        var bits = 0; var value = 0; var at = 0
        for (c in chars) {
            value = (value shl 5) or CROCKFORD.indexOf(c); bits += 5
            if (bits >= 8) { out[at++] = ((value ushr (bits - 8)) and 255).toByte(); bits -= 8 }
        }
        return out
    }
    fun newRecoveryKey() = random(20)
    fun newMasterKey() = random(32)
    fun wrapWithRecovery(master: ByteArray, recovery: ByteArray, rng: (Int) -> ByteArray = ::random): RecoveryEnvelope {
        val salt = rng(16)
        val wrapped = wrapMaster(hkdf(recovery, "harbor0/e2ee/v1/recovery", 32, salt), master, rng(12))
        return RecoveryEnvelope(1, "hkdf", b64url(salt), wrapped.iv, wrapped.ciphertext)
    }
    fun unwrapWithRecovery(e: RecoveryEnvelope, recovery: ByteArray): ByteArray =
        unwrapMaster(hkdf(recovery, "harbor0/e2ee/v1/recovery", 32, fromB64url(e.salt)), e.iv, e.ciphertext)

    private fun deviceKek(local: ByteArray, server: ByteArray) = hkdf(local + server, "harbor0/e2ee/v1/device")
    fun wrapForDevice(master: ByteArray, local: ByteArray, server: ByteArray, rng: (Int) -> ByteArray = ::random) = wrapMaster(deviceKek(local, server), master, rng(12))
    fun unwrapForDevice(e: DeviceEnvelope, local: ByteArray, server: ByteArray) = unwrapMaster(deviceKek(local, server), e.iv, e.ciphertext)

    fun accountKeys(master: ByteArray): AccountKeys {
        val names = hkdf(master, "harbor0/e2ee/v1/names", 64)
        return AccountKeys(master, aesKey(hkdf(master, "harbor0/e2ee/v1/file-keys")), aesKey(names.copyOfRange(0, 32)),
            SecretKeySpec(names.copyOfRange(32, 64), "HmacSHA256"), b64url(hkdf(master, "harbor0/e2ee/v1/proof")))
    }

    fun isEncryptedName(name: String) = encryptedName.matches(name)
    fun encryptName(keys: AccountKeys, name: String): String {
        val plain = Normalizer.normalize(name, Normalizer.Form.NFC).toByteArray()
        if (plain.size > MAX_NAME_BYTES) throw E2eeException("NAME_TOO_LONG", "This name is too long for an encrypted account.")
        val iv = hmac(keys.nameMac, plain).copyOf(12)
        return NAME_PREFIX + b64url(iv + seal(keys.nameEnc, iv, plain, NAME_AAD))
    }
    fun decryptName(keys: AccountKeys, token: String): String {
        val bytes = fromB64url(token.removePrefix(NAME_PREFIX))
        if (bytes.size < 12 + TAG) throw E2eeException("CORRUPT", "The name is damaged.")
        val plain = open(keys.nameEnc, bytes.copyOf(12), bytes, NAME_AAD, 12, bytes.size - 12)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plain)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) { throw E2eeException("CORRUPT", "The name is damaged.") }
    }

    internal fun chunkCount(plain: Long, chunk: Int = CHUNK_SIZE) = maxOf(1L, (plain + chunk - 1) / chunk)
    /** Stored size of a file of `plain` bytes. */
    fun encryptedSize(plain: Long, chunk: Int = CHUNK_SIZE) = HEADER_SIZE + plain + TAG * chunkCount(plain, chunk)
    /** Original size of a stored encrypted file. */
    fun plainSize(stored: Long, chunk: Int = CHUNK_SIZE): Long {
        val body = stored - HEADER_SIZE
        if (body < TAG) return 0
        return body - TAG * ((body + chunk + TAG - 1) / (chunk + TAG))
    }
}

/**
 * One file version's key, ready to encrypt or decrypt its chunks. Unlike the TypeScript class,
 * chunk counts follow the header's chunk size; every app writes 4 MiB chunks, so they agree.
 */
class FileCipher private constructor(val header: ByteArray, private val key: SecretKey, private val prefix: ByteArray, val chunkSize: Int) {
    companion object {
        /** A new random key for a new version. */
        fun create(keys: AccountKeys, rng: (Int) -> ByteArray = E2ee::random): FileCipher {
            val content = rng(32)
            val head = ByteArray(E2ee.HEADER_SIZE)
            E2ee.MAGIC.copyInto(head); head[4] = 1
            ByteBuffer.wrap(head).putInt(8, E2ee.CHUNK_SIZE)
            rng(8).copyInto(head, 12)
            val wrapIv = rng(12); wrapIv.copyInto(head, 20)
            E2ee.seal(keys.fileWrap, wrapIv, content, head.copyOf(20)).copyInto(head, 32)
            return FileCipher(head, E2ee.aesKey(content), head.copyOfRange(12, 20), E2ee.CHUNK_SIZE)
        }
        /** Reads the key from a stored file's header (its first 80 bytes, or more). */
        fun open(keys: AccountKeys, header: ByteArray): FileCipher {
            if (header.size < E2ee.HEADER_SIZE || E2ee.MAGIC.indices.any { header[it] != E2ee.MAGIC[it] } || header[4].toInt() != 1)
                throw E2eeException("CORRUPT", "This file isn’t encrypted the way harbor0 expects.")
            val head = header.copyOf(E2ee.HEADER_SIZE)
            val chunk = ByteBuffer.wrap(head).getInt(8).toLong() and 0xffffffffL
            if (chunk < 4096 || chunk > 64L * 1024 * 1024) throw E2eeException("CORRUPT", "This file’s header is damaged.")
            val content = E2ee.open(keys.fileWrap, head.copyOfRange(20, 32), head.copyOfRange(32, 80), head.copyOf(20))
            return FileCipher(head, E2ee.aesKey(content), head.copyOfRange(12, 20), chunk.toInt())
        }
        /** Decrypts a whole stored file from `input` (header first) into `output`; returns the plaintext size. */
        fun decrypt(keys: AccountKeys, input: InputStream, storedSize: Long, output: OutputStream): Long =
            DecryptingOutputStream(keys, storedSize, output).use { sink -> input.copyTo(sink, 1 shl 16); sink.finish() }
    }
    private val span get() = chunkSize.toLong() + E2ee.TAG
    fun chunkCount(plainTotal: Long) = E2ee.chunkCount(plainTotal, chunkSize)
    fun storedSize(plainTotal: Long) = E2ee.encryptedSize(plainTotal, chunkSize)
    fun plainSize(stored: Long) = E2ee.plainSize(stored, chunkSize)
    /** Plaintext bytes in chunk `index` of a `plainTotal`-byte file. */
    fun chunkPlainLength(plainTotal: Long, index: Long) = minOf(chunkSize.toLong(), plainTotal - index * chunkSize).toInt()
    private fun nonce(index: Long) = ByteArray(12).also { prefix.copyInto(it); ByteBuffer.wrap(it).putInt(8, index.toInt()) }
    private fun aad(last: Boolean) = header.copyOf(21).also { it[20] = if (last) 1 else 0 }
    fun encryptChunk(index: Long, plain: ByteArray, last: Boolean, offset: Int = 0, length: Int = plain.size - offset) =
        E2ee.seal(key, nonce(index), plain, aad(last), offset, length)
    fun decryptChunk(index: Long, cipher: ByteArray, last: Boolean, offset: Int = 0, length: Int = cipher.size - offset): ByteArray = try {
        E2ee.open(key, nonce(index), cipher, aad(last), offset, length)
    } catch (e: E2eeException) { throw E2eeException("CORRUPT", "Part of this file is damaged or was changed.") }

    /** Chunks `first..last` and their stored bytes [start, end) holding plaintext [begin, end). */
    data class CipherRange(val first: Long, val last: Long, val start: Long, val end: Long)
    fun cipherRange(plainTotal: Long, begin: Long, end: Long): CipherRange {
        val first = begin / chunkSize
        val last = maxOf(first, (end + chunkSize - 1) / chunkSize - 1)
        return CipherRange(first, last, E2ee.HEADER_SIZE + first * span, minOf(storedSize(plainTotal), E2ee.HEADER_SIZE + (last + 1) * span))
    }
    /** Decrypts stored bytes read with [cipherRange], returning plaintext [begin, end). */
    fun decryptRange(plainTotal: Long, begin: Long, end: Long, stored: ByteArray): ByteArray {
        val range = cipherRange(plainTotal, begin, end)
        val final = chunkCount(plainTotal) - 1
        val out = ByteArray((end - begin).toInt())
        var at = 0
        var written = 0
        for (i in range.first..range.last) {
            val length = chunkPlainLength(plainTotal, i) + E2ee.TAG
            if (at + length > stored.size) throw E2eeException("CORRUPT", "Part of this file is damaged or was changed.")
            val plain = decryptChunk(i, stored, i == final, at, length)
            at += length
            val chunkBegin = i * chunkSize
            val from = maxOf(begin, chunkBegin) - chunkBegin
            val to = minOf(end, chunkBegin + plain.size) - chunkBegin
            if (to > from) { plain.copyInto(out, written, from.toInt(), to.toInt()); written += (to - from).toInt() }
        }
        return if (written == out.size) out else out.copyOf(written)
    }
    /** The plaintext [begin, end) that stored bytes [start, end) are sealed from, if any. */
    fun plainRangeFor(plainTotal: Long, start: Long, end: Long): Pair<Long, Long>? {
        if (end <= E2ee.HEADER_SIZE) return null
        val first = (maxOf(start, E2ee.HEADER_SIZE.toLong()) - E2ee.HEADER_SIZE) / span
        val last = (end - 1 - E2ee.HEADER_SIZE) / span
        return first * chunkSize to minOf(plainTotal, (last + 1) * chunkSize)
    }
    /**
     * Stored bytes [start, end) of the encrypted file, reading plaintext [begin, end) through `read`.
     * Only the chunks the range touches are sealed; the same header always gives the same bytes.
     */
    fun encryptedRange(plainTotal: Long, start: Long, end: Long, read: (Long, Long) -> ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(maxOf(0L, end - start).toInt())
        if (start < E2ee.HEADER_SIZE) out.write(header, start.toInt(), (minOf(end, E2ee.HEADER_SIZE.toLong()) - start).toInt())
        val range = plainRangeFor(plainTotal, start, end)
        if (range != null) {
            val first = (maxOf(start, E2ee.HEADER_SIZE.toLong()) - E2ee.HEADER_SIZE) / span
            val last = (end - 1 - E2ee.HEADER_SIZE) / span
            val final = chunkCount(plainTotal) - 1
            val plain = read(range.first, range.second)
            if (plain.size.toLong() != range.second - range.first) throw FileChangedException("File changed during upload. Retry to preserve the latest content.")
            var i = first
            while (i <= last && i <= final) {
                val from = ((i - first) * chunkSize).toInt()
                val sealed = encryptChunk(i, plain, i == final, from, minOf(plain.size, from + chunkSize) - from)
                val chunkStart = E2ee.HEADER_SIZE + i * span
                val a = maxOf(0L, start - chunkStart).toInt()
                val b = minOf(sealed.size.toLong(), end - chunkStart).toInt()
                if (b > a) out.write(sealed, a, b - a)
                i++
            }
        }
        return out.toByteArray()
    }
    /** Encrypts a whole `plainTotal`-byte file from `input` into `output`, header first. */
    fun encrypt(input: InputStream, plainTotal: Long, output: OutputStream) { EncryptingInputStream(this, plainTotal, input).use { it.copyTo(output, 1 shl 16) } }
}

/**
 * The stored bytes of an encrypted file, sealed chunk by chunk as they are read from the plaintext
 * `source`, which must hold exactly `plainTotal` bytes. For uploads from streams that can't seek,
 * such as content URIs: a resumed upload reads it again and gets the same bytes.
 */
class EncryptingInputStream(private val cipher: FileCipher, private val plainTotal: Long, private val source: InputStream): InputStream() {
    private var buffer: ByteArray = cipher.header
    private var position = 0
    private var next = 0L
    private val chunks = cipher.chunkCount(plainTotal)
    /** Stored bytes this stream yields in all. */
    val storedSize = cipher.storedSize(plainTotal)
    private fun fill(): Boolean {
        if (next >= chunks) {
            if (source.read() != -1) throw IOException("File changed during upload. Retry to preserve the latest content.")
            return false
        }
        val length = cipher.chunkPlainLength(plainTotal, next)
        val plain = ByteArray(length)
        var at = 0
        while (at < length) {
            val n = source.read(plain, at, length - at)
            if (n < 0) throw IOException("File changed during upload. Retry to preserve the latest content.")
            at += n
        }
        buffer = cipher.encryptChunk(next, plain, next == chunks - 1); position = 0; next++
        return true
    }
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (position >= buffer.size) if (!fill()) return -1
        val n = minOf(len, buffer.size - position)
        buffer.copyInto(b, off, position, position + n); position += n
        return n
    }
    override fun available() = buffer.size - position
    override fun close() = source.close()
}

/**
 * Takes a stored encrypted file's bytes, header first, and writes its plaintext to `sink`. Every
 * chunk is checked before any of it is written; [finish] fails unless the whole file arrived.
 */
class DecryptingOutputStream(private val keys: AccountKeys, private val storedSize: Long, private val sink: OutputStream): OutputStream() {
    private var cipher: FileCipher? = null
    private var plainTotal = 0L
    private var chunks = 0L
    private var buffer = ByteArray(E2ee.HEADER_SIZE)
    private var filled = 0
    private var index = 0L
    /** Plaintext bytes written so far. */
    var written = 0L; private set
    private fun need(): Int = cipher?.let { if (index >= chunks) 0 else it.chunkPlainLength(plainTotal, index) + E2ee.TAG } ?: E2ee.HEADER_SIZE
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
    override fun write(b: ByteArray, off: Int, len: Int) {
        var at = off
        val end = off + len
        while (at < end) {
            val need = need()
            if (need == 0) throw E2eeException("CORRUPT", "The downloaded file is longer than expected.")
            if (buffer.size < need) buffer = buffer.copyOf(need)
            val n = minOf(need - filled, end - at)
            b.copyInto(buffer, filled, at, at + n); filled += n; at += n
            if (filled < need) continue
            val current = cipher
            if (current == null) {
                val opened = FileCipher.open(keys, buffer.copyOf(E2ee.HEADER_SIZE))
                cipher = opened; plainTotal = opened.plainSize(storedSize); chunks = opened.chunkCount(plainTotal)
                if (opened.storedSize(plainTotal) != storedSize) throw E2eeException("CORRUPT", "This file’s size doesn’t match its encryption.")
            } else {
                val plain = current.decryptChunk(index, buffer, index == chunks - 1, 0, need)
                sink.write(plain); written += plain.size; index++
            }
            filled = 0
        }
    }
    /** Checks the whole file was decrypted; returns its plaintext size. */
    fun finish(): Long {
        if (cipher == null || index < chunks || filled != 0) throw E2eeException("CORRUPT", "The downloaded file is incomplete.")
        sink.flush()
        return written
    }
    override fun flush() = sink.flush()
    override fun close() = sink.close()
}

/**
 * Plaintext of a stored encrypted file by range, for previews and media: only the chunks a read
 * touches are fetched and decrypted. `fetch(start, end)` returns stored bytes [start, end).
 */
class EncryptedReader private constructor(val size: Long, private val cipher: FileCipher, private val head: ByteArray,
    private val fetch: suspend (Long, Long) -> ByteArray) {
    companion object {
        suspend fun open(keys: AccountKeys, storedSize: Long, fetch: suspend (Long, Long) -> ByteArray): EncryptedReader {
            // The header comes with the first chunk: reads mostly start there, and a one-chunk file then needs a single request.
            val head = fetch(0, minOf(storedSize, E2ee.encryptedSize(E2ee.CHUNK_SIZE.toLong())))
            val cipher = FileCipher.open(keys, head)
            return EncryptedReader(cipher.plainSize(storedSize), cipher, head, fetch)
        }
    }
    suspend fun read(begin: Long, end: Long): ByteArray {
        val stop = minOf(end, size)
        if (stop <= begin) return ByteArray(0)
        val r = cipher.cipherRange(size, begin, stop)
        val stored = if (r.end <= head.size) head.copyOfRange(r.start.toInt(), r.end.toInt()) else fetch(r.start, r.end)
        return cipher.decryptRange(size, begin, stop, stored)
    }
}

/** Streaming SHA-256 of stored (encrypted) bytes, which the server checks uploads against. */
class Sha256Stream {
    private val digest = MessageDigest.getInstance("SHA-256")
    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) = digest.update(bytes, offset, length)
    fun hex(): String = digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Stored bytes [start, end) of an encrypted file, handed to [out] with their offset a chunk at a time, so a multipart
 * upload part never holds more than one chunk of plaintext. Pieces end at chunk boundaries and at [end], so the same
 * range always arrives in the same pieces; see [StoredHash].
 */
fun FileCipher.sealRange(plainTotal: Long, start: Long, end: Long, read: (Long, Long) -> ByteArray, out: (Long, ByteArray) -> Unit) {
    val span = chunkSize.toLong() + E2ee.TAG
    var at = start
    while (at < end) {
        val next = if (at < E2ee.HEADER_SIZE) E2ee.HEADER_SIZE.toLong() else E2ee.HEADER_SIZE + ((at - E2ee.HEADER_SIZE) / span + 1) * span
        val stop = minOf(end, next)
        out(at, encryptedRange(plainTotal, at, stop, read))
        at = stop
    }
}

/**
 * SHA-256 of an encrypted upload's stored bytes, which the server checks it against, fed in order as they are sealed.
 * A piece sent again (a retried part) is counted once: only the piece starting where the hash left off is added.
 */
class StoredHash {
    private val digest = Sha256Stream()
    /** Stored bytes hashed so far. */
    var length = 0L; private set
    fun offer(offset: Long, bytes: ByteArray) { if (offset == length) { digest.update(bytes); length += bytes.size } }
    fun hex() = digest.hex()
}

/**
 * Plaintext ranges of a file that can only be read from the start, such as a content URI: reads moving forward skip
 * ahead; a read going back opens the file again.
 */
class PlainReader(private val open: () -> InputStream): java.io.Closeable {
    private var stream: InputStream? = null
    private var position = 0L
    fun read(begin: Long, end: Long): ByteArray {
        var input = stream
        if (input == null || begin < position) { input?.close(); input = open(); stream = input; position = 0 }
        while (position < begin) {
            val skipped = input.skip(begin - position)
            if (skipped <= 0) { if (input.read() < 0) throw FileChangedException("File changed during upload. Retry to preserve the latest content."); position++ }
            else position += skipped
        }
        val out = ByteArray((end - begin).toInt())
        var at = 0
        while (at < out.size) {
            val n = input.read(out, at, out.size - at)
            if (n < 0) throw FileChangedException("File changed during upload. Retry to preserve the latest content.")
            at += n
        }
        position = end
        return out
    }
    override fun close() { stream?.close(); stream = null }
}
