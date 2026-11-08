import XCTest
@testable import Harbor0

/// An end-to-end encrypted account from the iPhone against the real local backend (test-ios.mjs turns the `e2ee` flag on).
@MainActor
final class E2eeIntegrationTests: XCTestCase {
    func testEnrollRememberLockAndUnlockAgainstTheBackend() async throws {
        let base = URL(string: "http://127.0.0.1:18988")!
        let marker = UUID().uuidString.lowercased().replacingOccurrences(of: "-", with: "")
        let email = "ios-e2ee-\(marker)@example.test"
        let password = "Development-only-123!"
        func post(_ path: String, _ body: [String: String]) async throws {
            var request = URLRequest(url: base.appendingPathComponent(path))
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            let (data, response) = try await URLSession.shared.data(for: request)
            XCTAssertTrue((200..<300).contains((response as! HTTPURLResponse).statusCode), String(decoding: data, as: UTF8.self))
        }
        try await post("v1/auth/signup", ["email": email, "password": password, "username": "ios_e_" + marker.prefix(18), "displayName": "iOS encryption"])
        try await post("v1/auth/confirm", ["email": email, "code": "123456"])
        let keychain = KeychainStore(service: "app.harbor0.integration." + marker)
        defer { try? keychain.clear() }
        let api = HarborAPI(baseURL: base, credentials: keychain)
        let copies = MemoryE2eeCopies()
        let vault = E2eeVault(store: copies)
        api.transport = vault.transport(api, strict: true)
        api.transferKeys = vault.transferKeys
        try await api.login(email: email, password: password, deviceName: "iPhone simulator")
        // Injected credentials skip the device key proof, so the build's version goes up with the sync registration.
        _ = try await api.registerSyncDevice()
        let before: Account = try await api.request("/v1/users/me")
        guard before.flag("e2ee") else { throw XCTSkip("The e2ee flag is off on this server.") }
        XCTAssertFalse(vault.view.enabled)

        let recovery = E2ee.newRecoveryKey()
        try await vault.enroll(api, passphrase: "correct horse battery staple", recovery: recovery)
        XCTAssertTrue(vault.view.enabled)
        XCTAssertNotNil(vault.view.keys)
        XCTAssertTrue(vault.remembered)
        let proof = vault.view.keys!.proof
        let me: Account = try await api.request("/v1/users/me")
        XCTAssertTrue(me.encrypted)

        // Another launch: the remembered copy opens with the server's half.
        let second = E2eeVault(store: copies)
        second.setAccount(me.user.id, enabled: true, decrypting: false)
        let restored = await second.restore(api)
        XCTAssertEqual(restored, .unlocked)
        XCTAssertEqual(second.view.keys?.proof, proof)

        // Locking removes this phone's copy; then the passphrase and the recovery key both unlock it again.
        await vault.lock(api)
        XCTAssertTrue(vault.locked)
        let again = await second.restore(api)
        XCTAssertEqual(again, .unlocked) // That session already held the keys.
        let third = E2eeVault(store: copies)
        third.setAccount(me.user.id, enabled: true, decrypting: false)
        let none = await third.restore(api)
        XCTAssertEqual(none, .noCopy)
        do { try await vault.unlock(api, passphrase: "wrong passphrase here"); XCTFail("Must fail") }
        catch { XCTAssertEqual((error as? E2eeError)?.code, "WRONG_KEY") }
        try await vault.unlock(api, passphrase: "correct horse battery staple", remember: false)
        XCTAssertEqual(vault.view.keys?.proof, proof)
        XCTAssertFalse(vault.remembered)
        await vault.lock(api)
        try await vault.unlock(api, recovery: recovery, newPassphrase: "a brand new passphrase")
        XCTAssertEqual(vault.view.keys?.proof, proof)
        await vault.lock(api)
        try await vault.unlock(api, passphrase: "a brand new passphrase")

        // File routes: once the server lists iOS in E2EE_CLIENTS, names go up encrypted and come back readable.
        do {
            try await api.createFolder(name: "Encrypted from iPhone", parentID: nil)
        } catch let error as APIError where error.code == "E2EE_UPDATE_REQUIRED" {
            XCTAssertTrue(api.encryptionBlocked)
            throw XCTSkip("The server doesn't list iOS in E2EE_CLIENTS yet, so encrypted file routes refuse this build.")
        }
        let listed = try await api.list(parentID: nil)
        XCTAssertTrue(listed.items.contains { $0.name == "Encrypted from iPhone" })
        let raw = try await api.direct("/v1/drive/folders/root/children")
        let names = (raw["items"] as? [[String: Any]] ?? []).compactMap { $0["name"] as? String }
        XCTAssertTrue(names.allSatisfy(E2ee.isEncryptedName), "The server must only hold encrypted names")
        let source = FileManager.default.temporaryDirectory.appendingPathComponent("secret-\(marker).txt")
        try Data("only my devices can read this".utf8).write(to: source)
        defer { try? FileManager.default.removeItem(at: source) }
        let transfers = FileTransfers(api: api)
        try await transfers.upload(source, parentID: nil) { _, _ in }
        let after = try await api.list(parentID: nil)
        let file = try XCTUnwrap(after.items.first { $0.name == source.lastPathComponent })
        XCTAssertTrue(file.isEncrypted)
        XCTAssertEqual(file.sizeBytes, 29)
        let back = try await transfers.download(file) { _, _ in }
        defer { FileTransfers.removePreview(back) }
        XCTAssertEqual(try String(contentsOf: back, encoding: .utf8), "only my devices can read this")
    }
}
