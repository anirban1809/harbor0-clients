import Foundation
import UniformTypeIdentifiers

struct UploadStatus: Decodable {
    struct Upload: Decodable { let id: String; let state: String; var partSizeBytes: Int64? = nil; var item: DriveItem? = nil }
    let upload: Upload
    var parts: [CompletedPart]? = nil
}

/// Storage uploads that carry on while the app is suspended: each part goes to iOS's background transfer service, so
/// it finishes even when iOS stops harbor0, and the part after it starts the next time harbor0 runs. An upload whose
/// result arrives after harbor0 was quit is read back from the server's list of parts, as any interrupted upload is.
final class BackgroundUploads: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    static let identifier = "app.harbor0.ios.uploads"
    static let shared = BackgroundUploads()
    private let lock = NSLock()
    private var waiting: [Int: CheckedContinuation<HTTPURLResponse, Error>] = [:]
    private var sending: [Int: (Int64) -> Void] = [:]
    private var finishEvents: (() -> Void)?
    private var startedSinceEvents = false
    /// The Files extension can't own this app's background session; it uploads as before.
    static var available: Bool { Bundle.main.bundleURL.pathExtension != "appex" }
    private static let awakeLock = NSLock()
    nonisolated(unsafe) private static var awakeValue = true
    /// harbor0 is open, or a continued backup keeps it running: parts go straight to storage. iOS holds back each
    /// new background transfer an app starts while it isn't in front, by a minute or more, so the background
    /// service only takes parts when harbor0 may be suspended before they finish.
    static var awake: Bool {
        get { awakeLock.withLock { awakeValue } }
        set { awakeLock.withLock { awakeValue = newValue } }
    }
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.background(withIdentifier: Self.identifier)
        configuration.isDiscretionary = false
        configuration.sessionSendsLaunchEvents = true
        configuration.timeoutIntervalForResource = 24 * 3600
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }()

    /// Reconnects to transfers iOS finished while harbor0 wasn't running (app delegate).
    func reconnect(_ completion: @escaping () -> Void) {
        lock.withLock { finishEvents = completion; startedSinceEvents = false }
        _ = session
    }

    /// Where parts wait until iOS has sent them; each is removed when its transfer ends, even after a relaunch.
    private static let parts: URL = {
        let folder = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("HarborUploadParts", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }()

    /// Sends a copy of `file`, kept until iOS is done with it. Cancelling stops waiting but not the transfer: a part
    /// that iOS is sending finishes, and the upload picks it up from the server's list when it resumes.
    /// `sent`: the bytes of this part sent so far, as they go, so a large part shows progress before it finishes.
    func upload(_ request: URLRequest, fromFile file: URL, sent: ((Int64) -> Void)? = nil) async throws -> HTTPURLResponse {
        let owned = Self.parts.appendingPathComponent(UUID().uuidString)
        try FileManager.default.copyItem(at: file, to: owned)
        let task = session.uploadTask(with: request, fromFile: owned)
        task.taskDescription = owned.lastPathComponent
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                lock.withLock { waiting[task.taskIdentifier] = continuation; sending[task.taskIdentifier] = sent; startedSinceEvents = true }
                task.resume()
                // Cancelled before it began waiting: the handler below found nothing to stop.
                if Task.isCancelled { lock.withLock { waiting.removeValue(forKey: task.taskIdentifier) }?.resume(throwing: CancellationError()) }
            }
        } onCancel: {
            lock.withLock { waiting.removeValue(forKey: task.taskIdentifier) }?.resume(throwing: CancellationError())
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64, totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        lock.withLock { sending[task.taskIdentifier] }?(totalBytesSent)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.withLock { _ = sending.removeValue(forKey: task.taskIdentifier) }
        if let name = task.taskDescription, !name.contains("/") { try? FileManager.default.removeItem(at: Self.parts.appendingPathComponent(name)) }
        guard let continuation = lock.withLock({ waiting.removeValue(forKey: task.taskIdentifier) }) else { return }
        if let error { continuation.resume(throwing: error) }
        else if let response = task.response as? HTTPURLResponse { continuation.resume(returning: response) }
        else { continuation.resume(throwing: URLError(.badServerResponse)) }
    }
    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        // iOS suspends harbor0 once told the events are handled: first let the upload go on to its next part.
        Task {
            for _ in 0..<20 where !lock.withLock({ startedSinceEvents }) { try? await Task.sleep(nanoseconds: 500_000_000) }
            let done = lock.withLock { () -> (() -> Void)? in defer { finishEvents = nil }; return finishEvents }
            await MainActor.run { done?() }
        }
    }
}

/// Reports a direct upload's bytes as they are sent, as the background session's delegate does.
final class SentBytes: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let sent: (Int64) -> Void
    init(_ sent: @escaping (Int64) -> Void) { self.sent = sent }
    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64, totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        sent(totalBytesSent)
    }
}

/// Resumable uploads and verified downloads for sync and backup (apps/desktop/src/transfers.ts).
/// Upload state is persisted after every step so an interrupted transfer resumes where it stopped.
enum SyncTransfers {
    static let storage: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 120
        configuration.waitsForConnectivity = false
        return URLSession(configuration: configuration)
    }()

    static func hash(_ url: URL) throws -> String { try FileTransfers.digest(url).hash }

    struct Changed: LocalizedError {
        var errorDescription: String? { "File changed while uploading. The next attempt will restart safely." }
    }

    /// Where an encrypted upload's sealed copy waits, so an interrupted upload resumes with the same bytes.
    static func sealedCopy(_ operationId: String) -> URL {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("HarborSealed", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent(operationId)
    }
    static func discardSealed(_ state: UploadState) { try? FileManager.default.removeItem(at: sealedCopy(state.operationId)) }

    static func upload(api: HarborAPI, file: URL, name: String, parentId: String?, state: inout UploadState,
                       persist: (UploadState) -> Void, existing: (itemId: String, revision: Int)? = nil,
                       backup: (rootId: String, runId: String)? = nil,
                       progress: ((Int64, Int64) -> Void)? = nil,
                       beforeComplete: (() async throws -> Void)? = nil) async throws -> DriveItem {
        guard let info = try LocalInfo.of(file), info.kind == .file else { throw CocoaError(.fileNoSuchFile) }
        let keys = try api.transferKeys.forUpload()
        let sealed = sealedCopy(state.operationId)
        // A changed file, encryption turned on or off, or a lost sealed copy starts over.
        let lostSeal = keys != nil && state.uploadId != nil && (state.sealedSize == nil || (try? LocalInfo.of(sealed))?.size != state.sealedSize)
        if (state.mtime != nil && (state.mtime != info.mtime || state.size != info.size)) || (state.encrypted == true) != (keys != nil) || lostSeal {
            if let uploadId = state.uploadId { let _: EmptyResponse? = try? await api.request("/v1/uploads/\(uploadId)", method: "DELETE") }
            discardSealed(state)
            state = UploadState(operationId: UUID().uuidString.lowercased())
        }
        state.mtime = info.mtime
        state.size = info.size
        if state.hash == nil { state.hash = try hash(file) }
        var source = file
        if let keys {
            let target = sealedCopy(state.operationId)
            if state.sealedSize == nil || (try? LocalInfo.of(target))?.size != state.sealedSize {
                let result = try FileCipher.create(keys).encryptFile(file, to: target)
                state.sealedSize = result.size
                state.storedHash = result.hash
            }
            state.encrypted = true
            source = target
        }
        persist(state)
        // A freshly created upload has no parts yet; only a resumed one needs its server status.
        var created = false
        if state.uploadId == nil {
            var body: [String: Any] = [
                "operationId": state.operationId, "parentId": parentId as Any? ?? NSNull(), "name": name,
                "sizeBytes": info.size, "contentHash": state.hash!,
                "mimeType": UTType(filenameExtension: (name as NSString).pathExtension)?.preferredMIMEType ?? "application/octet-stream",
            ]
            if keys != nil {
                // The server only ever sees the encrypted size and hash of an encrypted file.
                body["sizeBytes"] = state.sealedSize!
                body["encrypted"] = true
                body["mimeType"] = "application/octet-stream"
                body["contentHash"] = nil
            }
            if let existing { body["driveItemId"] = existing.itemId; body["baseRevision"] = existing.revision }
            // When the file last changed here, as Android sends it: a reused photo backup matches saved files by it.
            if info.mtime > 0 {
                let format = ISO8601DateFormatter()
                format.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
                body["modifiedAt"] = format.string(from: Date(timeIntervalSince1970: info.mtime / 1000))
            }
            let result: UploadResponse = try await api.request(backup.map { "/v1/backups/\($0.rootId)/runs/\($0.runId)/uploads" } ?? "/v1/uploads",
                                                               method: "POST", body: body)
            state.uploadId = result.upload.id
            state.partSize = result.upload.partSizeBytes
            state.parts = []
            persist(state)
            created = true
        }
        var parts = state.parts ?? []
        if !created {
            let status: UploadStatus = try await api.request("/v1/uploads/\(state.uploadId!)")
            if status.upload.state == "COMPLETED", let item = status.upload.item { discardSealed(state); return item }
            if ["ABORTED", "FAILED", "EXPIRED"].contains(status.upload.state) {
                discardSealed(state)
                state = UploadState(operationId: UUID().uuidString.lowercased())
                persist(state)
                return try await upload(api: api, file: file, name: name, parentId: parentId, state: &state, persist: persist,
                                        existing: existing, backup: backup, progress: progress, beforeComplete: beforeComplete)
            }
            if status.upload.state != "COMPLETING" { parts = status.parts ?? [] }
            if state.partSize == nil { state.partSize = status.upload.partSizeBytes }
        }
        guard let partSize = state.partSize, partSize > 0 else { throw URLError(.badServerResponse) }
        let size = keys != nil ? state.sealedSize! : info.size
        let count = max(1, Int((size + partSize - 1) / partSize))
        let done = Set(parts.map(\.partNumber))
        func bytes(_ n: Int) -> Int64 { min(partSize, size - Int64(n - 1) * partSize) }
        var loaded = parts.reduce(Int64(0)) { $0 + bytes($1.partNumber) }
        progress?(loaded, size)
        let handle = try FileHandle(forReadingFrom: source)
        defer { try? handle.close() }
        let scratch = FileManager.default.temporaryDirectory.appendingPathComponent("HarborSync-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: scratch) }
        for number in 1...count where !done.contains(number) {
            try Task.checkCancellation()
            try handle.seek(toOffset: UInt64(Int64(number - 1) * partSize))
            FileManager.default.createFile(atPath: scratch.path, contents: nil)
            let output = try FileHandle(forWritingTo: scratch)
            var remaining = bytes(number)
            do {
                while remaining > 0 {
                    guard let data = try handle.read(upToCount: Int(min(remaining, 1024 * 1024))), !data.isEmpty else {
                        throw APIError(status: 0, message: "File changed during upload. Retry to preserve the latest content.")
                    }
                    try output.write(contentsOf: data)
                    remaining -= Int64(data.count)
                }
                try output.close()
            } catch { try? output.close(); throw error }
            var etag: String?
            for attempt in 0..<3 {
                do {
                    let urls: PartURLs = try await api.request("/v1/uploads/\(state.uploadId!)/parts", method: "POST", body: ["partNumbers": [number]])
                    guard let url = urls.parts.first(where: { $0.partNumber == number })?.uploadUrl else { throw URLError(.badServerResponse) }
                    try FileTransfers.validateStorageURL(url, apiURL: await api.baseURL)
                    var request = URLRequest(url: url)
                    request.httpMethod = "PUT"
                    request.timeoutInterval = 300
                    let sent: (Int64) -> Void = { [base = loaded] bytes in progress?(base + bytes, size) }
                    let response: URLResponse = BackgroundUploads.available && !BackgroundUploads.awake
                        ? try await BackgroundUploads.shared.upload(request, fromFile: scratch, sent: sent)
                        : try await storage.upload(for: request, fromFile: scratch, delegate: SentBytes(sent)).1
                    guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                          let value = http.value(forHTTPHeaderField: "ETag"), !value.isEmpty else {
                        throw APIError(status: 0, message: "Storage upload failed. It will resume automatically.")
                    }
                    etag = value
                    break
                } catch {
                    try Task.checkCancellation()
                    ProblemReports.of(api).report("UPLOAD_PART_RETRY", stage: "storage", error: error, uploadId: state.uploadId, sizeBytes: size, partNumber: number, attempt: attempt)
                    if attempt == 2 { throw error }
                }
            }
            parts.append(CompletedPart(partNumber: number, etag: etag!))
            state.parts = parts
            persist(state)
            loaded += bytes(number)
            progress?(loaded, size)
        }
        guard let after = try LocalInfo.of(file), after.mtime == state.mtime, after.size == state.size else { throw Changed() }
        try await beforeComplete?()
        let sorted = parts.sorted { $0.partNumber < $1.partNumber }
        let result: ItemResponse = try await api.request("/v1/uploads/\(state.uploadId!)/complete", method: "POST", body: [
            "parts": sorted.map { ["partNumber": $0.partNumber, "etag": $0.etag] as [String: Any] },
            // An encrypted file is finished with the hash of what was stored.
            "contentHash": (keys != nil ? state.storedHash : state.hash)!,
        ])
        discardSealed(state)
        return result.item
    }

    struct IntegrityFailed: LocalizedError {
        var errorDescription: String? { "Download integrity check failed. The original local file was kept." }
    }

    /// Downloads authorized content, verifies size and SHA-256, then replaces `destination`.
    /// `beforeReplace` runs after verification and right before the local file is replaced.
    @discardableResult
    static func download(api: HarborAPI, _ body: [String: String], to destination: URL,
                         beforeReplace: (() async throws -> Void)? = nil) async throws -> String {
        for target in [destination, destination.appendingPathExtension("harbor-part")] {
            if try LocalInfo.of(target)?.kind == .link { throw SyncPaths.UnsafePath(message: "Refusing to write through a symbolic link.") }
        }
        let signed: DownloadResponse = try await api.request("/v1/downloads", method: "POST", body: body)
        // Known before anything is downloaded: an encrypted file can't be put in place without the key.
        let readKeys = signed.encrypted == true ? try api.transferKeys.forRead() : nil
        try FileTransfers.validateStorageURL(signed.downloadUrl, apiURL: await api.baseURL)
        let (temporary, response) = try await storage.download(from: signed.downloadUrl)
        defer { try? FileManager.default.removeItem(at: temporary) }
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw APIError(status: 0, message: "Download interrupted. It will resume automatically.")
        }
        let digest = try FileTransfers.digest(temporary)
        guard digest.size == signed.sizeBytes, digest.hash == signed.contentHash else { throw IntegrityFailed() }
        // Stage beside the destination so the final replacement is a same-volume rename.
        let part = destination.appendingPathExtension("harbor-part")
        try? FileManager.default.removeItem(at: part)
        var written = signed.contentHash
        if let readKeys {
            // An encrypted download is decrypted beside it; the hash returned is the file's as written.
            do { written = try FileCipher.decryptFile(readKeys, source: temporary, to: part).hash }
            catch { try? FileManager.default.removeItem(at: part); throw error }
        } else {
            try FileManager.default.copyItem(at: temporary, to: part)
        }
        do {
            try await beforeReplace?()
            if try LocalInfo.of(destination) != nil {
                _ = try FileManager.default.replaceItemAt(destination, withItemAt: part, backupItemName: nil, options: [])
            } else {
                try FileManager.default.moveItem(at: part, to: destination)
            }
        } catch {
            try? FileManager.default.removeItem(at: part)
            throw error
        }
        return written
    }
}
