package app.harbor0.android

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.Locale

// End-to-end encryption on the phone: unlocking (apps/web/components/e2ee-unlock.tsx, desktop E2eeUnlock) and the
// Settings card (apps/web/components/e2ee-settings.tsx, desktop EncryptionPanel).

/** A device that keeps its own copy of the key (`e2eeDeviceSchema`). */
@Serializable data class E2eeDevice(val id: String, val deviceId: String? = null, val name: String, val platform: String = "",
    val remember: Boolean = true, val createdAt: String = "", val lastUsedAt: String = "")
/** Progress of re-encrypting (or, while turning off, decrypting) the files stored the other way (`e2eeMigrationSchema`). */
@Serializable data class E2eeProgress(val totalFiles: Int = 0, val doneFiles: Int = 0, val totalBytes: Long = 0, val doneBytes: Long = 0,
    val skippedFiles: Int = 0, val complete: Boolean = false, val deviceName: String? = null, val updatedAt: String? = null)
/** `GET /v1/e2ee`, without the wrapped keys. */
@Serializable data class E2eeSummary(val enabled: Boolean = false, val enabledAt: String? = null, val recoveryCreatedAt: String? = null,
    val passphraseChangedAt: String? = null, val devices: List<E2eeDevice> = emptyList(), val migration: E2eeProgress? = null,
    val decryptingSince: String? = null, val decryption: E2eeProgress? = null)

/** Copy for features the server refuses on an encrypted account (E2EE_UNSUPPORTED), as its own messages. */
object E2eeUnsupported {
    const val SEND = "Sending files isn't available with end-to-end encryption yet."
    const val SAVE_RECEIVED = "Saving received files to My Drive isn't available with end-to-end encryption yet."
    const val SHARE = "Sharing folders isn't available with end-to-end encryption yet."
    const val SYNC_SHARE = "Shared sync isn't available with end-to-end encryption yet."
    const val ZIP = "Downloading folders as a ZIP from the server isn't available with end-to-end encryption yet."
    const val TOKENS = "Access tokens can't be used with end-to-end encryption yet."
}

/** What an encrypted account can't do yet, as the web's turn-on wizard says. */
const val E2EE_LIMITS = "With end-to-end encryption, downloading folders as a ZIP, sending files, sharing folders, shared sync, rclone and cloud WebDAV aren’t available for now."

private fun day(iso: String?) = if (iso.isNullOrBlank()) "—" else dateLabel(iso)
private fun count(n: Int) = "%,d".format(Locale.US, n)

/**
 * This phone's part in the account's end-to-end encryption: unlocking, the Settings card's state and its actions.
 * The keys themselves live in the shared [E2eeVault], which background sync uses too.
 */
class EncryptionState(private val m: WorkspaceModel) {
    private val vault get() = m.vault
    private val api get() = m.api.e2eeApi()
    /** The vault as the screens see it. */
    var view by mutableStateOf(E2eeView()); private set
    /** The account is encrypted and this phone doesn't have the key: the file pages ask for the passphrase. */
    val locked get() = view.enabled && view.keys == null
    /** Opening this phone's saved copy at launch or sign-in; the file pages wait for it. */
    var restoring by mutableStateOf(false); private set
    /** This phone was removed from the unlocked devices elsewhere, so its saved copy no longer opens. */
    var removed by mutableStateOf(false); private set
    var info by mutableStateOf(Loadable<E2eeSummary>()); private set
    /** What is being saved: "unlock", "enroll", "passphrase", "recovery", a device id… null when idle. */
    var working by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    /** The Settings dialog open: "setup", "passphrase" or "recovery". */
    var dialog by mutableStateOf<String?>(null)

    init { m.viewModelScope.launch { vault.view.collect { view = it } } }
    fun reset() { vault.reset(); restoring = false; removed = false; info = Loadable(); working = null; error = null; notice = null; dialog = null }

    /** Opens this phone's saved copy once `/v1/users/me` says the account is encrypted. */
    suspend fun restore() {
        if (!vault.locked) return
        restoring = true
        try { removed = vault.restore(api) == E2eeRestore.REMOVED } finally { restoring = false }
    }
    fun load() {
        m.viewModelScope.launch {
            info = info.copy(loading = true, error = null)
            info = try { Loadable(harborJson.decodeFromJsonElement(m.api.request("/v1/e2ee")["e2ee"]!!)) }
                catch (e: CancellationException) { throw e } catch (e: Exception) { info.copy(loading = false, error = m.message(e)) }
        }
    }
    private fun saved(state: JsonObject) { state["e2ee"]?.let { info = Loadable(harborJson.decodeFromJsonElement(it)) } }
    private fun run(key: String, block: suspend () -> Unit) {
        if (working != null) return
        working = key; error = null; notice = null
        m.viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = m.message(e) } finally { working = null }
        }
    }
    /** The phone is unlocked: what the file pages and sync showed while locked is read again with the key. */
    private fun opened() { removed = false; m.unlocked() }

    /** Unlocks with the passphrase; Argon2id takes a moment. */
    fun unlock(passphrase: String, remember: Boolean) = run("unlock") {
        try { vault.unlockWithPassphrase(api, passphrase, remember) } catch (e: E2eeException) {
            if (e.code == "WRONG_KEY") throw IllegalStateException("That passphrase isn’t right. Check it and try again.") else throw e
        }
        opened()
    }
    /** Unlocks with the recovery key and saves a new passphrase, as after a forgotten one. */
    fun unlockWithRecovery(recovery: String, next: String, confirm: String, remember: Boolean) {
        val bytes = E2ee.parseRecoveryKey(recovery)
        error = when {
            bytes == null -> "Enter all 8 groups of your recovery key."
            else -> passphraseProblem(next, confirm)
        }
        if (error != null || bytes == null) return
        run("unlock") {
            try { vault.unlockWithRecovery(api, bytes, next, remember) } catch (e: E2eeException) {
                if (e.code == "WRONG_KEY") throw IllegalStateException("That recovery key isn’t right. Check it and try again.") else throw e
            }
            opened()
        }
    }
    /** Turns encryption on; this phone then keeps its own copy of the new key. */
    fun enroll(passphrase: String, recovery: ByteArray, remember: Boolean, done: () -> Unit) = run("enroll") {
        saved(vault.enroll(api, passphrase, recovery, remember))
        m.loadAccount(); load()
        done()
        notice = "End-to-end encryption is on. Open harbor0 on the web to finish encrypting your existing files."
    }
    fun changePassphrase(value: String, confirm: String, done: () -> Unit) {
        error = passphraseProblem(value, confirm)
        if (error != null) return
        run("passphrase") { saved(vault.changePassphrase(api, value)); done(); notice = "Your passphrase is changed. Use the new one on new devices." }
    }
    fun replaceRecovery(recovery: ByteArray, done: () -> Unit) = run("recovery") {
        saved(vault.replaceRecovery(api, recovery)); done(); notice = "Your new recovery key is saved. The old one no longer works."
    }
    fun remember(device: E2eeDevice) = run(device.id) { vault.rememberPhone(api); load() }
    /** Removes another device's copy, or locks this phone when it is this one. */
    fun remove(device: E2eeDevice) {
        if (device.id == vault.deviceRecord) return lockPhone()
        run(device.id) { saved(m.api.request("/v1/e2ee/devices/${HarborApi.segment(device.id)}", "DELETE")) }
    }
    /** Forgets the key on this phone, as desktop's Lock: photo backups pause until the passphrase is entered again. */
    fun lockPhone() = m.confirm(Confirmation("Lock this phone?",
        "harbor0 forgets the encryption key on this phone. Photo backups pause until you enter your passphrase again. Your files stay as they are.",
        "Lock", "This phone is locked.", danger = false, cancel = "Cancel") {
        vault.lock(api); m.locked(); load()
    })
}

/** Text with a strong lead-in, as the web's `<strong>` openings in alerts. */
@Composable private fun Lead(lead: String, text: String, tone: Tone = Tone.Neutral) = Alert("$lead $text", tone = tone)
/** One line of a bulleted or numbered list. */
@Composable private fun ListLines(lines: List<String>, numbered: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEachIndexed { i, line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (numbered) "${i + 1}." else "•", Modifier.width(16.dp), style = Type.sm, color = theme.text2)
                Text(line, Modifier.weight(1f), style = Type.sm, color = theme.text2)
            }
        }
    }
}
@Composable private fun SectionLabel(text: String) = Text(text, style = Type.xs.copy(fontWeight = FontWeight(600), letterSpacing = .04.em), color = theme.text2)

/** A password field with a show/hide button. */
@Composable private fun SecretField(label: String, value: String, onChange: (String) -> Unit, tag: String, done: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null) {
    var reveal by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = Type.label)
            trailing?.invoke()
        }
        Box(contentAlignment = Alignment.CenterEnd) {
            HInput(value, onChange, Modifier.testTag(tag), visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (done != null) ImeAction.Done else ImeAction.Next, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { done?.invoke() }))
            HIconButton(if (reveal) Lucide.EyeOff else Lucide.Eye, if (reveal) "Hide $label" else "Show $label", { reveal = !reveal })
        }
    }
}
@Composable private fun RememberPhone(remember: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HCheckbox(remember, onChange, "Remember this phone")
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Remember this phone", style = Type.label.copy(fontSize = 14.sp))
            Text("Stay unlocked until you sign out. Leave this off on a shared phone.", style = Type.xs, color = theme.text2)
        }
    }
}

/**
 * Shown in place of the file pages on an encrypted account until this phone has the key: the passphrase, or the
 * recovery key and a new passphrase if it was forgotten.
 */
@Composable fun E2eeUnlockScreen(model: WorkspaceModel) {
    val s = model.encryption
    var recoveryMode by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(true) }
    var recovery by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val busy = s.working == "unlock"
    fun switch(to: Boolean) { recoveryMode = to; s.error = null }
    fun submit() { if (busy) return; if (recoveryMode) s.unlockWithRecovery(recovery, next, confirm, remember) else if (passphrase.isNotEmpty()) s.unlock(passphrase, remember) }
    if (s.restoring) return Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Progress(null, Modifier.width(120.dp), height = 4.dp)
        Text("Unlocking…", Modifier.padding(top = 12.dp), style = Type.sm, color = theme.text2)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp).testTag("e2ee-unlock"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        IconBox(if (recoveryMode) Lucide.KeyRound else Lucide.Lock, 44.dp, active = true)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (recoveryMode) "Use your recovery key" else "Unlock your files", Modifier.semantics { heading() }, style = Type.display.copy(fontSize = 24.sp, letterSpacing = (-.025).em))
            Text(if (recoveryMode) "Enter the recovery key you saved when you turned on encryption, then choose a new passphrase."
                else "Signed in as ${model.user?.email.orEmpty()}. Enter your encryption passphrase to read your files on this phone.", style = Type.sm.copy(fontSize = 14.sp), color = theme.text2)
        }
        if (s.removed && !recoveryMode) Alert("This phone was removed from your unlocked devices, so it needs your passphrase again.", tone = Tone.Warning)
        if (!recoveryMode) {
            SecretField("Encryption passphrase", passphrase, { passphrase = it }, "e2ee-passphrase", ::submit) {
                HButton("Forgot it?", { switch(true) }, variant = Variant.Link)
            }
            RememberPhone(remember) { remember = it }
        } else {
            Field("Recovery key") {
                HInput(recovery, { recovery = it.take(60) }, Modifier.testTag("e2ee-recovery"), placeholder = "XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX",
                    style = Type.mono.copy(fontSize = 14.sp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    visualTransformation = VisualTransformation.None)
            }
            SecretField("New passphrase", next, { next = it }, "e2ee-new-passphrase")
            SecretField("Confirm new passphrase", confirm, { confirm = it }, "e2ee-confirm-passphrase", ::submit)
            RememberPhone(remember) { remember = it }
            Alert("Your files stay as they are. Devices that are already unlocked stay unlocked, and your recovery key keeps working.")
        }
        s.error?.let { Alert(it, tone = Tone.Danger) }
        HButton(when { busy -> "Unlocking…"; recoveryMode -> "Unlock and save passphrase"; else -> "Unlock" }, ::submit, Modifier.fillMaxWidth().height(48.dp).testTag("e2ee-unlock-submit"),
            enabled = !busy && (recoveryMode || passphrase.isNotEmpty()))
        if (!recoveryMode) Text("Your passphrase is checked on this phone and never sent to harbor0.", style = Type.xs, color = theme.text2)
        Divider()
        if (!recoveryMode) HButton("Use recovery key instead", { switch(true) }, variant = Variant.Link)
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Remembered it?", style = Type.sm, color = theme.text2)
            HButton("Use your passphrase", { switch(false) }, variant = Variant.Link)
        }
        HButton("Sign out", model::logout, variant = Variant.Link, enabled = !busy)
    }
}

/** A row of the encryption card: icon tile, title, text and an action, as the web `Row`. */
@Composable private fun E2eeRow(icon: ImageVector, title: String, badge: (@Composable () -> Unit)? = null, action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBox(icon, 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, Modifier.weight(1f, fill = false), style = Type.label.copy(fontSize = 14.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                badge?.invoke()
            }
            content()
        }
        action?.let { Box(Modifier.align(Alignment.CenterVertically)) { it() } }
    }
}
@Composable private fun RowText(text: String) = Text(text, style = Type.sm, color = theme.text2)

/** Opens harbor0 on the web, where encryption is turned off and existing files are converted. */
@Composable private fun OpenWeb(label: String = "Open harbor0 on the web") {
    if (BuildConfig.WEB_URL.isEmpty()) return
    val context = LocalContext.current
    HButton(label, { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, (BuildConfig.WEB_URL + "/settings").toUri())) } },
        variant = Variant.Outline, small = true, icon = Lucide.ExternalLink)
}

/** Settings → End-to-end encryption: turn it on, then manage the passphrase, key and devices. */
@Composable fun EncryptionSettings(model: WorkspaceModel) {
    val user = model.user ?: return
    val s = model.encryption
    val allowed = model.flag("e2ee")
    if (!user.encrypted && !allowed) return
    if (user.encrypted) EncryptionOn(model) else Card(Modifier.testTag("encryption-settings")) {
        BadgedHeader("End-to-end encryption", "Your devices encrypt files and file names before they’re uploaded. Only your devices hold the key, so harbor0 can’t read your files.") { Badge("Off") }
        s.error?.takeIf { s.dialog == null }?.let { Alert(it, Modifier.padding(bottom = 8.dp), Tone.Danger) }
        E2eeRow(Lucide.Smartphone, "Works on this phone") {
            RowText("This phone encrypts new uploads and its own backups, and decrypts files as it downloads them.")
        }
        Divider()
        E2eeRow(Lucide.FolderOpen, "Existing files are included") {
            RowText("Your files are re-encrypted in the background by harbor0 on the web or the desktop app, not by this phone.")
        }
        Divider()
        E2eeRow(Lucide.Lock, "Your keys, your responsibility") {
            RowText("Lose your passphrase and recovery key and nobody can recover your files. You can turn it off later; your files are decrypted first.")
        }
        Row(Modifier.padding(top = 8.dp)) { HButton("Turn on…", { s.error = null; s.dialog = "setup" }, Modifier.testTag("e2ee-turn-on"), small = true) }
    }
    when (s.dialog) {
        "setup" -> E2eeSetupDialog(model)
        "passphrase" -> ChangePassphraseDialog(model)
        "recovery" -> NewRecoveryKeyDialog(model)
    }
}

@Composable private fun EncryptionOn(model: WorkspaceModel) {
    val s = model.encryption
    LaunchedEffect(model.user?.id, s.view.keys != null) { s.load() }
    val info = s.info.data
    val decrypting = info?.decryptingSince != null || model.user?.e2ee?.decryptingSince != null
    Card(Modifier.testTag("encryption-settings")) {
        BadgedHeader("End-to-end encryption", when {
            info == null -> null
            decrypting -> "Turning off since ${day(info.decryptingSince)}. harbor0 on the web is decrypting your files; keep it open. New uploads are stored unencrypted."
            else -> "On since ${day(info.enabledAt)}. Only your devices can read your files and file names."
        }) { if (decrypting) Badge("Turning off", Tone.Warning) else Badge("On", Tone.Success) }
        s.error?.takeIf { s.dialog == null }?.let { Alert(it, Modifier.padding(bottom = 8.dp), Tone.Danger) }
        s.notice?.let { Alert(it, Modifier.padding(bottom = 8.dp), Tone.Success) }
        when {
            info == null && s.info.error != null -> Alert(s.info.error!!, tone = Tone.Danger, action = "Try again") { s.load() }
            info == null -> SkeletonRows()
            else -> {
                E2eeRow(Lucide.LockKeyhole, "This phone", { if (s.locked) Badge("Locked", Tone.Warning) else Badge("Unlocked", Tone.Success) },
                    { if (!s.locked) HButton("Lock", s::lockPhone, Modifier.testTag("e2ee-lock"), Variant.Outline, small = true, icon = Lucide.Lock, enabled = !model.busy) }) {
                    RowText(if (s.locked) "Enter your passphrase in My Drive to see your files and back up photos." else "Stays unlocked until you sign out or lock it.")
                }
                Divider()
                val progress = if (decrypting) info.decryption else info.migration
                E2eeRow(Lucide.FolderOpen, if (decrypting) "Decrypting your files" else "Existing files") {
                    when {
                        // The web or desktop app converts existing files; until one starts, there is no progress to show.
                        progress == null -> RowText(if (decrypting) "Open harbor0 on the web to decrypt your files." else "Open harbor0 on the web or the desktop app to encrypt your existing files.")
                        progress.complete -> RowText(if (decrypting) "All your files are decrypted." else "All your files are encrypted.")
                        else -> {
                            RowText("${count(progress.doneFiles)} of ${count(progress.totalFiles)} ${if (decrypting) "decrypted" else "encrypted"}" +
                                (progress.deviceName?.let { " · last ran on $it" } ?: "") +
                                (if (progress.skippedFiles > 0) " · ${count(progress.skippedFiles)} in backups or only on synced devices wait for those apps" else ""))
                            Progress(if (progress.totalFiles > 0) progress.doneFiles.toFloat() / progress.totalFiles else 1f, height = 4.dp)
                            RowText("Files are ${if (decrypting) "decrypted" else "re-encrypted"} by harbor0 on the web or the desktop app; keep one open to finish. This phone converts its own backups.")
                        }
                    }
                }
                Divider()
                E2eeRow(Lucide.Lock, "Encryption passphrase", action = {
                    HButton("Change", { s.error = null; s.dialog = "passphrase" }, Modifier.testTag("e2ee-change-passphrase"), Variant.Outline, small = true, enabled = !s.locked)
                }) { RowText((info.passphraseChangedAt?.let { "Changed ${day(it)}. " } ?: "") + "Changing it doesn’t re-encrypt your files.") }
                Divider()
                E2eeRow(Lucide.KeyRound, "Recovery key", action = {
                    HButton("Create new key", { s.error = null; s.dialog = "recovery" }, Modifier.testTag("e2ee-new-recovery"), Variant.Outline, small = true, enabled = !s.locked)
                }) { RowText("Created ${day(info.recoveryCreatedAt ?: info.enabledAt)}. A new key replaces the old one.") }
                if (!decrypting) {
                    Divider()
                    E2eeRow(Lucide.LockOpen, "Turn off end-to-end encryption", action = { OpenWeb("Turn off on the web") }) {
                        RowText("harbor0 on the web decrypts every file, then your keys are deleted. Turn it off there.")
                    }
                }
            }
        }
    }
    if (info != null) Card(Modifier.testTag("e2ee-devices")) {
        CardHeader("Unlocked devices", "These keep a copy of your key. Removed devices need your passphrase again.")
        if (info.devices.isEmpty()) RowText("No devices keep a copy of your key.")
        info.devices.forEachIndexed { index, device ->
            if (index > 0) Divider()
            val here = device.id == model.vault.deviceRecord
            E2eeRow(when (device.platform) { "WEB" -> Lucide.Globe; "IOS", "ANDROID" -> Lucide.Smartphone; else -> Lucide.Laptop }, device.name,
                { if (here) Badge("This phone", Tone.Accent) }, {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (here && !device.remember) HButton("Remember", { s.remember(device) }, variant = Variant.Outline, small = true, enabled = s.working == null)
                        HButton(if (s.working == device.id) "Removing…" else if (here) "Lock now" else "Remove", { s.remove(device) },
                            variant = if (here) Variant.Outline else Variant.Danger, small = true, enabled = s.working == null)
                    }
                }) {
                RowText(if (device.remember) "Remembered · last used ${day(device.lastUsedAt)}" else "This session only · locks when the app closes")
            }
        }
    }
}

/** Passphrase and confirmation, with a strength meter and a suggestion. */
@Composable private fun PassphraseFields(value: String, confirm: String, onChange: (String) -> Unit, onConfirm: (String) -> Unit, label: String = "Passphrase") {
    val strength = passphraseStrength(value)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SecretField(label, value, onChange, "e2ee-new-passphrase")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..4).forEach { n ->
                Box(Modifier.weight(1f).height(4.dp).background(if (value.isNotEmpty() && n <= strength.level) when (strength.level) {
                    1 -> theme.palette.destructive; 2 -> theme.warningText; else -> theme.successText } else theme.fill2, RoundedCornerShape(2.dp)))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (value.isNotEmpty()) strength.label else "At least 12 characters", Modifier.weight(1f), style = Type.xs, color = theme.text2)
            HButton("Suggest a passphrase", { val words = suggestPassphrase(); onChange(words); onConfirm(words) }, variant = Variant.Link)
        }
    }
    SecretField("Confirm ${label.lowercase()}", confirm, onConfirm, "e2ee-confirm-passphrase")
}

/** The recovery key in groups, with ways to keep it. */
@Composable private fun RecoveryKeyBox(model: WorkspaceModel, value: String) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().border(1.dp, theme.line, RoundedCornerShape(Radius.lg)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(value.split('-').chunked(4).joinToString("\n") { it.joinToString("  ") }, Modifier.fillMaxWidth().semantics { contentDescription = "Recovery key" }.testTag("e2ee-recovery-key"),
            style = Type.mono.copy(fontSize = 18.sp, letterSpacing = .08.em, lineHeight = 28.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton("Download", { model.saveRecoveryKey(value) }, variant = Variant.Outline, small = true, icon = Lucide.Download)
            HButton(if (copied) "Copied" else "Copy", { copyText(context, "harbor0 recovery key", value, sensitive = true); copied = true },
                variant = Variant.Outline, small = true, icon = if (copied) Lucide.Check else Lucide.Copy)
        }
    }
}
/** Asks for one group of the key back, so a key that wasn't saved is caught now. */
@Composable private fun RecoveryCheck(value: String, group: Int, typed: String, onType: (String) -> Unit) {
    val groups = value.split('-')
    val ok = typed.trim().uppercase() == groups[group]
    Field("Group ${group + 1}") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(groups.indices.joinToString(" ") { if (it == group) "____" else "••••" }, Modifier.weight(1f), style = Type.mono.copy(fontSize = 12.sp), color = theme.text3)
            HInput(typed, { onType(it.take(4)) }, Modifier.width(96.dp).testTag("e2ee-group"), style = Type.mono.copy(fontSize = 16.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
        }
    }
    if (typed.length == 4) Text(if (ok) "Matches" else "That doesn’t match. Check the key you saved.", style = Type.xs, color = if (ok) theme.successText else theme.dangerText)
}

/**
 * Turning encryption on, as the web wizard: what changes, a passphrase, the recovery key, then a check that the key
 * was saved. Existing files are re-encrypted by harbor0 on the web or the desktop app; this phone converts its own backups.
 */
@Composable private fun E2eeSetupDialog(model: WorkspaceModel) {
    val s = model.encryption
    var step by remember { mutableIntStateOf(1) }
    var understood by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val recovery = remember { E2ee.newRecoveryKey() }
    val recoveryText = remember(recovery) { E2ee.formatRecoveryKey(recovery) }
    var saved by remember { mutableStateOf(false) }
    val group = remember { 2 + java.security.SecureRandom().nextInt(6) }
    var typed by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(true) }
    val busy = s.working == "enroll"
    val matches = typed.trim().uppercase() == recoveryText.split('-')[group]
    val close = { if (!busy) { s.dialog = null; s.error = null } }
    val titles = mapOf(1 to ("Before you turn on end-to-end encryption" to "Read this first."),
        2 to ("Create an encryption passphrase" to "You’ll enter this when you sign in on a new device or browser. Make it different from your sign-in password."),
        3 to ("Save your recovery key" to "If you forget your passphrase, this key is the only way back into your files. It’s shown once."),
        4 to ("Check your recovery key" to "To make sure you saved it, enter group ${group + 1} of your recovery key."))
    HDialog(titles.getValue(step).first, close, titles.getValue(step).second, actions = {
        when (step) {
            1 -> HButton("Continue", { step = 2 }, Modifier.fillMaxWidth().testTag("e2ee-continue"), enabled = understood)
            2 -> HButton("Continue", { s.error = passphraseProblem(passphrase, confirm); if (s.error == null) step = 3 }, Modifier.fillMaxWidth().testTag("e2ee-continue"))
            3 -> HButton("Continue", { step = 4 }, Modifier.fillMaxWidth().testTag("e2ee-continue"), enabled = saved)
            else -> HButton(if (busy) "Turning on…" else "Turn on encryption", { s.enroll(passphrase, recovery, remember) { s.dialog = null } },
                Modifier.fillMaxWidth().testTag("e2ee-enroll"), enabled = matches && !busy)
        }
        HButton(if (step == 1) "Cancel" else "Back", { if (step == 1) close() else { s.error = null; step-- } }, Modifier.fillMaxWidth(), Variant.Outline, enabled = !busy)
    }) {
        Text("Step $step of 4", style = Type.xs, color = theme.text2)
        when (step) {
            1 -> {
                Lead("Keep your keys safe.", "If you lose both your passphrase and your recovery key, nobody can get your files back, including harbor0.", Tone.Warning)
                SectionLabel("What changes")
                ListLines(listOf("Search, previews and downloads run on your devices.",
                    "Downloading folders as a ZIP, sending files, sharing folders, shared sync, rclone and cloud WebDAV aren’t available for now.",
                    "Folders you’ve shared with others are paused for them.",
                    "Apps that can’t encrypt yet pause syncing and backups until they’re updated. Nothing is uploaded unencrypted meanwhile."))
                Lead("Your existing files:", "harbor0 on the web or the desktop app re-encrypts them in the background, then the unencrypted copies are deleted. Earlier versions of these files are deleted too. This phone re-encrypts the folders it backs up. Turning it off later decrypts everything the same way.")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HCheckbox(understood, { understood = it }, "I understand harbor0 can’t recover my files without my keys")
                    Text("I understand harbor0 can’t recover my files without my keys", Modifier.weight(1f), style = Type.sm)
                }
            }
            2 -> {
                PassphraseFields(passphrase, confirm, { passphrase = it }, { confirm = it })
                Alert("Your passphrase never leaves this phone, so we can’t reset it by email. Save it in your password manager.")
            }
            3 -> {
                RecoveryKeyBox(model, recoveryText)
                ListLines(listOf("Keep it somewhere other than this phone, such as your password manager or on paper.",
                    "Anyone with this key and your sign-in can read your files."))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HCheckbox(saved, { saved = it }, "I’ve saved my recovery key")
                    Text("I’ve saved my recovery key", Modifier.weight(1f), style = Type.sm)
                }
            }
            else -> {
                RecoveryCheck(recoveryText, group, typed) { typed = it }
                SectionLabel("What happens next")
                ListLines(listOf("New uploads from this phone are encrypted straight away.",
                    "Open harbor0 on the web to re-encrypt your existing files in the background. Keep the tab open to finish sooner.",
                    "Once a file is encrypted, its unencrypted copy is deleted from our servers.",
                    "Other devices ask for your passphrase once."), numbered = true)
                RememberPhone(remember) { remember = it }
            }
        }
        s.error?.let { Alert(it, tone = Tone.Danger) }
    }
}

@Composable private fun ChangePassphraseDialog(model: WorkspaceModel) {
    val s = model.encryption
    var value by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val busy = s.working == "passphrase"
    val close = { if (!busy) { s.dialog = null; s.error = null } }
    HDialog("Change your encryption passphrase", close, "Your files stay as they are. Devices that are already unlocked stay unlocked.", actions = {
        HButton(if (busy) "Changing…" else "Change passphrase", { s.changePassphrase(value, confirm) { s.dialog = null } }, Modifier.fillMaxWidth().testTag("e2ee-save-passphrase"), enabled = !busy)
        HButton("Cancel", close, Modifier.fillMaxWidth(), Variant.Outline, enabled = !busy)
    }) {
        PassphraseFields(value, confirm, { value = it }, { confirm = it }, "New passphrase")
        s.error?.let { Alert(it, tone = Tone.Danger) }
    }
}

@Composable private fun NewRecoveryKeyDialog(model: WorkspaceModel) {
    val s = model.encryption
    val recovery = remember { E2ee.newRecoveryKey() }
    val text = remember(recovery) { E2ee.formatRecoveryKey(recovery) }
    val group = remember { java.security.SecureRandom().nextInt(8) }
    var typed by remember { mutableStateOf("") }
    val busy = s.working == "recovery"
    val matches = typed.trim().uppercase() == text.split('-')[group]
    val close = { if (!busy) { s.dialog = null; s.error = null } }
    HDialog("Create a new recovery key", close, "Save this key, then enter one group of it. Your old recovery key stops working.", actions = {
        HButton(if (busy) "Replacing…" else "Replace recovery key", { s.replaceRecovery(recovery) { s.dialog = null } }, Modifier.fillMaxWidth().testTag("e2ee-replace-recovery"),
            enabled = matches && !busy)
        HButton("Cancel", close, Modifier.fillMaxWidth(), Variant.Outline, enabled = !busy)
    }) {
        RecoveryKeyBox(model, text)
        RecoveryCheck(text, group, typed) { typed = it }
        s.error?.let { Alert(it, tone = Tone.Danger) }
    }
}

/** The explanation shown in place of a feature an encrypted account can't use yet. */
@Composable fun UnsupportedNote(text: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Lucide.Lock, null, Modifier.size(14.dp), tint = theme.text2)
        Text(text, style = Type.xs, color = theme.text2)
    }
}
