import Foundation

// What the app reports for the staff console, as Android Activity.kt and ProblemReports.kt and packages/api-client:
// what sync and backups are doing (`POST /v1/activity`, docs/specs/device-activity.md) and upload problems the
// API can't see (`POST /v1/client-events`). Counts and codes only: never file or folder names, never URLs.

/// What one subject is doing now: the engine (no folderId), a sync folder (its cloud id) or a backup (its id).
struct ActivitySubject: Equatable {
    let scope: String
    var folderId: String? = nil
    let status: String
    var reason: String? = nil
}

/// What happened, going from the status last reported to the new one.
func activityAction(_ previous: (status: String, reason: String?)?, _ next: ActivitySubject) -> String {
    if next.status == "PAUSED" { return "PAUSED" }
    if previous?.status == "PAUSED" { return "RESUMED" }
    if next.status == "BLOCKED" { return "BLOCKED" }
    if previous?.status == "BLOCKED" { return "UNBLOCKED" }
    if next.status == "ACTIVE" { return "STARTED" }
    return previous != nil ? "FINISHED" : "IDLE"
}

/// Queues an event whenever a subject's status or reason changes and sends them in batches a few seconds later.
/// The queue and what was last reported survive restarts (per account, in the app's defaults).
@MainActor
final class ActivityReporter {
    private struct Reported: Codable { let status: String; let reason: String? }
    private let api: HarborAPI
    private let key: String
    private var reported: [String: Reported]
    private var queue: [[String: Any]] = []
    private var busyAt: [String: Date] = [:]
    private var timer: Task<Void, Never>?
    private var unsupported = false

    init(api: HarborAPI, userId: String) {
        self.api = api
        key = "harbor0.activity." + E2ee.sha256Hex(Data((api.baseURL.absoluteString + userId).utf8)).prefix(12)
        reported = (UserDefaults.standard.data(forKey: key + ".reported")).flatMap { try? JSONDecoder().decode([String: Reported].self, from: $0) } ?? [:]
        queue = (UserDefaults.standard.array(forKey: key + ".queue") as? [[String: Any]]) ?? []
    }

    func observe(_ subjects: [ActivitySubject]) {
        guard !unsupported else { return }
        let now = Date()
        var seen = Set<String>()
        var changed = false
        for subject in subjects {
            let id = subject.scope + "#" + (subject.folderId ?? "-")
            seen.insert(id)
            let previous = reported[id]
            if subject.scope == "SYNC" {
                if subject.status == "ACTIVE" { busyAt[id] = now }
                // Idle only once it has had nothing to do for a while.
                else if subject.status == "IDLE", previous?.status == "ACTIVE", now.timeIntervalSince(busyAt[id] ?? .distantPast) < 30 { continue }
            }
            if let previous, previous.status == subject.status, previous.reason == subject.reason { continue }
            var event: [String: Any] = ["id": UUID().uuidString.lowercased(), "at": SyncClock.iso(now), "scope": subject.scope,
                                        "action": activityAction(previous.map { ($0.status, $0.reason) }, subject), "status": subject.status]
            if let folder = subject.folderId { event["folderId"] = folder }
            if let reason = subject.reason { event["reason"] = reason }
            queue.append(event)
            reported[id] = Reported(status: subject.status, reason: subject.reason)
            changed = true
        }
        // A folder no longer set up is forgotten, so setting it up again reports it afresh.
        let forgotten = reported.keys.filter { !seen.contains($0) }
        forgotten.forEach { reported[$0] = nil; busyAt[$0] = nil }
        guard changed || !forgotten.isEmpty else { return }
        if queue.count > 500 { queue.removeFirst(queue.count - 500) }
        save()
        if changed && timer == nil {
            timer = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                await self?.flush()
                self?.timer = nil
            }
        }
    }
    func flush() async {
        while !queue.isEmpty && !unsupported {
            let batch = Array(queue.prefix(50))
            let ids = Set(batch.compactMap { $0["id"] as? String })
            do { let _: EmptyResponse = try await api.request("/v1/activity", method: "POST", body: ["events": batch]) }
            catch let error as APIError where error.status == 404 { unsupported = true; queue = []; save(); return }
            // Refused for its content: dropped rather than sent again forever.
            catch let error as APIError where error.status == 400 { queue.removeAll { ids.contains($0["id"] as? String ?? "") }; save(); continue }
            catch { return }
            queue.removeAll { ids.contains($0["id"] as? String ?? "") }
            save()
        }
    }
    private func save() {
        UserDefaults.standard.set(try? JSONEncoder().encode(reported), forKey: key + ".reported")
        UserDefaults.standard.set(queue, forKey: key + ".queue")
    }

    /// The subjects a sync snapshot describes: the engine, each synced folder and each backup on this iPhone.
    static func subjects(_ snapshot: SyncSnapshot, locked: Bool, photos: PhotoBackupState?, photosRunning: Bool) -> [ActivitySubject] {
        let state = snapshot.state
        var out: [ActivitySubject] = [ActivitySubject(scope: "ENGINE", status: locked ? "BLOCKED" : state.paused ? "PAUSED" : state.active != nil ? "ACTIVE" : "IDLE",
                                                      reason: locked ? "LOCKED" : state.paused ? "ALL" : nil)]
        for folder in snapshot.folders {
            let root = folder.root
            let full = state.issues.contains { $0.rootId == root.id && $0.code == "STORAGE_QUOTA_EXCEEDED" }
            let status = locked || full ? "BLOCKED" : state.paused || root.paused ? "PAUSED" : state.active?.rootId == root.id ? "ACTIVE" : "IDLE"
            let reason: String? = locked ? "LOCKED" : full ? "STORAGE_FULL" : state.paused ? "ALL" : root.paused ? "USER" : nil
            if root.mode == .sync, let id = root.remoteId { out.append(ActivitySubject(scope: "SYNC", folderId: id, status: status, reason: reason)) }
            if root.mode == .backup, let id = root.backupId { out.append(ActivitySubject(scope: "BACKUP", folderId: id, status: status, reason: reason)) }
        }
        if let photos {
            out.append(ActivitySubject(scope: "BACKUP", folderId: photos.backupId, status: locked ? "BLOCKED" : photos.paused ? "PAUSED" : photosRunning ? "ACTIVE" : "IDLE",
                                       reason: locked ? "LOCKED" : photos.paused ? "USER" : nil))
        }
        return out
    }
}

