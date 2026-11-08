import SwiftUI
import UIKit

// End-to-end encryption on the phone: unlocking (apps/web/components/e2ee-unlock.tsx, Android E2eeScreens.kt)
// and the Settings card (apps/web/components/e2ee-settings.tsx).

/// Copy for features the server refuses on an encrypted account (E2EE_UNSUPPORTED), as its own messages.
enum E2eeUnsupported {
    static let send = "Sending files isn't available with end-to-end encryption yet."
    static let saveReceived = "Saving received files to My Drive isn't available with end-to-end encryption yet."
    static let share = "Sharing folders isn't available with end-to-end encryption yet."
    static let syncShare = "Shared sync isn't available with end-to-end encryption yet."
    static let zip = "Downloading folders as a ZIP from the server isn't available with end-to-end encryption yet."
    static let tokens = "Access tokens can't be used with end-to-end encryption yet."
    static let limits = "With end-to-end encryption, downloading folders as a ZIP, sending files, sharing folders, shared sync, rclone and cloud WebDAV aren’t available for now."
}

// MARK: - Passphrases (apps/web/lib/e2ee-passphrase.ts)

enum Passphrase {
    /// Short, distinct, easy-to-type words: each adds about 8 bits to a suggested passphrase.
    private static let words = """
    able acid acre agent alarm album alley amber anchor angle apple apron arch arena armor arrow atlas attic autumn avenue \
    bacon badge bagel baker bamboo banjo barn basil basket beacon beard berry bicycle biscuit blanket blossom bonfire \
    border bottle bridge broom bucket buffalo bundle butter cabin cactus camel candle canoe canvas canyon carbon carpet \
    cedar cellar cereal chalk cherry chess chimney cinema circus citrus clay cliff clover cobalt cocoa comet copper coral \
    cotton cradle crane crayon cricket crown crystal cumin cushion dagger daisy delta denim desert diesel dinner dolphin \
    domino donkey dragon drum eagle easel echo eclipse elbow ember engine fabric falcon feather fender fern ferry fiddle \
    fig flannel flute forest fossil fountain fox gadget galaxy garden garlic gazebo geyser ginger glacier globe goblet \
    gopher granite gravel guitar hammer harbor harvest hazel helmet hermit honey hornet hotel igloo index iris island \
    ivory jacket jaguar jasmine jelly jigsaw jungle kayak kettle kiwi koala ladder lagoon lantern laptop lemon lilac lily \
    lobster locket lotus lumber magnet mango maple marble meadow melon meteor mint mirror mitten monsoon mosaic muffin \
    nectar needle nickel noodle nutmeg oasis oat ocean olive onion opal orbit orchid otter oven paddle pancake panda paper \
    parrot pebble pepper pickle pigeon pillow pine planet plum pocket pony poppy prism pumpkin puzzle quartz quill rabbit \
    radar raisin ranch raven reef ribbon river robin rocket saddle saffron salmon sandal satin scarf schooner shovel \
    signal silver sketch sled slipper sparrow spice spider sponge spruce squash stable statue summit sunset swan tablet \
    tango teapot thimble thistle thunder tiger timber toast tomato topaz trail trumpet tulip tundra turnip velvet violet \
    walnut wander willow window winter yarn zebra zephyr zinc
    """.split(separator: " ").map(String.init)
    /// Five random words: about 40 bits, stretched further by Argon2id.
    static func suggest() -> String {
        (0..<5).map { _ in
            var index: UInt32 = 0
            _ = SecRandomCopyBytes(kSecRandomDefault, 4, &index)
            return words[Int(index % UInt32(words.count))]
        }.joined(separator: "-")
    }
    /// A rough strength for the meter (level 1–4); the only hard rule is 12 characters.
    static func strength(_ value: String) -> (ok: Bool, level: Int, label: String) {
        let count = value.split(whereSeparator: { " -_.,\t\n".contains($0) }).filter { $0.count >= 3 }.count
        let classes = [value.contains(where: \.isLowercase), value.contains(where: \.isUppercase), value.contains(where: \.isNumber),
                       value.contains { !$0.isLetter && !$0.isNumber && !$0.isWhitespace && $0 != "_" }].filter { $0 }.count
        let ok = value.count >= 12
        let level = !ok ? 1 : (count >= 4 || value.count >= 20) ? 4 : (classes >= 3 || value.count >= 16) ? 3 : 2
        let label = !ok ? "Too short · at least 12 characters" : level == 4 ? (count >= 4 ? "Strong · \(count) words" : "Strong")
            : level == 3 ? "Good" : "Fair · a few random words are stronger"
        return (ok, level, label)
    }
    /// Why a new passphrase can't be used yet, or nil.
    static func problem(_ value: String, _ confirm: String) -> String? {
        if !strength(value).ok { return "Use at least 12 characters." }
        if value != confirm { return "The passphrases don’t match." }
        return nil
    }
}

private func wrongKeyMessage(_ error: Error, recovery: Bool) -> String {
    if (error as? E2eeError)?.code == "WRONG_KEY" {
        return recovery ? "That recovery key isn’t right. Check it and try again." : "That passphrase isn’t right. Check it and try again."
    }
    return error.localizedDescription
}

private func day(_ iso: String?) -> String { iso.map { Format.date($0) } ?? "—" }

/// A passphrase field with its strength meter.
struct PassphraseField: View {
    let label: String
    @Binding var text: String
    var meter = false
    var identifier = "passphrase"
    var submit: (() -> Void)? = nil
    @Environment(\.tokens) private var tokens
    var body: some View {
        Field(label: label) {
            VStack(alignment: .leading, spacing: 6) {
                PasswordInput(text: $text, newPassword: meter, identifier: identifier, submit: submit)
                if meter && !text.isEmpty {
                    let s = Passphrase.strength(text)
                    HStack(spacing: 4) {
                        ForEach(1...4, id: \.self) { i in
                            Capsule().fill(i <= s.level ? (s.level >= 3 ? tokens.successText : s.level == 2 ? tokens.warningText : tokens.dangerText) : tokens.fill3)
                                .frame(height: 4)
                        }
                    }
                    Text(s.label).font(TypeScale.xs).foregroundStyle(tokens.text2)
                }
            }
        }
    }
}

// MARK: - Unlock

/// In place of the file pages while the account is encrypted and this phone doesn't have the key.
struct UnlockScreen: View {
    @EnvironmentObject private var workspace: Workspace
    @Environment(\.tokens) private var tokens
    @State private var recovery = false
    @State private var passphrase = ""
    @State private var key = ""
    @State private var next = ""
    @State private var confirm = ""
    @State private var remember = true
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        PageScroll {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 12) {
                    IconTile(icon: .lock, size: 44, iconSize: 20, radius: 12, active: true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(recovery ? "Use your recovery key" : "Unlock your files").font(TypeScale.title).tracking(-0.6)
                            .accessibilityIdentifier("unlockTitle")
                    }
                }
                Text(recovery ? "Enter the recovery key you saved when you turned on encryption, then choose a new passphrase."
                     : "Signed in as \(workspace.account?.user.email ?? ""). Enter your encryption passphrase to read your files on this iPhone.")
                    .font(TypeScale.sm).foregroundStyle(tokens.text2).fixedSize(horizontal: false, vertical: true)
                if let notice = workspace.unlockNotice { AlertBanner(tone: .warning, text: notice) }
                Card {
                    VStack(alignment: .leading, spacing: 16) {
                        if recovery {
                            Field(label: "Recovery key") {
                                TextInput(placeholder: "XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX", text: $key, large: true, identifier: "recoveryKey")
                                    .font(.system(size: 15, design: .monospaced))
                            }
                            PassphraseField(label: "New passphrase", text: $next, meter: true, identifier: "newPassphrase")
                            PassphraseField(label: "Confirm new passphrase", text: $confirm, identifier: "confirmPassphrase") { submit() }
                            Text("Your files stay as they are. Devices that are already unlocked stay unlocked, and your recovery key keeps working.")
                                .font(TypeScale.xs).foregroundStyle(tokens.text2).fixedSize(horizontal: false, vertical: true)
                        } else {
                            PassphraseField(label: "Encryption passphrase", text: $passphrase, identifier: "unlockPassphrase") { submit() }
                        }
                        Toggle(isOn: $remember) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Remember this iPhone").font(TypeScale.baseMedium)
                                Text("Stay unlocked until you sign out. Leave this off on a shared iPhone.").font(TypeScale.xs).foregroundStyle(tokens.text2)
                            }
                        }.tint(tokens.primary)
                        if let error { AlertBanner(tone: .danger, text: error).accessibilityIdentifier("unlockError") }
                        Button { submit() } label: {
                            if busy { Spinner(size: 16) }
                            Text(busy ? "Unlocking…" : recovery ? "Unlock and save passphrase" : "Unlock")
                        }
                        .harborButton(.primary, size: .lg, block: true)
                        .disabled(busy || !ready)
                        .accessibilityIdentifier("unlock")
                        Text("Your passphrase is checked on this iPhone and never sent to harbor0.")
                            .font(TypeScale.xs).foregroundStyle(tokens.text2)
                    }
                }
                HStack(spacing: 4) {
                    Text(recovery ? "Remembered it?" : "Forgot your passphrase?").foregroundStyle(tokens.text2)
                    Button(recovery ? "Use your passphrase" : "Use recovery key instead") { recovery.toggle(); error = nil }.harborButton(.link)
                }.font(TypeScale.sm)
            }
        }
    }
    private var ready: Bool {
        recovery ? key.filter { $0.isLetter || $0.isNumber }.count == 32 && Passphrase.problem(next, confirm) == nil : !passphrase.isEmpty
    }
    private func submit() {
        guard ready, !busy else { return }
        if recovery, E2ee.parseRecoveryKey(key) == nil { error = "Enter all 8 groups of your recovery key."; return }
        busy = true; error = nil
        Task {
            defer { busy = false }
            do {
                if recovery {
                    try await workspace.vault.unlock(workspace.api, recovery: E2ee.parseRecoveryKey(key)!, newPassphrase: next, remember: remember)
                } else {
                    try await workspace.vault.unlock(workspace.api, passphrase: passphrase, remember: remember)
                }
                passphrase = ""; key = ""; next = ""; confirm = ""
                workspace.unlocked()
            } catch { self.error = wrongKeyMessage(error, recovery: recovery) }
        }
    }
}

/// In place of the file pages while the account is end-to-end encrypted and this version can't open it.
struct EncryptedAccountNotice: View {
    @EnvironmentObject private var workspace: Workspace
    @Environment(\.tokens) private var tokens
    var body: some View {
        PageScroll {
            EmptyStateView(icon: .lock, title: "Update harbor0 to open your files",
                           description: "This account uses end-to-end encryption, which this version of harbor0 for iPhone can’t open. Update the app, or use harbor0 on the web.") {
                Button("Try again") { workspace.api.clearEncryptionBlock(); Task { await workspace.refreshAccount() } }.harborButton(.outline)
            }
        }
    }
}

// MARK: - Settings card

@MainActor
final class EncryptionModel: ObservableObject {
    @Published var state: E2eeState?
    @Published var error: String?
    @Published var notice: String?
    @Published var busy = false
    func load(_ api: HarborAPI) async {
        do {
            let result = try await api.direct("/v1/e2ee")
            guard let value = result["e2ee"], !(value is NSNull) else { state = nil; return }
            state = try JSONDecoder().decode(E2eeState.self, from: JSONSerialization.data(withJSONObject: value))
            error = nil
        } catch is CancellationError {
        } catch { self.error = error.localizedDescription }
    }
    func run(_ api: HarborAPI, done: String? = nil, _ action: () async throws -> Void) async -> Bool {
        busy = true; error = nil; notice = nil
        defer { busy = false }
        do { try await action(); if let done { notice = done }; await load(api); return true }
        catch { self.error = wrongKeyMessage(error, recovery: false); return false }
    }
}

/// Settings › End-to-end encryption: status, this iPhone, existing files, passphrase, recovery key, unlocked devices.
struct EncryptionCard: View {
    @EnvironmentObject private var workspace: Workspace
    @EnvironmentObject private var vault: E2eeVault
    @Environment(\.tokens) private var tokens
    @StateObject private var model = EncryptionModel()
    enum SheetKind: Identifiable { case enroll, passphrase, recovery, lock; var id: Int { hashValue } }
    @State private var sheet: SheetKind?

    var body: some View {
        let encrypted = workspace.account?.encrypted == true
        Card {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 8) {
                    Text("End-to-end encryption").font(TypeScale.lg).tracking(-0.16)
                    if encrypted { Badge(text: model.state?.decryptingSince != nil ? "Turning off" : "On", tone: model.state?.decryptingSince != nil ? .warning : .success, icon: .lock) }
                }
                if let error = model.error { AlertBanner(tone: .danger, text: error) }
                if let notice = model.notice { AlertBanner(tone: .success, text: notice) }
                if encrypted { onBody } else { offBody }
            }
        }
        .task(id: encrypted) { if encrypted { await model.load(workspace.api) } }
        .sheet(item: $sheet) { kind in
            Group {
                switch kind {
                case .enroll: EnrollSheet { done in sheet = nil; if done { model.notice = "End-to-end encryption is on. Open harbor0 on the web to finish encrypting your existing files."; Task { await workspace.refreshAccount(); await model.load(workspace.api) } } }
                case .passphrase: ChangePassphraseSheet(model: model) { sheet = nil }
                case .recovery: NewRecoveryKeySheet(model: model) { sheet = nil }
                case .lock:
                    ConfirmSheet(title: "Lock this iPhone?", description: "It forgets your key and removes its copy. You’ll need your passphrase to open your files here again.",
                                 confirm: "Lock", busy: model.busy) {
                        Task { await vault.lock(workspace.api); sheet = nil; model.notice = "This iPhone is locked."; await model.load(workspace.api) }
                    }
                }
            }.environment(\.tokens, tokens).environmentObject(workspace).environmentObject(vault)
        }
    }

    @ViewBuilder private var offBody: some View {
        Text("Your devices encrypt files and file names before they’re uploaded. Only your devices hold the key, so harbor0 can’t read your files.")
            .font(TypeScale.sm).foregroundStyle(tokens.text2).fixedSize(horizontal: false, vertical: true)
        if workspace.account?.flag("e2ee") == true {
            Button("Turn on end-to-end encryption") { sheet = .enroll }.harborButton(.outline).accessibilityIdentifier("turnOnE2ee")
        } else {
            Text("Not available for your account yet.").font(TypeScale.xs).foregroundStyle(tokens.text3)
        }
    }

    @ViewBuilder private var onBody: some View {
        let info = model.state
        let decrypting = info?.decryptingSince != nil
        Text(decrypting ? "Turning off since \(day(info?.decryptingSince)). harbor0 on the web is decrypting your files; keep it open. New uploads are stored unencrypted."
             : "On since \(day(info?.enabledAt ?? workspace.account?.user.e2ee?.enabledAt)). Only your devices can read your files and file names.")
            .font(TypeScale.sm).foregroundStyle(tokens.text2).fixedSize(horizontal: false, vertical: true)
        row(icon: .smartphone, title: "This iPhone",
            text: vault.locked ? "Locked. Enter your passphrase in My Drive to see your files and back up photos." : vault.remembered ? "Stays unlocked until you sign out or lock it." : "Unlocked for this session · locks when the app closes.",
            badge: vault.locked ? ("Locked", .warning) : ("Unlocked", .success)) {
            if !vault.locked {
                HStack(spacing: 8) {
                    if !vault.remembered {
                        Button("Remember") { Task { _ = await model.run(workspace.api, done: "This iPhone stays unlocked.") { try await vault.rememberPhone(workspace.api) } } }
                            .harborButton(.outline, size: .sm).disabled(model.busy)
                    }
                    Button("Lock") { sheet = .lock }.harborButton(.outline, size: .sm).disabled(model.busy).accessibilityIdentifier("lockPhone")
                }
            }
        }
        if let progress = decrypting ? info?.decryption : info?.migration, progress.totalFiles > 0 {
            let done = progress.complete == true || progress.doneFiles >= progress.totalFiles
            row(icon: .file, title: decrypting ? "Decrypting your files" : "Existing files",
                text: done ? (decrypting ? "All your files are decrypted." : "All your files are encrypted.")
                    : "\(progress.doneFiles) of \(Format.count(progress.totalFiles, "file")) \(decrypting ? "decrypted" : "encrypted"). " + (decrypting ? "Open harbor0 on the web to decrypt your files." : "Open harbor0 on the web or the desktop app to encrypt your existing files."),
                badge: nil) {
                if !done { ProgressBar(value: Double(progress.doneFiles) / Double(max(1, progress.totalFiles)), height: 4) }
            }
        }
        if !decrypting {
            row(icon: .keyRound, title: "Encryption passphrase",
                text: (info?.passphraseChangedAt.map { "Changed \(day($0)). " } ?? "") + "Changing it doesn’t re-encrypt your files.", badge: nil) {
                Button("Change passphrase") { sheet = .passphrase }.harborButton(.outline, size: .sm).disabled(vault.locked)
            }
            row(icon: .shieldCheck, title: "Recovery key", text: "Created \(day(info?.recoveryCreatedAt ?? info?.enabledAt)). A new key replaces the old one.", badge: nil) {
                Button("Create new key") { sheet = .recovery }.harborButton(.outline, size: .sm).disabled(vault.locked)
            }
        }
        VStack(alignment: .leading, spacing: 8) {
            SectionTitle(text: "Unlocked devices")
            Text("These keep a copy of your key. Removed devices need your passphrase again.").font(TypeScale.xs).foregroundStyle(tokens.text2)
            let devices = info?.devices ?? []
            if devices.isEmpty {
                Text("No devices keep a copy of your key.").font(TypeScale.sm).foregroundStyle(tokens.text3)
            }
            ForEach(devices) { device in
                HStack(spacing: 10) {
                    IconTile(icon: ["IOS", "ANDROID"].contains(device.platform ?? "") ? .smartphone : .laptop, size: 32)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(device.name + (device.id == vault.deviceRecord ? " (this iPhone)" : "")).font(TypeScale.smMedium).lineLimit(1)
                        Text(device.remember == false ? "This session only · locks when the app closes" : "Remembered · last used \(day(device.lastUsedAt))")
                            .font(TypeScale.xs).foregroundStyle(tokens.text2)
                    }
                    Spacer()
                    Button("Remove") {
                        Task {
                            _ = await model.run(workspace.api) {
                                if device.id == vault.deviceRecord { await vault.lock(workspace.api) }
                                else { _ = try await workspace.api.direct("/v1/e2ee/devices/\(E2eeTransport.segment(device.id))", method: "DELETE") }
                            }
                        }
                    }.harborButton(.ghost, size: .sm).disabled(model.busy)
                }
            }
        }
        if !decrypting {
            Text("To turn off end-to-end encryption, open Settings in harbor0 on the web. Your files are decrypted first.")
                .font(TypeScale.xs).foregroundStyle(tokens.text3).fixedSize(horizontal: false, vertical: true)
        }
    }

    private func row<Actions: View>(icon: Lucide, title: String, text: String, badge: (String, Tone)?, @ViewBuilder actions: () -> Actions) -> some View {
        HStack(alignment: .top, spacing: 12) {
            IconTile(icon: icon, size: 36, iconSize: 17, radius: 10)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(title).font(TypeScale.baseMedium)
                    if let badge { Badge(text: badge.0, tone: badge.1) }
                }
                Text(text).font(TypeScale.sm).foregroundStyle(tokens.text2).fixedSize(horizontal: false, vertical: true)
                actions()
            }
            Spacer(minLength: 0)
        }
    }
}

/// Shows a recovery key in 8 groups, with Copy.
private struct RecoveryKeyView: View {
    let key: Data
    @Environment(\.tokens) private var tokens
    @State private var copied = false
    var body: some View {
        let text = E2ee.formatRecoveryKey(key)
        VStack(alignment: .leading, spacing: 10) {
            Text(text).font(.system(size: 17, weight: .medium, design: .monospaced)).textSelection(.enabled)
                .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                .background(tokens.fill2, in: RoundedRectangle(cornerRadius: 10))
                .accessibilityIdentifier("recoveryKeyText")
            Button { UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: text]], options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)]); copied = true } label: {
                Icon(.copy, size: 15); Text(copied ? "Copied" : "Copy recovery key")
            }.harborButton(.outline, size: .sm)
        }
    }
}

/// Asks for one group of the recovery key, to make sure it was saved.
private struct GroupCheck: View {
    let key: Data
    let group: Int
    @Binding var typed: String
    var body: some View {
        Field(label: "Group \(group + 1)") {
            TextInput(placeholder: "XXXX", text: $typed, large: true, identifier: "recoveryGroup")
        }
    }
    static func matches(_ key: Data, group: Int, typed: String) -> Bool {
        let groups = E2ee.formatRecoveryKey(key).split(separator: "-")
        let clean = typed.uppercased().filter { !$0.isWhitespace }
            .map { c -> Character in switch c { case "O": "0"; case "I", "L": "1"; case "U": "V"; default: c } }
        return group < groups.count && String(clean) == String(groups[group])
    }
}

private struct ChangePassphraseSheet: View {
    @ObservedObject var model: EncryptionModel
    let close: () -> Void
    @EnvironmentObject private var workspace: Workspace
    @EnvironmentObject private var vault: E2eeVault
    @State private var value = ""
    @State private var confirm = ""
    var body: some View {
        FittedSheet {
            SheetScaffold(title: "Change your encryption passphrase", description: "Your files stay as they are. Devices that are already unlocked stay unlocked.") {
                VStack(alignment: .leading, spacing: 14) {
                    PassphraseField(label: "New passphrase", text: $value, meter: true, identifier: "newPassphrase")
                    PassphraseField(label: "Confirm new passphrase", text: $confirm, identifier: "confirmPassphrase")
                    if let error = model.error { AlertBanner(tone: .danger, text: error) }
                    SheetActions {
                        Button(model.busy ? "Saving…" : "Change passphrase") {
                            Task { if await model.run(workspace.api, done: "Your passphrase is changed. Use the new one on new devices.", { try await vault.changePassphrase(workspace.api, passphrase: value) }) { close() } }
                        }.harborButton(.primary, size: .lg, block: true).disabled(model.busy || Passphrase.problem(value, confirm) != nil)
                        Button("Cancel", action: close).harborButton(.outline, size: .lg, block: true)
                    }
                }
            }
        }
    }
}

private struct NewRecoveryKeySheet: View {
    @ObservedObject var model: EncryptionModel
    let close: () -> Void
    @EnvironmentObject private var workspace: Workspace
    @EnvironmentObject private var vault: E2eeVault
    @State private var key = E2ee.newRecoveryKey()
    @State private var group = Int.random(in: 0..<8)
    @State private var typed = ""
    var body: some View {
        FittedSheet {
            SheetScaffold(title: "Create a new recovery key", description: "Save this key, then enter one group of it. Your old recovery key stops working.") {
                VStack(alignment: .leading, spacing: 14) {
                    RecoveryKeyView(key: key)
                    GroupCheck(key: key, group: group, typed: $typed)
                    if let error = model.error { AlertBanner(tone: .danger, text: error) }
                    SheetActions {
                        Button(model.busy ? "Saving…" : "Replace recovery key") {
                            Task { if await model.run(workspace.api, done: "Your new recovery key is saved. The old one no longer works.", { try await vault.replaceRecovery(workspace.api, recovery: key) }) { close() } }
                        }.harborButton(.primary, size: .lg, block: true).disabled(model.busy || !GroupCheck.matches(key, group: group, typed: typed))
                        Button("Cancel", action: close).harborButton(.outline, size: .lg, block: true)
                    }
                }
            }
        }
    }
}

/// The four-step turn-on wizard (web e2ee-setup), behind the `e2ee` flag.
struct EnrollSheet: View {
    let finish: (Bool) -> Void
    @EnvironmentObject private var workspace: Workspace
    @EnvironmentObject private var vault: E2eeVault
    @Environment(\.tokens) private var tokens
    @State private var step = 1
    @State private var understood = false
    @State private var passphrase = ""
    @State private var confirm = ""
    @State private var key = E2ee.newRecoveryKey()
    @State private var saved = false
    @State private var group = Int.random(in: 0..<8)
    @State private var typed = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(spacing: 0) {
            SheetScaffold(title: titles[step - 1], description: "Step \(step) of 4", divider: true, scrolls: true) {
                VStack(alignment: .leading, spacing: 14) {
                    switch step {
                    case 1:
                        bullet("Keep your keys safe.", "If you lose both your passphrase and your recovery key, nobody can get your files back, including harbor0.")
                        bullet("Search, previews and downloads run on your devices.", E2eeUnsupported.limits)
                        bullet("Folders you’ve shared with others are paused for them.", "Apps that can’t encrypt yet pause syncing and backups until they’re updated. Nothing is uploaded unencrypted meanwhile.")
                        Toggle("I understand harbor0 can’t recover my files without my keys", isOn: $understood).tint(tokens.primary).font(TypeScale.sm)
                    case 2:
                        Text("You’ll enter this when you sign in on a new device or browser. Make it different from your sign-in password.")
                            .font(TypeScale.sm).foregroundStyle(tokens.text2).fixedSize(horizontal: false, vertical: true)
                        PassphraseField(label: "Encryption passphrase", text: $passphrase, meter: true, identifier: "newPassphrase")
                        Button("Suggest a passphrase") { passphrase = Passphrase.suggest(); confirm = "" }.harborButton(.link).font(TypeScale.sm)
                        PassphraseField(label: "Confirm encryption passphrase", text: $confirm, identifier: "confirmPassphrase")
                        Text("Your passphrase never leaves this iPhone, so we can’t reset it by email. Save it in your password manager.")
                            .font(TypeScale.xs).foregroundStyle(tokens.text2).fixedSize(horizontal: false, vertical: true)
                    case 3:
                        Text("If you forget your passphrase, this key is the only way back into your files. It’s shown once.")
                            .font(TypeScale.sm).foregroundStyle(tokens.text2).fixedSize(horizontal: false, vertical: true)
                        RecoveryKeyView(key: key)
                        Text("Keep it somewhere other than this iPhone, such as your password manager or on paper. Anyone with this key and your sign-in can read your files.")
                            .font(TypeScale.xs).foregroundStyle(tokens.text2).fixedSize(horizontal: false, vertical: true)
                        Toggle("I’ve saved my recovery key", isOn: $saved).tint(tokens.primary).font(TypeScale.sm)
                    default:
                        Text("To make sure you saved it, enter group \(group + 1) of your recovery key.").font(TypeScale.sm).foregroundStyle(tokens.text2)
                        GroupCheck(key: key, group: group, typed: $typed)
                        if !typed.isEmpty && typed.count >= 4 && !GroupCheck.matches(key, group: group, typed: typed) {
                            Text("That doesn’t match. Check the key you saved.").font(TypeScale.xs).foregroundStyle(tokens.dangerText)
                        }
                        SectionTitle(text: "What happens next")
                        bullet("New uploads from this iPhone are encrypted straight away.", "Open harbor0 on the web to re-encrypt your existing files in the background. Once a file is encrypted, its unencrypted copy is deleted from our servers. Other devices ask for your passphrase once.")
                    }
                    if let error { AlertBanner(tone: .danger, text: error) }
                }
            }
            SheetActions {
                Button(busy ? "Turning on…" : step == 4 ? "Turn on encryption" : "Continue") { advance() }
                    .harborButton(.primary, size: .lg, block: true).disabled(busy || !canAdvance).accessibilityIdentifier("enrollNext")
                Button(step == 1 ? "Cancel" : "Back") { if step == 1 { finish(false) } else { step -= 1; error = nil } }
                    .harborButton(.outline, size: .lg, block: true).disabled(busy)
            }
            .padding(.horizontal, 20).padding(.bottom, 16)
        }
        .cardSurface(paint: false)
        .modifier(TallSheet(large: true))
        .interactiveDismissDisabled(busy)
    }
    private let titles = ["Before you turn on end-to-end encryption", "Create an encryption passphrase", "Save your recovery key", "Check your recovery key"]
    private var canAdvance: Bool {
        switch step {
        case 1: understood
        case 2: Passphrase.problem(passphrase, confirm) == nil
        case 3: saved
        default: GroupCheck.matches(key, group: group, typed: typed)
        }
    }
    private func advance() {
        guard step == 4 else { step += 1; return }
        busy = true; error = nil
        Task {
            defer { busy = false }
            do {
                try await vault.enroll(workspace.api, passphrase: passphrase, recovery: key)
                finish(true)
            } catch { self.error = error.localizedDescription }
        }
    }
    private func bullet(_ title: String, _ text: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).font(TypeScale.baseMedium)
            Text(text).font(TypeScale.sm).foregroundStyle(tokens.text2).fixedSize(horizontal: false, vertical: true)
        }
    }
}
