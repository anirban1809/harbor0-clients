import FileProvider

/// The extension's connection to harbor0, using the session the app shares (SharedAccount).
@MainActor
final class FileProviderSession {
    private let domain: NSFileProviderDomain
    private var client: HarborAPI?
    private var vault: E2eeVault?
    private var unlocking: Task<Void, Never>?

    nonisolated init(domain: NSFileProviderDomain) { self.domain = domain }
    #if os(macOS)
    static let deviceKind = "Mac"
    #else
    static let deviceKind = "iPhone"
    #endif

    func api() throws -> HarborAPI {
        if let client, client.signedIn { return client }
        guard let base = SharedAccount.apiURL, SharedAccount.userId == domain.identifier.rawValue else {
            throw NSFileProviderError(.notAuthenticated)
        }
        #if os(macOS)
        let api = HarborAPI(baseURL: base, credentials: MacAppCredentials(), deviceKeys: nil)
        api.renews = false
        #else
        let api = HarborAPI(baseURL: base, credentials: KeychainStore(service: SharedAccount.credentialService(for: base)), deviceKeys: nil)
        #endif
        guard api.loadStoredSession() else { throw NSFileProviderError(.notAuthenticated) }
        // An end-to-end encrypted account opens with the copy the app remembered; until then file requests are
        // refused rather than showing encrypted names or storing plaintext.
        let vault = E2eeVault(store: KeychainE2eeCopies(baseURL: base))
        api.transport = vault.transport(api, strict: true)
        api.transferKeys = vault.transferKeys
        self.vault = vault
        client = api
        unlocking = Task { [weak vault] in
            guard let vault, let _ = try? await api.json("/v1/users/me") else { return }
            if vault.locked { _ = await vault.restore(api) }
        }
        return api
    }
    /// Waits for a remembered unlock to finish before file work.
    func ready() async throws -> HarborAPI {
        let api = try api()
        await unlocking?.value
        return api
    }

    func item(_ id: String) async throws -> DriveItem {
        let response: ItemResponse = try await ready().request("/v1/drive/items/\(id)")
        return response.item
    }

    /// The current end of the change feed. Servers without `cursor=latest` are read to the end.
    func latestCursor() async throws -> Int {
        let api = try api()
        do {
            let page: SyncChangesPage = try await api.request("/v1/sync/changes?cursor=latest")
            return page.nextCursor
        } catch let error as APIError where error.status == 400 || error.code == "VALIDATION_ERROR" {
            var cursor = 0
            while true {
                let page: SyncChangesPage = try await api.request("/v1/sync/changes?cursor=\(cursor)&limit=500")
                cursor = page.nextCursor
                if !page.hasMore { return cursor }
            }
        }
    }

    /// A file whose bytes are in the cloud. Files kept only on linked computers are requested
    /// from them; the download waits a short while for one of them to provide it.
    func available(_ id: String) async throws -> DriveItem {
        var current = try await item(id)
        guard current.cloudState == "RELEASED" || current.cloudState == "REQUESTED" else { return current }
        let _: ItemResponse = try await api().request("/v1/sync/items/\(id)/request-content", method: "POST")
        let deadline = Date().addingTimeInterval(60)
        while Date() < deadline {
            try await Task.sleep(nanoseconds: 3_000_000_000)
            current = try await item(id)
            if current.cloudState != "RELEASED" && current.cloudState != "REQUESTED" { return current }
        }
        throw NSError(domain: NSCocoaErrorDomain, code: NSFileReadUnknownError, userInfo: [
            NSLocalizedDescriptionKey: "“\(current.name)” is stored on your other devices. It was requested and will open once one of them is online.",
        ])
    }

    func download(_ item: DriveItem, into directory: URL, progress: @escaping (Double) -> Void) async throws -> URL {
        var body = ["driveItemId": item.id]
        if let version = item.currentVersionId { body["versionId"] = version }
        let signed: DownloadResponse = try await ready().request("/v1/downloads", method: "POST", body: body)
        let readKeys = signed.encrypted == true ? try api().transferKeys.forRead() : nil
        try FileTransfers.validateStorageURL(signed.downloadUrl, apiURL: try api().baseURL)
        let (temporary, response) = try await SyncTransfers.storage.download(from: signed.downloadUrl)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw NSFileProviderError(.serverUnreachable)
        }
        let digest = try FileTransfers.digest(temporary)
        guard digest.size == signed.sizeBytes, digest.hash == signed.contentHash else { throw SyncTransfers.IntegrityFailed() }
        let target = directory.appendingPathComponent(UUID().uuidString)
        if let readKeys {
            try FileCipher.decryptFile(readKeys, source: temporary, to: target)
            try? FileManager.default.removeItem(at: temporary)
        } else {
            try FileManager.default.moveItem(at: temporary, to: target)
        }
        progress(1)
        return target
    }

    func upload(_ url: URL, name: String, parentID: String?, existing: DriveItem?, progress: @escaping (Double) -> Void) async throws -> DriveItem {
        var state = UploadState(operationId: UUID().uuidString.lowercased())
        return try await SyncTransfers.upload(api: ready(), file: url, name: name, parentId: parentID, state: &state, persist: { _ in },
                                              existing: existing.map { ($0.id, $0.revision ?? 1) },
                                              progress: { loaded, total in progress(total > 0 ? Double(loaded) / Double(total) : 1) })
    }

    func createFolder(_ name: String, parentID: String?, mayExist: Bool) async throws -> DriveItem {
        do { return try await ready().createFolder(name: name, parentID: parentID) }
        catch let error as APIError where error.code == "NAME_CONFLICT" && mayExist {
            var cursor: String?
            repeat {
                let page = try await ready().list(parentID: parentID, cursor: cursor)
                if let match = page.items.first(where: { $0.isFolder && SyncPaths.normalized($0.name) == SyncPaths.normalized(name) }) { return match }
                cursor = page.nextCursor
            } while cursor != nil
            throw error
        }
    }

    func rename(_ item: DriveItem, to name: String) async throws -> DriveItem {
        let response: ItemResponse = try await ready().request("/v1/drive/items/\(item.id)", method: "PATCH",
            body: ["operationId": UUID().uuidString.lowercased(), "baseRevision": item.revision ?? 1, "name": name])
        return response.item
    }
    func move(_ item: DriveItem, to parentID: String?) async throws -> DriveItem {
        let response: ItemResponse = try await ready().request("/v1/drive/items/\(item.id)/move", method: "POST",
            body: ["operationId": UUID().uuidString.lowercased(), "baseRevision": item.revision ?? 1, "parentId": parentID as Any? ?? NSNull()])
        return response.item
    }
    func trash(_ item: DriveItem) async throws {
        try await ready().changeTrash(item, action: .trash)
    }

    /// Errors the Files app understands; anything else keeps the server's own message.
    nonisolated static func fileProviderError(_ error: Error) -> Error {
        if error is NSFileProviderError { return error }
        if error is CancellationError { return NSError(domain: NSCocoaErrorDomain, code: NSUserCancelledError) }
        if error is URLError { return NSFileProviderError(.serverUnreachable) }
        if let api = error as? APIError {
            switch api.code {
            case "NAME_CONFLICT": return NSFileProviderError(.filenameCollision)
            case "ITEM_NOT_FOUND", "PARENT_NOT_FOUND": return NSFileProviderError(.noSuchItem)
            case "STORAGE_QUOTA_EXCEEDED": return NSFileProviderError(.insufficientQuota)
            case "AUTH_INVALID", "DEVICE_REVOKED", "E2EE_LOCKED", "E2EE_UPDATE_REQUIRED": return NSFileProviderError(.notAuthenticated)
            default:
                if api.status == 401 { return NSFileProviderError(.notAuthenticated) }
                if api.status >= 500 || api.status == 429 { return NSFileProviderError(.serverUnreachable) }
            }
        }
        return error
    }
}
