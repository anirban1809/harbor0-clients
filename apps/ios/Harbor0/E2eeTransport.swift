import Foundation

/// What the transport needs to know about the signed-in account's encryption.
struct E2eeView {
    var userId: String? = nil
    /// The account is encrypted.
    var enabled = false
    /// Being turned off: new names go up unencrypted, stored ones are still read.
    var decrypting = false
    /// Present once this app has unlocked the account.
    var keys: AccountKeys? = nil
    var locked: Bool { enabled && keys == nil }
    /// Uploads must be encrypted: the account is encrypted, unlocked here and not turning off.
    var uploadKeys: AccountKeys? { enabled && !decrypting ? keys : nil }
    /// Stored encrypted files can be read.
    var readKeys: AccountKeys? { enabled ? keys : nil }
}

func e2eeLocked() -> APIError { APIError(status: 423, message: "Unlock your files to continue.", code: "E2EE_LOCKED") }
/// The server's normalizedName: NFC and lower case.
func normalizeName(_ name: String) -> String { name.precomposedStringWithCanonicalMapping.lowercased() }

private let mimes: [String: String] = [
    "png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg", "gif": "image/gif", "webp": "image/webp", "avif": "image/avif",
    "heic": "image/heic", "svg": "image/svg+xml", "bmp": "image/bmp", "pdf": "application/pdf", "txt": "text/plain", "md": "text/markdown",
    "csv": "text/csv", "json": "application/json", "html": "text/html", "mp4": "video/mp4", "m4v": "video/mp4", "mov": "video/quicktime",
    "webm": "video/webm", "mp3": "audio/mpeg", "m4a": "audio/mp4", "wav": "audio/wav", "ogg": "audio/ogg", "flac": "audio/flac",
    "zip": "application/zip", "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation"]
/// The MIME type a browser would give a file of this name; uploads of encrypted files hide it.
func guessMime(_ name: String) -> String { mimes[(name as NSString).pathExtension.lowercased()] ?? "application/octet-stream" }

/// The keys transfers use (desktop `transferKeys`).
struct TransferKeys: @unchecked Sendable {
    let view: () -> E2eeView
    static let none = TransferKeys { E2eeView() }
    /// Keys for a new upload, or nil for an unencrypted one; refuses while locked.
    func forUpload() throws -> AccountKeys? {
        let v = view()
        if let keys = v.uploadKeys { return keys }
        if v.enabled && !v.decrypting && v.keys == nil { throw e2eeLocked() }
        return nil
    }
    /// Keys to decrypt a stored encrypted file.
    func forRead() throws -> AccountKeys {
        guard let keys = view().readKeys else { throw e2eeLocked() }
        return keys
    }
}

/**
 * Makes end-to-end encryption invisible to the rest of the app, as e2ee-transport.ts (and Android
 * E2eeTransport.kt) do. On an encrypted account, names going to the server for the account's own
 * items are encrypted, and answers come back with names decrypted, sizes as the original files and
 * a MIME type guessed from the name. Name search runs here, as the server can't read names. Signed
 * download links keep the stored size and hash: they describe the bytes as stored, which the app
 * checks before decrypting. `strict` refuses file requests while locked, for sync.
 */
@MainActor
final class E2eeTransport {
    typealias Inner = (String, String, [String: Any]?) async throws -> [String: Any]
    private let inner: Inner
    private let vault: () -> E2eeView
    private let onAccount: (String, Bool, Bool) -> Void
    let strict: Bool
    private var owners: [String: String] = [:]
    private var names: [String: String] = [:]
    private var checkedAt = Date.distantPast

    init(inner: @escaping Inner, vault: @escaping () -> E2eeView, onAccount: @escaping (String, Bool, Bool) -> Void, strict: Bool = false) {
        self.inner = inner; self.vault = vault; self.onAccount = onAccount; self.strict = strict
    }

    private func ownerOf(_ id: String) async throws -> String {
        if let owner = owners[id] { return owner }
        let item = try await inner("/v1/drive/items/\(Self.segment(id))", "GET", nil)["item"] as? [String: Any]
        guard let owner = item?["ownerUserId"] as? String else { throw URLError(.cannotParseResponse) }
        owners[id] = owner
        return owner
    }
    /// Whether a name under `id` (an item, or a folder for new items) is the account's own.
    private func mine(_ id: String?) async throws -> Bool {
        guard let id, id != "root" else { return true }
        return try await ownerOf(id) == vault().userId
    }
    private func keys() throws -> AccountKeys {
        guard let keys = vault().keys else { throw e2eeLocked() }
        return keys
    }
    private func sealName(_ name: Any?) throws -> Any? {
        guard let text = name as? String else { return name }
        return E2ee.isEncryptedName(text) ? text : try E2ee.encryptName(try keys(), text)
    }
    private func sealPath(_ path: String) throws -> String {
        try path.split(separator: "/", omittingEmptySubsequences: false).map { try sealName(String($0)) as! String }.joined(separator: "/")
    }
    private static func match(_ pattern: String, _ text: String) -> [String]? {
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let m = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) else { return nil }
        return (0..<m.numberOfRanges).map { m.range(at: $0).location == NSNotFound ? "" : String(text[Range(m.range(at: $0), in: text)!]) }
    }

    /// The request as it goes to the server: the path (a lookup's name is in its query) and body.
    func outgoing(_ path: String, _ method: String, _ body: [String: Any]?) async throws -> (String, [String: Any]?) {
        let verb = method.uppercased()
        let route = path.components(separatedBy: "?")[0]
        let query = path.contains("?") ? String(path[path.index(after: path.firstIndex(of: "?")!)...]) : ""
        if var body, let name = body["name"] as? String {
            let item = Self.match("^/v1/drive/items/([^/]+)(?:/move)?$", route)?[1]
            let backupRun = Self.match("^/v1/backups/[^/]+/runs/[^/]+/(folders|uploads|items/[^/]+/name)$", route)?[1]
            let encrypted = (body["encrypted"] as? Bool) == true
            var encrypt = false
            if verb == "POST" && route == "/v1/drive/folders" { encrypt = try await mine(body["parentId"] as? String) }
            else if let item, ["PATCH", "POST"].contains(verb) { encrypt = try await mine(item.removingPercentEncoding ?? item) }
            else if verb == "POST" && route == "/v1/uploads" && encrypted { encrypt = true }
            // Backup folders always belong to the account backing up.
            else if verb == "POST" && (backupRun == "folders" || backupRun?.hasPrefix("items/") == true) { encrypt = true }
            else if verb == "POST" && backupRun == "uploads" && encrypted { encrypt = true }
            if encrypt { body["name"] = try sealName(name); return (path, body) }
        }
        // A backed up file's path, which the server keeps with the backup run.
        if verb == "POST", var body, let relativePath = body["relativePath"] as? String,
           Self.match("^/v1/backups/[^/]+/runs/[^/]+/files$", route) != nil {
            body["relativePath"] = try sealPath(relativePath)
            return (path, body)
        }
        // Batches of small files: each encrypted upload's name, and each path a backup records.
        let batch = route == "/v1/uploads/batch" ? "uploads" : Self.match("^/v1/backups/[^/]+/runs/[^/]+/(uploads|files)/batch$", route)?[1]
        if verb == "POST", var body, batch == "uploads", let uploads = body["uploads"] as? [Any] {
            body["uploads"] = try uploads.map { upload -> Any in
                guard var o = upload as? [String: Any], (o["encrypted"] as? Bool) == true else { return upload }
                o["name"] = try sealName(o["name"]) ?? NSNull()
                return o
            }
            return (path, body)
        }
        if verb == "POST", var body, batch == "files", let files = body["files"] as? [Any] {
            body["files"] = try files.map { file -> Any in
                guard var o = file as? [String: Any], let p = o["relativePath"] as? String else { return file }
                o["relativePath"] = try sealPath(p)
                return o
            }
            return (path, body)
        }
        if verb == "POST", var body, route == "/v1/sync/operations", let operations = body["operations"] as? [Any] {
            var out: [Any] = []
            for operation in operations {
                if var o = operation as? [String: Any], var payload = o["payload"] as? [String: Any], let name = payload["name"] as? String,
                   try await mine(o["entityId"] as? String) {
                    payload["name"] = try sealName(name)
                    o["payload"] = payload
                    out.append(o)
                } else { out.append(operation) }
            }
            body["operations"] = out
            return (path, body)
        }
        if let lookup = Self.match("^/v1/drive/folders/([^/]+)/lookup$", route)?[1], try await mine(lookup.removingPercentEncoding ?? lookup) {
            var params = Self.parseQuery(query)
            let name = params.first { $0.0 == "name" }?.1
            let sealed = (try sealName(name) as? String) ?? "null"
            params.removeAll { $0.0 == "name" }
            params.append(("name", sealed))
            return (route + "?" + Self.formatQuery(params), body)
        }
        return (path, body)
    }

    private func reveal(_ k: AccountKeys, _ token: String) -> String {
        if let known = names[token] { return known }
        // Lower-cased copies (normalizedName) and damaged names stay as they are.
        guard let plain = try? E2ee.decryptName(k, token) else { return token }
        if names.count > 50_000 { names.removeAll() }
        names[token] = plain
        return plain
    }
    private static let tokenPattern = try! NSRegularExpression(pattern: "h0e1\\.[A-Za-z0-9_-]+")
    /// An answer with its names decrypted, sizes as the original files and MIME types from the names.
    func incoming(_ value: Any, _ k: AccountKeys) -> Any {
        if let array = value as? [Any] { return array.map { incoming($0, k) } }
        if let text = value as? String {
            guard text.contains("h0e1."), !text.hasPrefix("http://"), !text.hasPrefix("https://") else { return text }
            if E2ee.isEncryptedName(text) { return reveal(k, text) }
            // Paths and messages that name encrypted items.
            var out = text
            let tokens = Set(Self.tokenPattern.matches(in: text, range: NSRange(text.startIndex..., in: text)).map { String(text[Range($0.range, in: text)!]) })
            for t in tokens { out = out.replacingOccurrences(of: t, with: reveal(k, t)) }
            return out
        }
        guard let object = value as? [String: Any] else { return value }
        if let id = object["id"] as? String, let owner = object["ownerUserId"] as? String { owners[id] = owner }
        var out: [String: Any] = [:]
        for (key, v) in object { out[key] = incoming(v, k) }
        if let name = object["name"] as? String, E2ee.isEncryptedName(name), object["normalizedName"] != nil, let shown = out["name"] as? String {
            out["normalizedName"] = normalizeName(shown)
        }
        if (object["encrypted"] as? Bool) == true, let size = object["sizeBytes"] as? NSNumber, !(object["sizeBytes"] is String),
           object["downloadUrl"] == nil {
            out["sizeBytes"] = NSNumber(value: E2ee.plainSize(size.int64Value))
            let mime = object["mimeType"] as? String
            if let shown = out["name"] as? String, mime == nil || mime!.isEmpty || mime == "application/octet-stream" {
                out["mimeType"] = guessMime(shown)
            }
        }
        return out
    }

    /// Name search and type filters, applied to decrypted names a page at a time.
    private func search(_ path: String, _ method: String, _ body: [String: Any]?, _ k: AccountKeys) async throws -> [String: Any] {
        var params = Self.parseQuery(path.contains("?") ? String(path[path.index(after: path.firstIndex(of: "?")!)...]) : "")
        func take(_ key: String) -> String? { params.first { $0.0 == key }?.1 }
        let q = normalizeName(take("q") ?? "")
        let ext = take("extension")?.lowercased()
        let mimeType = take("mimeType")
        params.removeAll { ["q", "extension", "mimeType"].contains($0.0) }
        let limit = Double(take("limit") ?? "") ?? 100
        var items: [Any] = []
        var cursor = take("cursor")
        for _ in 0..<20 {
            if let cursor { params.removeAll { $0.0 == "cursor" }; params.append(("cursor", cursor)) }
            let page = incoming(try await inner("/v1/search?" + Self.formatQuery(params), method, body), k) as? [String: Any] ?? [:]
            items += (page["items"] as? [Any] ?? []).filter { item in
                let o = item as? [String: Any] ?? [:]
                let normalized = o["normalizedName"] as? String ?? ""
                return (q.isEmpty || normalized.contains(q)) && (ext?.isEmpty ?? true || normalized.hasSuffix("." + ext!)) &&
                    (mimeType?.isEmpty ?? true || (o["mimeType"] as? String) == mimeType)
            }
            cursor = page["nextCursor"] as? String
            if cursor == nil || Double(items.count) >= limit { break }
        }
        return ["items": items, "nextCursor": cursor as Any? ?? NSNull()]
    }

    /// Requests whose answers name the account's files.
    private static let fileRoute = try! NSRegularExpression(pattern: "^/v1/(drive|uploads|downloads|sync|backups|search)(/|\\?|$)")
    static func isFileRoute(_ path: String) -> Bool { fileRoute.firstMatch(in: path, range: NSRange(path.startIndex..., in: path)) != nil }

    func call(_ path: String, _ method: String, _ body: [String: Any]?) async throws -> [String: Any] {
        do { return try await sealed(path, method, body) }
        catch let error as APIError where error.code == "E2EE_OFF" || error.code == "E2EE_REQUIRED" {
            // Turned on or off elsewhere: read the account again so the next attempt goes up the right way.
            _ = try? await sealed("/v1/users/me", "GET", nil)
            throw error
        }
    }
    private func sealed(_ path: String, _ method: String, _ body: [String: Any]?) async throws -> [String: Any] {
        let before = vault()
        let files = Self.isFileRoute(path)
        if strict && files && before.enabled && before.keys == nil { throw e2eeLocked() }
        var target = path
        var payload = body
        if before.enabled, let keys = before.keys {
            if path.hasPrefix("/v1/search?"), Self.match("[?&](q|extension|mimeType)=", path) != nil { return try await search(path, method, body, keys) }
            // While turning off, new names go up unencrypted; stored ones are still read here.
            if !before.decrypting { (target, payload) = try await outgoing(path, method, body) }
        } else if before.enabled && !before.decrypting {
            _ = try await outgoing(path, method, body) // Throws if a name would need encrypting.
        }
        let result = try await inner(target, method, payload)
        if path == "/v1/users/me" { account(result) }
        let after = vault()
        if after.enabled, let keys = after.keys { return incoming(result, keys) as? [String: Any] ?? result }
        if strict && files && !after.enabled && Date().timeIntervalSince(checkedAt) > 60 {
            // Encryption may have been turned on elsewhere: find out before anything acts on encrypted names or bytes.
            if let data = try? JSONSerialization.data(withJSONObject: result), let text = String(data: data, encoding: .utf8),
               text.contains("\"h0e1.") || text.contains("\"encrypted\":true") {
                checkedAt = Date()
                if let me = try? await inner("/v1/users/me", "GET", nil) { account(me) }
                if vault().enabled { throw e2eeLocked() }
            }
        }
        return result
    }
    /// Tells the vault about the account in a /v1/users/me answer.
    func account(_ me: [String: Any]) {
        guard let user = me["user"] as? [String: Any], let id = user["id"] as? String else { return }
        let e2ee = user["e2ee"] as? [String: Any]
        onAccount(id, e2ee != nil, !((e2ee?["decryptingSince"] as? String) ?? "").isEmpty)
    }

    static func segment(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(CharacterSet(charactersIn: "-._~"))) ?? value
    }
    static func parseQuery(_ query: String) -> [(String, String)] {
        query.split(separator: "&").filter { !$0.isEmpty }.map { part in
            let pieces = part.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            func decode(_ s: Substring) -> String { String(s).replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? String(s) }
            return (decode(pieces[0]), pieces.count > 1 ? decode(pieces[1]) : "")
        }
    }
    static func formatQuery(_ params: [(String, String)]) -> String {
        params.map { segment($0.0) + "=" + segment($0.1) }.joined(separator: "&")
    }
}
