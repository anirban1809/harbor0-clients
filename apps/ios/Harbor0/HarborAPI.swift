import Foundation
import Combine
#if canImport(UIKit)
import UIKit
#endif

@MainActor
final class HarborAPI: ObservableObject {
    @Published private(set) var signedIn = false
    @Published private(set) var restoring = true
    @Published var sessionMessage: String?
    /// The account is end-to-end encrypted and this build can't open it: file routes answer 426 E2EE_UPDATE_REQUIRED.
    @Published private(set) var encryptionBlocked = false
    func clearEncryptionBlock() { encryptionBlocked = false }
    /// Set while the sync engine is at work: its requests carry X-Harbor-Background (contracts `BACKGROUND_HEADER`),
    /// so the staff console doesn't count them as a person using the account.
    nonisolated static let background = TaskLocal(wrappedValue: false)
    let baseURL: URL
    private let session: URLSession
    private let credentials: CredentialStore
    private let deviceKeys: DeviceKeyStore?
    #if canImport(UIKit)
    private(set) var deviceName = UIDevice.current.model
    #else
    private(set) var deviceName = Host.current().localizedName ?? "Mac"
    #endif
    private var pendingDeviceName: String?
    private struct DeviceResponse: Decodable { struct Device: Decodable { let id: String; let name: String }; let device: Device }
    private var saved: SavedSession?
    private var refreshTask: Task<Void, Error>?
    private var generation = UUID()
    /// False for the Mac Finder extension: the harbor0 app owns the session and renews it.
    var renews = true
    /// The build sent with the device proof and sync registration; the server gates encrypted accounts and flags by it.
    static let appVersion: String = (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String).map { String($0.prefix(32)) } ?? "0"
    /// Wraps every signed-in request when set: on an end-to-end encrypted account the vault's transport encrypts
    /// names going out and decrypts names and sizes coming back, so the rest of the app only sees plaintext.
    var transport: E2eeTransport?
    /// The keys uploads and downloads use on an end-to-end encrypted account; read from any actor.
    nonisolated(unsafe) var transferKeys = TransferKeys.none
    /// Milliseconds before retry `attempt` of a throttled request; `retryAfter` is the server's hint in seconds.
    var retryDelay: (Int, Double?) -> Double = { attempt, after in
        if let after { return min(10, max(0, after)) * 1000 }
        let step = 500.0 * pow(2, Double(attempt))
        return step / 2 + Double.random(in: 0...(step / 2))
    }
    private struct Envelope: Decodable {
        struct Details: Decodable { let folderId: String?; var session: String? = nil }
        struct Failure: Decodable {
            let message: String
            let code: String?
            let details: Details?
            enum Keys: String, CodingKey { case message, code, details }
            init(from decoder: Decoder) throws {
                let container = try decoder.container(keyedBy: Keys.self)
                message = try container.decode(String.self, forKey: .message)
                code = try container.decodeIfPresent(String.self, forKey: .code)
                details = try? container.decodeIfPresent(Details.self, forKey: .details)
            }
        }
        let error: Failure
    }
    var canRestore: Bool { saved != nil }

    /// Device keys default to the Keychain alongside default credentials; injected stores opt in explicitly.
    init(baseURL: URL = HarborConfiguration.apiURL, session: URLSession = .shared, credentials: CredentialStore? = nil, deviceKeys: DeviceKeyStore? = nil) {
        self.baseURL = baseURL
        self.session = session
        self.credentials = credentials ?? KeychainStore(service: SharedAccount.credentialService(for: baseURL))
        self.deviceKeys = deviceKeys ?? (credentials == nil ? KeychainDeviceKeys(service: "app.harbor0.ios." + baseURL.absoluteString) : nil)
    }

    func restore() async {
        restoring = true
        sessionMessage = nil
        defer { restoring = false }
        do {
            saved = try credentials.load()
            guard saved != nil else { return }
            if let name = saved?.deviceName { deviceName = name }
            try await refresh()
            // Sessions from before signed device identity are upgraded once; a failure retries next launch.
            // A new build says so too, so the server knows this version can encrypt.
            if var current = saved, current.deviceProven != true || current.appVersion != Self.appVersion,
               (try? await proveDevice(token: current.tokens.accessToken)) != nil {
                current.deviceProven = true
                current.appVersion = Self.appVersion
                try? credentials.save(current)
                saved = current
            }
            signedIn = true
        } catch {
            sessionMessage = error.localizedDescription
        }
    }

    /// Signs in with the password. Accounts with two-step verification get a challenge instead of tokens;
    /// finish with `loginVerify`. Returns nil once signed in.
    @discardableResult
    func login(email: String, password: String, deviceName: String) async throws -> TwoFactorChallenge? {
        let result: LoginResult = try await raw("/v1/auth/login", method: "POST", body: [
            "email": email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
            "password": password, "deviceName": deviceName, "platform": "IOS",
            // This app can ask for a second-step code, so the server sends a challenge rather than TWO_FACTOR_UNSUPPORTED.
            "twoFactor": true
        ])
        if let challenge = result.twoFactor { return challenge }
        guard let tokens = result.tokens else { throw URLError(.badServerResponse) }
        try await signIn(tokens, deviceName: deviceName)
        return nil
    }
    /// Chooses how to finish a two-step sign-in; EMAIL sends a code now. The returned session replaces the old one.
    func loginMethod(email: String, session: String, method: String) async throws -> TwoFactorChallenge {
        struct Result: Decodable { let twoFactor: TwoFactorChallenge }
        let result: Result = try await raw("/v1/auth/login/method", method: "POST", body: [
            "email": email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(), "session": session, "method": method
        ])
        return result.twoFactor
    }
    /// Finishes a two-step sign-in. A wrong code throws AUTH_INVALID whose details carry the session to keep;
    /// AUTH_EXPIRED means starting again from the password.
    func loginVerify(email: String, session: String, method: String, code: String, deviceName: String) async throws {
        let tokens: Tokens = try await raw("/v1/auth/login/verify", method: "POST", body: [
            "email": email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(), "session": session,
            "method": method, "code": code, "platform": "IOS", "deviceName": deviceName
        ])
        try await signIn(tokens, deviceName: deviceName)
    }
    private func signIn(_ tokens: Tokens, deviceName: String) async throws {
        self.deviceName = deviceName
        pendingDeviceName = deviceName
        // Servers without signed device identity don't offer the challenge; restore() upgrades later.
        var proven = true
        do { try await proveDevice(token: tokens.accessToken) }
        catch let error as APIError where error.status == 404 { proven = false }
        try persist(tokens, deviceProven: proven)
        generation = UUID()
        sessionMessage = nil
        encryptionBlocked = false
        signedIn = true
    }

    func logout() async throws {
        // Revoke remotely before removing the local credential, so failures are retryable.
        let stamp = generation
        if saved != nil {
            if saved!.expiresAt.timeIntervalSinceNow < 60 { try await refresh() }
            func revoke() async throws {
                guard let tokens = saved?.tokens else { return }
                let _: EmptyResponse = try await raw("/v1/auth/logout", method: "POST", body: ["refreshToken": tokens.refreshToken], token: tokens.accessToken)
            }
            do { try await revoke() }
            catch let error as APIError where error.status == 401 {
                try await refresh()
                try await revoke()
            } catch let error as APIError where error.code == "DEVICE_REVOKED" {
                // The server has already revoked this session.
            }
        }
        guard generation == stamp else { throw CancellationError() }
        try clear()
    }

    func forgetLocalSession() throws { try clear() }

    /// For the Files extension: use the session the app saved, without refreshing or proving it.
    @discardableResult
    func loadStoredSession() -> Bool {
        saved = try? credentials.load()
        signedIn = saved != nil
        return signedIn
    }

    /// The app and the Files extension share one session and refresh tokens rotate, so adopt
    /// tokens the other process saved instead of refreshing with a stale one.
    private func adoptStoredTokens() -> Bool {
        guard let stored = try? credentials.load(),
              stored.tokens.refreshToken != saved?.tokens.refreshToken || stored.tokens.accessToken != saved?.tokens.accessToken else { return false }
        saved = stored
        return true
    }

    /// This session's device record. Sync checkpoints and backups are tied to its id; the
    /// existing signed device identity is kept because no devicePublicId is sent.
    func registerSyncDevice() async throws -> (id: String, name: String) {
        let response: DeviceResponse = try await request("/v1/sync/devices/register", method: "POST",
                                                         body: ["name": String(deviceName.prefix(100)), "platform": "IOS", "appVersion": Self.appVersion])
        return (response.device.id, response.device.name)
    }

    /// Signed-out account flows: confirm, resend, forgot and reset.
    func publicRequest<T: Decodable>(_ path: String, body: [String: Any]) async throws -> T {
        try await raw(path, method: "POST", body: body)
    }

    private func clear() throws {
        generation = UUID()
        refreshTask?.cancel()
        refreshTask = nil
        saved = nil
        signedIn = false
        encryptionBlocked = false
        try credentials.clear()
    }

    /// Binds this session to the installation's key so the server can tell devices apart.
    private func proveDevice(token: String) async throws {
        guard let deviceKeys else { return }
        let challenge: DeviceChallenge = try await raw("/v1/auth/session/challenge", method: "POST", body: nil, token: token)
        let key = try deviceKeys.key(for: challenge.userId)
        let _: EmptyResponse = try await raw("/v1/auth/session", method: "POST", body: [
            "name": deviceName, "platform": "IOS", "devicePublicId": key.fingerprint, "appVersion": Self.appVersion,
            "proof": try key.proof(challenge: challenge.challenge, userID: challenge.userId)
        ], token: token)
    }

    private func persist(_ tokens: Tokens, deviceProven: Bool? = nil) throws {
        let next = SavedSession(tokens: tokens, expiresAt: Date().addingTimeInterval(tokens.expiresIn),
                                deviceProven: deviceProven ?? saved?.deviceProven,
                                deviceName: pendingDeviceName ?? saved?.deviceName,
                                appVersion: deviceProven == nil ? saved?.appVersion : Self.appVersion)
        pendingDeviceName = nil
        try credentials.save(next)
        saved = next
    }

    private func refresh() async throws {
        if let pending = refreshTask { return try await pending.value }
        if adoptStoredTokens(), let saved, saved.expiresAt.timeIntervalSinceNow > 60 { return }
        guard renews else { throw APIError(status: 401, message: "Open harbor0 to continue.") }
        guard let token = saved?.tokens.refreshToken else {
            throw APIError(status: 401, message: "Sign in to continue.")
        }
        let stamp = generation
        let task = Task { @MainActor in
            let tokens: Tokens = try await self.raw("/v1/auth/refresh", method: "POST", body: ["refreshToken": token])
            try Task.checkCancellation()
            guard self.generation == stamp else { throw CancellationError() }
            try self.persist(tokens)
        }
        refreshTask = task
        defer { if generation == stamp { refreshTask = nil } }
        do { try await task.value }
        catch let error as APIError where error.status == 401 || error.status == 403 {
            guard generation == stamp else { throw CancellationError() }
            // The other process may have rotated the refresh token at the same moment.
            if adoptStoredTokens(), let saved, saved.expiresAt.timeIntervalSinceNow > 60 { return }
            try clear()
            sessionMessage = "Your session expired. Sign in again."
            throw error
        }
    }

    func request<T: Decodable>(_ path: String, method: String = "GET", body: [String: Any]? = nil) async throws -> T {
        let data: Data
        if let transport {
            let value = try await transport.call(path, method, body)
            data = try JSONSerialization.data(withJSONObject: value)
        } else {
            data = try await directData(path, method: method, body: body)
        }
        return try Self.decode(data)
    }
    /// A signed-in request's answer as JSON, through the transport.
    func json(_ path: String, method: String = "GET", body: [String: Any]? = nil) async throws -> [String: Any] {
        if let transport { return try await transport.call(path, method, body) }
        return try Self.object(try await directData(path, method: method, body: body))
    }
    /// A signed-in request exactly as the server sends and receives it, without the transport.
    func direct(_ path: String, method: String = "GET", body: [String: Any]? = nil) async throws -> [String: Any] {
        try Self.object(try await directData(path, method: method, body: body))
    }
    static func object(_ data: Data) throws -> [String: Any] {
        if data.isEmpty { return [:] }
        guard let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw URLError(.cannotParseResponse) }
        return value
    }
    static func decode<T: Decodable>(_ data: Data) throws -> T {
        if data.isEmpty, let empty = EmptyResponse() as? T { return empty }
        return try JSONDecoder().decode(T.self, from: data)
    }

    private func directData(_ path: String, method: String, body: [String: Any]?) async throws -> Data {
        let stamp = generation
        if let saved, saved.expiresAt.timeIntervalSinceNow < 60 { try await refresh() }
        guard let token = saved?.tokens.accessToken else { throw APIError(status: 401, message: "Sign in to continue.") }
        do {
            let value = try await send(path, method: method, body: body, token: token)
            guard stamp == generation else { throw CancellationError() }
            return value
        } catch let error as APIError where error.code == "DEVICE_REVOKED" {
            guard generation == stamp else { throw CancellationError() }
            try clear()
            sessionMessage = "This device was signed out. Sign in again."
            throw error
        } catch let error as APIError where error.status == 401 {
            guard stamp == generation else { throw CancellationError() }
            try await refresh()
            do {
                let value = try await send(path, method: method, body: body, token: saved?.tokens.accessToken)
                guard stamp == generation else { throw CancellationError() }
                return value
            } catch let retryError as APIError where retryError.status == 401 || retryError.code == "DEVICE_REVOKED" {
                guard generation == stamp else { throw CancellationError() }
                try clear()
                sessionMessage = "Your session expired. Sign in again."
                throw retryError
            }
        }
    }

    private func raw<T: Decodable>(_ path: String, method: String, body: [String: Any]?, token: String? = nil) async throws -> T {
        try Self.decode(try await send(path, method: method, body: body, token: token))
    }

    /// One API call. Signed-in calls refused by throttling (429 RATE_LIMITED) or an unavailable server (503) are
    /// tried up to 3 times, honoring Retry-After; both are refused before the handler runs, so repeating is safe.
    private func send(_ path: String, method: String, body: [String: Any]?, token: String?) async throws -> Data {
        guard let url = URL(string: baseURL.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path) else {
            throw APIError(status: 0, message: "The server address is invalid.")
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 45
        if let token { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        if Self.background.get() { request.setValue("1", forHTTPHeaderField: "X-Harbor-Background") }
        if let body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        var attempt = 0
        while true {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
            if (200..<300).contains(http.statusCode) { return data }
            let detail = try? JSONDecoder().decode(Envelope.self, from: data)
            let code = detail?.error.code
            // Only throttling: other 429s, like USERNAME_CHANGE_LIMIT, are answers.
            let throttled = http.statusCode == 503 || (http.statusCode == 429 && (code == nil || code == "RATE_LIMITED"))
            if token == nil || !throttled || attempt >= 2 {
                if code == "E2EE_UPDATE_REQUIRED" { encryptionBlocked = true }
                throw APIError(status: http.statusCode, message: detail?.error.message ?? "The server could not complete this request (\(http.statusCode)). Try again.",
                               code: code, folderID: detail?.error.details?.folderId, session: detail?.error.details?.session)
            }
            let after = http.value(forHTTPHeaderField: "Retry-After").flatMap { Double($0.trimmingCharacters(in: .whitespaces)) }
            try await Task.sleep(nanoseconds: UInt64(retryDelay(attempt, after) * 1_000_000))
            attempt += 1
        }
    }

    func list(parentID: String?, cursor: String? = nil) async throws -> DrivePage {
        var query = URLComponents()
        if let cursor { query.queryItems = [URLQueryItem(name: "cursor", value: cursor)] }
        let suffix = query.percentEncodedQuery.map { "?" + $0 } ?? ""
        return try await request("/v1/drive/folders/\(parentID ?? "root")/children" + suffix)
    }

    @discardableResult
    func createFolder(name: String, parentID: String?) async throws -> DriveItem {
        let response: ItemResponse = try await request("/v1/drive/folders", method: "POST", body: [
            "name": name, "parentId": parentID as Any? ?? NSNull(), "operationId": UUID().uuidString
        ])
        return response.item
    }

    static func pagePath(_ path: String, cursor: String?) -> String {
        guard let cursor else { return path }
        var query = URLComponents()
        query.queryItems = [URLQueryItem(name: "cursor", value: cursor)]
        return path + (path.contains("?") ? "&" : "?") + (query.percentEncodedQuery ?? "")
    }

    func trashPage(cursor: String? = nil) async throws -> DrivePage {
        try await request(Self.pagePath("/v1/search?trash=true", cursor: cursor))
    }

    enum TrashAction { case trash, restore, permanent }
    func changeTrash(_ item: DriveItem, action: TrashAction) async throws {
        guard let revision = item.revision, revision > 0 else {
            throw APIError(status: 0, message: "Refresh this list before changing the file.")
        }
        let suffix = action == .restore ? "/restore" : (action == .permanent ? "/permanent" : "")
        let _: EmptyResponse = try await request("/v1/drive/items/\(item.id)" + suffix,
            method: action == .restore ? "POST" : "DELETE",
            body: ["operationId": UUID().uuidString, "baseRevision": revision])
    }

    func emptyTrash() async throws {
        var cursor: String?
        var seen = Set<String>()
        repeat {
            var body: [String: Any] = ["operationId": UUID().uuidString]
            if let cursor { body["cursor"] = cursor }
            let page: TrashResult = try await request("/v1/drive/trash/empty", method: "POST", body: body)
            cursor = page.nextCursor
            if let cursor, !seen.insert(cursor).inserted {
                throw APIError(status: 0, message: "Trash cleanup stopped unexpectedly. Refresh and try again.")
            }
        } while cursor != nil
    }
}
