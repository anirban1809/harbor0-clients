package app.harbor0.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Encrypted public links against vectors made by packages/api-client/src/e2ee.ts (apps/android/e2ee-link-vectors.ts). */
class E2eeLinksTest {
    private val v = harborJson.parseToJsonElement(javaClass.classLoader!!.getResource("e2ee-link-vectors.json")!!.readText()).jsonObject
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun JsonElement.obj(key: String) = jsonObject[key]!!.jsonObject
    private fun JsonElement.str(key: String) = jsonObject[key]!!.jsonPrimitive.content
    private fun queued(values: JsonArray): (Int) -> ByteArray {
        val queue = ArrayDeque(values.map { hex(it.jsonPrimitive.content) })
        return { n -> queue.removeFirst().also { assertEquals(n, it.size) } }
    }
    private val keys = E2ee.accountKeys(hex(v.str("master")))
    private val secret = hex(v.str("secret"))
    private val link = E2eeLinks.keys(secret)

    @Test fun linkKeysAndSecretWrapMatch() {
        assertEquals(v.obj("linkKeys").str("names"), hex(E2ee.hkdf(secret, "harbor0/e2ee/v1/link-names")))
        assertEquals(v.obj("linkKeys").str("files"), hex(E2ee.hkdf(secret, "harbor0/e2ee/v1/link-files")))
        val wrap = v.obj("secretWrap")
        assertEquals(wrap.str("wrap"), E2eeLinks.wrapSecret(keys, secret, queued(wrap["random"]!!.jsonArray)))
        assertArrayEquals(secret, E2eeLinks.unwrapSecret(keys, wrap.str("wrap")))
        // Another account's key can't open it.
        val other = E2ee.accountKeys(ByteArray(32) { 7 })
        assertEquals("WRONG_KEY", assertThrows(E2eeException::class.java) { E2eeLinks.unwrapSecret(other, wrap.str("wrap")) }.code)
    }
    @Test fun sharedUrlCarriesTheSecretInTheFragment() {
        val url = v.obj("url")
        assertEquals(url.str("shared"), E2eeLinks.url(url.str("server"), secret))
        // The share site splits the fragment at the first dot: the token, then the secret.
        val fragment = url.str("shared").substringAfter('#')
        assertEquals(url.str("secret"), fragment.substringAfter('.'))
        assertArrayEquals(secret, E2ee.fromB64url(fragment.substringAfter('.')))
    }
    @Test fun namesMatchAndAreBoundToTheirItem() {
        for (entry in v["names"]!!.jsonArray) {
            val itemId = entry.str("itemId")
            val wrap = entry.str("nameWrap")
            assertEquals(wrap, E2eeLinks.wrapName(link, itemId, entry.str("name"), queued(entry.jsonObject["random"]!!.jsonArray)))
            assertEquals(java.text.Normalizer.normalize(entry.str("name"), java.text.Normalizer.Form.NFC), E2eeLinks.unwrapName(link, itemId, wrap))
            assertThrows(E2eeException::class.java) { E2eeLinks.unwrapName(link, "$itemId-moved", wrap) }
        }
    }
    @Test fun fileKeyWrapMatchesAndOpensForVisitors() {
        val f = v.obj("file")
        val header = hex(f.str("header"))
        assertEquals(hex(FileCipher.create(keys, queued(f["random"]!!.jsonArray)).header), f.str("header"))
        val wrap = E2eeLinks.wrapFileKey(keys, link, header, queued(f["wrapRandom"]!!.jsonArray))
        assertEquals(f.str("keyWrap"), wrap.keyWrap)
        assertEquals(f.str("head"), wrap.head)
        // The visitor's content key is the one the account sealed the file with.
        assertArrayEquals(E2eeLinks.contentKey(keys, header), E2eeLinks.openFileKey(link, wrap))
        val stored = hex(f.str("stored"))
        val out = java.io.ByteArrayOutputStream()
        FileCipher.decrypt(keys, stored.inputStream(), stored.size.toLong(), out)
        assertEquals(f.str("plain"), hex(out.toByteArray()))
        // A wrap is bound to its version's header.
        val otherHead = E2ee.b64url(FileCipher.create(keys).header.copyOf(E2eeLinks.HEAD_SIZE))
        assertThrows(E2eeException::class.java) { E2eeLinks.openFileKey(link, wrap.copy(head = otherHead)) }
        assertEquals("CORRUPT", assertThrows(E2eeException::class.java) { E2eeLinks.contentKey(keys, header.copyOf(40)) }.code)
    }

    /** The server's link routes, in memory: a folder with a subfolder, an encrypted file, one still uploading and one current. */
    private class LinkServer(val header: String) {
        val saved = mutableListOf<JsonObject>()
        val scans = mutableListOf<JsonObject>()
        private fun item(id: String, type: String, name: String, current: Boolean = false, encrypted: Boolean = false, keyHeader: String? = null) = buildJsonObject {
            put("id", id); put("type", type); put("name", name); put("revision", 3); put("versionId", if (type == "FILE") "v-$id" else null)
            put("current", current); put("encrypted", encrypted); put("keyHeader", keyHeader)
        }
        val api = E2eeApi { path, method, body ->
            when {
                path == "/v1/links/link-1/e2ee/scan" && method == "POST" -> {
                    scans += body!!
                    val folders = body["folders"]!!.jsonArray.map { it.jsonObject }
                    buildJsonObject {
                        put("root", item("root", "FOLDER", "Trip"))
                        put("folders", JsonArray(folders.map { f ->
                            when (f["folderId"]?.jsonPrimitive?.content) {
                                null -> if (f["cursor"] == null) buildJsonObject {
                                    put("folderId", "root"); put("items", JsonArray(listOf(item("sub", "FOLDER", "Day 1"), item("a", "FILE", "a.jpg", encrypted = true, keyHeader = header))))
                                    put("nextCursor", "page-2")
                                } else error("cursor without a folder")
                                "root" -> buildJsonObject {
                                    assertEquals("page-2", f["cursor"]!!.jsonPrimitive.content)
                                    put("folderId", "root"); put("items", JsonArray(listOf(item("b", "FILE", "uploading.mov", encrypted = true)))); put("nextCursor", null)
                                }
                                "sub" -> buildJsonObject {
                                    put("folderId", "sub"); put("items", JsonArray(listOf(item("c", "FILE", "notes.txt", current = true), item("d", "FILE", "plain.txt")))); put("nextCursor", null)
                                }
                                else -> error("unknown folder")
                            }
                        }))
                    }
                }
                path == "/v1/links/link-1/e2ee/wraps" && method == "PUT" -> {
                    val wraps = body!!["wraps"]!!.jsonArray.map { it.jsonObject }
                    saved += wraps
                    buildJsonObject { put("saved", wraps.size); put("stale", JsonArray(emptyList())) }
                }
                else -> error("Unexpected $method $path")
            }
        }
    }
    @Test fun wrapsEverythingUnderAFolderLinkThatIsNotCurrent() = runBlocking {
        val cipher = FileCipher.create(keys)
        val server = LinkServer(E2ee.b64url(cipher.header))
        val secret = E2eeLinks.newSecret()
        val link = PublicLink("link-1", "root", "FOLDER", "Trip", "https://harbor0content.online/s#tok", e2ee = LinkE2ee(E2eeLinks.wrapSecret(keys, secret)))
        val seen = mutableListOf<WrapProgress>()
        val progress = LinkWraps(server.api).sync(keys, link) { seen += it }
        assertEquals(WrapProgress(scanned = 6, wrapped = 4, waiting = 1, done = true), progress)
        assertTrue(seen.last().done)
        val linkKeys = E2eeLinks.keys(secret)
        val byId = server.saved.associateBy { it["itemId"]!!.jsonPrimitive.content }
        assertEquals(setOf("root", "sub", "a", "d"), byId.keys)
        assertEquals("Trip", E2eeLinks.unwrapName(linkKeys, "root", byId["root"]!!["nameWrap"]!!.jsonPrimitive.content))
        assertEquals("a.jpg", E2eeLinks.unwrapName(linkKeys, "a", byId["a"]!!["nameWrap"]!!.jsonPrimitive.content))
        assertEquals(3, byId["a"]!!["revision"]!!.jsonPrimitive.int)
        // The encrypted file's key opens for the link; a plain file has no key to hand out.
        val wrap = LinkFileWrap(byId["a"]!!["keyWrap"]!!.jsonPrimitive.content, byId["a"]!!["head"]!!.jsonPrimitive.content)
        assertArrayEquals(E2eeLinks.contentKey(keys, cipher.header), E2eeLinks.openFileKey(linkKeys, wrap))
        assertEquals(JsonNull, byId["d"]!!["keyWrap"])
        assertEquals(JsonNull, byId["d"]!!["head"])
        // First the root alone, then its children and the next page, at most 25 folders a scan.
        assertEquals(listOf(buildJsonObject {}), server.scans[0]["folders"]!!.jsonArray.toList())
        assertTrue(server.scans.all { it["folders"]!!.jsonArray.size <= 25 })
    }
    @Test fun fileLinkWrapsOnlyItselfAndPlainLinksNeedNothing() = runBlocking {
        val server = LinkServer("")
        val link = PublicLink("link-1", "root", "FILE", "Trip", "https://x/s#t", e2ee = LinkE2ee(E2eeLinks.wrapSecret(keys, E2eeLinks.newSecret())))
        val progress = LinkWraps(server.api).sync(keys, link)
        assertEquals(1, progress.scanned)
        assertEquals(listOf(JsonArray(emptyList())), server.scans.map { it["folders"] })
        assertEquals(WrapProgress(done = true), LinkWraps(server.api).sync(keys, link.copy(e2ee = null)))
    }
}
