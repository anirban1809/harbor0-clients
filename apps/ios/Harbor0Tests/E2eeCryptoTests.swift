import XCTest
import CryptoKit
@testable import Harbor0

/// Checks the Swift port against vectors made by packages/api-client/src/e2ee.ts (apps/android/e2ee-vectors.ts).
final class E2eeCryptoTests: XCTestCase {
    private lazy var v: [String: Any] = {
        let url = Bundle(for: E2eeCryptoTests.self).url(forResource: "e2ee-vectors", withExtension: "json")!
        return try! JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
    }()
    private func hex(_ s: String) -> Data {
        var out = Data(capacity: s.count / 2)
        var index = s.startIndex
        while index < s.endIndex {
            let next = s.index(index, offsetBy: 2)
            out.append(UInt8(s[index..<next], radix: 16)!)
            index = next
        }
        return out
    }
    private func obj(_ o: Any?, _ key: String) -> [String: Any] { (o as! [String: Any])[key] as! [String: Any] }
    private func str(_ o: Any?, _ key: String) -> String { (o as! [String: Any])[key] as! String }
    private func int(_ o: Any?, _ key: String) -> Int64 { ((o as! [String: Any])[key] as! NSNumber).int64Value }
    /// Hands out the random values the TypeScript run used, in order.
    private func queued(_ values: Any?) -> (Int) -> Data {
        var queue = (values as! [String]).map(hex)
        return { n in let next = queue.removeFirst(); XCTAssertEqual(n, next.count); return next }
    }
    private var master: Data { hex(v["master"] as! String) }
    private var keys: AccountKeys { E2ee.accountKeys(master) }
    private func content(_ n: Int, _ seed: Int) -> Data { Data((0..<n).map { i in UInt8(truncatingIfNeeded: i &* 31 &+ seed &+ (i >> 11)) }) }
    private func decode<T: Decodable>(_ value: Any) throws -> T { try JSONDecoder().decode(T.self, from: JSONSerialization.data(withJSONObject: value)) }

    func testHkdfSubkeysAndProof() {
        let s = v["subkeys"]
        XCTAssertEqual(str(s, "fileWrap"), E2ee.hex(E2ee.hkdf(master, info: "harbor0/e2ee/v1/file-keys")))
        XCTAssertEqual(str(s, "names"), E2ee.hex(E2ee.hkdf(master, info: "harbor0/e2ee/v1/names", length: 64)))
        XCTAssertEqual(str(s, "proofRaw"), E2ee.hex(E2ee.hkdf(master, info: "harbor0/e2ee/v1/proof")))
        XCTAssertEqual(str(s, "proof"), keys.proof)
    }

    func testPassphraseEnvelopesBothWays() throws {
        for (name, cost) in [("cheapPassphrase", Argon2Cost(memoryKiB: 19_456, iterations: 2, parallelism: 1)), ("passphrase", E2ee.argon2)] {
            let p = v[name]
            let envelope: PassphraseEnvelope = try decode(obj(p, "envelope"))
            let started = Date()
            XCTAssertEqual(master, try E2ee.unwrapWithPassphrase(envelope, passphrase: str(p, "passphrase")))
            print("Argon2id \(cost.memoryKiB) KiB × \(cost.iterations): \(Int(Date().timeIntervalSince(started) * 1000)) ms")
            XCTAssertEqual(envelope, try E2ee.wrapWithPassphrase(master, passphrase: str(p, "passphrase"), cost: cost, rng: queued((p as! [String: Any])["random"])))
        }
        let cheap: PassphraseEnvelope = try decode(obj(v["cheapPassphrase"], "envelope"))
        XCTAssertThrowsError(try E2ee.unwrapWithPassphrase(cheap, passphrase: "hunter3")) { XCTAssertEqual(($0 as? E2eeError)?.code, "WRONG_KEY") }
        // NFKC: the ligature and combining accent of the vector's passphrase are folded before Argon2id.
        let typed = str(v["passphrase"], "passphrase")
        XCTAssertNotEqual(typed.precomposedStringWithCompatibilityMapping, typed)
    }

    func testRecoveryKeyFormatParseAndEnvelope() throws {
        let r = v["recovery"]
        let bytes = hex(str(r, "bytes"))
        XCTAssertEqual(str(r, "text"), E2ee.formatRecoveryKey(bytes))
        XCTAssertEqual(bytes, E2ee.parseRecoveryKey(str(r, "text")))
        XCTAssertEqual(bytes, E2ee.parseRecoveryKey(str(r, "sloppy")))
        for bad in (r as! [String: Any])["invalid"] as! [String] { XCTAssertNil(E2ee.parseRecoveryKey(bad)) }
        let envelope: RecoveryEnvelope = try decode(obj(r, "envelope"))
        XCTAssertEqual(master, try E2ee.unwrapWithRecovery(envelope, recovery: bytes))
        XCTAssertEqual(envelope, try E2ee.wrapWithRecovery(master, recovery: bytes, rng: queued((r as! [String: Any])["random"])))
        XCTAssertThrowsError(try E2ee.unwrapWithRecovery(envelope, recovery: Data(count: 20)))
    }

    func testDeviceEnvelope() throws {
        let d = v["device"]
        let envelope: DeviceEnvelope = try decode(obj(d, "envelope"))
        let local = hex(str(d, "local")), server = hex(str(d, "server"))
        XCTAssertEqual(master, try E2ee.unwrapForDevice(envelope, local: local, server: server))
        XCTAssertEqual(envelope, try E2ee.wrapForDevice(master, local: local, server: server, rng: queued((d as! [String: Any])["random"])))
        XCTAssertThrowsError(try E2ee.unwrapForDevice(envelope, local: server, server: local))
    }

    func testNamesEncryptDeterministicallyAndDecrypt() throws {
        let names = v["names"] as! [[String: String]]
        for n in names {
            XCTAssertEqual(n["token"], try E2ee.encryptName(keys, n["name"]!))
            XCTAssertEqual(n["name"]!.precomposedStringWithCanonicalMapping, try E2ee.decryptName(keys, n["token"]!))
            XCTAssertTrue(E2ee.isEncryptedName(n["token"]!))
        }
        XCTAssertThrowsError(try E2ee.encryptName(keys, String(repeating: "é", count: 128))) { XCTAssertEqual(($0 as? E2eeError)?.code, "NAME_TOO_LONG") }
        let token = names[0]["token"]!
        let tampered = String(token.dropLast()) + (token.last == "A" ? "B" : "A")
        XCTAssertThrowsError(try E2ee.decryptName(keys, tampered))
        XCTAssertThrowsError(try E2ee.decryptName(E2ee.accountKeys(Data(count: 32)), token))
        XCTAssertFalse(E2ee.isEncryptedName("report.pdf"))
    }

    func testSizes() {
        for s in v["sizes"] as! [[String: NSNumber]] {
            XCTAssertEqual(s["stored"]!.int64Value, E2ee.encryptedSize(s["plain"]!.int64Value))
            XCTAssertEqual(s["back"]!.int64Value, E2ee.plainSize(s["stored"]!.int64Value))
        }
    }

    func testSmallFilesSealOpenAndRanges() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        for f in v["smallFiles"] as! [[String: Any]] {
            let size = int(f, "size")
            let plain = content(Int(size), Int(int(f, "seed")))
            let stored = hex(str(f, "stored"))
            let made = try FileCipher.create(keys, rng: queued(f["random"]))
            XCTAssertEqual(str(f, "header"), E2ee.hex(made.header))
            let source = directory.appendingPathComponent("plain"), sealed = directory.appendingPathComponent("sealed"), back = directory.appendingPathComponent("back")
            try plain.write(to: source)
            let result = try made.encryptFile(source, to: sealed)
            XCTAssertEqual(try Data(contentsOf: sealed), stored)
            XCTAssertEqual(result.size, Int64(stored.count))
            XCTAssertEqual(result.hash, E2ee.sha256Hex(stored))
            XCTAssertEqual(stored, try made.encryptedRange(size, start: 0, end: Int64(stored.count)) { plain.subdata(in: Int($0)..<Int($1)) })
            for p in f["parts"] as! [[String: Any]] {
                let start = int(p, "start"), end = int(p, "end")
                XCTAssertEqual(stored.subdata(in: Int(start)..<Int(end)), try made.encryptedRange(size, start: start, end: end) { plain.subdata(in: Int($0)..<Int($1)) })
            }
            let opened = try FileCipher.decryptFile(keys, source: sealed, to: back)
            XCTAssertEqual(opened.size, size)
            XCTAssertEqual(try Data(contentsOf: back), plain)
            XCTAssertEqual(try FileCipher.decrypt(keys, stored), plain)
        }
    }

    func testMultiChunkFileMatchesByHash() throws {
        let f = v["bigFile"] as! [String: Any]
        let size = int(f, "size")
        let plain = content(Int(size), Int(int(f, "seed")))
        XCTAssertEqual(str(f, "plainSha256"), E2ee.sha256Hex(plain))
        let cipher = try FileCipher.create(keys, rng: queued(f["random"]))
        XCTAssertEqual(str(f, "header"), E2ee.hex(cipher.header))
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let source = directory.appendingPathComponent("plain"), sealed = directory.appendingPathComponent("sealed")
        try plain.write(to: source)
        let result = try cipher.encryptFile(source, to: sealed)
        XCTAssertEqual(int(f, "storedSize"), result.size)
        XCTAssertEqual(str(f, "sha256"), result.hash)
        var total = SHA256()
        for p in f["parts"] as! [[String: Any]] {
            let part = try cipher.encryptedRange(size, start: int(p, "start"), end: int(p, "end")) { plain.subdata(in: Int($0)..<Int($1)) }
            XCTAssertEqual(str(p, "sha256"), E2ee.sha256Hex(part))
            total.update(data: part)
        }
        XCTAssertEqual(str(f, "sha256"), E2ee.hex(Data(total.finalize())))
        let back = directory.appendingPathComponent("back")
        let opened = try FileCipher.decryptFile(keys, source: sealed, to: back)
        XCTAssertEqual(str(f, "plainSha256"), opened.hash)
    }

    func testDamagedOrCutFilesFail() throws {
        let f = (v["smallFiles"] as! [[String: Any]])[0]
        let stored = hex(str(f, "stored"))
        var flipped = stored
        flipped[200] ^= 1
        XCTAssertThrowsError(try FileCipher.decrypt(keys, flipped)) { XCTAssertEqual(($0 as? E2eeError)?.code, "CORRUPT") }
        XCTAssertThrowsError(try FileCipher.decrypt(keys, stored.prefix(stored.count - 1)))
        XCTAssertThrowsError(try FileCipher.open(E2ee.accountKeys(Data(count: 32)), header: stored))
        XCTAssertThrowsError(try FileCipher.open(keys, header: Data(count: 80)))
    }

    func testFreshKeysRoundTrip() throws {
        let fresh = E2ee.accountKeys(E2ee.newMasterKey())
        let cipher = try FileCipher.create(fresh)
        let plain = E2ee.random(E2ee.chunkSize + 3)
        let stored = try cipher.encryptedRange(Int64(plain.count), start: 0, end: E2ee.encryptedSize(Int64(plain.count))) { plain.subdata(in: Int($0)..<Int($1)) }
        XCTAssertEqual(E2ee.encryptedSize(Int64(plain.count)), Int64(stored.count))
        XCTAssertEqual(plain, try FileCipher.decrypt(fresh, stored))
        let recovery = E2ee.newRecoveryKey()
        XCTAssertEqual(recovery, E2ee.parseRecoveryKey(E2ee.formatRecoveryKey(recovery)))
    }

    @MainActor
    func testTransportSealsNamesAndRevealsAnswers() async throws {
        let k = keys
        var sent: [(String, String, [String: Any]?)] = []
        let token = try E2ee.encryptName(k, "report.pdf")
        let transport = E2eeTransport(inner: { path, method, body in
            sent.append((path, method, body))
            if path.hasPrefix("/v1/drive/folders/root/children") {
                return ["items": [["id": "a", "ownerUserId": "u", "name": token, "normalizedName": token, "sizeBytes": 96 + 10,
                                   "encrypted": true, "mimeType": "application/octet-stream"]], "nextCursor": NSNull()]
            }
            return ["item": ["id": "f", "ownerUserId": "u", "name": body?["name"] ?? ""]]
        }, vault: { E2eeView(userId: "u", enabled: true, decrypting: false, keys: k) }, onAccount: { _, _, _ in })
        _ = try await transport.call("/v1/drive/folders", "POST", ["name": "Taxes", "parentId": NSNull()])
        XCTAssertEqual(sent.last?.2?["name"] as? String, try E2ee.encryptName(k, "Taxes"))
        let page = try await transport.call("/v1/drive/folders/root/children", "GET", nil)
        let item = (page["items"] as! [[String: Any]])[0]
        XCTAssertEqual(item["name"] as? String, "report.pdf")
        XCTAssertEqual(item["normalizedName"] as? String, "report.pdf")
        XCTAssertEqual((item["sizeBytes"] as? NSNumber)?.int64Value, 10)
        XCTAssertEqual(item["mimeType"] as? String, "application/pdf")
        // Locked: a name that would need encrypting is refused rather than sent in the clear.
        let locked = E2eeTransport(inner: { _, _, _ in [:] }, vault: { E2eeView(userId: "u", enabled: true) }, onAccount: { _, _, _ in })
        do { _ = try await locked.call("/v1/drive/folders", "POST", ["name": "Taxes", "parentId": NSNull()]); XCTFail("Must refuse") }
        catch { XCTAssertEqual((error as? APIError)?.code, "E2EE_LOCKED") }
    }

    /// Values from packages/api-client/src/e2ee.ts with fixed IVs (link secret, name and file-key wraps).
    func testPublicLinkWrapsMatchTypeScript() throws {
        let secret = hex("01080f161d242b323940474e555c636a71787f868d949ba2a9b0b7bec5ccd3da")
        let link = E2eeLinks.keys(secret)
        let iv = { (k: UInt8) in Data(repeating: k, count: 12) }
        XCTAssertEqual(try E2eeLinks.wrapSecret(keys, secret, iv: iv(1)), "AQEBAQEBAQEBAQEBzAXn4k-PdVGNgzmYsaiW5n0OBKe0j9ESO91Ddhm2LkabpJJmGq6oQ-_AipwDLQK5")
        XCTAssertEqual(try E2eeLinks.unwrapSecret(keys, "AQEBAQEBAQEBAQEBzAXn4k-PdVGNgzmYsaiW5n0OBKe0j9ESO91Ddhm2LkabpJJmGq6oQ-_AipwDLQK5"), secret)
        let nameWrap = try E2eeLinks.wrapName(link, itemId: "item-1", name: "Résumé.pdf", iv: iv(2))
        XCTAssertEqual(nameWrap, "AgICAgICAgICAgICVyhJxEAaHgVC9N52ijjEggmnq87998RUqU0JQw")
        XCTAssertEqual(try E2eeLinks.unwrapName(link, itemId: "item-1", nameWrap), "Résumé.pdf")
        XCTAssertThrowsError(try E2eeLinks.unwrapName(link, itemId: "item-2", nameWrap))
        let header = hex(str((v["smallFiles"] as! [[String: Any]])[0], "header"))
        let file = try E2eeLinks.wrapFileKey(keys, link, header: header, iv: iv(3))
        XCTAssertEqual(file.keyWrap, "AwMDAwMDAwMDAwMDOuqLtt1UBDuU4PfPfuJE-5QtANbIaAwJj8Qf6cbYzHgoB7ML0tLiepXc4vpzGsem")
        XCTAssertEqual(file.head, "SDBFMQEAAAAAQAAATiCqcenpcc8")
        XCTAssertEqual(E2eeLinks.url("https://harbor0content.online/s#tok", secret: secret), "https://harbor0content.online/s#tok.AQgPFh0kKzI5QEdOVVxjanF4f4aNlJuiqbC3vsXM09o")
    }
}
