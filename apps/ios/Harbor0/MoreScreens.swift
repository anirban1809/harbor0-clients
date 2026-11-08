import SwiftUI

// MARK: - Devices

/// Devices signed in to the account, each with Sign out (pause) and Revoke (remove), as the web ConnectedDevices.
struct DevicesScreen: View {
    @EnvironmentObject private var workspace: Workspace
    @Environment(\.tokens) private var tokens
    @State private var devices: [Device]?
    @State private var failed = false
    @State private var revoking: Device?
    @State private var signingOut: Device?

    private var current: String? { workspace.sync.deviceId }
    var body: some View {
        PageScroll {
            VStack(alignment: .leading, spacing: 16) {
                PageTitle(text: "Devices")
                Card(title: "Connected devices", description: "Sign out a device to pause its sync and backups until it signs in again. Revoke a device to remove it for good.") {
                    if devices == nil && !failed {
                        SkeletonRows(count: 3, tile: 32)
                    } else if failed && (devices ?? []).isEmpty {
                        LoadErrorView(compact: true) { Task { await load() } }
                    } else if let devices, devices.isEmpty {
                        EmptyStateView(icon: .laptop, title: "No connected devices",
                                       description: "Sign in to harbor0 on a computer or phone. Your devices will appear here.", compact: true) {
                            Button("Refresh devices") { Task { await load() } }.harborButton(.outline)
                        }
                    } else if let devices {
                        // Signed-out devices and browsers last, then by name.
                        let list = devices.sorted { a, b in
                            let sa = a.status == "SIGNED_OUT", sb = b.status == "SIGNED_OUT"
                            if sa != sb { return !sa }
                            if (a.platform == "WEB") != (b.platform == "WEB") { return a.platform != "WEB" }
                            return a.name.lowercased() < b.name.lowercased()
                        }
                        VStack(spacing: 0) {
                            ForEach(Array(list.enumerated()), id: \.element.id) { index, device in row(device, last: index == list.count - 1) }
                        }
                    }
                }
            }
        }
        .refreshable { await load() }
        .task { await load() }
        .sheet(item: $revoking) { device in RevokeSheet(device: device, here: device.id == current) { await load() }.environment(\.tokens, tokens) }
        .sheet(item: $signingOut) { device in
            let here = device.id == current
            AsyncConfirm(title: "Sign out \(device.name)?",
                         description: here ? "This is the iPhone you’re using. You’ll be signed out, and its sync and backups pause until you sign in again. Nothing is deleted."
                            : "\(device.name) is signed out of harbor0. Its sync and backups pause, and resume when you sign in on it again. Nothing is deleted."
                                + (device.platform == "WEB" ? " If it’s the browser you’re using now, you’ll need to sign in again." : ""),
                         confirm: "Sign out", danger: false) {
                do {
                    let _: EmptyResponse = try await workspace.api.request("/v1/devices/\(E2eeTransport.segment(device.id))/sign-out", method: "POST", body: [:])
                    if here { await workspace.sync.disconnect(); try? workspace.api.forgetLocalSession(); return true }
                    workspace.notify("\(device.name) is signed out.")
                    await load()
                    return true
                } catch { workspace.fail(error); return false }
            }.environment(\.tokens, tokens)
        }
    }
    private func row(_ device: Device, last: Bool) -> some View {
        let signedOut = device.status == "SIGNED_OUT" || device.revokedAt != nil
        return VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                IconTile(icon: device.isPhone ? .smartphone : device.platform == "CLI" ? .monitor : .laptop)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(device.name).font(TypeScale.baseMedium).lineLimit(1)
                        if device.id == current { Badge(text: "This device", tone: .accent) }
                    }
                    Text("\(device.platformName) · \(signedOut ? "Signed out · sync and backups paused" : "Last active \(Format.date(device.lastSeenAt ?? device.createdAt))")")
                        .font(TypeScale.xs).foregroundStyle(tokens.text2).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }
            HStack(spacing: 8) {
                if !signedOut { Button("Sign out") { signingOut = device }.harborButton(.outline, size: .sm).accessibilityIdentifier("signOut-" + device.name) }
                Button("Revoke") { revoking = device }.harborButton(.outline, size: .sm).accessibilityIdentifier("revoke-" + device.name)
            }.padding(.leading, 44)
        }
        .padding(.vertical, 12)
        .overlay(alignment: .bottom) { if !last { Hairline() } }
    }
    private func load() async {
        do {
            let page: Page<Device> = try await workspace.api.request("/v1/devices")
            devices = page.items
            failed = false
        } catch is CancellationError {
        } catch { failed = true }
    }
}

/// What revoking a device does, before it happens: its running backups are archived and it leaves its synced folders.
private struct RevokeSheet: View {
    let device: Device
    let here: Bool
    let done: () async -> Void
    @EnvironmentObject private var workspace: Workspace
    @Environment(\.tokens) private var tokens
    @Environment(\.dismiss) private var dismiss
    @State private var backups: [BackupRoot]?
    @State private var folders: [DriveItem]?
    @State private var failed = false
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        FittedSheet {
            SheetScaffold(title: "Revoke \(device.name)?", description: "This removes the device and stops everything it does in harbor0.") {
                VStack(alignment: .leading, spacing: 12) {
                    Text("\(device.name) is signed out and removed from your devices. Nothing stored on it is deleted.").font(TypeScale.base)
                        .fixedSize(horizontal: false, vertical: true)
                    if failed {
                        AlertBanner(text: "Couldn’t load this device’s backups and synced folders. Revoking still archives its backups and removes it from sync.")
                    } else if let backups, let folders {
                        part("Backups stop", backups.isEmpty ? "This device doesn’t back up any folders."
                             : "\(Format.count(backups.count, "backup folder")) \(backups.count == 1 ? "is" : "are") archived in the cloud. Every backed-up file and version is kept, read-only.",
                             backups.map(\.localPathDisplayName))
                        part("Sync stops", folders.isEmpty ? "This device doesn’t sync any folders." : "It leaves \(Format.count(folders.count, "synced folder")). Your other devices keep syncing them.",
                             folders.map(\.name))
                        let alone = folders.filter { ($0.syncDevices ?? []).allSatisfy { $0.id == device.id } }
                        if !alone.isEmpty {
                            AlertBanner(text: "\(alone.map(\.name).joined(separator: ", ")) \(alone.count == 1 ? "isn’t" : "aren’t") synced to any other device. \(alone.count == 1 ? "It stays" : "They stay") in My Drive under Synced Folders.")
                        }
                    } else { SkeletonRows(count: 2) }
                    Text("If someone signs in on \(device.name) again later, it’s added as a new device.").font(TypeScale.sm).foregroundStyle(tokens.text2)
                        .fixedSize(horizontal: false, vertical: true)
                    if let error { AlertBanner(tone: .danger, text: error) }
                    SheetActions {
                        Button(busy ? "Working…" : "Revoke device") { Task { await revoke() } }.harborButton(.danger, size: .lg, block: true).disabled(busy)
                            .accessibilityIdentifier("confirm-Revoke device")
                        Button("Go back") { dismiss() }.harborButton(.outline, size: .lg, block: true).disabled(busy)
                    }
                }
            }
        }
        .task {
            do {
                let catalog: BackupCatalog = try await workspace.api.request("/v1/backups")
                backups = catalog.items.filter { root in
                    guard root.state != "REMOVED" && root.state != "ARCHIVED" else { return false }
                    if let mine = root.devicePublicId, let theirs = device.devicePublicId { return mine == theirs }
                    return root.deviceId == device.id || root.deviceName == device.name
                }
                var all: [DriveItem] = []
                var cursor: String?
                repeat {
                    let page: DrivePage = try await workspace.api.request(HarborAPI.pagePath("/v1/sync/folders", cursor: cursor))
                    all += page.items; cursor = page.nextCursor
                } while cursor != nil && all.count < 5000
                folders = all.filter { ($0.syncDevices ?? []).contains { $0.id == device.id } }
            } catch { failed = true }
        }
        .interactiveDismissDisabled(busy)
    }
    private func part(_ title: String, _ text: String, _ names: [String]) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(TypeScale.baseMedium.weight(.semibold))
            Text(text).font(TypeScale.sm).foregroundStyle(tokens.text2).fixedSize(horizontal: false, vertical: true)
            ForEach(names, id: \.self) { Text("• " + $0).font(TypeScale.sm) }
        }
    }
    private func revoke() async {
        busy = true; error = nil
        defer { busy = false }
        do {
            let _: EmptyResponse = try await workspace.api.request("/v1/devices/\(E2eeTransport.segment(device.id))", method: "DELETE")
            dismiss()
            if here { await workspace.sync.disconnect(); try? workspace.api.forgetLocalSession(); return }
            workspace.notify("\(device.name) is revoked.")
            await done()
        } catch { self.error = error.localizedDescription }
    }
}

// MARK: - Storage

struct StorageScreen: View {
    @EnvironmentObject private var workspace: Workspace
    @Environment(\.tokens) private var tokens
    var body: some View {
        PageScroll {
            VStack(alignment: .leading, spacing: 16) {
                PageTitle(text: "Storage")
                if let usage = workspace.account?.storage {
                    Card {
                        VStack(alignment: .leading, spacing: 14) {
                            HStack(alignment: .firstTextBaseline, spacing: 8) {
                                Text(UploadActivity.bytes(usage.usedBytes)).font(Font.geist(28, 600)).tracking(-0.84).monospacedDigit()
                                Text("of \(UploadActivity.bytes(usage.quotaBytes)) used").font(TypeScale.base).foregroundStyle(tokens.text2)
                            }
                            ProgressBar(value: Double(usage.usedBytes + usage.reservedBytes) / Double(max(1, usage.quotaBytes)), height: 8)
                            ViewThatFits(in: .horizontal) {
                                HStack(spacing: 20) { breakdown(usage) }
                                VStack(alignment: .leading, spacing: 6) { breakdown(usage) }
                            }
                            .font(TypeScale.sm).foregroundStyle(tokens.text2).monospacedDigit()
                            Text("Storage includes current files, version history, backups, trash, and content retained for sent transfers. Permanently deleting unused files frees up space.")
                                .font(TypeScale.sm).foregroundStyle(tokens.text2).lineSpacing(5).fixedSize(horizontal: false, vertical: true)
                            Button { workspace.navigate(.trash) } label: { Icon(.trash, size: 15); Text("Open Trash") }.harborButton(.outline, size: .sm)
                        }
                    }
                } else {
                    LoadErrorView { Task { await workspace.refreshAccount() } }
                }
                StorageAuditCard()
                // No plan is named: the account doesn't say which one it has, and plans aren't bought in this app.
                Card {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Included").font(TypeScale.lg).tracking(-0.16)
                        Text("Backups, file sharing and transfers are included with every account.").font(TypeScale.sm).foregroundStyle(tokens.text2)
                            .lineSpacing(3).fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        }
        .refreshable { await workspace.refreshAccount() }
        .task { await workspace.refreshAccount() }
    }
    @ViewBuilder private func breakdown(_ usage: Account.Storage) -> some View {
        Text("\(UploadActivity.bytes(usage.usedBytes)) stored")
        Text("\(UploadActivity.bytes(usage.reservedBytes)) uploading")
        Spacer(minLength: 0)
        Text("\(UploadActivity.bytes(usage.available)) available").font(TypeScale.smMedium).foregroundStyle(tokens.text)
    }
}

/// One stored file version, the unit storage is charged in; `countedBytes` is what it adds to `usedBytes`.
struct StorageAuditRow: Decodable {
    let itemId: String; let versionId: String; let path: String; let location: String; var locationDetail: String? = nil
    let state: String; var versionNumber = 0; var sizeBytes: Int64 = 0; var countedBytes: Int64 = 0
    var contentHash = ""; var uploadedAt = ""; var uploadedFrom: String? = nil
}
enum StorageAudit {
    private static let locations: [(String, String)] = [("MY_DRIVE", "My Drive"), ("BACKUP", "Backups"), ("SYNC", "Synced folders"), ("TRASH", "Trash"), ("DELETING", "Being deleted")]
    private static let states = ["CURRENT": "Current version", "PREVIOUS_VERSION": "Previous version",
                                 "RETAINED_FOR_TRANSFER": "Kept for a sent transfer", "ON_DEVICES_ONLY": "On devices only (not counted)"]
    static func size(_ bytes: Int64) -> String {
        if bytes < 1000 { return "\(bytes) B" }
        let unit = min(4, Int(log10(Double(bytes)) / 3))
        return String(format: "%.2f %@", locale: Locale(identifier: "en_US_POSIX"), Double(bytes) / pow(1000, Double(unit)), ["B", "KB", "MB", "GB", "TB"][unit])
    }
    private static func cell(_ value: Any?) -> String {
        guard let value else { return "" }
        var text = "\(value)"
        // Keep spreadsheet apps from evaluating file names as formulas.
        if value is String, let first = text.first, "=+-@\t\r".contains(first) { text = "'" + text }
        return text.contains(where: { ",\"\n\r".contains($0) }) ? "\"" + text.replacingOccurrences(of: "\"", with: "\"\"") + "\"" : text
    }
    private static func line(_ values: [Any?]) -> String { values.map(cell).joined(separator: ",") }
    /// The audit CSV, byte for byte as the web builds it: a summary, then every version, largest first.
    static func csv(_ rows: [StorageAuditRow], storage: Account.Storage, at: Date = Date()) -> String {
        let counted = rows.reduce(Int64(0)) { $0 + $1.countedBytes }
        var byLocation: [String: Int64] = [:]
        for row in rows { byLocation[row.location, default: 0] += row.countedBytes }
        let previous = rows.filter { $0.state == "PREVIOUS_VERSION" }.reduce(Int64(0)) { $0 + $1.countedBytes }
        var summary: [(String, Int64)] = locations.compactMap { key, label in byLocation[key].map { (label, $0) } }
        summary += [("  of which previous versions", previous), ("Total of files listed below", counted), ("Storage used (account total)", storage.usedBytes),
                    ("Unaccounted difference", storage.usedBytes - counted), ("Uploads in progress (reserved)", storage.reservedBytes),
                    ("Available", storage.available), ("Quota", storage.quotaBytes)]
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        var out = [line(["harbor0 storage audit", iso.string(from: at)]), line(["Summary", "Bytes", "Size"])]
        for (label, bytes) in summary { out.append(line([label, bytes, size(abs(bytes))])) }
        out.append("")
        out.append(line(["Path", "Location", "Location detail", "Version state", "Version", "Size (bytes)", "Counted (bytes)", "Size",
                         "Uploaded at", "Uploaded from", "SHA-256", "File ID", "Version ID"]))
        let label = Dictionary(uniqueKeysWithValues: locations)
        for r in rows.sorted(by: { $0.countedBytes != $1.countedBytes ? $0.countedBytes > $1.countedBytes : $0.path < $1.path }) {
            out.append(line([r.path, label[r.location], r.locationDetail, states[r.state], r.versionNumber, r.sizeBytes, r.countedBytes,
                             size(r.sizeBytes), r.uploadedAt, r.uploadedFrom, r.contentHash, r.itemId, r.versionId]))
        }
        return out.joined(separator: "\r\n") + "\r\n"
    }
}

/// The Storage page's audit card: every file version counted toward storage, as a CSV for the share sheet.
struct StorageAuditCard: View {
    @EnvironmentObject private var workspace: Workspace
    @EnvironmentObject private var transfers: TransferCenter
    @State private var running = false
    @State private var done: Int?
    @State private var error: String?
    var body: some View {
        Card(title: "Storage audit", description: "Download a spreadsheet of every file version counted toward your storage — My Drive, backups and synced folders from all your devices, trash, and version history.") {
            VStack(alignment: .leading, spacing: 10) {
                if let done { AlertBanner(tone: .success, text: "Downloaded an audit of \(done) file \(done == 1 ? "version" : "versions").") }
                if let error { AlertBanner(tone: .danger, text: error) }
                Button { Task { await run() } } label: { Icon(.fileDown, size: 15); Text(running ? "Preparing audit…" : "Download audit (CSV)") }
                    .harborButton(.outline, block: true).disabled(running).accessibilityIdentifier("storageAudit")
            }
        }
    }
    private struct AuditPage: Decodable { let rows: [StorageAuditRow]; let storage: Account.Storage; var nextCursor: String? = nil }
    private func run() async {
        running = true; error = nil; done = nil
        defer { running = false }
        do {
            var rows: [StorageAuditRow] = []
            var cursor: String?
            var storage: Account.Storage?
            var seen = Set<String>()
            repeat {
                let page: AuditPage = try await workspace.api.request(HarborAPI.pagePath("/v1/storage/audit?limit=200", cursor: cursor))
                rows += page.rows; storage = page.storage; cursor = page.nextCursor
                if let cursor, !seen.insert(cursor).inserted { throw APIError(status: 0, message: "Could not load the whole audit. Try again.") }
            } while cursor != nil
            let at = Date()
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent("HarborTransfers/\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let day = ISO8601DateFormatter().string(from: at).prefix(10)
            let file = directory.appendingPathComponent("harbor0-storage-audit-\(day).csv")
            // BOM so spreadsheet apps read non-ASCII file names as UTF-8.
            try Data(("\u{FEFF}" + StorageAudit.csv(rows, storage: storage!, at: at)).utf8).write(to: file)
            done = rows.count
            transfers.shareURLs = [file]
        } catch { self.error = error.localizedDescription }
    }
}

// MARK: - Notifications

/// What a server notification says and where it leads (web describeNotification).
enum NoticeTarget { case received, sent, storage }
func describeNotice(_ n: HarborNotification) -> (title: String, target: NoticeTarget?) {
    let actor = n.field("actorName") ?? "Someone"
    let itemName = n.field("itemName")
    let named = itemName.map { "“\($0)”" }
    switch n.type {
    case "TRANSFER_RECEIVED": return ("\(actor) sent you \(named ?? "files")", .received)
    case "TRANSFER_ACCEPTED": return ("\(actor) accepted \(named ?? "your transfer")", .sent)
    case "TRANSFER_DECLINED": return ("\(actor) declined \(named ?? "your transfer")", .sent)
    case "TRANSFER_CANCELLED": return ("\(actor) cancelled \(named.map { "the transfer of \($0)" } ?? "a transfer to you")", .received)
    case "TRANSFER_EXPIRED": return ("Your transfer\(named.map { " of \($0)" } ?? "") expired", .sent)
    case "SHARE_RECEIVED": return ("\(actor) shared \(named ?? "an item") with you", .received)
    case "SHARE_REVOKED": return ("\(actor) removed your access to \(named ?? "a shared item")", .received)
    case "SHARED_UPLOAD_BLOCKED_BY_STORAGE": return ("\(actor) couldn’t upload to \(named ?? "your shared folder") because your storage is full", .storage)
    default:
        let text = n.type.replacingOccurrences(of: "_", with: " ").lowercased()
        let sentence = text.prefix(1).uppercased() + text.dropFirst()
        return (itemName.map { "\(sentence): \($0)" } ?? sentence, nil)
    }
}

struct NotificationsScreen: View {
    @EnvironmentObject private var workspace: Workspace
    @Environment(\.tokens) private var tokens
    @State private var items: [HarborNotification]?
    @State private var failed = false
    @State private var busy = false
    var body: some View {
        PageScroll {
            VStack(alignment: .leading, spacing: 16) {
                PageTitle(text: "Notifications")
                Card(padding: 16) {
                    if items == nil && !failed {
                        SkeletonRows(count: 3, tile: 32)
                    } else if failed && (items ?? []).isEmpty {
                        LoadErrorView(compact: true) { Task { await load() } }
                    } else if let items, items.isEmpty {
                        EmptyStateView(icon: .bell, title: "No notifications", description: "Updates about your files and transfers will appear here.", compact: true) {
                            Button("Back to My Drive") { workspace.navigate(.drive) }.harborButton(.outline)
                        }
                    } else if let items {
                        let unread = items.filter { $0.readAt == nil }
                        VStack(spacing: 0) {
                            if !unread.isEmpty {
                                HStack {
                                    Text("\(unread.count) unread").font(TypeScale.sm).foregroundStyle(tokens.text2)
                                    Spacer()
                                    Button { Task { await markRead(unread) } } label: { Icon(.check, size: 14); Text("Mark all as read") }
                                        .harborButton(.outline, size: .sm).disabled(busy)
                                }.padding(.bottom, 8)
                            }
                            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                                let text = describeNotice(item)
                                HStack(spacing: 12) {
                                    Icon(.bell, size: 18).foregroundStyle(item.readAt == nil ? tokens.accentText : tokens.text2).padding(.horizontal, 8)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(text.title).font(item.readAt == nil ? TypeScale.baseMedium.weight(.semibold) : TypeScale.base)
                                            .fixedSize(horizontal: false, vertical: true)
                                        Text(Format.date(item.createdAt)).font(TypeScale.sm).foregroundStyle(tokens.text2)
                                    }
                                    Spacer(minLength: 8)
                                    Button(item.readAt != nil ? "Read" : "Mark read") { Task { await markRead([item]) } }
                                        .harborButton(.ghost, size: .sm).disabled(item.readAt != nil || busy)
                                }
                                .padding(.vertical, 12)
                                .contentShape(Rectangle())
                                .onTapGesture { if text.target != nil { Task { await openNotice(item, text.target!) } } }
                                .overlay(alignment: .bottom) { if index < items.count - 1 { Hairline() } }
                            }
                        }
                    }
                }
            }
        }
        .refreshable { await load() }
        .task { await load() }
    }
    private func load() async {
        do {
            let page: Page<HarborNotification> = try await workspace.api.request("/v1/notifications")
            items = page.items
            failed = false
        } catch is CancellationError {
        } catch { failed = true }
    }
    private func markRead(_ list: [HarborNotification]) async {
        busy = true
        defer { busy = false }
        do {
            try await withThrowingTaskGroup(of: Void.self) { group in
                for item in list {
                    group.addTask { @MainActor in let _: EmptyResponse = try await workspace.api.request("/v1/notifications/\(item.id)/read", method: "POST") }
                }
                try await group.waitForAll()
            }
            await load()
        } catch { workspace.fail(error) }
    }
    /// Marks a notice read and opens where it leads: Shared → Received or Sent, or Storage.
    private func openNotice(_ item: HarborNotification, _ target: NoticeTarget) async {
        if item.readAt == nil { await markRead([item]) }
        switch target {
        case .storage: workspace.navigate(.storage)
        case .received, .sent: workspace.sharedTab = target == .sent ? .sent : .received; workspace.navigate(.shared)
        }
    }
}

// MARK: - Settings

struct SettingsScreen: View {
    @EnvironmentObject private var workspace: Workspace
    @EnvironmentObject private var appearance: AppearanceStore
    @Environment(\.tokens) private var tokens
    @State private var deleting = false
    @State private var signingOut = false
    @StateObject private var security = SecurityModel()
    var body: some View {
        PageScroll {
            VStack(alignment: .leading, spacing: 16) {
                PageTitle(text: "Settings")
                if let user = workspace.account?.user {
                    Card(title: "Your account", description: "Manage active sessions in Devices. Use “Forgot password” on the sign-in screen to reset your password.") {
                        VStack(alignment: .leading, spacing: 0) {
                            Grid(alignment: .leading, horizontalSpacing: 20) {
                                GridRow {
                                    Text("Email").foregroundStyle(tokens.text2)
                                    HStack(spacing: 8) {
                                        Text(user.email).lineLimit(1).truncationMode(.middle)
                                        if user.emailVerified != false { Badge(text: "Verified", tone: .success) }
                                    }
                                }
                            }.font(TypeScale.sm)
                            ProfileForm(user: user).id((user.displayName) + (user.username ?? ""))
                                .padding(.vertical, 20)
                                .overlay(alignment: .top) { Hairline() }
                                .overlay(alignment: .bottom) { Hairline() }
                                .padding(.top, 20)
                            Button { workspace.navigate(.devices) } label: { Icon(.settings, size: 16); Text("Manage devices") }
                                .harborButton(.outline).padding(.top, 16)
                        }
                    }
                    // The web order: account, encryption, two-step verification, rclone, email, sync & backups, appearance, delete.
                    if workspace.encrypted || workspace.flag("e2ee") { EncryptionCard() }
                    TwoFactorCard(model: security)
                    AccessTokensCard(model: security)
                    EmailCard()
                    SyncBackupsCard()
                    AppearanceCard()
                    Card(title: "Delete account", description: "You’ll be signed out everywhere right away. Your files, backups and transfers are kept for 30 days and then permanently deleted.") {
                        Button { deleting = true } label: { Icon(.trash, size: 16); Text("Delete account") }
                            .harborButton(.danger).accessibilityIdentifier("deleteAccount")
                    }
                    Card(title: "Sign out", description: "harbor0 for iPhone · Version \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "") — Uploads and downloads run while the app is open. Photo backups run while harbor0 is open, and in the background when iOS allows.") {
                        Button { signingOut = true; Task { await workspace.signOut(); signingOut = false } } label: {
                            Icon(.logOut, size: 16); Text(signingOut ? "Signing out…" : "Sign out")
                        }
                        .harborButton(.outline).disabled(signingOut).accessibilityIdentifier("settingsSignOut")
                    }
                } else if appearance.loading {
                    SkeletonRows(count: 3)
                } else {
                    AppearanceCard()
                }
                LegalCard()
            }
        }
        .refreshable { await workspace.refreshAccount(); await security.load(workspace.api, tokens: workspace.flag("rclone")) }
        .task { await security.load(workspace.api, tokens: workspace.flag("rclone")) }
        .sheet(isPresented: $deleting) {
            if let email = workspace.account?.user.email { DeleteAccountSheet(email: email).environment(\.tokens, tokens) }
        }
    }
}

/// App Review wants the privacy policy reachable from inside the app, not only from the store listing.
private struct LegalCard: View {
    @Environment(\.openURL) private var openURL
    var body: some View {
        Card(title: "Privacy and terms", description: "How harbor0 handles your data, and the terms for using it.") {
            HStack(spacing: 8) {
                Button { openURL(URL(string: "https://harbor0.com/privacy")!) } label: { Icon(.externalLink, size: 16); Text("Privacy policy") }
                    .harborButton(.outline).accessibilityIdentifier("privacyPolicy")
                Button { openURL(URL(string: "https://harbor0.com/terms")!) } label: { Icon(.externalLink, size: 16); Text("Terms of service") }
                    .harborButton(.outline).accessibilityIdentifier("termsOfService")
            }
        }
    }
}

private struct ProfileForm: View {
    let user: Account.User
    @EnvironmentObject private var workspace: Workspace
    @State private var displayName = ""
    @State private var username = ""
    @State private var saving = false
    private var changed: Bool { displayName.trimmingCharacters(in: .whitespaces) != user.displayName || username != (user.username ?? "") }
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Field(label: "Display name") { TextInput(placeholder: "Your name", text: $displayName, content: .name, autocapitalize: true, identifier: "displayName") }
            Field(label: "Username", hint: "3–32 lowercase letters, numbers, dots, or underscores. Usernames can be changed once every 30 days.") {
                TextInput(placeholder: "username", text: Binding(get: { username }, set: { username = $0.lowercased() }), prefix: "@",
                          content: .username, identifier: "username")
            }
            Button(saving ? "Saving…" : "Save profile") { save() }.harborButton(.primary)
                .disabled(!changed || saving || displayName.trimmingCharacters(in: .whitespaces).isEmpty)
                .accessibilityIdentifier("saveProfile")
        }
        .onAppear { displayName = user.displayName; username = user.username ?? "" }
    }
    private func save() {
        guard username.isEmpty || username.range(of: "^[a-z0-9_.]{3,32}$", options: .regularExpression) != nil else {
            workspace.error = "Use 3–32 lowercase letters, numbers, dots, or underscores."
            return
        }
        saving = true
        // Only changed fields are sent, so an unchanged username never counts as a rename.
        var body: [String: Any] = ["operationId": UUID().uuidString]
        let name = displayName.trimmingCharacters(in: .whitespaces)
        if name != user.displayName { body["displayName"] = name }
        if username != (user.username ?? "") { body["username"] = username }
        Task {
            defer { saving = false }
            do {
                let _: UserResponse = try await workspace.api.request("/v1/users/me", method: "PATCH", body: body)
                await workspace.refreshAccount()
                workspace.notify("Profile updated.")
            } catch { workspace.fail(error) }
        }
    }
}

private struct DeleteAccountSheet: View {
    let email: String
    @EnvironmentObject private var workspace: Workspace
    @Environment(\.dismiss) private var dismiss
    @State private var typed = ""
    @State private var busy = false
    @State private var error: String?
    private var matches: Bool { typed.trimmingCharacters(in: .whitespaces).lowercased() == email.lowercased() }
    var body: some View {
        FittedSheet {
            SheetScaffold(title: "Delete your account?", description: "This can’t be undone. You’ll be signed out on every device, and your data will be permanently deleted after 30 days.") {
                VStack(alignment: .leading, spacing: 16) {
                    if let error { AlertBanner(tone: .danger, text: error) }
                    Field(label: "Type \(email) to confirm") {
                        TextInput(placeholder: "", text: $typed, keyboard: .emailAddress, content: .emailAddress, identifier: "confirmEmail")
                    }
                    SheetActions {
                        Button(busy ? "Deleting…" : "Delete account") { delete() }.harborButton(.danger, size: .lg, block: true)
                            .disabled(!matches || busy).accessibilityIdentifier("confirmDeleteAccount")
                        Button("Go back") { dismiss() }.harborButton(.outline, size: .lg, block: true).disabled(busy)
                    }
                }
            }
        }
        .interactiveDismissDisabled(busy)
    }
    private func delete() {
        guard matches, !busy else { return }
        busy = true
        error = nil
        Task {
            do {
                let _: EmptyResponse = try await workspace.api.request("/v1/users/me/delete", method: "POST",
                    body: ["operationId": UUID().uuidString, "email": typed.trimmingCharacters(in: .whitespaces)])
                dismiss()
                try? workspace.api.forgetLocalSession()
            } catch { self.error = error.localizedDescription }
            busy = false
        }
    }
}

/// Settings → Appearance (appearance-settings.tsx): color mode, preset themes and custom colors, saved to the account.
private struct AppearanceCard: View {
    @EnvironmentObject private var workspace: Workspace
    @EnvironmentObject private var appearance: AppearanceStore
    @Environment(\.tokens) private var tokens
    @Environment(\.colorScheme) private var colorScheme
    private var mode: String { (appearance.appearance.colorScheme ?? colorScheme) == .dark ? "dark" : "light" }
    private let fields: [(String, String, String)] = [
        ("primary", "Accent", "Buttons, links, and focus rings"), ("background", "Background", "Your main workspace"),
        ("card", "Cards", "File lists, panels, and popovers"), ("sidebar", "Navigation", "Navigation and account area"),
        ("border", "Borders", "Dividers and input outlines")]
    var body: some View {
        let ready = appearance.account != nil
        Card(title: "Appearance", description: "Theme settings apply across your devices.") {
            VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 8) {
                    SectionTitle(text: "Color mode")
                    Segmented(options: [SegmentOption(value: "light", label: "Light", icon: .sun), SegmentOption(value: "dark", label: "Dark", icon: .moon),
                                        SegmentOption(value: "system", label: "System", icon: .monitor)],
                              selection: Binding(get: { appearance.appearance.preference },
                                                 set: { value in appearance.change(workspace.api) { $0.preference = value } }),
                              height: 40, identifier: "mode")
                }
                VStack(alignment: .leading, spacing: 8) {
                    SectionTitle(text: "Preset themes")
                    Text("Each theme includes light and dark colors. Choosing a preset resets custom colors.")
                        .font(TypeScale.sm).foregroundStyle(tokens.text2).fixedSize(horizontal: false, vertical: true).padding(.bottom, 4)
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                        ForEach(ThemePreset.all) { preset in presetCard(preset) }
                    }
                }
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 8) {
                        SectionTitle(text: "Custom colors")
                        Badge(text: mode == "light" ? "Light theme" : "Dark theme")
                    }.padding(.bottom, 4)
                    Text("Choose a color or enter a hex code. Light and dark themes keep separate colors.")
                        .font(TypeScale.sm).foregroundStyle(tokens.text2).fixedSize(horizontal: false, vertical: true).padding(.bottom, 12)
                    ForEach(fields, id: \.0) { field in ColorRow(key: field.0, label: field.1, detail: field.2, mode: mode) }
                }
                VStack(alignment: .leading, spacing: 10) {
                    Hairline()
                    Text(status).font(TypeScale.sm).foregroundStyle(tokens.text2).accessibilityIdentifier("appearanceSaved")
                    HStack(spacing: 8) {
                        if appearance.error != nil {
                            Button("Retry") { Task { if appearance.dirty { await appearance.save(workspace.api) } else { await appearance.load(workspace.api) } } }
                                .harborButton(.outline)
                        }
                        Button { appearance.change(workspace.api) { if mode == "dark" { $0.palettes.dark = [:] } else { $0.palettes.light = [:] } } } label: {
                            Icon(.rotateCcw, size: 16); Text("Reset \(mode) colors")
                        }
                        .harborButton(.outline)
                        .disabled(!ready || (mode == "dark" ? appearance.appearance.palettes.dark : appearance.appearance.palettes.light).isEmpty)
                    }
                }
            }
            .disabled(!ready)
        }
    }
    private var status: String {
        if appearance.loading && appearance.account == nil { return "Loading your account theme…" }
        if appearance.saving { return "Saving to your account…" }
        if appearance.error != nil { return appearance.account != nil ? "Applied here. Couldn’t save to your account. Please retry." : "Couldn’t load your account theme. Please retry." }
        if appearance.savedMessage != nil || !appearance.dirty { return "Saved to your account." }
        return "Saving to your account…"
    }
    private func presetCard(_ preset: ThemePreset) -> some View {
        let selected = appearance.appearance.preset == preset.id
        let customized = !appearance.appearance.palettes.light.isEmpty || !appearance.appearance.palettes.dark.isEmpty
        return Button {
            appearance.change(workspace.api) { $0.preset = preset.id; $0.palettes = .init() }
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 0) {
                    ForEach(preset.cardSwatches(dark: mode == "dark"), id: \.self) { Rectangle().fill(Color(hex: $0)) }
                }
                .frame(height: 40)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(tokens.line, lineWidth: 1))
                .padding(.bottom, 8)
                Text(preset.name).font(TypeScale.smMedium.weight(.semibold)).foregroundStyle(tokens.text)
                Text(preset.detail).font(TypeScale.xs).foregroundStyle(tokens.text2)
                if selected {
                    Text(customized ? "Customized" : "Selected").font(TypeScale.xsMedium).foregroundStyle(tokens.accentText)
                } else { Text(" ").font(TypeScale.xsMedium) }
            }
            .padding(.horizontal, 8).padding(.top, 8).padding(.bottom, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(selected ? tokens.primary : tokens.line, lineWidth: selected ? 2 : 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("theme-" + preset.id)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

/// One custom color: label, picker, hex input and reset.
private struct ColorRow: View {
    let key: String
    let label: String
    let detail: String
    let mode: String
    @EnvironmentObject private var workspace: Workspace
    @EnvironmentObject private var appearance: AppearanceStore
    @Environment(\.tokens) private var tokens
    @State private var text = ""
    @State private var invalid = false
    private var custom: String? { (mode == "dark" ? appearance.appearance.palettes.dark : appearance.appearance.palettes.light)[key] }
    private var effective: String {
        let preset = ThemePreset.all.first { $0.id == appearance.appearance.preset } ?? ThemePreset.all[0]
        return custom ?? (mode == "dark" ? preset.dark : preset.light)[key] ?? "#000000"
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(TypeScale.smMedium)
                Text(detail).font(TypeScale.xs).foregroundStyle(tokens.text2)
            }
            HStack(spacing: 6) {
                ColorPicker(label, selection: Binding(get: { Color(hex: effective) }, set: { commit($0.hex) }), supportsOpacity: false)
                    .labelsHidden()
                    .frame(width: 40, height: 40)
                    .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(tokens.line, lineWidth: 1).allowsHitTesting(false))
                TextInput(placeholder: "#000000", text: $text, invalid: invalid, identifier: "hex-" + key) { commit(text) }
                    .frame(width: 120)
                    .onChange(of: text) { _, value in
                        invalid = false
                        if value.range(of: "^#[a-fA-F0-9]{6}$", options: .regularExpression) != nil, value.lowercased() != effective { commit(value) }
                    }
                Button { set(nil) } label: { Icon(.rotateCcw, size: 16) }.harborButton(.ghost, size: .icon).disabled(custom == nil)
                    .accessibilityLabel("Reset \(label.lowercased()) color")
                Spacer(minLength: 0)
            }
            if invalid { Text("Use a hex color, like #2563eb or #fff.").font(TypeScale.xs).foregroundStyle(tokens.dangerText) }
        }
        .padding(.vertical, 12)
        .overlay(alignment: .top) { Hairline() }
        .onAppear { text = effective }
        .onChange(of: effective) { _, value in if text.lowercased() != value { text = value } }
    }
    private func commit(_ value: String) {
        guard let color = HarborPalette.normalize(value) else { invalid = true; return }
        invalid = false
        text = color
        if color != effective { set(color) }
    }
    private func set(_ color: String?) {
        appearance.change(workspace.api) { value in
            if mode == "dark" { value.palettes.dark[key] = color } else { value.palettes.light[key] = color }
        }
    }
}
