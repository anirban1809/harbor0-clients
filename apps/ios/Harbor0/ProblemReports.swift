import Foundation

/// Upload problems the API can't see (a failed storage PUT, an upload that gave up), in batches a few seconds later.
/// If sending fails they are dropped. File names and URLs are never sent.
final class ProblemReports: @unchecked Sendable {
    private static var all: [ObjectIdentifier: ProblemReports] = [:]
    private static let lock = NSLock()
    /// One reporter per signed-in API client, shared by manual transfers and the sync engine.
    static func of(_ api: HarborAPI) -> ProblemReports {
        lock.lock(); defer { lock.unlock() }
        if let existing = all[ObjectIdentifier(api)] { return existing }
        let made = ProblemReports(api: api)
        all[ObjectIdentifier(api)] = made
        return made
    }
    private weak var api: HarborAPI?
    private let lock = NSLock()
    private var problems: [[String: Any]] = []
    private var reported: [String: Date] = [:]
    private var scheduled = false
    init(api: HarborAPI) { self.api = api }

    /// `kind` UPLOAD_PART_RETRY or UPLOAD_FAILED; `stage` hash, create, parts, storage, complete or other.
    func report(_ kind: String, stage: String, error: Error?, uploadId: String? = nil, sizeBytes: Int64? = nil, partNumber: Int? = nil, attempt: Int? = nil) {
        if error is CancellationError { return }
        var event: [String: Any] = ["kind": kind, "at": ISO8601DateFormatter.harbor.string(from: Date()), "stage": stage]
        if let api = error as? APIError {
            if let code = api.code { event["code"] = String(code.prefix(64)) }
            event["httpStatus"] = min(599, max(0, api.status))
            event["message"] = String(api.message.prefix(300))
        } else if let url = error as? URLError {
            // The connection failed before any status; messages can name hosts, so only the kind is sent.
            event["code"] = "URLError\(url.code.rawValue)"; event["httpStatus"] = 0
        } else if let error {
            event["code"] = String(String(describing: type(of: error)).prefix(64))
        }
        if let uploadId { event["uploadId"] = String(uploadId.prefix(128)) }
        if let sizeBytes { event["sizeBytes"] = max(0, sizeBytes) }
        if let partNumber { event["partNumber"] = partNumber }
        if let attempt { event["attempt"] = attempt }
        let key = [kind, stage, event["code"] as? String ?? "", uploadId ?? "", "\(partNumber ?? 0)"].joined(separator: "|")
        lock.lock()
        // The same problem again within a minute is dropped, so a stuck retry loop can't flood.
        if Date().timeIntervalSince(reported[key] ?? .distantPast) < 60 || problems.count >= 100 { lock.unlock(); return }
        if reported.count > 500 { reported.removeAll() }
        reported[key] = Date()
        problems.append(event)
        let start = !scheduled
        scheduled = true
        lock.unlock()
        if start { Task { try? await Task.sleep(nanoseconds: 5_000_000_000); await self.flush() } }
    }
    func flush() async {
        lock.lock(); scheduled = false; lock.unlock()
        while true {
            lock.lock()
            let batch = Array(problems.prefix(20))
            problems.removeFirst(batch.count)
            lock.unlock()
            guard !batch.isEmpty, let api else { return }
            let _: EmptyResponse? = try? await api.request("/v1/client-events", method: "POST", body: ["events": batch])
        }
    }
}

extension ISO8601DateFormatter {
    /// UTC with milliseconds, as JavaScript's toISOString.
    static let harbor: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()
}
