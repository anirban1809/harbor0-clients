package app.harbor0.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class E2eeTransportTest {
    private val keys = E2ee.accountKeys(ByteArray(32) { it.toByte() })
    private class Fake(val answer: (String, String, JsonObject?) -> JsonObject): E2eeApi {
        val calls = mutableListOf<Triple<String, String, JsonObject?>>()
        override suspend fun call(path: String, method: String, body: JsonObject?): JsonObject { calls += Triple(path, method, body); return answer(path, method, body) }
    }
    private fun obj(text: String) = harborJson.parseToJsonElement(text).jsonObject

    @Test fun encryptsOwnNamesAndDecryptsAnswers() = runBlocking {
        val token = E2ee.encryptName(keys, "Photo.JPG")
        val inner = Fake { path, _, _ ->
            when {
                path.startsWith("/v1/drive/items/") -> obj("""{"item":{"id":"f1","ownerUserId":"u1"}}""")
                else -> obj("""{"item":{"id":"i1","ownerUserId":"u1","name":"$token","normalizedName":"${token.lowercase()}","encrypted":true,
                    "sizeBytes":${E2ee.encryptedSize(1000)},"mimeType":"application/octet-stream","path":"/a/$token"}}""")
            }
        }
        val transport = E2eeTransport(inner, { E2eeView("u1", true, false, keys) }, { _, _, _ -> })
        val result = transport.call("/v1/drive/folders", "POST", obj("""{"name":"Photo.JPG","parentId":"f1"}"""))
        assertEquals(token, inner.calls.last().third!!["name"]!!.jsonPrimitive.content)
        val item = result["item"]!!.jsonObject
        assertEquals("Photo.JPG", item["name"]!!.jsonPrimitive.content)
        assertEquals("photo.jpg", item["normalizedName"]!!.jsonPrimitive.content)
        assertEquals(1000L, item["sizeBytes"]!!.jsonPrimitive.long)
        assertEquals("image/jpeg", item["mimeType"]!!.jsonPrimitive.content)
        assertEquals("/a/Photo.JPG", item["path"]!!.jsonPrimitive.content)
        // A folder someone shared with the account keeps its plaintext name.
        val shared = E2eeTransport(Fake { path, _, _ -> if (path.startsWith("/v1/drive/items/")) obj("""{"item":{"ownerUserId":"u2"}}""") else obj("{}") },
            { E2eeView("u1", true, false, keys) }, { _, _, _ -> })
        val (_, body) = shared.outgoing("/v1/drive/folders", "POST", obj("""{"name":"x","parentId":"f9"}"""))
        assertEquals("x", body!!["name"]!!.jsonPrimitive.content)
    }
    @Test fun mapsBatchesBackupPathsOperationsAndLookups() = runBlocking {
        val transport = E2eeTransport(Fake { _, _, _ -> obj("""{"item":{"ownerUserId":"u1"}}""") }, { E2eeView("u1", true, false, keys) }, { _, _, _ -> })
        val sealed = { s: String -> E2ee.encryptName(keys, s) }
        val (_, batch) = transport.outgoing("/v1/uploads/batch", "POST", obj("""{"uploads":[{"name":"a","encrypted":true},{"name":"b"}]}"""))
        assertEquals(listOf(sealed("a"), "b"), batch!!["uploads"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        val (_, files) = transport.outgoing("/v1/backups/r/runs/x/files/batch", "POST", obj("""{"files":[{"relativePath":"dir/f.txt"}]}"""))
        assertEquals(sealed("dir") + "/" + sealed("f.txt"), files!!["files"]!!.jsonArray[0].jsonObject["relativePath"]!!.jsonPrimitive.content)
        val (_, ops) = transport.outgoing("/v1/sync/operations", "POST", obj("""{"operations":[{"entityId":"e","payload":{"name":"n"}}]}"""))
        assertEquals(sealed("n"), ops!!["operations"]!!.jsonArray[0].jsonObject["payload"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val (path, _) = transport.outgoing("/v1/drive/folders/root/lookup?name=My%20file.txt&x=1", "GET", null)
        assertEquals("/v1/drive/folders/root/lookup?x=1&name=${sealed("My file.txt")}", path)
        val (_, upload) = transport.outgoing("/v1/uploads", "POST", obj("""{"name":"plain","encrypted":false}"""))
        assertEquals("plain", upload!!["name"]!!.jsonPrimitive.content)
    }
    @Test fun lockedRefusesNamesAndStrictRefusesFiles() = runBlocking {
        val transport = E2eeTransport(Fake { _, _, _ -> obj("{}") }, { E2eeView("u1", true, false, null) }, { _, _, _ -> }, strict = true)
        assertEquals("E2EE_LOCKED", (runCatching { transport.call("/v1/drive/folders/root/children", "GET", null) }.exceptionOrNull() as ApiException).code)
        val loose = E2eeTransport(Fake { _, _, _ -> obj("{}") }, { E2eeView("u1", true, false, null) }, { _, _, _ -> })
        assertEquals(423, (runCatching { loose.call("/v1/drive/folders", "POST", obj("""{"name":"a"}""")) }.exceptionOrNull() as ApiException).status)
    }
    @Test fun searchFiltersDecryptedNames() = runBlocking {
        val inner = Fake { path, _, _ ->
            assertFalse(path.contains("q="))
            val items = listOf("Report.pdf", "photo.png", "report-2.txt").map { n ->
                buildJsonObject { put("id", n); put("ownerUserId", "u1"); put("name", E2ee.encryptName(keys, n)); put("normalizedName", E2ee.encryptName(keys, n).lowercase()) }
            }
            buildJsonObject { put("items", JsonArray(items)); put("nextCursor", JsonNull) }
        }
        val transport = E2eeTransport(inner, { E2eeView("u1", true, false, keys) }, { _, _, _ -> })
        val result = transport.call("/v1/search?q=REPORT&limit=10", "GET", null)
        assertEquals(listOf("Report.pdf", "report-2.txt"), result["items"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals("/v1/search?limit=10", inner.calls.single().first)
    }
    @Test fun usersMeFollowsAccount() = runBlocking {
        var seen: Triple<String, Boolean, Boolean>? = null
        val transport = E2eeTransport(Fake { _, _, _ -> obj("""{"user":{"id":"u9","e2ee":{"enabledAt":"x","decryptingSince":"y"}}}""") },
            { E2eeView() }, { u, e, d -> seen = Triple(u, e, d) })
        transport.call("/v1/users/me", "GET", null)
        assertEquals(Triple("u9", true, true), seen)
        assertEquals("image/heic", guessMime("IMG_1.HEIC")); assertEquals("application/octet-stream", guessMime("noext"))
    }

    @Test fun vaultUnlocksKeepsRestoresAndHandlesRemovedDevice() = runBlocking {
        val master = E2ee.newMasterKey()
        val proof = E2ee.accountKeys(master).proof
        val passphrase = E2ee.wrapWithPassphrase(master, "open sesame", Argon2Cost(19_456, 2, 1))
        val recovery = E2ee.newRecoveryKey()
        val recoveryEnvelope = E2ee.wrapWithRecovery(master, recovery)
        val secrets = mutableMapOf<String, String>()
        var newPassphrase: JsonObject? = null
        val server = Fake { path, method, body ->
            when {
                path == "/v1/e2ee" -> buildJsonObject { put("e2ee", buildJsonObject {
                    put("passphrase", harborJson.encodeToJsonElement(PassphraseEnvelope.serializer(), passphrase))
                    put("recovery", harborJson.encodeToJsonElement(RecoveryEnvelope.serializer(), recoveryEnvelope)) }) }
                path == "/v1/e2ee/devices" && method == "POST" -> {
                    assertEquals(proof, body!!["proof"]!!.jsonPrimitive.content)
                    val id = "d${secrets.size + 1}"; secrets[id] = body["serverSecret"]!!.jsonPrimitive.content
                    buildJsonObject { put("id", id) }
                }
                path.endsWith("/secret") -> secrets[path.split('/')[4]]?.let { s -> buildJsonObject { put("serverSecret", s) } }
                    ?: throw ApiException(404, "This device was removed.", "E2EE_DEVICE_REMOVED")
                path == "/v1/e2ee/passphrase" -> { newPassphrase = body; buildJsonObject {} }
                method == "DELETE" -> { secrets.remove(path.split('/')[4]); buildJsonObject {} }
                else -> error(path)
            }
        }
        val store = MemoryE2eeCopies()
        val vault = E2eeVault(store)
        vault.setAccount("u1", true, false)
        assertTrue(vault.locked)
        assertEquals(E2eeRestore.NO_COPY, vault.restore(server))
        assertThrows(E2eeException::class.java) { runBlocking { vault.unlockWithPassphrase(server, "wrong") } }
        vault.unlockWithPassphrase(server, "open sesame")
        assertArrayEquals(master, vault.readKeys()!!.master)
        assertEquals("d1", vault.deviceRecord)
        // Next launch: a new vault opens the saved copy with the server's half.
        val next = E2eeVault(store).apply { setAccount("u1", true, false) }
        assertEquals(E2eeRestore.UNLOCKED, next.restore(server))
        assertArrayEquals(master, next.uploadKeys()!!.master)
        // Removed in the web app: the copy is dropped and the passphrase is needed again.
        secrets.clear()
        val removed = E2eeVault(store).apply { setAccount("u1", true, false) }
        assertEquals(E2eeRestore.REMOVED, removed.restore(server))
        assertNull(store.load("u1"))
        // Recovery sets a new passphrase that opens the same key; not remembered keeps nothing on disk.
        removed.unlockWithRecovery(server, E2ee.parseRecoveryKey(E2ee.formatRecoveryKey(recovery).lowercase())!!, "new one", remember = false)
        assertNull(store.load("u1"))
        val changed = harborJson.decodeFromJsonElement(PassphraseEnvelope.serializer(), newPassphrase!!["passphrase"]!!)
        assertArrayEquals(master, E2ee.unwrapWithPassphrase(changed, "new one"))
        assertEquals(proof, newPassphrase!!["proof"]!!.jsonPrimitive.content)
        removed.lock(server)
        assertTrue(removed.locked); assertTrue(secrets.isEmpty())
        // Turning encryption off forgets the copy; another account starts locked.
        vault.setAccount("u1", false, false)
        assertNull(vault.readKeys()); assertNull(store.load("u1"))
        next.setAccount("u2", true, false); assertTrue(next.locked)
    }
}
