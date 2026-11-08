import Foundation
import CryptoKit

/*
 * End-to-end encryption, byte for byte as packages/api-client/src/e2ee.ts (and Android E2eeCrypto.kt)
 * do it, so a file or name encrypted by any harbor0 app opens in every other one.
 *
 * Keys: one random 32-byte master key per account, stored only wrapped (AES-256-GCM) by a key from
 * the passphrase (Argon2id), from the recovery key (HKDF) and from each unlocked device's secret
 * (HKDF over a device half and a server half). Subkeys come from the master key with HKDF.
 * Files (H0E1): an 80-byte header, then 4 MiB chunks, each sealed with nonce prefix ‖ index and
 * the header plus a last-chunk flag as associated data. Names: `h0e1.` + base64url(iv ‖ ciphertext)
 * with the IV an HMAC of the name, so equal names encrypt equally.
 */

/// WRONG_KEY, CORRUPT, NAME_TOO_LONG or LOCKED, as the TypeScript E2eeError.
struct E2eeError: LocalizedError, Equatable {
    let code: String
    let message: String
    var errorDescription: String? { message }
    static let damaged = E2eeError(code: "CORRUPT", message: "The stored key is damaged.")
    static let wrongKey = E2eeError(code: "WRONG_KEY", message: "The key doesn’t match.")
}

struct PassphraseEnvelope: Codable, Equatable {
    let v: Int; let kdf: String; let salt: String; let memoryKiB: Int; let iterations: Int; let parallelism: Int
    let iv: String; let ciphertext: String
    var json: [String: Any] {
        ["v": v, "kdf": kdf, "salt": salt, "memoryKiB": memoryKiB, "iterations": iterations, "parallelism": parallelism, "iv": iv, "ciphertext": ciphertext]
    }
}
struct RecoveryEnvelope: Codable, Equatable {
    let v: Int; let kdf: String; let salt: String; let iv: String; let ciphertext: String
    var json: [String: Any] { ["v": v, "kdf": kdf, "salt": salt, "iv": iv, "ciphertext": ciphertext] }
}
/// A device's copy: the master key wrapped by both halves of the device secret.
struct DeviceEnvelope: Codable, Equatable { let iv: String; let ciphertext: String }
struct Argon2Cost: Equatable { let memoryKiB: Int; let iterations: Int; let parallelism: Int }

/// The keys an unlocked device works with, derived once from the master key.
struct AccountKeys: @unchecked Sendable {
    let master: Data
    let fileWrap: SymmetricKey
    let nameEnc: SymmetricKey
    let nameMac: SymmetricKey
    /// Sent to the server to show this device holds the key.
    let proof: String
}

enum E2ee {
    static let namePrefix = "h0e1."
    /// Longest name, in UTF-8 bytes, that fits the server's limit once encrypted.
    static let maxNameBytes = 255
    static let chunkSize = 4 * 1024 * 1024
    static let headerSize = 80
    static let tag = 16
    /// Argon2id cost for new passphrases: OWASP's 64 MiB, 3 passes.
    static let argon2 = Argon2Cost(memoryKiB: 65_536, iterations: 3, parallelism: 1)
    static let magic: [UInt8] = [0x48, 0x30, 0x45, 0x31] // "H0E1"
    private static let masterAAD = Data("harbor0/e2ee/v1/master".utf8)
    private static let nameAAD = Data("harbor0/e2ee/v1/name".utf8)

    static func random(_ n: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: n)
        precondition(SecRandomCopyBytes(kSecRandomDefault, n, &bytes) == errSecSuccess)
        return Data(bytes)
    }
    static func b64url(_ data: Data) -> String { data.base64URLEncodedString() }
    static func fromB64url(_ text: String) throws -> Data {
        var s = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while s.hasSuffix("=") { s.removeLast() }
        s += String(repeating: "=", count: (4 - s.count % 4) % 4)
        guard let data = Data(base64Encoded: s) else { throw E2eeError.damaged }
        return data
    }
    static func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }
    static func sha256Hex(_ data: Data) -> String { hex(Data(SHA256.hash(data: data))) }

    /// HKDF-SHA256 (RFC 5869); an empty salt is a block of zeros, as WebCrypto treats it.
    static func hkdf(_ secret: Data, info: String, length: Int = 32, salt: Data = Data()) -> Data {
        let key = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: secret), salt: salt.isEmpty ? Data(count: 32) : salt,
                                         info: Data(info.utf8), outputByteCount: length)
        return key.withUnsafeBytes { Data($0) }
    }
    static func seal(_ key: SymmetricKey, iv: Data, _ data: Data, aad: Data) throws -> Data {
        let box = try AES.GCM.seal(data, using: key, nonce: AES.GCM.Nonce(data: iv), authenticating: aad)
        var out = Data(box.ciphertext)
        out.append(box.tag)
        return out
    }
    static func open(_ key: SymmetricKey, iv: Data, _ data: Data, aad: Data) throws -> Data {
        guard data.count >= tag else { throw E2eeError.wrongKey }
        do {
            let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: iv), ciphertext: data.prefix(data.count - tag), tag: data.suffix(tag))
            return Data(try AES.GCM.open(box, using: key, authenticating: aad))
        } catch { throw E2eeError.wrongKey }
    }

    private static func wrapMaster(_ kek: Data, _ master: Data, iv: Data) throws -> DeviceEnvelope {
        DeviceEnvelope(iv: b64url(iv), ciphertext: b64url(try seal(SymmetricKey(data: kek), iv: iv, master, aad: masterAAD)))
    }
    private static func unwrapMaster(_ kek: Data, iv: String, ciphertext: String) throws -> Data {
        let master = try open(SymmetricKey(data: kek), iv: try fromB64url(iv), try fromB64url(ciphertext), aad: masterAAD)
        guard master.count == 32 else { throw E2eeError.damaged }
        return master
    }

    /// Argon2id v1.3 over the NFKC passphrase's UTF-8 bytes, 32 bytes out, as hash-wasm.
    static func passphraseKek(_ passphrase: String, salt: Data, cost: Argon2Cost) throws -> Data {
        // The server's schema bounds, so a damaged envelope can't ask for gigabytes.
        guard (19_456...1_048_576).contains(cost.memoryKiB), (2...20).contains(cost.iterations), (1...8).contains(cost.parallelism) else {
            throw E2eeError.damaged
        }
        let password = Data(passphrase.precomposedStringWithCompatibilityMapping.utf8)
        return Argon2id.hash(password: password, salt: salt, iterations: cost.iterations, memoryKiB: cost.memoryKiB,
                             parallelism: cost.parallelism, length: 32)
    }
    static func wrapWithPassphrase(_ master: Data, passphrase: String, cost: Argon2Cost = argon2, rng: (Int) -> Data = random) throws -> PassphraseEnvelope {
        let salt = rng(16)
        let wrapped = try wrapMaster(try passphraseKek(passphrase, salt: salt, cost: cost), master, iv: rng(12))
        return PassphraseEnvelope(v: 1, kdf: "argon2id", salt: b64url(salt), memoryKiB: cost.memoryKiB, iterations: cost.iterations,
                                  parallelism: cost.parallelism, iv: wrapped.iv, ciphertext: wrapped.ciphertext)
    }
    static func unwrapWithPassphrase(_ e: PassphraseEnvelope, passphrase: String) throws -> Data {
        guard e.v == 1, e.kdf == "argon2id" else { throw E2eeError.damaged }
        let kek = try passphraseKek(passphrase, salt: try fromB64url(e.salt),
                                    cost: Argon2Cost(memoryKiB: e.memoryKiB, iterations: e.iterations, parallelism: e.parallelism))
        return try unwrapMaster(kek, iv: e.iv, ciphertext: e.ciphertext)
    }

    // Recovery keys: 20 random bytes as 32 Crockford base32 characters, shown in 8 groups of 4.
    private static let crockford = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")
    static func formatRecoveryKey(_ bytes: Data) -> String {
        var bits = 0, value = 0
        var out: [Character] = []
        for b in bytes {
            value = ((value << 8) | Int(b)) & 0xFFFF; bits += 8
            while bits >= 5 { out.append(crockford[(value >> (bits - 5)) & 31]); bits -= 5 }
        }
        return stride(from: 0, to: out.count, by: 4).map { String(out[$0..<min($0 + 4, out.count)]) }.joined(separator: "-")
    }
    /// Reads a typed recovery key, forgiving case, spaces, dashes and look-alike letters.
    static func parseRecoveryKey(_ typed: String) -> Data? {
        let chars = typed.uppercased().filter { !$0.isWhitespace && $0 != "-" }
            .map { c -> Character in switch c { case "O": "0"; case "I", "L": "1"; case "U": "V"; default: c } }
        guard chars.count == 32 else { return nil }
        var out = Data(), bits = 0, value = 0
        for c in chars {
            guard let index = crockford.firstIndex(of: c) else { return nil }
            value = ((value << 5) | index) & 0xFFFF; bits += 5
            if bits >= 8 { out.append(UInt8((value >> (bits - 8)) & 255)); bits -= 8 }
        }
        return out
    }
    static func newRecoveryKey() -> Data { random(20) }
    static func newMasterKey() -> Data { random(32) }
    static func wrapWithRecovery(_ master: Data, recovery: Data, rng: (Int) -> Data = random) throws -> RecoveryEnvelope {
        let salt = rng(16)
        let wrapped = try wrapMaster(hkdf(recovery, info: "harbor0/e2ee/v1/recovery", salt: salt), master, iv: rng(12))
        return RecoveryEnvelope(v: 1, kdf: "hkdf", salt: b64url(salt), iv: wrapped.iv, ciphertext: wrapped.ciphertext)
    }
    static func unwrapWithRecovery(_ e: RecoveryEnvelope, recovery: Data) throws -> Data {
        guard e.v == 1, e.kdf == "hkdf" else { throw E2eeError.damaged }
        return try unwrapMaster(hkdf(recovery, info: "harbor0/e2ee/v1/recovery", salt: try fromB64url(e.salt)), iv: e.iv, ciphertext: e.ciphertext)
    }

    private static func deviceKek(_ local: Data, _ server: Data) -> Data { hkdf(local + server, info: "harbor0/e2ee/v1/device") }
    static func wrapForDevice(_ master: Data, local: Data, server: Data, rng: (Int) -> Data = random) throws -> DeviceEnvelope {
        try wrapMaster(deviceKek(local, server), master, iv: rng(12))
    }
    static func unwrapForDevice(_ e: DeviceEnvelope, local: Data, server: Data) throws -> Data {
        try unwrapMaster(deviceKek(local, server), iv: e.iv, ciphertext: e.ciphertext)
    }

    static func accountKeys(_ master: Data) -> AccountKeys {
        let names = hkdf(master, info: "harbor0/e2ee/v1/names", length: 64)
        return AccountKeys(master: master, fileWrap: SymmetricKey(data: hkdf(master, info: "harbor0/e2ee/v1/file-keys")),
                           nameEnc: SymmetricKey(data: names.prefix(32)), nameMac: SymmetricKey(data: names.suffix(32)),
                           proof: b64url(hkdf(master, info: "harbor0/e2ee/v1/proof")))
    }

    static func isEncryptedName(_ name: String) -> Bool {
        guard name.hasPrefix(namePrefix), name.count > namePrefix.count else { return false }
        return name.dropFirst(namePrefix.count).allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }
    }
    static func encryptName(_ keys: AccountKeys, _ name: String) throws -> String {
        let plain = Data(name.precomposedStringWithCanonicalMapping.utf8)
        guard plain.count <= maxNameBytes else { throw E2eeError(code: "NAME_TOO_LONG", message: "This name is too long for an encrypted account.") }
        let iv = Data(HMAC<SHA256>.authenticationCode(for: plain, using: keys.nameMac)).prefix(12)
        return namePrefix + b64url(iv + (try seal(keys.nameEnc, iv: iv, plain, aad: nameAAD)))
    }
    static func decryptName(_ keys: AccountKeys, _ token: String) throws -> String {
        let bytes = try fromB64url(String(token.dropFirst(token.hasPrefix(namePrefix) ? namePrefix.count : 0)))
        guard bytes.count >= 12 + tag else { throw E2eeError(code: "CORRUPT", message: "The name is damaged.") }
        let plain = try open(keys.nameEnc, iv: bytes.prefix(12), bytes.dropFirst(12), aad: nameAAD)
        guard let text = String(data: plain, encoding: .utf8) else { throw E2eeError(code: "CORRUPT", message: "The name is damaged.") }
        return text
    }

    static func chunkCount(_ plain: Int64, chunk: Int = chunkSize) -> Int64 { max(1, (plain + Int64(chunk) - 1) / Int64(chunk)) }
    /// Stored size of a file of `plain` bytes.
    static func encryptedSize(_ plain: Int64, chunk: Int = chunkSize) -> Int64 { Int64(headerSize) + plain + Int64(tag) * chunkCount(plain, chunk: chunk) }
    /// Original size of a stored encrypted file.
    static func plainSize(_ stored: Int64, chunk: Int = chunkSize) -> Int64 {
        let body = stored - Int64(headerSize)
        if body < Int64(tag) { return 0 }
        return body - Int64(tag) * ((body + Int64(chunk) + Int64(tag) - 1) / Int64(chunk + tag))
    }
}

/// One file version's key, ready to encrypt or decrypt its chunks.
struct FileCipher {
    let header: Data
    private let key: SymmetricKey
    private let prefix: Data
    let chunkSize: Int

    /// A new random key for a new version.
    static func create(_ keys: AccountKeys, rng: (Int) -> Data = E2ee.random) throws -> FileCipher {
        let content = rng(32)
        var head = Data(count: E2ee.headerSize)
        head.replaceSubrange(0..<4, with: E2ee.magic)
        head[4] = 1
        let size = UInt32(E2ee.chunkSize)
        head.replaceSubrange(8..<12, with: [UInt8(size >> 24), UInt8((size >> 16) & 255), UInt8((size >> 8) & 255), UInt8(size & 255)])
        head.replaceSubrange(12..<20, with: rng(8))
        let wrapIV = rng(12)
        head.replaceSubrange(20..<32, with: wrapIV)
        let wrapped = try E2ee.seal(keys.fileWrap, iv: wrapIV, content, aad: Data(head.prefix(20)))
        head.replaceSubrange(32..<80, with: wrapped)
        return FileCipher(header: head, key: SymmetricKey(data: content), prefix: head.subdata(in: 12..<20), chunkSize: E2ee.chunkSize)
    }
    /// Reads the key from a stored file's header (its first 80 bytes, or more).
    static func open(_ keys: AccountKeys, header: Data) throws -> FileCipher {
        let head = Data(header.prefix(E2ee.headerSize))
        guard head.count == E2ee.headerSize, Array(head.prefix(4)) == E2ee.magic, head[4] == 1 else {
            throw E2eeError(code: "CORRUPT", message: "This file isn’t encrypted the way harbor0 expects.")
        }
        let chunk = head.subdata(in: 8..<12).reduce(0) { ($0 << 8) | Int($1) }
        guard (4096...64 * 1024 * 1024).contains(chunk) else { throw E2eeError(code: "CORRUPT", message: "This file’s header is damaged.") }
        let content = try E2ee.open(keys.fileWrap, iv: head.subdata(in: 20..<32), head.subdata(in: 32..<80), aad: head.prefix(20))
        return FileCipher(header: head, key: SymmetricKey(data: content), prefix: head.subdata(in: 12..<20), chunkSize: chunk)
    }
    func chunkCount(_ plainTotal: Int64) -> Int64 { E2ee.chunkCount(plainTotal, chunk: chunkSize) }
    func storedSize(_ plainTotal: Int64) -> Int64 { E2ee.encryptedSize(plainTotal, chunk: chunkSize) }
    func plainSize(_ stored: Int64) -> Int64 { E2ee.plainSize(stored, chunk: chunkSize) }
    /// Plaintext bytes in chunk `index` of a `plainTotal`-byte file.
    func chunkPlainLength(_ plainTotal: Int64, _ index: Int64) -> Int { Int(min(Int64(chunkSize), plainTotal - index * Int64(chunkSize))) }
    private func nonce(_ index: Int64) -> Data {
        let i = UInt32(truncatingIfNeeded: index)
        return prefix + Data([UInt8(i >> 24), UInt8((i >> 16) & 255), UInt8((i >> 8) & 255), UInt8(i & 255)])
    }
    private func aad(_ last: Bool) -> Data { var a = Data(header.prefix(21)); a[20] = last ? 1 : 0; return a }
    func encryptChunk(_ index: Int64, _ plain: Data, last: Bool) throws -> Data { try E2ee.seal(key, iv: nonce(index), plain, aad: aad(last)) }
    func decryptChunk(_ index: Int64, _ cipher: Data, last: Bool) throws -> Data {
        do { return try E2ee.open(key, iv: nonce(index), cipher, aad: aad(last)) }
        catch { throw E2eeError(code: "CORRUPT", message: "Part of this file is damaged or was changed.") }
    }
    /// Stored bytes [start, end) of the encrypted file, reading plaintext [begin, end) through `read`.
    func encryptedRange(_ plainTotal: Int64, start: Int64, end: Int64, read: (Int64, Int64) throws -> Data) throws -> Data {
        var out = Data()
        let head = Int64(E2ee.headerSize), span = Int64(chunkSize + E2ee.tag)
        if start < head { out.append(header.subdata(in: Int(start)..<Int(min(end, head)))) }
        guard end > head else { return out }
        let first = (max(start, head) - head) / span
        let last = (end - 1 - head) / span
        let final = chunkCount(plainTotal) - 1
        var i = first
        while i <= last && i <= final {
            let begin = i * Int64(chunkSize)
            let plain = try read(begin, begin + Int64(chunkPlainLength(plainTotal, i)))
            guard plain.count == chunkPlainLength(plainTotal, i) else { throw SyncTransfers.Changed() }
            let sealed = try encryptChunk(i, plain, last: i == final)
            let chunkStart = head + i * span
            let a = Int(max(0, start - chunkStart)), b = Int(min(Int64(sealed.count), end - chunkStart))
            if b > a { out.append(sealed.subdata(in: a..<b)) }
            i += 1
        }
        return out
    }

    /// Encrypts the whole file at `source` into `target`, header first; returns the stored size and SHA-256.
    func encryptFile(_ source: URL, to target: URL) throws -> (size: Int64, hash: String) {
        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }
        let plainTotal = Int64(try input.seekToEnd())
        try input.seek(toOffset: 0)
        FileManager.default.createFile(atPath: target.path, contents: nil, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        let output = try FileHandle(forWritingTo: target)
        defer { try? output.close() }
        var hash = SHA256()
        var written: Int64 = 0
        func emit(_ data: Data) throws { try output.write(contentsOf: data); hash.update(data: data); written += Int64(data.count) }
        try emit(header)
        let chunks = chunkCount(plainTotal)
        for index in 0..<chunks {
            try Task.checkCancellation()
            let length = chunkPlainLength(plainTotal, index)
            let plain = try input.read(upToCount: length) ?? Data()
            guard plain.count == length else { throw SyncTransfers.Changed() }
            try autoreleasepool { try emit(try encryptChunk(index, plain, last: index == chunks - 1)) }
        }
        if let extra = try input.read(upToCount: 1), !extra.isEmpty { throw SyncTransfers.Changed() }
        return (written, E2ee.hex(Data(hash.finalize())))
    }

    /// Decrypts a whole stored file into `target`; every chunk is checked. Returns the plaintext size and SHA-256.
    @discardableResult
    static func decryptFile(_ keys: AccountKeys, source: URL, to target: URL) throws -> (size: Int64, hash: String) {
        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }
        let stored = Int64(try input.seekToEnd())
        try input.seek(toOffset: 0)
        let head = try input.read(upToCount: E2ee.headerSize) ?? Data()
        let cipher = try open(keys, header: head)
        let plainTotal = cipher.plainSize(stored)
        guard cipher.storedSize(plainTotal) == stored else { throw E2eeError(code: "CORRUPT", message: "This file’s size doesn’t match its encryption.") }
        FileManager.default.createFile(atPath: target.path, contents: nil, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        let output = try FileHandle(forWritingTo: target)
        defer { try? output.close() }
        var hash = SHA256()
        let chunks = cipher.chunkCount(plainTotal)
        for index in 0..<chunks {
            try Task.checkCancellation()
            let length = cipher.chunkPlainLength(plainTotal, index) + E2ee.tag
            let sealed = try input.read(upToCount: length) ?? Data()
            guard sealed.count == length else { throw E2eeError(code: "CORRUPT", message: "The downloaded file is incomplete.") }
            try autoreleasepool {
                let plain = try cipher.decryptChunk(index, sealed, last: index == chunks - 1)
                try output.write(contentsOf: plain)
                hash.update(data: plain)
            }
        }
        return (plainTotal, E2ee.hex(Data(hash.finalize())))
    }
    /// A small file encrypted whole, as any file is, for the account to open with `decrypt` (a Photos thumbnail).
    static func sealWhole(_ keys: AccountKeys, _ plain: Data) throws -> Data {
        let cipher = try create(keys)
        let plainTotal = Int64(plain.count)
        let chunks = cipher.chunkCount(plainTotal)
        var out = cipher.header
        for index in 0..<chunks {
            let begin = Int(index) * cipher.chunkSize
            let piece = plain.subdata(in: (plain.startIndex + begin)..<(plain.startIndex + begin + cipher.chunkPlainLength(plainTotal, index)))
            out += try cipher.encryptChunk(index, piece, last: index == chunks - 1)
        }
        return out
    }
    /// Decrypts a whole stored file held in memory.
    static func decrypt(_ keys: AccountKeys, _ input: Data) throws -> Data {
        let stored = Data(input)
        let cipher = try open(keys, header: stored)
        let plainTotal = cipher.plainSize(Int64(stored.count))
        guard cipher.storedSize(plainTotal) == Int64(stored.count) else { throw E2eeError(code: "CORRUPT", message: "This file’s size doesn’t match its encryption.") }
        var out = Data(capacity: Int(plainTotal))
        var at = E2ee.headerSize
        let chunks = cipher.chunkCount(plainTotal)
        for index in 0..<chunks {
            let length = cipher.chunkPlainLength(plainTotal, index) + E2ee.tag
            out.append(try cipher.decryptChunk(index, stored.subdata(in: at..<at + length), last: index == chunks - 1))
            at += length
        }
        return out
    }
}

// MARK: - Argon2id (RFC 9106), with BLAKE2b; no third-party packages

enum Blake2b {
    private static let iv: [UInt64] = [0x6a09e667f3bcc908, 0xbb67ae8584caa73b, 0x3c6ef372fe94f82b, 0xa54ff53a5f1d36f1,
                                       0x510e527fade682d1, 0x9b05688c2b3e6c1f, 0x1f83d9abfb41bd6b, 0x5be0cd19137e2179]
    private static let sigma: [[Int]] = [
        [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15], [14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3],
        [11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4], [7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8],
        [9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13], [2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9],
        [12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11], [13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10],
        [6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5], [10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0]]

    struct State {
        var h: [UInt64]
        var buffer: [UInt8] = []
        var counter: UInt64 = 0
        let outLength: Int
        init(outLength: Int) {
            self.outLength = outLength
            h = Blake2b.iv
            h[0] ^= 0x01010000 ^ UInt64(outLength)
        }
        mutating func update(_ data: [UInt8]) {
            var at = 0
            while at < data.count {
                if buffer.count == 128 { counter &+= 128; Blake2b.compress(&h, buffer, counter, last: false); buffer.removeAll(keepingCapacity: true) }
                let n = min(128 - buffer.count, data.count - at)
                buffer.append(contentsOf: data[at..<at + n])
                at += n
            }
        }
        mutating func update(le32 value: Int) { update([UInt8(value & 255), UInt8((value >> 8) & 255), UInt8((value >> 16) & 255), UInt8((value >> 24) & 255)]) }
        mutating func finish() -> [UInt8] {
            counter &+= UInt64(buffer.count)
            buffer += [UInt8](repeating: 0, count: 128 - buffer.count)
            Blake2b.compress(&h, buffer, counter, last: true)
            var out: [UInt8] = []
            out.reserveCapacity(64)
            for word in h { for i in 0..<8 { out.append(UInt8((word >> (8 * UInt64(i))) & 255)) } }
            return Array(out.prefix(outLength))
        }
    }

    @_optimize(speed)
    static func compress(_ h: inout [UInt64], _ block: [UInt8], _ counter: UInt64, last: Bool) {
        var m = [UInt64](repeating: 0, count: 16)
        for i in 0..<16 {
            var w: UInt64 = 0
            for j in 0..<8 { w |= UInt64(block[i * 8 + j]) << (8 * UInt64(j)) }
            m[i] = w
        }
        var v = h + iv
        v[12] ^= counter
        if last { v[14] = ~v[14] }
        @inline(__always) func g(_ a: Int, _ b: Int, _ c: Int, _ d: Int, _ x: UInt64, _ y: UInt64) {
            v[a] = v[a] &+ v[b] &+ x; v[d] = (v[d] ^ v[a]).rotr(32)
            v[c] = v[c] &+ v[d]; v[b] = (v[b] ^ v[c]).rotr(24)
            v[a] = v[a] &+ v[b] &+ y; v[d] = (v[d] ^ v[a]).rotr(16)
            v[c] = v[c] &+ v[d]; v[b] = (v[b] ^ v[c]).rotr(63)
        }
        for round in 0..<12 {
            let s = sigma[round % 10]
            g(0, 4, 8, 12, m[s[0]], m[s[1]]); g(1, 5, 9, 13, m[s[2]], m[s[3]])
            g(2, 6, 10, 14, m[s[4]], m[s[5]]); g(3, 7, 11, 15, m[s[6]], m[s[7]])
            g(0, 5, 10, 15, m[s[8]], m[s[9]]); g(1, 6, 11, 12, m[s[10]], m[s[11]])
            g(2, 7, 8, 13, m[s[12]], m[s[13]]); g(3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for i in 0..<8 { h[i] ^= v[i] ^ v[i + 8] }
    }

    static func hash(_ data: [UInt8], length: Int) -> [UInt8] {
        var state = State(outLength: length)
        state.update(data)
        return state.finish()
    }
}

extension UInt64 {
    @inline(__always) func rotr(_ n: UInt64) -> UInt64 { (self >> n) | (self << (64 - n)) }
}

enum Argon2id {
    private static let blockWords = 128

    /// H′, Argon2's variable-length hash.
    static func longHash(_ input: [UInt8], length: Int) -> [UInt8] {
        var prefixed = [UInt8(length & 255), UInt8((length >> 8) & 255), UInt8((length >> 16) & 255), UInt8((length >> 24) & 255)]
        prefixed += input
        if length <= 64 { return Blake2b.hash(prefixed, length: length) }
        var out: [UInt8] = []
        out.reserveCapacity(length)
        var v = Blake2b.hash(prefixed, length: 64)
        out += v.prefix(32)
        let r = (length + 31) / 32 - 2
        if r >= 2 { for _ in 2...r { v = Blake2b.hash(v, length: 64); out += v.prefix(32) } }
        out += Blake2b.hash(v, length: length - 32 * r)
        return out
    }

    static func hash(password: Data, salt: Data, iterations: Int, memoryKiB: Int, parallelism: Int, length: Int) -> Data {
        let lanes = parallelism
        let memoryBlocks = max(memoryKiB, 8 * lanes) / (4 * lanes) * (4 * lanes)
        let segment = memoryBlocks / (lanes * 4)
        let laneLength = segment * 4

        var h0 = Blake2b.State(outLength: 64)
        for value in [lanes, length, memoryKiB, iterations, 0x13, 2] { h0.update(le32: value) }
        h0.update(le32: password.count); h0.update([UInt8](password))
        h0.update(le32: salt.count); h0.update([UInt8](salt))
        h0.update(le32: 0); h0.update(le32: 0)
        let seed = h0.finish()

        let memory = UnsafeMutablePointer<UInt64>.allocate(capacity: memoryBlocks * blockWords)
        defer { memory.deallocate() }
        memory.initialize(repeating: 0, count: memoryBlocks * blockWords)
        func le32(_ v: Int) -> [UInt8] { [UInt8(v & 255), UInt8((v >> 8) & 255), UInt8((v >> 16) & 255), UInt8((v >> 24) & 255)] }
        for lane in 0..<lanes {
            for i in 0..<2 {
                let bytes = longHash(seed + le32(i) + le32(lane), length: 1024)
                let block = memory + (lane * laneLength + i) * blockWords
                for w in 0..<blockWords {
                    var word: UInt64 = 0
                    for j in 0..<8 { word |= UInt64(bytes[w * 8 + j]) << (8 * UInt64(j)) }
                    block[w] = word
                }
            }
        }
        let scratch = UnsafeMutablePointer<UInt64>.allocate(capacity: blockWords * 5)
        defer { scratch.deallocate() }
        scratch.initialize(repeating: 0, count: blockWords * 5)
        for pass in 0..<iterations {
            for slice in 0..<4 {
                for lane in 0..<lanes {
                    fillSegment(memory, scratch: scratch, pass: pass, lane: lane, slice: slice, lanes: lanes, segment: segment,
                                laneLength: laneLength, memoryBlocks: memoryBlocks, iterations: iterations)
                }
            }
        }
        let final = scratch
        for w in 0..<blockWords { final[w] = memory[((laneLength - 1)) * blockWords + w] }
        if lanes > 1 { for lane in 1..<lanes { for w in 0..<blockWords { final[w] ^= memory[(lane * laneLength + laneLength - 1) * blockWords + w] } } }
        var bytes = [UInt8](repeating: 0, count: 1024)
        for w in 0..<blockWords { for j in 0..<8 { bytes[w * 8 + j] = UInt8((final[w] >> (8 * UInt64(j))) & 255) } }
        return Data(longHash(bytes, length: length))
    }

    @_optimize(speed)
    private static func fillSegment(_ memory: UnsafeMutablePointer<UInt64>, scratch: UnsafeMutablePointer<UInt64>, pass: Int, lane: Int, slice: Int,
                                    lanes: Int, segment: Int, laneLength: Int, memoryBlocks: Int, iterations: Int) {
        let independent = pass == 0 && slice < 2
        let address = scratch + blockWords * 2, input = scratch + blockWords * 3, zero = scratch + blockWords * 4
        if independent {
            for w in 0..<blockWords { input[w] = 0; zero[w] = 0; address[w] = 0 }
            input[0] = UInt64(pass); input[1] = UInt64(lane); input[2] = UInt64(slice)
            input[3] = UInt64(memoryBlocks); input[4] = UInt64(iterations); input[5] = 2
        }
        func nextAddresses() {
            input[6] &+= 1
            fill(zero, input, address, xor: false, scratch: scratch)
            fill(zero, address, address, xor: false, scratch: scratch)
        }
        var start = 0
        if pass == 0 && slice == 0 {
            start = 2
            if independent { nextAddresses() }
        }
        var current = lane * laneLength + slice * segment + start
        var previous = current % laneLength == 0 ? current + laneLength - 1 : current - 1
        var i = start
        while i < segment {
            if current % laneLength == 1 { previous = current - 1 }
            let random: UInt64
            if independent {
                if i % blockWords == 0 { nextAddresses() }
                random = address[i % blockWords]
            } else {
                random = memory[previous * blockWords]
            }
            var refLane = Int((random >> 32) % UInt64(lanes))
            if pass == 0 && slice == 0 { refLane = lane }
            let sameLane = refLane == lane
            // index_alpha
            var area: Int
            if pass == 0 {
                if slice == 0 { area = i - 1 }
                else if sameLane { area = slice * segment + i - 1 }
                else { area = slice * segment + (i == 0 ? -1 : 0) }
            } else {
                if sameLane { area = laneLength - segment + i - 1 }
                else { area = laneLength - segment + (i == 0 ? -1 : 0) }
            }
            let j1 = random & 0xFFFF_FFFF
            let x = (j1 &* j1) >> 32
            let y = (UInt64(area) &* x) >> 32
            let relative = UInt64(area) - 1 - y
            let startPosition = pass == 0 || slice == 3 ? 0 : (slice + 1) * segment
            let refIndex = (startPosition + Int(relative)) % laneLength
            fill(memory + previous * blockWords, memory + (laneLength * refLane + refIndex) * blockWords, memory + current * blockWords,
                 xor: pass != 0, scratch: scratch)
            i += 1; current += 1; previous += 1
        }
    }

    @_optimize(speed) @inline(__always)
    private static func blamka(_ x: UInt64, _ y: UInt64) -> UInt64 {
        x &+ y &+ 2 &* (x & 0xFFFF_FFFF) &* (y & 0xFFFF_FFFF)
    }
    @_optimize(speed) @inline(__always)
    private static func round(_ r: UnsafeMutablePointer<UInt64>, _ i: (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int)) {
        @inline(__always) func g(_ a: Int, _ b: Int, _ c: Int, _ d: Int) {
            r[a] = blamka(r[a], r[b]); r[d] = (r[d] ^ r[a]).rotr(32)
            r[c] = blamka(r[c], r[d]); r[b] = (r[b] ^ r[c]).rotr(24)
            r[a] = blamka(r[a], r[b]); r[d] = (r[d] ^ r[a]).rotr(16)
            r[c] = blamka(r[c], r[d]); r[b] = (r[b] ^ r[c]).rotr(63)
        }
        g(i.0, i.4, i.8, i.12); g(i.1, i.5, i.9, i.13); g(i.2, i.6, i.10, i.14); g(i.3, i.7, i.11, i.15)
        g(i.0, i.5, i.10, i.15); g(i.1, i.6, i.11, i.12); g(i.2, i.7, i.8, i.13); g(i.3, i.4, i.9, i.14)
    }
    /// next = G(prev, ref) (xor next when `xor`), Argon2 v1.3.
    @_optimize(speed)
    private static func fill(_ prev: UnsafeMutablePointer<UInt64>, _ ref: UnsafeMutablePointer<UInt64>, _ next: UnsafeMutablePointer<UInt64>,
                             xor: Bool, scratch: UnsafeMutablePointer<UInt64>) {
        let r = scratch, tmp = scratch + blockWords
        for w in 0..<blockWords { r[w] = ref[w] ^ prev[w]; tmp[w] = r[w] }
        if xor { for w in 0..<blockWords { tmp[w] ^= next[w] } }
        for i in 0..<8 {
            let b = 16 * i
            round(r, (b, b + 1, b + 2, b + 3, b + 4, b + 5, b + 6, b + 7, b + 8, b + 9, b + 10, b + 11, b + 12, b + 13, b + 14, b + 15))
        }
        for i in 0..<8 {
            let b = 2 * i
            round(r, (b, b + 1, b + 16, b + 17, b + 32, b + 33, b + 48, b + 49, b + 64, b + 65, b + 80, b + 81, b + 96, b + 97, b + 112, b + 113))
        }
        for w in 0..<blockWords { next[w] = tmp[w] ^ r[w] }
    }
}

// MARK: - Public links of an encrypted account (docs/specs/public-folder-links.md)

/**
 * A link's random 32-byte secret is only in its URL; keys derived from it seal each shared item's
 * name and content key. The account keeps the secret wrapped by the master key so its apps can show
 * the link again. Byte for byte as `packages/api-client/src/e2ee.ts`.
 */
struct LinkKeys { let names: SymmetricKey; let files: SymmetricKey }
enum E2eeLinks {
    /// Bytes of a header a link wrap is bound to and hands out: magic, chunk size, nonce prefix.
    static let headSize = 20
    private static let secretAAD = Data("harbor0/e2ee/v1/link-secret".utf8)
    static func newSecret() -> Data { E2ee.random(32) }
    static func keys(_ secret: Data) -> LinkKeys {
        LinkKeys(names: SymmetricKey(data: E2ee.hkdf(secret, info: "harbor0/e2ee/v1/link-names")),
                 files: SymmetricKey(data: E2ee.hkdf(secret, info: "harbor0/e2ee/v1/link-files")))
    }
    static func sealed(_ key: SymmetricKey, _ data: Data, aad: Data, iv: Data = E2ee.random(12)) throws -> String {
        E2ee.b64url(iv + (try E2ee.seal(key, iv: iv, data, aad: aad)))
    }
    static func opened(_ key: SymmetricKey, _ wrapped: String, aad: Data) throws -> Data {
        let bytes = try E2ee.fromB64url(wrapped)
        guard bytes.count >= 12 + E2ee.tag else { throw E2eeError(code: "CORRUPT", message: "The link’s data is damaged.") }
        return try E2ee.open(key, iv: bytes.prefix(12), Data(bytes.dropFirst(12)), aad: aad)
    }
    private static func secretKey(_ keys: AccountKeys) -> SymmetricKey { SymmetricKey(data: E2ee.hkdf(keys.master, info: "harbor0/e2ee/v1/link-secrets")) }
    /// The link's secret wrapped by the account, for the server to keep for the owner.
    static func wrapSecret(_ keys: AccountKeys, _ secret: Data, iv: Data = E2ee.random(12)) throws -> String {
        try sealed(secretKey(keys), secret, aad: secretAAD, iv: iv)
    }
    static func unwrapSecret(_ keys: AccountKeys, _ secretWrap: String) throws -> Data { try opened(secretKey(keys), secretWrap, aad: secretAAD) }
    /// The link's address with its secret: the server's URL plus `.<secret>` in the fragment.
    static func url(_ url: String, secret: Data) -> String { url + "." + E2ee.b64url(secret) }
    // The item id is bound in, so the server can't move a name to another item.
    private static func nameAAD(_ itemId: String) -> Data { Data("harbor0/e2ee/v1/link-name:\(itemId)".utf8) }
    static func wrapName(_ link: LinkKeys, itemId: String, name: String, iv: Data = E2ee.random(12)) throws -> String {
        try sealed(link.names, Data(name.precomposedStringWithCanonicalMapping.utf8), aad: nameAAD(itemId), iv: iv)
    }
    static func unwrapName(_ link: LinkKeys, itemId: String, _ wrap: String) throws -> String {
        String(decoding: try opened(link.names, wrap, aad: nameAAD(itemId)), as: UTF8.self)
    }
    // The version's header is bound in: it is random per version, so a wrap opens no other file.
    private static func fileAAD(_ head: Data) -> Data { Data("harbor0/e2ee/v1/link-file".utf8) + head.prefix(headSize) }
    /// A stored version's content key, re-sealed for a link, from the 80-byte header the server keeps.
    static func wrapFileKey(_ keys: AccountKeys, _ link: LinkKeys, header: Data, iv: Data = E2ee.random(12)) throws -> (keyWrap: String, head: String) {
        let head = Data(header.prefix(E2ee.headerSize))
        guard head.count == E2ee.headerSize else { throw E2eeError(code: "CORRUPT", message: "This file’s header is damaged.") }
        let content = try E2ee.open(keys.fileWrap, iv: head.subdata(in: 20..<32), head.subdata(in: 32..<80), aad: head.prefix(20))
        let short = Data(head.prefix(headSize))
        return (try sealed(link.files, content, aad: fileAAD(short), iv: iv), E2ee.b64url(short))
    }
}
