package app.harbor0.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Checks the Kotlin port against vectors made by packages/api-client/src/e2ee.ts (apps/android/e2ee-vectors.ts). */
class E2eeCryptoTest {
    private val v = harborJson.parseToJsonElement(javaClass.classLoader!!.getResource("e2ee-vectors.json")!!.readText()).jsonObject
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun JsonElement.obj(key: String) = jsonObject[key]!!.jsonObject
    private fun JsonElement.str(key: String) = jsonObject[key]!!.jsonPrimitive.content
    private fun JsonElement.long(key: String) = jsonObject[key]!!.jsonPrimitive.long
    /** Hands out the random values the TypeScript run used, in order. */
    private fun queued(values: JsonArray): (Int) -> ByteArray {
        val queue = ArrayDeque(values.map { hex(it.jsonPrimitive.content) })
        return { n -> queue.removeFirst().also { assertEquals(n, it.size) } }
    }
    private val master = hex(v.str("master"))
    private val keys = E2ee.accountKeys(master)
    private fun content(n: Int, seed: Int) = ByteArray(n) { i -> ((i * 31 + seed + (i ushr 11)) and 255).toByte() }

    @Test fun hkdfSubkeysAndProofMatch() {
        val s = v.obj("subkeys")
        assertEquals(s.str("fileWrap"), hex(E2ee.hkdf(master, "harbor0/e2ee/v1/file-keys")))
        assertEquals(s.str("names"), hex(E2ee.hkdf(master, "harbor0/e2ee/v1/names", 64)))
        assertEquals(s.str("proofRaw"), hex(E2ee.hkdf(master, "harbor0/e2ee/v1/proof")))
        assertEquals(s.str("proof"), keys.proof)
    }
    @Test fun passphraseEnvelopeMatchesBothWays() {
        for ((name, cost) in listOf("cheapPassphrase" to Argon2Cost(19_456, 2, 1), "passphrase" to E2ee.ARGON2)) {
            val p = v.obj(name)
            val envelope = harborJson.decodeFromJsonElement(PassphraseEnvelope.serializer(), p.obj("envelope"))
            val started = System.nanoTime()
            assertArrayEquals(master, E2ee.unwrapWithPassphrase(envelope, p.str("passphrase")))
            println("Argon2id ${cost.memoryKiB} KiB × ${cost.iterations}: ${(System.nanoTime() - started) / 1_000_000} ms")
            assertEquals(envelope, E2ee.wrapWithPassphrase(master, p.str("passphrase"), cost, queued(p["random"]!!.jsonArray)))
            assertEquals(p.obj("envelope"), harborJson.encodeToJsonElement(PassphraseEnvelope.serializer(), envelope))
        }
        val cheap = harborJson.decodeFromJsonElement(PassphraseEnvelope.serializer(), v.obj("cheapPassphrase").obj("envelope"))
        assertEquals("WRONG_KEY", assertThrows(E2eeException::class.java) { E2ee.unwrapWithPassphrase(cheap, "hunter3") }.code)
        // NFKC: the ligature and combining accent of the vector's passphrase are folded before Argon2id.
        val p = v.obj("passphrase")
        val folded = java.text.Normalizer.normalize(p.str("passphrase"), java.text.Normalizer.Form.NFKC)
        assertNotEquals(folded, p.str("passphrase"))
    }
    @Test fun argon2TimingWithRealCost() {
        val salt = ByteArray(16) { it.toByte() }
        E2ee.passphraseKek("warm up", salt, E2ee.ARGON2)
        val runs = 3
        val started = System.nanoTime()
        repeat(runs) { E2ee.passphraseKek("correct horse battery staple", salt, E2ee.ARGON2) }
        println("Argon2id 64 MiB × 3 on the JVM: ${(System.nanoTime() - started) / 1_000_000 / runs} ms per derivation")
    }
    @Test fun recoveryKeyFormatParseAndEnvelope() {
        val r = v.obj("recovery")
        val bytes = hex(r.str("bytes"))
        assertEquals(r.str("text"), E2ee.formatRecoveryKey(bytes))
        assertArrayEquals(bytes, E2ee.parseRecoveryKey(r.str("text")))
        assertArrayEquals(bytes, E2ee.parseRecoveryKey(r.str("sloppy")))
        for (bad in r["invalid"]!!.jsonArray) assertNull(E2ee.parseRecoveryKey(bad.jsonPrimitive.content))
        val envelope = harborJson.decodeFromJsonElement(RecoveryEnvelope.serializer(), r.obj("envelope"))
        assertArrayEquals(master, E2ee.unwrapWithRecovery(envelope, bytes))
        assertEquals(envelope, E2ee.wrapWithRecovery(master, bytes, queued(r["random"]!!.jsonArray)))
        assertThrows(E2eeException::class.java) { E2ee.unwrapWithRecovery(envelope, ByteArray(20)) }
    }
    @Test fun deviceEnvelopeMatches() {
        val d = v.obj("device")
        val envelope = harborJson.decodeFromJsonElement(DeviceEnvelope.serializer(), d.obj("envelope"))
        assertArrayEquals(master, E2ee.unwrapForDevice(envelope, hex(d.str("local")), hex(d.str("server"))))
        assertEquals(envelope, E2ee.wrapForDevice(master, hex(d.str("local")), hex(d.str("server")), queued(d["random"]!!.jsonArray)))
        assertThrows(E2eeException::class.java) { E2ee.unwrapForDevice(envelope, hex(d.str("server")), hex(d.str("local"))) }
    }
    @Test fun namesEncryptDeterministicallyAndDecrypt() {
        for (n in v["names"]!!.jsonArray) {
            val name = n.str("name"); val token = n.str("token")
            assertEquals(token, E2ee.encryptName(keys, name))
            assertEquals(java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC), E2ee.decryptName(keys, token))
            assertTrue(E2ee.isEncryptedName(token))
        }
        assertEquals("NAME_TOO_LONG", assertThrows(E2eeException::class.java) { E2ee.encryptName(keys, "é".repeat(128)) }.code)
        val token = v["names"]!!.jsonArray[0].str("token")
        val tampered = token.dropLast(1) + (if (token.last() == 'A') 'B' else 'A')
        assertThrows(E2eeException::class.java) { E2ee.decryptName(keys, tampered) }
        assertThrows(E2eeException::class.java) { E2ee.decryptName(E2ee.accountKeys(ByteArray(32)), token) }
    }
    @Test fun sizesMatch() {
        for (s in v["sizes"]!!.jsonArray) {
            assertEquals(s.long("stored"), E2ee.encryptedSize(s.long("plain")))
            assertEquals(s.long("back"), E2ee.plainSize(s.long("stored")))
        }
    }
    @Test fun smallFilesSealOpenAndRangesMatch() {
        for (f in v["smallFiles"]!!.jsonArray) {
            val size = f.long("size")
            val plain = content(size.toInt(), f.long("seed").toInt())
            val stored = hex(f.str("stored"))
            val made = FileCipher.create(keys, queued(f.jsonObject["random"]!!.jsonArray))
            assertEquals(f.str("header"), hex(made.header))
            val cipher = FileCipher.open(keys, stored)
            val whole = ByteArrayOutputStream().also { cipher.encrypt(ByteArrayInputStream(plain), size, it) }.toByteArray()
            assertArrayEquals(stored, whole)
            assertArrayEquals(stored, made.encryptedRange(size, 0, stored.size.toLong()) { a, b -> plain.copyOfRange(a.toInt(), b.toInt()) })
            val out = ByteArrayOutputStream()
            assertEquals(size, FileCipher.decrypt(keys, ByteArrayInputStream(stored), stored.size.toLong(), out))
            assertArrayEquals(plain, out.toByteArray())
            for (r in f.jsonObject["ranges"]!!.jsonArray) {
                val range = cipher.cipherRange(size, r.long("begin"), r.long("end"))
                assertEquals(FileCipher.CipherRange(r.long("first"), r.long("last"), r.long("start"), r.long("storedEnd")), range)
                assertArrayEquals(plain.copyOfRange(r.long("begin").toInt(), r.long("end").toInt()),
                    cipher.decryptRange(size, r.long("begin"), r.long("end"), stored.copyOfRange(range.start.toInt(), range.end.toInt())))
            }
            for (p in f.jsonObject["parts"]!!.jsonArray) {
                val start = p.long("start"); val end = p.long("end")
                val expected = (p.jsonObject["plainRange"] as? JsonArray)?.let { it[0].jsonPrimitive.long to it[1].jsonPrimitive.long }
                assertEquals(expected, cipher.plainRangeFor(size, start, end))
                assertArrayEquals(stored.copyOfRange(start.toInt(), end.toInt()), cipher.encryptedRange(size, start, end) { a, b -> plain.copyOfRange(a.toInt(), b.toInt()) })
            }
        }
    }
    @Test fun multiChunkFileMatchesByHash() {
        val f = v.obj("bigFile")
        val size = f.long("size")
        val plain = content(size.toInt(), f.long("seed").toInt())
        assertEquals(f.str("plainSha256"), E2ee.sha256Hex(plain))
        val cipher = FileCipher.create(keys, queued(f["random"]!!.jsonArray))
        assertEquals(f.str("header"), hex(cipher.header))
        val stream = EncryptingInputStream(cipher, size, ByteArrayInputStream(plain))
        assertEquals(f.long("storedSize"), stream.storedSize)
        val stored = stream.readBytes()
        assertEquals(f.long("storedSize"), stored.size.toLong())
        assertEquals(f.str("sha256"), E2ee.sha256Hex(stored))
        // Multipart: each part sealed alone, as a resumed upload re-seals it, hashes as TypeScript's.
        val read = { a: Long, b: Long -> plain.copyOfRange(a.toInt(), b.toInt()) }
        val total = Sha256Stream()
        for (p in f["parts"]!!.jsonArray) {
            val part = cipher.encryptedRange(size, p.long("start"), p.long("end"), read)
            assertEquals(p.str("sha256"), E2ee.sha256Hex(part)); total.update(part)
        }
        assertEquals(f.str("sha256"), total.hex())
        for (r in f["ranges"]!!.jsonArray) assertEquals(r.str("sha256"), E2ee.sha256Hex(cipher.encryptedRange(size, r.long("start"), r.long("end"), read)))
        val w = f.obj("window")
        val range = cipher.cipherRange(size, w.long("begin"), w.long("end"))
        assertEquals(FileCipher.CipherRange(w.long("first"), w.long("last"), w.long("start"), w.long("storedEnd")), range)
        assertEquals(w.str("plain"), hex(cipher.decryptRange(size, w.long("begin"), w.long("end"), stored.copyOfRange(range.start.toInt(), range.end.toInt()))))
        // Streaming decrypt in odd-sized writes, and range reads through the reader.
        val sink = ByteArrayOutputStream()
        val decrypting = DecryptingOutputStream(keys, stored.size.toLong(), sink)
        var at = 0
        while (at < stored.size) { val n = minOf(777_777, stored.size - at); decrypting.write(stored, at, n); at += n }
        assertEquals(size, decrypting.finish())
        assertEquals(f.str("plainSha256"), E2ee.sha256Hex(sink.toByteArray()))
        runBlocking {
            val fetches = mutableListOf<Pair<Long, Long>>()
            val reader = EncryptedReader.open(keys, stored.size.toLong()) { a, b -> fetches += a to b; stored.copyOfRange(a.toInt(), b.toInt()) }
            assertEquals(size, reader.size)
            assertArrayEquals(plain.copyOfRange(10, 20), reader.read(10, 20))
            assertEquals(1, fetches.size)
            assertArrayEquals(plain.copyOfRange(E2ee.CHUNK_SIZE * 2 - 5, size.toInt()), reader.read(E2ee.CHUNK_SIZE * 2L - 5, size + 100))
            assertEquals(0, reader.read(size, size + 1).size)
        }
    }
    @Test fun damagedSwappedOrCutFilesFail() {
        val f = v["smallFiles"]!!.jsonArray[0]
        val stored = hex(f.str("stored"))
        val flipped = stored.copyOf().also { it[200] = (it[200].toInt() xor 1).toByte() }
        assertEquals("CORRUPT", assertThrows(E2eeException::class.java) { FileCipher.decrypt(keys, ByteArrayInputStream(flipped), flipped.size.toLong(), ByteArrayOutputStream()) }.code)
        val cut = stored.copyOf(stored.size - 1)
        assertThrows(E2eeException::class.java) { FileCipher.decrypt(keys, ByteArrayInputStream(cut), cut.size.toLong(), ByteArrayOutputStream()) }
        assertThrows(E2eeException::class.java) { FileCipher.decrypt(keys, ByteArrayInputStream(cut), stored.size.toLong(), ByteArrayOutputStream()) }
        assertThrows(E2eeException::class.java) { FileCipher.open(E2ee.accountKeys(ByteArray(32)), stored) }
        assertThrows(E2eeException::class.java) { FileCipher.open(keys, ByteArray(80)) }
        // A source that ends early or runs long means the file changed while it was read.
        val cipher = FileCipher.open(keys, stored)
        assertThrows(IOException::class.java) { EncryptingInputStream(cipher, 10_000, ByteArrayInputStream(ByteArray(9_999))).readBytes() }
        assertThrows(IOException::class.java) { EncryptingInputStream(cipher, 10_000, ByteArrayInputStream(ByteArray(10_001))).readBytes() }
    }
    @Test fun freshKeysRoundTrip() {
        val fresh = E2ee.accountKeys(E2ee.newMasterKey())
        val cipher = FileCipher.create(fresh)
        val plain = E2ee.random(E2ee.CHUNK_SIZE + 3)
        val stored = EncryptingInputStream(cipher, plain.size.toLong(), ByteArrayInputStream(plain)).readBytes()
        assertEquals(E2ee.encryptedSize(plain.size.toLong()), stored.size.toLong())
        val out = ByteArrayOutputStream()
        FileCipher.decrypt(fresh, ByteArrayInputStream(stored), stored.size.toLong(), out)
        assertArrayEquals(plain, out.toByteArray())
        val recovery = E2ee.newRecoveryKey()
        assertArrayEquals(recovery, E2ee.parseRecoveryKey(E2ee.formatRecoveryKey(recovery)))
    }
}
