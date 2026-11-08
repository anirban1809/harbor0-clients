import Foundation

/// What the app shares with the harbor0 Files extension: the server, the signed-in account and
/// the session in the shared keychain group (see Keychain.swift).
enum SharedAccount {
    #if os(macOS)
    /// The Mac app's group, recorded in the extension's Info.plist when it is built.
    static let appGroup = Bundle.main.object(forInfoDictionaryKey: "HarborAppGroup") as? String ?? ""
    #else
    static let appGroup = "group.app.harbor0.ios"
    #endif
    private static var defaults: UserDefaults? { UserDefaults(suiteName: appGroup) }

    static func credentialService(for baseURL: URL) -> String { "app.harbor0.ios." + baseURL.absoluteString }

    /// Recorded by the app after sign-in so the extension uses the same server and account.
    static func save(apiURL: URL, userId: String) {
        defaults?.set(apiURL.absoluteString, forKey: "apiURL")
        defaults?.set(userId, forKey: "userId")
    }
    #if os(macOS)
    /// The Mac app (Electron) writes `account.json` into the group container instead: it has no
    /// UserDefaults suite or keychain group, and it keeps the refresh token to itself.
    struct MacAccount: Decodable {
        let apiUrl: String
        let userId: String?
        let accessToken: String?
        /// Milliseconds since 1970.
        let accessExpiresAt: Double?
    }
    static var mac: MacAccount? {
        guard let folder = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup),
              let data = try? Data(contentsOf: folder.appendingPathComponent("account.json")) else { return nil }
        return try? JSONDecoder().decode(MacAccount.self, from: data)
    }
    static var apiURL: URL? { mac.flatMap { URL(string: $0.apiUrl) } }
    static var userId: String? { mac?.userId }
    #else
    static var apiURL: URL? { defaults?.string(forKey: "apiURL").flatMap(URL.init(string:)) }
    static var userId: String? { defaults?.string(forKey: "userId") }
    #endif
    static func clear() {
        defaults?.removeObject(forKey: "userId")
    }
}

#if os(macOS)
/// The access token the Mac app last renewed. The extension never renews it itself: refresh
/// tokens rotate, and two processes renewing one session sign each other out.
struct MacAppCredentials: CredentialStore {
    func load() throws -> SavedSession? {
        guard let account = SharedAccount.mac, let token = account.accessToken, !token.isEmpty,
              let expires = account.accessExpiresAt else { return nil }
        let expiresAt = Date(timeIntervalSince1970: expires / 1000)
        return SavedSession(tokens: Tokens(accessToken: token, refreshToken: "", expiresIn: expiresAt.timeIntervalSinceNow),
                            expiresAt: expiresAt)
    }
    func save(_ session: SavedSession) throws {}
    func clear() throws {}
}
#endif
