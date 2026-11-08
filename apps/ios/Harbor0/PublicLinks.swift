import SwiftUI
import UIKit

// Public links (flag `public-links`): apps/web/components/public-link-dialog.tsx, public-links-list.tsx and
// lib/e2ee-links.ts; docs/specs/public-folder-links.md. Encrypted accounts make encrypted links: the
// secret is only in the address, and this phone wraps each shared item's name and content key with it.

struct PublicLink: Decodable, Identifiable, Equatable {
    struct E2ee: Decodable, Equatable { let secretWrap: String }
    let id: String
    let driveItemId: String
    let itemType: String
    var itemName: String? = nil
    let url: String
    var hasPassword = false
    let createdAt: String
    var expiresAt: String? = nil
    var disabledAt: String? = nil
    var e2ee: E2ee? = nil
}
struct LinkAllowance: Decodable { var blockedReason: String? = nil; var neverExpires = false; var active = 0; var limit = 20 }

/// "Expires in 6 days · Oct 22, 2026": how long a link has left.
func linkEnds(_ expiresAt: String?, now: Date = Date()) -> String {
    guard let expiresAt, let end = Format.parse(expiresAt) else { return "Never expires" }
    let seconds = end.timeIntervalSince(now)
    if seconds <= 0 { return "Expired" }
    let days = Int(ceil(seconds / 86400))
    let left = seconds < 86400 ? "Expires today" : "Expires in \(Format.count(days, "day"))"
    return left + " · " + Format.date(expiresAt)
}
/// The address without its scheme, for showing; copying always takes the full URL.
func shownLink(_ url: String) -> String { url.replacingOccurrences(of: "^https?://", with: "", options: .regularExpression) }
func expiresSoon(_ link: PublicLink, now: Date = Date()) -> Bool {
    guard let end = Format.parse(link.expiresAt) else { return false }
    return end.timeIntervalSince(now) < 2 * 86400
}

/// Progress of wrapping an encrypted link's items.
struct WrapProgress: Equatable { var scanned = 0; var wrapped = 0; var waiting = 0; var done = false }

@MainActor
enum PublicLinks {
    /// The address to share: an encrypted link's carries its secret, which needs the account's keys.
    static func shareable(_ link: PublicLink, keys: AccountKeys?) -> String? {
        guard let e2ee = link.e2ee else { return link.url }
        guard let keys, let secret = try? E2eeLinks.unwrapSecret(keys, e2ee.secretWrap) else { return nil }
        return E2eeLinks.url(link.url, secret: secret)
    }

    private static var running: [String: Task<WrapProgress, Error>] = [:]
    /// Wraps whatever under the link isn't wrapped for its current name and version yet. One run per link at a time.
    static func syncWraps(_ api: HarborAPI, keys: AccountKeys, link: PublicLink, progress: ((WrapProgress) -> Void)? = nil) async throws -> WrapProgress {
        if let current = running[link.id] { return try await current.value }
        let task = Task { try await wrapAll(api, keys: keys, link: link, progress: progress) }
        running[link.id] = task
        defer { running[link.id] = nil }
        return try await task.value
    }
    private static func wrapAll(_ api: HarborAPI, keys: AccountKeys, link: PublicLink, progress report: ((WrapProgress) -> Void)?) async throws -> WrapProgress {
        guard let e2ee = link.e2ee else { return WrapProgress(done: true) }
        let sealed = E2eeLinks.keys(try E2eeLinks.unwrapSecret(keys, e2ee.secretWrap))
        var progress = WrapProgress()
        var pending: [[String: Any]] = []
        func save(_ all: Bool) async throws {
            while pending.count >= 100 || (all && !pending.isEmpty) {
                let wraps = Array(pending.prefix(100))
                pending.removeFirst(wraps.count)
                let result = try await api.json("/v1/links/\(E2eeTransport.segment(link.id))/e2ee/wraps", method: "PUT", body: ["wraps": wraps])
                // Items changed since the scan are wrapped on the next run.
                progress.wrapped += (result["saved"] as? Int) ?? 0
            }
        }
        func consider(_ item: [String: Any]) throws {
            progress.scanned += 1
            guard (item["current"] as? Bool) != true, let id = item["id"] as? String, let name = item["name"] as? String,
                  let revision = item["revision"] as? Int else { return }
            var keyWrap: Any = NSNull(), head: Any = NSNull()
            if (item["encrypted"] as? Bool) == true {
                guard let header = item["keyHeader"] as? String else { progress.waiting += 1; return }
                let wrapped = try E2eeLinks.wrapFileKey(keys, sealed, header: try E2ee.fromB64url(header))
                keyWrap = wrapped.keyWrap; head = wrapped.head
            }
            // The transport has decrypted the name already: that's what the visitor sees.
            pending.append(["itemId": id, "revision": revision, "nameWrap": try E2eeLinks.wrapName(sealed, itemId: id, name: name),
                            "keyWrap": keyWrap, "head": head])
        }
        var queue: [[String: Any]] = link.itemType == "FOLDER" ? [[:]] : []
        var first = true
        while first || !queue.isEmpty {
            try Task.checkCancellation()
            let folders = Array(queue.prefix(25))
            queue.removeFirst(folders.count)
            let scan = try await api.json("/v1/links/\(E2eeTransport.segment(link.id))/e2ee/scan", method: "POST", body: ["folders": folders])
            if first, let root = scan["root"] as? [String: Any] { try consider(root) }
            first = false
            for folder in scan["folders"] as? [[String: Any]] ?? [] {
                for item in folder["items"] as? [[String: Any]] ?? [] {
                    try consider(item)
                    if item["type"] as? String == "FOLDER", let id = item["id"] as? String { queue.append(["folderId": id]) }
                }
                if let next = folder["nextCursor"] as? String, let id = folder["folderId"] as? String { queue.append(["folderId": id, "cursor": next]) }
            }
            try await save(false)
            report?(progress)
        }
        try await save(true)
        progress.done = true
        report?(progress)
        return progress
    }
    /// Brings every encrypted link of the account up to date, one at a time; errors wait for the next run.
    static func syncAll(_ api: HarborAPI, keys: AccountKeys) async {
        guard let page: Page<PublicLink> = try? await api.request("/v1/links") else { return }
        for link in page.items where link.e2ee != nil && link.disabledAt == nil {
            _ = try? await syncWraps(api, keys: keys, link: link)
        }
    }
}

private let linkExpiries: [(String, String)] = [("1", "1 day"), ("7", "7 days"), ("30", "30 days")]

/// The “Share with a link” sheet: make a link, or copy, share, change or turn off the one there is.
struct PublicLinkSheet: View {
    let item: DriveItem
    var changed: () -> Void = {}
    @EnvironmentObject private var workspace: Workspace
    @EnvironmentObject private var vault: E2eeVault
    @Environment(\.tokens) private var tokens
    @State private var allowance: LinkAllowance?
    @State private var links: [PublicLink]?
    @State private var error: String?
    @State private var notice: String?
    @State private var busy = false
    @State private var expiry = "7"
    @State private var usePassword = false
    @State private var password = ""
    @State private var wrap: WrapProgress?
    @State private var copied = false
    @State private var sharing: URL?
    @State private var confirmOff = false
    @State private var editingPassword = false
    private var encrypted: Bool { workspace.encrypted }

    var body: some View {
        VStack(spacing: 0) {
            SheetScaffold(title: "Share with a link", divider: true, scrolls: true) {
                VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 12) {
                        FileTile(kind: item.kind, size: 36)
                        Text(item.name).font(TypeScale.name).lineLimit(2)
                    }
                    if let error { AlertBanner(tone: .danger, text: error) }
                    if let notice { AlertBanner(tone: .success, text: notice) }
                    if let allowance, let links {
                        if let link = links.first { panel(link, allowance) }
                        else if let reason = allowance.blockedReason { notice("Public links aren’t available yet", reason) }
                        else if allowance.active >= allowance.limit {
                            notice("You’ve reached your link limit", "You can have \(allowance.limit) public links at once. Turn one off in Shared → Links to make another.")
                        } else { create(allowance) }
                    } else if error == nil { SkeletonRows(count: 2) }
                }
            }
        }
        .cardSurface(paint: false)
        .modifier(TallSheet())
        .task { await load() }
        .sheet(item: Binding(get: { sharing.map(PreviewURL.init) }, set: { if $0 == nil { sharing = nil } })) { item in
            ShareSheet(urls: [item.url]) { sharing = nil }.presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $confirmOff) {
            ConfirmSheet(title: "Stop this link for everyone?", description: "Anyone who has it can’t open it any more. You can make a new link later.",
                         confirm: "Turn off link", busy: busy) {
                Task { await turnOff(); confirmOff = false }
            }.environment(\.tokens, tokens)
        }
    }

    private func notice(_ title: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Icon(.lock, size: 18).foregroundStyle(tokens.text2)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(TypeScale.baseMedium)
                Text(text).font(TypeScale.sm).foregroundStyle(tokens.text2).fixedSize(horizontal: false, vertical: true)
            }
        }.padding(14).frame(maxWidth: .infinity, alignment: .leading).background(tokens.fill2, in: RoundedRectangle(cornerRadius: 10))
    }

    private func expiryOptions(_ allowance: LinkAllowance) -> [(String, String)] {
        linkExpiries + (allowance.neverExpires ? [("never", "Never")] : [])
    }
    private func settingRow<Trailing: View>(_ label: String, @ViewBuilder trailing: () -> Trailing) -> some View {
        HStack(spacing: 8) {
            Text(label).font(TypeScale.baseMedium)
            Spacer(minLength: 8)
            trailing()
        }.frame(minHeight: 36)
    }

    @ViewBuilder private func create(_ allowance: LinkAllowance) -> some View {
        Field(label: "Expires") {
            Segmented(options: expiryOptions(allowance).map { SegmentOption(value: $0.0, label: $0.1) },
                      selection: $expiry, fill: true, identifier: "linkExpiry")
        }
        Toggle(isOn: $usePassword) { Text("Require a password").font(TypeScale.baseMedium) }.tint(tokens.primary)
        if usePassword {
            Field(label: "Password", hint: "At least 4 characters") { PasswordInput(text: $password, newPassword: true, identifier: "linkPassword") }
        }
        Button { Task { await make() } } label: { Icon(.link2, size: 16); Text(busy ? "Creating…" : "Create link") }
            .harborButton(.primary, size: .lg, block: true)
            .disabled(busy || (usePassword && password.count < 4) || (encrypted && vault.uploadKeys == nil))
            .accessibilityIdentifier("createLink")
    }

    @ViewBuilder private func panel(_ link: PublicLink, _ allowance: LinkAllowance) -> some View {
        let url = PublicLinks.shareable(link, keys: vault.readKeys)
        VStack(alignment: .leading, spacing: 10) {
            if let url {
                Text(shownLink(url)).font(.system(size: 13, design: .monospaced)).lineLimit(1).truncationMode(.middle).textSelection(.enabled)
                    .padding(.horizontal, 12).padding(.vertical, 10).frame(maxWidth: .infinity, alignment: .leading)
                    .background(tokens.fill2, in: RoundedRectangle(cornerRadius: 8))
                    .accessibilityIdentifier("linkUrl")
                HStack(spacing: 8) {
                    Button { UIPasteboard.general.string = url; copied = true } label: { Icon(copied ? .check : .copy, size: 15); Text(copied ? "Copied" : "Copy link") }
                        .harborButton(.primary, block: true).accessibilityIdentifier("copyLink")
                    Button { sharing = URL(string: url) } label: { Icon(.share2, size: 15); Text("Share") }.harborButton(.outline, block: true)
                }
            } else {
                AlertBanner(tone: .warning, text: "Unlock your files to see this link")
            }
            if link.e2ee != nil {
                HStack(spacing: 6) {
                    if let wrap, !wrap.done {
                        Spinner(size: 12); Text("Encrypting… \(wrap.scanned) checked")
                    } else if let wrap, wrap.waiting > 0 {
                        Icon(.lock, size: 12); Text("Encrypted · \(Format.count(wrap.waiting, "file")) still uploading")
                    } else {
                        Icon(.lock, size: 12); Text("End-to-end encrypted")
                    }
                }.font(TypeScale.xs).foregroundStyle(tokens.text2)
            }
        }
        Hairline()
        VStack(alignment: .leading, spacing: 6) {
            settingRow("Expires") {
                Menu {
                    ForEach(expiryOptions(allowance), id: \.0) { option in
                        Button(option.0 == "never" ? option.1 : option.1 + " from now") {
                            Task { await update(link, ["expiresInDays": option.0 == "never" ? NSNull() : Int(option.0)! as Any], done: "Expiry updated.") }
                        }
                    }
                } label: { Chip(label: link.expiresAt.map { Format.date($0) } ?? "Never", active: expiresSoon(link)) }
                    .disabled(busy).accessibilityIdentifier("linkChangeExpiry")
            }
            settingRow("Password") {
                if !editingPassword {
                    if link.hasPassword {
                        Button("Remove") { Task { await update(link, ["password": NSNull()], done: "Password removed.") } }.harborButton(.ghost, size: .sm).disabled(busy)
                    }
                    Button(link.hasPassword ? "Change" : "Add") { editingPassword = true }.harborButton(.outline, size: .sm).disabled(busy)
                }
            }
            if editingPassword {
                PasswordInput(text: $password, newPassword: true, identifier: "linkNewPassword")
                HStack(spacing: 8) {
                    Button("Cancel") { editingPassword = false; password = "" }.harborButton(.ghost, size: .sm, block: true)
                    Button("Save") {
                        Task { await update(link, ["password": password], done: "Password saved."); password = ""; editingPassword = false }
                    }.harborButton(.primary, size: .sm, block: true).disabled(busy || password.count < 4)
                }
            }
        }
        Hairline()
        Button { confirmOff = true } label: { Text("Turn off link").foregroundStyle(tokens.dangerText) }.harborButton(.ghost, block: true).disabled(busy)
            .accessibilityIdentifier("turnOffLink")
    }

    private func load() async {
        do {
            async let allowed: LinkAllowance = workspace.api.request("/v1/links/allowance")
            async let listed: Page<PublicLink> = workspace.api.request("/v1/links?driveItemId=" + E2eeTransport.segment(item.id))
            let (a, l) = try await (allowed, listed)
            allowance = a; links = l.items; error = nil
            if let link = l.items.first, link.e2ee != nil, let keys = vault.readKeys { await keepWrapped(link, keys) }
        } catch { self.error = error.localizedDescription }
    }
    private func keepWrapped(_ link: PublicLink, _ keys: AccountKeys) async {
        do { wrap = try await PublicLinks.syncWraps(workspace.api, keys: keys, link: link) { wrap = $0 } }
        catch { self.error = error.localizedDescription }
    }
    private func make() async {
        busy = true; error = nil; notice = nil
        defer { busy = false }
        do {
            var body: [String: Any] = ["driveItemId": item.id, "expiresInDays": expiry == "never" ? NSNull() : Int(expiry)! as Any,
                                       "password": usePassword ? password : NSNull()]
            var secret: Data?
            if encrypted {
                guard let keys = vault.uploadKeys else { throw e2eeLocked() }
                let fresh = E2eeLinks.newSecret()
                body["e2ee"] = ["secretWrap": try E2eeLinks.wrapSecret(keys, fresh)]
                secret = fresh
            }
            struct Created: Decodable { let link: PublicLink }
            let created: Created = try await workspace.api.request("/v1/links", method: "POST", body: body)
            links = [created.link] + (links ?? [])
            allowance?.active += 1
            password = ""; usePassword = false
            if let url = secret.map({ E2eeLinks.url(created.link.url, secret: $0) }) ?? Optional(created.link.url) {
                UIPasteboard.general.string = url; copied = true
            }
            changed()
            if let keys = vault.readKeys, created.link.e2ee != nil { await keepWrapped(created.link, keys) }
        } catch { self.error = error.localizedDescription }
    }
    private func update(_ link: PublicLink, _ changes: [String: Any], done: String) async {
        busy = true; error = nil; notice = nil
        defer { busy = false }
        do {
            struct Changed: Decodable { let link: PublicLink }
            let result: Changed = try await workspace.api.request("/v1/links/\(E2eeTransport.segment(link.id))", method: "PATCH", body: changes)
            links = links?.map { $0.id == result.link.id ? result.link : $0 }
            notice = done
        } catch { self.error = error.localizedDescription }
    }
    private func turnOff() async {
        guard let link = links?.first else { return }
        busy = true; error = nil
        defer { busy = false }
        do {
            let _: EmptyResponse = try await workspace.api.request("/v1/links/\(E2eeTransport.segment(link.id))", method: "DELETE")
            links = links?.filter { $0.id != link.id }
            allowance?.active -= 1
            notice = nil; copied = false
            changed()
        } catch { self.error = error.localizedDescription }
    }
}

/// Shared → Links: every public link that works now, to copy, change or turn off.
struct PublicLinksList: View {
    @EnvironmentObject private var workspace: Workspace
    @EnvironmentObject private var vault: E2eeVault
    @Environment(\.tokens) private var tokens
    @State private var links: [PublicLink]?
    @State private var error: String?
    @State private var managing: DriveItem?
    @State private var copied: String?
    var body: some View {
        Group {
            if let links {
                if links.isEmpty {
                    EmptyStateView(icon: .link2, title: "No public links", description: "Choose a file or folder in My Drive and select Get public link from its menu.") {
                        Button("Browse My Drive") { workspace.navigate(.drive) }.harborButton(.outline)
                    }
                } else {
                    VStack(spacing: 0) {
                        ForEach(links) { link in row(link) }
                    }
                }
            } else if let error { LoadErrorView { Task { await load() } }.accessibilityLabel(error) }
            else { SkeletonRows(count: 3) }
        }
        .task(id: workspace.revision) { await load() }
        .sheet(item: $managing, onDismiss: { Task { await load() } }) { item in
            PublicLinkSheet(item: item).environment(\.tokens, tokens).environmentObject(workspace).environmentObject(vault)
        }
    }
    private func row(_ link: PublicLink) -> some View {
        let url = PublicLinks.shareable(link, keys: vault.readKeys)
        return HStack(spacing: 12) {
            IconTile(icon: link.itemType == "FOLDER" ? .folder : .file, size: 40, iconSize: 18, radius: 10)
            VStack(alignment: .leading, spacing: 2) {
                Text(link.itemName ?? "Deleted item").font(TypeScale.name).lineLimit(1)
                Text(linkEnds(link.expiresAt) + (link.hasPassword ? " · Password" : "") + (link.disabledAt != nil ? " · Taken down" : ""))
                    .font(TypeScale.xs).foregroundStyle(expiresSoon(link) ? tokens.warningText : tokens.text2).lineLimit(1)
            }
            Spacer(minLength: 4)
            if let url {
                Button { UIPasteboard.general.string = url; copied = link.id } label: { Icon(copied == link.id ? .check : .copy, size: 16) }
                    .harborButton(.ghost, size: .icon).accessibilityLabel("Copy link")
            }
            Button("Manage") {
                managing = DriveItem(id: link.driveItemId, parentId: nil, type: link.itemType, name: link.itemName ?? "", mimeType: nil, sizeBytes: 0,
                                     updatedAt: link.createdAt, backupRootId: nil, cloudState: nil)
            }.harborButton(.outline, size: .sm)
        }
        .frame(minHeight: 64)
        .overlay(alignment: .bottom) { Hairline() }
    }
    private func load() async {
        do { links = (try await workspace.api.request("/v1/links") as Page<PublicLink>).items.filter { $0.itemName != nil }; error = nil }
        catch { self.error = error.localizedDescription }
    }
}
