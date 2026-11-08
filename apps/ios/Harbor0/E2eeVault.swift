import Foundation
import Combine
import Security

/// This phone's copy of an account key: it opens only with the half the server keeps.
struct E2eeDeviceCopy: Codable, Equatable {
    let userId: String
    let recordId: String
    let local: String
    let envelope: DeviceEnvelope
}

protocol E2eeCopyStore {
    func load(_ userId: String) -> E2eeDeviceCopy?
    func save(_ copy: E2eeDeviceCopy) throws
    func clear(_ userId: String)
}

final class MemoryE2eeCopies: E2eeCopyStore {
    private var copies: [String: E2eeDeviceCopy] = [:]
    func load(_ userId: String) -> E2eeDeviceCopy? { copies[userId] }
    func save(_ copy: E2eeDeviceCopy) throws { copies[copy.userId] = copy }
    func clear(_ userId: String) { copies[userId] = nil }
}

/// Remembered copies in the shared keychain group, readable after first unlock, so the Files
/// extension can open the account too. Never synced to iCloud and never in backups.
struct KeychainE2eeCopies: E2eeCopyStore {
    let service: String
    init(baseURL: URL) { service = "app.harbor0.e2ee." + baseURL.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) }
    private func query(_ userId: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
         kSecAttrAccount as String: "copy." + E2ee.sha256Hex(Data(userId.utf8))]
    }
    func load(_ userId: String) -> E2eeDeviceCopy? {
        var request = query(userId)
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess, let data = result as? Data,
              let copy = try? JSONDecoder().decode(E2eeDeviceCopy.self, from: data), copy.userId == userId else { return nil }
        return copy
    }
    func save(_ copy: E2eeDeviceCopy) throws {
        let values: [String: Any] = [kSecValueData as String: try JSONEncoder().encode(copy),
                                     kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let base = query(copy.userId)
        var status = SecItemUpdate(base as CFDictionary, values as CFDictionary)
        if status == errSecItemNotFound { status = SecItemAdd(base.merging(values) { _, new in new } as CFDictionary, nil) }
        guard status == errSecSuccess else { throw APIError(status: 0, message: "Could not keep this iPhone unlocked securely. Try again.") }
    }
    func clear(_ userId: String) { SecItemDelete(query(userId) as CFDictionary) }
}

/// A thread-safe copy of the vault's view.
final class E2eeSnapshot: @unchecked Sendable {
    private let lock = NSLock()
    private var value = E2eeView()
    func get() -> E2eeView { lock.lock(); defer { lock.unlock() }; return value }
    func set(_ next: E2eeView) { lock.lock(); value = next; lock.unlock() }
}

/// How opening the saved copy went.
enum E2eeRestore { case unlocked, noCopy, removed, failed }

/// The account's encryption state (`GET /v1/e2ee`, without the wrapped keys), as Settings shows it.
struct E2eeState: Decodable {
    struct Progress: Decodable {
        var totalFiles = 0; var doneFiles = 0; var totalBytes: Int64 = 0; var doneBytes: Int64 = 0
        var skippedFiles: Int? = nil; var complete: Bool? = nil; var deviceName: String? = nil; var updatedAt: String? = nil
    }
    struct DeviceRecord: Decodable, Identifiable {
        let id: String; var deviceId: String? = nil; let name: String; var platform: String? = nil; var remember: Bool? = nil
        var createdAt: String? = nil; var lastUsedAt: String? = nil
    }
    var enabled: Bool? = nil
    var enabledAt: String? = nil
    var passphraseChangedAt: String? = nil
    var recoveryCreatedAt: String? = nil
    var decryptingSince: String? = nil
    var migration: Progress? = nil
    var decryption: Progress? = nil
    var devices: [DeviceRecord]? = nil
}

/**
 * The signed-in account's end-to-end encryption on this phone, as the desktop and Android vaults.
 * The keys live in memory only. A remembered unlock saves a copy in the Keychain that also needs
 * the server's half, so removing this phone in the web app's encryption settings locks it for good;
 * one not remembered keeps its copy in memory, so the app locks when its process ends, and the
 * server forgets its half after 7 days unused.
 */
@MainActor
final class E2eeVault: ObservableObject {
    @Published private(set) var view = E2eeView() { didSet { snapshot.set(view) } }
    /// The view for actors off the main thread (sync, transfers).
    nonisolated let snapshot = E2eeSnapshot()
    private let store: E2eeCopyStore
    private var deviceRecordId: String?
    private var sessionCopy: E2eeDeviceCopy?
    init(store: E2eeCopyStore) { self.store = store }

    var uploadKeys: AccountKeys? { view.uploadKeys }
    var readKeys: AccountKeys? { view.readKeys }
    var locked: Bool { view.locked }
    /// This phone's record among the account's unlocked devices, while unlocked.
    var deviceRecord: String? { view.keys != nil ? deviceRecordId : nil }
    /// Whether this phone's copy is remembered (survives the app closing).
    var remembered: Bool { deviceRecordId != nil && sessionCopy == nil }
    nonisolated var transferKeys: TransferKeys { let snapshot = snapshot; return TransferKeys { snapshot.get() } }

    /// A transport that keeps this vault in step with the account.
    func transport(_ api: HarborAPI, strict: Bool = false) -> E2eeTransport {
        E2eeTransport(inner: { [weak api] path, method, body in
            guard let api else { throw CancellationError() }
            return try await api.direct(path, method: method, body: body)
        }, vault: { [weak self] in self?.view ?? E2eeView() }, onAccount: { [weak self] id, enabled, decrypting in
            self?.setAccount(id, enabled: enabled, decrypting: decrypting)
        }, strict: strict)
    }

    /// Called with each /v1/users/me answer; a different account starts locked.
    func setAccount(_ userId: String, enabled: Bool, decrypting: Bool) {
        let before = view
        if before.userId != userId {
            deviceRecordId = nil; sessionCopy = nil
            view = E2eeView(userId: userId, enabled: enabled, decrypting: decrypting, keys: nil)
            return
        }
        if before.enabled && !enabled {
            // Turned off: the saved copy opens nothing any more.
            store.clear(userId)
            deviceRecordId = nil; sessionCopy = nil
            view = E2eeView(userId: userId, enabled: false, decrypting: false, keys: nil)
            return
        }
        guard before.enabled != enabled || before.decrypting != decrypting else { return }
        view.enabled = enabled; view.decrypting = decrypting
    }
    /// Signed out: forget the keys, keeping the saved copy for the next sign-in.
    func reset() { deviceRecordId = nil; sessionCopy = nil; view = E2eeView() }

    /// Opens this phone's saved copy, if the server still recognises it.
    func restore(_ api: HarborAPI) async -> E2eeRestore {
        guard let userId = view.userId else { return .noCopy }
        if view.keys != nil { return .unlocked }
        guard let saved = sessionCopy.flatMap({ $0.userId == userId ? $0 : nil }) ?? store.load(userId) else { return .noCopy }
        do {
            let result = try await api.direct("/v1/e2ee/devices/\(E2eeTransport.segment(saved.recordId))/secret", method: "POST")
            guard let secret = result["serverSecret"] as? String else { return .failed }
            let master = try E2ee.unwrapForDevice(saved.envelope, local: try E2ee.fromB64url(saved.local), server: try E2ee.fromB64url(secret))
            guard view.userId == userId else { return .failed }
            deviceRecordId = saved.recordId
            view.keys = E2ee.accountKeys(master)
            return .unlocked
        } catch {
            // Removed from the web app (E2EE_DEVICE_REMOVED), or damaged: the passphrase unlocks it again.
            if (error as? APIError)?.status == 404 || error is E2eeError { sessionCopy = nil; store.clear(userId); return .removed }
            return .failed
        }
    }

    private func keep(_ api: HarborAPI, _ keys: AccountKeys, remember: Bool) async throws {
        guard let userId = view.userId else { throw APIError(status: 0, message: "Sign in to continue.") }
        let local = E2ee.random(32), server = E2ee.random(32)
        let envelope = try E2ee.wrapForDevice(keys.master, local: local, server: server)
        let result = try await api.direct("/v1/e2ee/devices", method: "POST", body: [
            "proof": keys.proof, "serverSecret": E2ee.b64url(server), "remember": remember
        ])
        guard let id = result["id"] as? String else { throw URLError(.cannotParseResponse) }
        let copy = E2eeDeviceCopy(userId: userId, recordId: id, local: E2ee.b64url(local), envelope: envelope)
        if remember { try store.save(copy); sessionCopy = nil }
        else { sessionCopy = copy; store.clear(userId) }
        deviceRecordId = id
        view.keys = keys
    }
    private func state(_ api: HarborAPI) async throws -> [String: Any] {
        guard let state = try await api.direct("/v1/e2ee")["e2ee"] as? [String: Any] else {
            throw APIError(status: 0, message: "Encryption is off for this account.")
        }
        return state
    }
    private static func decode<T: Decodable>(_ value: Any?) throws -> T {
        guard let value else { throw APIError(status: 0, message: "Encryption is off for this account.") }
        return try JSONDecoder().decode(T.self, from: JSONSerialization.data(withJSONObject: value))
    }
    /// Unlocks with the passphrase; a wrong one throws WRONG_KEY. Argon2id takes a moment.
    func unlock(_ api: HarborAPI, passphrase: String, remember: Bool = true) async throws {
        let envelope: PassphraseEnvelope = try Self.decode(try await state(api)["passphrase"])
        let master = try await Task.detached(priority: .userInitiated) { try E2ee.unwrapWithPassphrase(envelope, passphrase: passphrase) }.value
        try await keep(api, E2ee.accountKeys(master), remember: remember)
    }
    /// Unlocks with the recovery key and sets a new passphrase, as after a forgotten one.
    func unlock(_ api: HarborAPI, recovery: Data, newPassphrase: String, remember: Bool = true) async throws {
        let envelope: RecoveryEnvelope = try Self.decode(try await state(api)["recovery"])
        let (master, passphrase) = try await Task.detached(priority: .userInitiated) {
            let master = try E2ee.unwrapWithRecovery(envelope, recovery: recovery)
            return (master, try E2ee.wrapWithPassphrase(master, passphrase: newPassphrase))
        }.value
        let keys = E2ee.accountKeys(master)
        _ = try await api.direct("/v1/e2ee/passphrase", method: "PUT", body: ["passphrase": passphrase.json, "proof": keys.proof])
        try await keep(api, keys, remember: remember)
    }
    func requireKeys() throws -> AccountKeys {
        guard let keys = view.keys else { throw e2eeLocked() }
        return keys
    }
    /// Turns encryption on for the account (web `enroll`); this phone then keeps its own copy.
    func enroll(_ api: HarborAPI, passphrase: String, recovery: Data, remember: Bool = true) async throws {
        let master = E2ee.newMasterKey()
        let (body, keys) = try await Task.detached(priority: .userInitiated) { () -> ([String: Any], AccountKeys) in
            let keys = E2ee.accountKeys(master)
            return (["passphrase": try E2ee.wrapWithPassphrase(master, passphrase: passphrase).json,
                     "recovery": try E2ee.wrapWithRecovery(master, recovery: recovery).json, "proof": keys.proof], keys)
        }.value
        _ = try await api.direct("/v1/e2ee/enroll", method: "POST", body: body)
        view.enabled = true; view.decrypting = false
        try await keep(api, keys, remember: remember)
    }
    /// A new passphrase for the same key: files stay as they are, unlocked devices stay unlocked.
    func changePassphrase(_ api: HarborAPI, passphrase: String) async throws {
        let keys = try requireKeys()
        let master = keys.master
        let envelope = try await Task.detached(priority: .userInitiated) { try E2ee.wrapWithPassphrase(master, passphrase: passphrase) }.value
        _ = try await api.direct("/v1/e2ee/passphrase", method: "PUT", body: ["passphrase": envelope.json, "proof": keys.proof])
    }
    /// A new recovery key; the old one stops working.
    func replaceRecovery(_ api: HarborAPI, recovery: Data) async throws {
        let keys = try requireKeys()
        _ = try await api.direct("/v1/e2ee/recovery", method: "PUT", body: [
            "recovery": try E2ee.wrapWithRecovery(keys.master, recovery: recovery).json, "proof": keys.proof
        ])
    }
    /// Keeps this phone unlocked after the app closes: a remembered copy replaces this session's.
    func rememberPhone(_ api: HarborAPI) async throws {
        let keys = try requireKeys()
        let previous = deviceRecordId
        try await keep(api, keys, remember: true)
        if let previous { _ = try? await api.direct("/v1/e2ee/devices/\(E2eeTransport.segment(previous))", method: "DELETE") }
    }
    /// Forgets the key on this phone and removes its copy; it needs the passphrase again.
    func lock(_ api: HarborAPI?) async {
        let userId = view.userId
        let record = deviceRecordId ?? sessionCopy?.recordId ?? userId.flatMap { store.load($0)?.recordId }
        sessionCopy = nil
        if let userId { store.clear(userId) }
        deviceRecordId = nil
        view.keys = nil
        if let api, let record { _ = try? await api.direct("/v1/e2ee/devices/\(E2eeTransport.segment(record))", method: "DELETE") }
    }
}
