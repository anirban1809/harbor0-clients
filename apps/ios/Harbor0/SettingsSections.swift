import SwiftUI
import UIKit
import CoreImage.CIFilterBuiltins

// Settings cards after Your account, in the web order (apps/web/components/app-shell.tsx, Android SettingsSections.kt):
// two-step verification, rclone access tokens and email.

/// `GET /v1/users/me/two-factor`. An account uses one method at a time.
struct TwoFactorStatus: Decodable, Equatable {
    var totp = false
    var email = false
    var active: String? { totp ? "TOTP" : email ? "EMAIL" : nil }
}
struct TotpSetup: Decodable, Equatable { let secret: String; let uri: String }
/// A personal access token for rclone and similar tools; also listed as a CLI device. The secret is shown once.
struct AccessToken: Decodable, Identifiable, Equatable {
    let id: String; let name: String; var scope: String? = nil; var createdAt: String? = nil; var expiresAt: String? = nil; var lastUsedAt: String? = nil
}

/// Copies a secret: kept on this device only and cleared after two minutes.
func copySecret(_ text: String) {
    UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: text]], options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)])
}

@MainActor
final class SecurityModel: ObservableObject {
    private static let path = "/v1/users/me/two-factor"
    @Published var twoFactor: TwoFactorStatus?
    @Published var twoFactorError: String?
    @Published var setup: TotpSetup?
    @Published var tokens: [AccessToken]?
    @Published var tokensError: String?
    /// A token just created; shown once, then forgotten.
    @Published var createdToken: String?
    @Published var notice: String?
    @Published var error: String?
    /// What is being saved: "totp", "verify", "email", "off", "create", "revoke"; nil when idle.
    @Published var working: String?

    private struct TwoFactorResponse: Decodable { let twoFactor: TwoFactorStatus }
    func load(_ api: HarborAPI, tokens wantTokens: Bool) async {
        do { twoFactor = (try await api.request(Self.path) as TwoFactorResponse).twoFactor; twoFactorError = nil }
        catch is CancellationError {} catch { twoFactorError = error.localizedDescription }
        guard wantTokens else { return }
        do { tokens = (try await api.request("/v1/tokens") as Page<AccessToken>).items; tokensError = nil }
        catch is CancellationError {} catch { tokensError = error.localizedDescription }
    }
    private func run(_ key: String, done: String? = nil, _ block: () async throws -> Void) async -> Bool {
        guard working == nil else { return false }
        working = key; error = nil; notice = nil
        defer { working = nil }
        do { try await block(); if let done { notice = done }; return true }
        catch { self.error = error.localizedDescription; return false }
    }
    func startTotp(_ api: HarborAPI) async { _ = await run("totp") { setup = try await api.request(Self.path + "/totp/setup", method: "POST") } }
    func verifyTotp(_ api: HarborAPI, code: String) async -> Bool {
        await run("verify", done: "Your authenticator app is set up. You’ll need a code from it when you sign in.") {
            twoFactor = (try await api.request(Self.path + "/totp/verify", method: "POST", body: ["code": code]) as TwoFactorResponse).twoFactor
            setup = nil
        }
    }
    func turnOnEmail(_ api: HarborAPI) async -> Bool {
        let replacing = twoFactor?.active == "TOTP"
        return await run("email", done: replacing ? "You now sign in with email codes. Your authenticator app no longer works for harbor0."
                         : "Email codes are on. You’ll get one the next time you sign in.") {
            twoFactor = (try await api.request(Self.path + "/email", method: "POST") as TwoFactorResponse).twoFactor
        }
    }
    func turnOff(_ api: HarborAPI, method: String) async -> Bool {
        await run("off", done: "Two-step verification is off.") {
            twoFactor = (try await api.request(Self.path + "/disable", method: "POST", body: ["method": method]) as TwoFactorResponse).twoFactor
        }
    }
    private struct Created: Decodable { let item: AccessToken; let token: String }
    func createToken(_ api: HarborAPI, name: String, scope: String, days: Int?) async -> Bool {
        await run("create") {
            let result: Created = try await api.request("/v1/tokens", method: "POST", body: [
                "name": name.trimmingCharacters(in: .whitespaces), "scope": scope, "expiresInDays": days as Any? ?? NSNull()
            ])
            tokens = [result.item] + (tokens ?? [])
            createdToken = result.token
        }
    }
    func revoke(_ api: HarborAPI, _ token: AccessToken) async -> Bool {
        await run("revoke") {
            let _: EmptyResponse = try await api.request("/v1/tokens/\(E2eeTransport.segment(token.id))", method: "DELETE")
            tokens = (tokens ?? []).filter { $0.id != token.id }
        }
    }
}

/// A method or token row: icon tile, title with an optional badge, text, and an action.
private struct OptionRow<Action: View>: View {
    let icon: Lucide
    let title: String
    let text: String
    var badge: (String, Tone)? = nil
    @ViewBuilder var action: Action
    @Environment(\.tokens) private var tokens
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            IconTile(icon: icon, size: 36, iconSize: 17, radius: 10)
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Text(title).font(TypeScale.baseMedium)
                    if let badge { Badge(text: badge.0, tone: badge.1) }
                }
                Text(text).font(TypeScale.sm).foregroundStyle(tokens.text2).fixedSize(horizontal: false, vertical: true)
                action
            }
            Spacer(minLength: 0)
        }
    }
}

/// Two-step verification (apps/web/components/two-factor-settings.tsx).
struct TwoFactorCard: View {
    @ObservedObject var model: SecurityModel
    @EnvironmentObject private var workspace: Workspace
    @Environment(\.tokens) private var tokens
    @State private var confirming: (method: String, turnOn: Bool)?
    private let names = ["TOTP": "authenticator app", "EMAIL": "email codes"]
    var body: some View {
        let enabled = workspace.flag("two-factor")
        let on = model.twoFactor ?? TwoFactorStatus()
        // Turning a method on is still being rolled out; anyone who already has one keeps the card to turn it off.
        if enabled || on.active != nil {
            Card {
                VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 8) {
                        Text("Two-step verification").font(TypeScale.lg).tracking(-0.16)
                        if on.active != nil { Badge(text: "On", tone: .success) }
                    }
                    Text("Ask for a code as well as your password when you sign in, so a leaked password isn’t enough to get into your files. Choose one method.")
                        .font(TypeScale.sm).foregroundStyle(tokens.text2).fixedSize(horizontal: false, vertical: true)
                    if let error = model.error, model.setup == nil, confirming == nil { AlertBanner(tone: .danger, text: error) }
                    if let notice = model.notice { AlertBanner(tone: .success, text: notice) }
                    if model.twoFactor == nil {
                        if let error = model.twoFactorError { AlertBanner(tone: .danger, text: error) } else { SkeletonRows(count: 2, tile: 32) }
                    } else {
                        method("TOTP", .smartphone, "Authenticator app", "A 6-digit code from 1Password, Google Authenticator, Authy or a similar app.", on: on.totp, active: on.active, enabled: enabled)
                        Hairline()
                        method("EMAIL", .inbox, "Email codes", "We email a code to \(workspace.account?.user.email ?? "") each time you sign in.", on: on.email, active: on.active, enabled: enabled)
                    }
                }
            }
            .accessibilityIdentifier("twoFactorSettings")
            .sheet(item: Binding(get: { model.setup.map { IdentifiedSetup(setup: $0) } }, set: { if $0 == nil { model.setup = nil } })) { item in
                TotpSetupSheet(model: model, setup: item.setup, replacing: on.email).environment(\.tokens, tokens).environmentObject(workspace)
            }
            .sheet(isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } })) {
                if let confirming {
                    let other = confirming.method == "TOTP" ? "EMAIL" : "TOTP"
                    ConfirmSheet(title: confirming.turnOn ? "Switch to \(names[confirming.method]!)?" : "Turn off two-step verification?",
                                 description: confirming.turnOn ? "Your \(names[other]!) will stop working for harbor0. You can only use one method at a time." : "Signing in will only need your password again.",
                                 confirm: confirming.turnOn ? "Switch to \(names[confirming.method]!)" : "Turn off", danger: !confirming.turnOn,
                                 busy: model.working != nil, error: model.error) {
                        Task {
                            let ok = confirming.turnOn ? await model.turnOnEmail(workspace.api) : await model.turnOff(workspace.api, method: confirming.method)
                            if ok { self.confirming = nil }
                        }
                    }.environment(\.tokens, tokens)
                }
            }
        }
    }
    private struct IdentifiedSetup: Identifiable { let setup: TotpSetup; var id: String { setup.secret } }
    @ViewBuilder private func method(_ key: String, _ icon: Lucide, _ title: String, _ text: String, on: Bool, active: String?, enabled: Bool) -> some View {
        OptionRow(icon: icon, title: title, text: text, badge: on ? ("In use", .success) : nil) {
            if on {
                Button("Turn off") { model.error = nil; confirming = (key, false) }.harborButton(.outline, size: .sm).disabled(model.working != nil)
            } else if enabled {
                Button(model.working == (key == "TOTP" ? "totp" : "email") ? "Working…" : active != nil ? "Switch to this" : key == "TOTP" ? "Set up" : "Turn on") {
                    model.error = nil
                    if key == "TOTP" { Task { await model.startTotp(workspace.api) } }
                    else if active != nil { confirming = (key, true) }
                    else { Task { _ = await model.turnOnEmail(workspace.api) } }
                }
                .harborButton(active != nil ? .outline : .primary, size: .sm).disabled(model.working != nil)
                .accessibilityIdentifier("twoFactor-" + key)
            }
        }
    }
}

/// Scan or enter the secret, then confirm with the first code. Email codes stay on until the app is set up.
private struct TotpSetupSheet: View {
    @ObservedObject var model: SecurityModel
    let setup: TotpSetup
    let replacing: Bool
    @EnvironmentObject private var workspace: Workspace
    @Environment(\.tokens) private var tokens
    @Environment(\.openURL) private var openURL
    @State private var code = ""
    @State private var copied = false
    var body: some View {
        VStack(spacing: 0) {
            SheetScaffold(title: "Set up an authenticator app",
                          description: "Scan the QR code with your authenticator app, then enter the 6-digit code it shows." + (replacing ? " Email codes stay on until the app is set up, then stop." : ""),
                          scrolls: true) {
                VStack(alignment: .leading, spacing: 14) {
                    QRCodeView(text: setup.uri).frame(width: 196, height: 196).frame(maxWidth: .infinity)
                    // On a phone the authenticator is usually on the same device, so offer the otpauth link too.
                    Button { if let url = URL(string: setup.uri) { openURL(url) } } label: { Icon(.externalLink, size: 15); Text("Open in authenticator app") }
                        .harborButton(.outline, block: true)
                    VStack(alignment: .leading, spacing: 6) {
                        FieldLabel(text: "Can’t scan it? Enter this key")
                        HStack(spacing: 8) {
                            Text(stride(from: 0, to: setup.secret.count, by: 4).map { i in
                                let start = setup.secret.index(setup.secret.startIndex, offsetBy: i)
                                return String(setup.secret[start..<(setup.secret.index(start, offsetBy: 4, limitedBy: setup.secret.endIndex) ?? setup.secret.endIndex)])
                            }.joined(separator: " "))
                            .font(.system(size: 14, design: .monospaced)).textSelection(.enabled)
                            .padding(10).frame(maxWidth: .infinity, alignment: .leading).background(tokens.fill2, in: RoundedRectangle(cornerRadius: 8))
                            Button(copied ? "Copied" : "Copy") { copySecret(setup.secret); copied = true }.harborButton(.outline, size: .sm)
                        }
                    }
                    if let error = model.error { AlertBanner(tone: .danger, text: error) }
                    Field(label: "6-digit code") {
                        TextField("", text: $code, prompt: Text("000000").foregroundStyle(tokens.text3.opacity(0.6)))
                            .font(.system(size: 18, design: .monospaced)).tracking(5.4)
                            .keyboardType(.numberPad).textContentType(.oneTimeCode)
                            .onChange(of: code) { _, value in let digits = String(value.filter(\.isNumber).prefix(6)); if digits != value { code = digits } }
                            .accessibilityIdentifier("totpCode")
                            .modifier(InputChrome(focused: false, large: true))
                    }
                }
            }
            SheetActions {
                Button(model.working == "verify" ? "Checking…" : "Turn on") { Task { if await model.verifyTotp(workspace.api, code: code) { code = "" } else { code = "" } } }
                    .harborButton(.primary, size: .lg, block: true).disabled(model.working != nil || code.count != 6)
                Button("Cancel") { model.setup = nil; model.error = nil }.harborButton(.outline, size: .lg, block: true).disabled(model.working != nil)
            }.padding(.horizontal, 20).padding(.bottom, 16)
        }
        .cardSurface(paint: false)
        .modifier(TallSheet(large: true))
    }
}

/// A QR code drawn dark on white with a quiet zone, whatever the theme, so every scanner reads it.
struct QRCodeView: View {
    let text: String
    var body: some View {
        let image: UIImage? = {
            let filter = CIFilter.qrCodeGenerator()
            filter.message = Data(text.utf8)
            filter.correctionLevel = "M"
            guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
                  let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
            return UIImage(cgImage: cg)
        }()
        Group {
            if let image { Image(uiImage: image).interpolation(.none).resizable().scaledToFit().padding(10) }
            else { Color.clear }
        }
        .background(Color.white, in: RoundedRectangle(cornerRadius: 10))
        .accessibilityLabel("QR code for your authenticator app")
    }
}

/// Personal access tokens for rclone (apps/web/components/access-token-settings.tsx), behind the `rclone` flag.
struct AccessTokensCard: View {
    @ObservedObject var model: SecurityModel
    @EnvironmentObject private var workspace: Workspace
    @Environment(\.tokens) private var tokens
    @State private var creating = false
    @State private var revoking: AccessToken?
    var body: some View {
        if workspace.flag("rclone") {
            Card(title: "Use with rclone", description: "rclone copies, syncs and mounts files from the command line, on Linux, NAS boxes and servers. Give it an access token so it can use My Drive without your password.") {
                VStack(alignment: .leading, spacing: 12) {
                    if let list = model.tokens {
                        if list.isEmpty {
                            Text("No access tokens yet. Create one, then follow the rclone install guide at rclone.org/install.").font(TypeScale.sm).foregroundStyle(tokens.text2)
                        }
                        ForEach(Array(list.enumerated()), id: \.element.id) { index, token in
                            if index > 0 { Hairline() }
                            OptionRow(icon: .monitor, title: token.name,
                                      text: (token.lastUsedAt.map { "Last used \(Format.date($0))" } ?? "Not used yet") + " · " + (token.expiresAt.map { "Expires \(Format.date($0))" } ?? "Never expires"),
                                      badge: token.scope == "READ" ? ("Read-only", .neutral) : nil) {
                                Button("Revoke") { model.error = nil; revoking = token }.harborButton(.outline, size: .sm)
                            }
                        }
                    } else if let error = model.tokensError { AlertBanner(tone: .danger, text: error) }
                    else { SkeletonRows(count: 2, tile: 32) }
                    // rclone can't encrypt, so it would read ciphertext and write plaintext.
                    if workspace.encrypted { AlertBanner(tone: .neutral, text: E2eeUnsupported.tokens) }
                    else { Button("Create token") { model.error = nil; creating = true }.harborButton(.primary, size: .sm).disabled(model.working != nil) }
                }
            }
            .sheet(isPresented: $creating) { CreateTokenSheet(model: model) { creating = false }.environment(\.tokens, tokens).environmentObject(workspace) }
            .sheet(isPresented: Binding(get: { model.createdToken != nil }, set: { if !$0 { model.createdToken = nil } })) {
                if let token = model.createdToken { TokenCreatedSheet(token: token) { model.createdToken = nil }.environment(\.tokens, tokens) }
            }
            .sheet(item: $revoking) { token in
                ConfirmSheet(title: "Revoke “\(token.name)”?", description: "Anything using this token stops working at once. This can’t be undone.",
                             confirm: "Revoke", busy: model.working != nil, error: model.error) {
                    Task { if await model.revoke(workspace.api, token) { revoking = nil; await workspace.catalog.loadDevices(workspace.api) } }
                }.environment(\.tokens, tokens)
            }
        }
    }
}

private struct CreateTokenSheet: View {
    @ObservedObject var model: SecurityModel
    let close: () -> Void
    @EnvironmentObject private var workspace: Workspace
    @State private var name = ""
    @State private var scope = "FULL"
    @State private var expiry = "365"
    var body: some View {
        FittedSheet {
            SheetScaffold(title: "Create an access token", description: "The token can use your files in My Drive, but not your account settings, sharing or devices.") {
                VStack(alignment: .leading, spacing: 14) {
                    if let error = model.error { AlertBanner(tone: .danger, text: error) }
                    Field(label: "Name") { TextInput(placeholder: "e.g. Home NAS", text: $name, autocapitalize: true, identifier: "tokenName") }
                    Field(label: "Access") {
                        Segmented(options: [SegmentOption(value: "FULL", label: "Read and write"), SegmentOption(value: "READ", label: "Read-only")], selection: $scope)
                    }
                    Field(label: "Expires") {
                        Segmented(options: [("30", "30 days"), ("90", "90 days"), ("365", "1 year"), ("never", "Never")].map { SegmentOption(value: $0.0, label: $0.1) }, selection: $expiry)
                    }
                    SheetActions {
                        Button(model.working == "create" ? "Creating…" : "Create token") {
                            Task { if await model.createToken(workspace.api, name: String(name.prefix(60)), scope: scope, days: Int(expiry)) { close() } }
                        }.harborButton(.primary, size: .lg, block: true).disabled(model.working != nil || name.trimmingCharacters(in: .whitespaces).isEmpty)
                        Button("Cancel", action: close).harborButton(.outline, size: .lg, block: true)
                    }
                }
            }
        }
    }
}

private struct TokenCreatedSheet: View {
    let token: String
    let close: () -> Void
    @Environment(\.tokens) private var tokens
    @State private var copied: String?
    var body: some View {
        FittedSheet {
            SheetScaffold(title: "Copy your token now", description: "This is the only time harbor0 shows it. If you lose it, revoke it and create another.") {
                VStack(alignment: .leading, spacing: 14) {
                    ForEach([("token", "Token", token), ("command", "Set up rclone with it", "rclone config create harbor0 harbor0 token=\(token)")], id: \.0) { entry in
                        Field(label: entry.1) {
                            HStack(spacing: 8) {
                                Text(entry.2).font(.system(size: 13, design: .monospaced)).textSelection(.enabled).lineLimit(3)
                                    .padding(10).frame(maxWidth: .infinity, alignment: .leading).background(tokens.fill2, in: RoundedRectangle(cornerRadius: 8))
                                Button(copied == entry.0 ? "Copied" : "Copy") { copySecret(entry.2); copied = entry.0 }.harborButton(.outline, size: .sm)
                            }
                        }
                    }
                    Text("Then try rclone lsd harbor0: to list the folders in My Drive.").font(TypeScale.sm).foregroundStyle(tokens.text2)
                    Button("Done", action: close).harborButton(.primary, size: .lg, block: true)
                }
            }
        }
    }
}

/// Optional email the account can turn off (apps/web/components/email-settings.tsx).
struct EmailCard: View {
    @EnvironmentObject private var workspace: Workspace
    @Environment(\.tokens) private var tokens
    @State private var busy = false
    var body: some View {
        if let user = workspace.account?.user {
            let on = user.emailPreferences?.productUpdates ?? true
            Card(title: "Email", description: "Emails about your account's security, storage and billing are always sent.") {
                Toggle(isOn: Binding(get: { on }, set: { value in save(value) })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Product updates").font(TypeScale.baseMedium)
                        Text("New features and changes to harbor0, a few times a year.").font(TypeScale.xs).foregroundStyle(tokens.text2)
                    }
                }
                .tint(tokens.primary).disabled(busy).accessibilityIdentifier("productUpdates")
            }
        }
    }
    private func save(_ on: Bool) {
        busy = true
        Task {
            defer { busy = false }
            do {
                let _: UserResponse = try await workspace.api.request("/v1/users/me", method: "PATCH", body: [
                    "operationId": UUID().uuidString, "emailPreferences": ["productUpdates": on]
                ])
                await workspace.refreshAccount()
                workspace.notify(on ? "Product updates turned on." : "Product updates turned off.")
            } catch { workspace.fail(error) }
        }
    }
}

/// Photo backup on this phone: set it up or open it, pause or resume it. Sync and folder backups are set up on a computer.
struct SyncBackupsCard: View {
    @EnvironmentObject private var workspace: Workspace
    var body: some View { Inner(photos: workspace.photos) }
    private struct Inner: View {
        @ObservedObject var photos: PhotoBackup
        @EnvironmentObject private var workspace: Workspace
        @EnvironmentObject private var backups: BackupsModel
        @Environment(\.tokens) private var tokens
        var body: some View {
            Card(title: "Photo backup", description: "Back up the photos and videos on this iPhone. To sync or back up folders, use harbor0 on your computer.") {
                VStack(alignment: .leading, spacing: 14) {
                    if let state = photos.state {
                        let paused = state.paused
                        row(.images, "Photos from this iPhone",
                            !workspace.sync.ready ? (workspace.sync.startError != nil ? "Backup starts when harbor0 can reach the server." : workspace.needsUnlock ? "Locked · unlock to back up." : "Starting…")
                                : paused ? "New photos wait until you resume." : "New photos are saved when iOS lets harbor0 run.",
                            badge: paused ? ("Paused", .warning) : nil) {
                            Button("Open") { workspace.navigate(.backups) }.harborButton(.outline, size: .sm)
                        }
                        Button { photos.update { $0.paused.toggle() } } label: { Icon(paused ? .play : .pause, size: 14); Text(paused ? "Resume backup" : "Pause backup") }
                            .harborButton(.outline, size: .sm).accessibilityIdentifier("syncPauseAll")
                    } else {
                        BackUpPhotosButton()
                    }
                    row(.hardDrive, "All backups", "Folders backed up from your computers, and this iPhone’s photos.") {
                        Button("Open") { backups.select(nil); workspace.navigate(.backups) }.harborButton(.outline, size: .sm)
                    }
                }
            }
        }
        private func row<A: View>(_ icon: Lucide, _ title: String, _ detail: String, badge: (String, Tone)? = nil, @ViewBuilder action: () -> A) -> some View {
            HStack(spacing: 12) {
                Icon(icon, size: 18).foregroundStyle(tokens.text2)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 8) { Text(title).font(Font.geist(15, 500)); if let badge { Badge(text: badge.0, tone: badge.1) } }
                    Text(detail).font(TypeScale.xs).foregroundStyle(tokens.text2).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 8)
                action()
            }
        }
    }
}
