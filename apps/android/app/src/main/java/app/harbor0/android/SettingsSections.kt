package app.harbor0.android

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// Settings cards after Your account, in the web order (apps/web/components/app-shell.tsx): encryption, two-step
// verification, rclone access tokens, email, and sync & backups.

/** `GET /v1/users/me/two-factor`. An account uses one method at a time. */
@Serializable data class TwoFactorStatus(val totp: Boolean = false, val email: Boolean = false) {
    val active get() = if (totp) "TOTP" else if (email) "EMAIL" else null
}
@Serializable data class TotpSetup(val secret: String, val uri: String)
/** A personal access token for rclone and similar tools; also listed as a CLI device. The secret is shown once. */
@Serializable data class AccessToken(val id: String, val name: String, val scope: String = "FULL", val createdAt: String = "",
    val expiresAt: String? = null, val lastUsedAt: String? = null)

private const val TWO_FACTOR = "/v1/users/me/two-factor"
private val methodNames = mapOf("TOTP" to "authenticator app", "EMAIL" to "email codes")

/** State of the security cards in Settings. Each card shows its own error and notice, as on the web. */
class SecurityState(private val m: WorkspaceModel) {
    private val api get() = m.api
    var twoFactor by mutableStateOf(Loadable<TwoFactorStatus>()); private set
    var setup by mutableStateOf<TotpSetup?>(null)
    var tokens by mutableStateOf(Loadable<List<AccessToken>>()); private set
    /** A token just created; shown once, then forgotten. */
    var createdToken by mutableStateOf<String?>(null)
    /** The card a message belongs to ("two-factor", "totp", "tokens") and the message. */
    var error by mutableStateOf<Pair<String, String>?>(null)
    var notice by mutableStateOf<Pair<String, String>?>(null)
    /** What is being saved: "totp", "email", "off", "tokens"… null when idle. */
    var working by mutableStateOf<String?>(null); private set

    fun reset() { twoFactor = Loadable(); setup = null; tokens = Loadable(); createdToken = null; error = null; notice = null; working = null }
    /** Loads after the account, whose flags decide which cards show. */
    fun load() {
        m.viewModelScope.launch {
            runCatching { m.loadAccount() }.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            launch { twoFactor = fetch(twoFactor) { api.request(TWO_FACTOR)["twoFactor"]!!.let { harborJson.decodeFromJsonElement(it) } } }
            if (m.flag("rclone")) launch { tokens = fetch(tokens) { api.get<Page<AccessToken>>("/v1/tokens").items } }
            if (m.user?.encrypted == true || m.flag("e2ee")) m.encryption.load()
        }
    }
    private suspend fun <T> fetch(current: Loadable<T>, block: suspend () -> T): Loadable<T> = try { Loadable(block()) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { current.copy(loading = false, error = m.message(e)) }
    private fun run(key: String, card: String, done: String? = null, block: suspend () -> Unit) {
        if (working != null) return
        working = key; error = null; notice = null
        m.viewModelScope.launch {
            try { block(); done?.let { notice = card to it } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { error = card to m.message(e) }
            finally { working = null }
        }
    }
    private fun saved(result: JsonObject) { twoFactor = Loadable(harborJson.decodeFromJsonElement(result["twoFactor"]!!)) }

    fun startTotp() = run("totp", "two-factor") { setup = harborJson.decodeFromJsonElement(api.request("$TWO_FACTOR/totp/setup", "POST")) }
    fun verifyTotp(code: String) = run("verify", "totp") {
        saved(api.request("$TWO_FACTOR/totp/verify", "POST", buildJsonObject { put("code", code) }))
        setup = null
        notice = "two-factor" to "Your authenticator app is set up. You’ll need a code from it when you sign in."
    }
    fun turnOnEmail(done: () -> Unit = {}) {
        val replacing = twoFactor.data?.active == "TOTP"
        run("email", "two-factor", if (replacing) "You now sign in with email codes. Your authenticator app no longer works for harbor0."
            else "Email codes are on. You’ll get one the next time you sign in.") { saved(api.request("$TWO_FACTOR/email", "POST")); done() }
    }
    fun turnOff(method: String, done: () -> Unit = {}) = run("off", "two-factor", "Two-step verification is off.") {
        saved(api.request("$TWO_FACTOR/disable", "POST", buildJsonObject { put("method", method) })); done()
    }

    fun createToken(name: String, scope: String, days: Int?, done: () -> Unit) = run("create", "create-token") {
        val result = api.request("/v1/tokens", "POST", buildJsonObject {
            put("name", name.trim()); put("scope", scope); put("expiresInDays", days)
        })
        val item = harborJson.decodeFromJsonElement<AccessToken>(result["item"]!!)
        tokens = Loadable(listOf(item) + tokens.data.orEmpty())
        createdToken = result["token"]!!.jsonPrimitive.content
        done()
    }
    fun revokeToken(token: AccessToken, done: () -> Unit) = run("revoke", "tokens") {
        api.request("/v1/tokens/${HarborApi.segment(token.id)}", "DELETE")
        tokens = Loadable(tokens.data.orEmpty().filter { it.id != token.id })
        done()
    }
}

/** Copies [text] to the clipboard; secrets are marked sensitive so Android 13+ hides them in the preview. */
fun copyText(context: Context, label: String, text: String, sensitive: Boolean = false) {
    val clip = ClipData.newPlainText(label, text)
    if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/** A card title with a badge after it, as the web cards with a title action. */
@Composable fun BadgedHeader(title: String, description: String?, badge: (@Composable () -> Unit)? = null) {
    Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, Modifier.weight(1f, fill = false).semantics { heading() }, style = Type.h2)
            badge?.invoke()
        }
        description?.let { Text(it, style = Type.sm, color = theme.text2) }
    }
}
/** A method or token row: icon tile, title with an optional badge, text, and an action. */
@Composable fun OptionRow(icon: ImageVector, title: String, text: String, badge: String? = null, badgeTone: Tone = Tone.Success,
    action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBox(icon, 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, Modifier.weight(1f, fill = false), style = Type.label.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                badge?.let { Badge(it, badgeTone) }
            }
            Text(text, style = Type.sm, color = theme.text2)
        }
        action()
    }
}
@Composable private fun CardMessages(s: SecurityState, card: String) {
    s.error?.takeIf { it.first == card }?.let { Alert(it.second, Modifier.padding(bottom = 12.dp), Tone.Danger) }
    s.notice?.takeIf { it.first == card }?.let { Alert(it.second, Modifier.padding(bottom = 12.dp), Tone.Success) }
}

/** Two-step verification (apps/web/components/two-factor-settings.tsx). */
@Composable fun TwoFactorCard(model: WorkspaceModel) {
    val s = model.security
    val enabled = model.flag("two-factor")
    val status = s.twoFactor
    val on = status.data ?: TwoFactorStatus()
    val active = on.active
    // Turning a method on is still being rolled out; anyone who already has one keeps the card to turn it off.
    if (!enabled && active == null) return
    val email = model.user?.email.orEmpty()
    var confirming by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    Card(Modifier.testTag("two-factor-settings")) {
        BadgedHeader("Two-step verification",
            "Ask for a code as well as your password when you sign in, so a leaked password isn’t enough to get into your files. Choose one method.") {
            if (active != null) Badge("On", Tone.Success)
        }
        CardMessages(s, "two-factor")
        when {
            status.data == null && status.error == null -> SkeletonRows()
            status.data == null -> Alert(status.error!!, tone = Tone.Danger)
            else -> listOf(
                Triple("TOTP", Lucide.Smartphone, "Authenticator app" to "A 6-digit code from 1Password, Google Authenticator, Authy or a similar app."),
                Triple("EMAIL", Lucide.Inbox, "Email codes" to "We email a code to $email each time you sign in."),
            ).forEachIndexed { index, (method, icon, copy) ->
                if (index > 0) Divider()
                val isOn = if (method == "TOTP") on.totp else on.email
                OptionRow(icon, copy.first, copy.second, if (isOn) "In use" else null) {
                    if (isOn) HButton("Turn off", { confirming = method to false }, variant = Variant.Outline, small = true, enabled = s.working == null)
                    else if (enabled) HButton(when { s.working == (if (method == "TOTP") "totp" else "email") -> "Working…"; active != null -> "Switch to this"; method == "TOTP" -> "Set up"; else -> "Turn on" }, {
                        when { method == "TOTP" -> s.startTotp(); active != null -> confirming = method to true; else -> s.turnOnEmail() }
                    }, variant = if (active != null) Variant.Outline else Variant.Primary, small = true, enabled = s.working == null)
                }
            }
        }
    }
    confirming?.let { (method, turnOn) ->
        val close = { if (s.working == null) confirming = null }
        HDialog(if (turnOn) "Switch to ${methodNames[method]}?" else "Turn off two-step verification?", close,
            if (turnOn) "Your ${methodNames[if (method == "TOTP") "EMAIL" else "TOTP"]} will stop working for harbor0. You can only use one method at a time."
            else "Signing in will only need your password again.", actions = {
                HButton(if (s.working != null) "Saving…" else if (turnOn) "Switch to ${methodNames[method]}" else "Turn off", {
                    if (turnOn) s.turnOnEmail { confirming = null } else s.turnOff(method) { confirming = null }
                }, Modifier.fillMaxWidth(), if (turnOn) Variant.Primary else Variant.Danger, enabled = s.working == null)
                HButton("Cancel", close, Modifier.fillMaxWidth(), Variant.Outline, enabled = s.working == null)
            }) { s.error?.takeIf { it.first == "two-factor" }?.let { Alert(it.second, tone = Tone.Danger) } }
    }
    s.setup?.let { TotpSetupSheet(s, it, replacing = on.email) }
}

/** Scan or enter the secret, then confirm with the first code. Email codes stay on until the app is set up. */
@Composable private fun TotpSetupSheet(s: SecurityState, setup: TotpSetup, replacing: Boolean) {
    val context = LocalContext.current
    var code by remember(setup) { mutableStateOf("") }
    var noApp by remember(setup) { mutableStateOf(false) }
    val close = { if (s.working == null) { s.setup = null; if (s.error?.first == "totp") s.error = null } }
    HDialog("Set up an authenticator app", close,
        "Scan the QR code with your authenticator app, then enter the 6-digit code it shows.${if (replacing) " Email codes stay on until the app is set up, then stop." else ""}",
        actions = {
            HButton(if (s.working == "verify") "Checking…" else "Turn on", { s.verifyTotp(code); code = "" }, Modifier.fillMaxWidth().testTag("totp-submit"),
                enabled = s.working == null && code.length == 6)
            HButton("Cancel", close, Modifier.fillMaxWidth(), Variant.Outline, enabled = s.working == null)
        }) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(setup.uri, Modifier.size(196.dp)) }
        // On a phone the authenticator is usually on the same device, so offer the otpauth link too.
        HButton("Open in authenticator app", {
            noApp = try { context.startActivity(Intent(Intent.ACTION_VIEW, setup.uri.toUri())); false } catch (_: ActivityNotFoundException) { true }
        }, Modifier.fillMaxWidth(), Variant.Outline, icon = Lucide.ExternalLink)
        if (noApp) Alert("No app on this phone opens authenticator setup links. Enter the key below in your authenticator app instead.", tone = Tone.Warning)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Can’t scan it? Enter this key", style = Type.label)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(setup.secret.chunked(4).joinToString(" "), Modifier.weight(1f).background(theme.fill1, RoundedCornerShape(Radius.md)).padding(10.dp),
                    style = Type.mono.copy(fontSize = 14.sp))
                HButton("Copy", { copyText(context, "harbor0 key", setup.secret, sensitive = true) }, variant = Variant.Outline, small = true)
            }
        }
        CardMessages(s, "totp")
        Field("6-digit code") {
            HInput(code, { code = it.filter(Char::isDigit).take(6) }, Modifier.testTag("totp-code"), placeholder = "000000", style = Type.mono.copy(fontSize = 18.sp, letterSpacing = .2.em),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        }
    }
}

/** A QR code drawn dark on white with a quiet zone, whatever the theme, so every scanner reads it. */
@Composable fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        runCatching { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)) }.getOrNull()
    }
    if (matrix == null) { Box(modifier.background(theme.fill2, RoundedCornerShape(Radius.md))); return }
    Canvas(modifier.background(Color.White, RoundedCornerShape(Radius.md)).semantics { contentDescription = "QR code for your authenticator app" }) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            // Slight overlap avoids hairline gaps between modules.
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + .5f, cell + .5f))
        }
    }
}

private val expiries = listOf("30" to "30 days", "90" to "90 days", "365" to "1 year", "never" to "Never")
private fun setupCommand(token: String) = "rclone config create harbor0 harbor0 token=$token"

/** Personal access tokens for rclone (apps/web/components/access-token-settings.tsx), behind the `rclone` flag. */
@Composable fun AccessTokensCard(model: WorkspaceModel) {
    if (!model.flag("rclone")) return
    val s = model.security
    val tokens = s.tokens
    var creating by remember { mutableStateOf(false) }
    var revoking by remember { mutableStateOf<AccessToken?>(null) }
    Card(Modifier.testTag("token-settings")) {
        CardHeader("Use with rclone", "rclone copies, syncs and mounts files from the command line, on Linux, NAS boxes and servers. Give it an access token so it can use My Drive without your password.")
        CardMessages(s, "tokens")
        val list = tokens.data
        when {
            list == null && tokens.error == null -> SkeletonRows()
            list == null -> Alert(tokens.error!!, tone = Tone.Danger)
            list.isEmpty() -> Text("No access tokens yet. Create one, then follow the rclone install guide at rclone.org/install.", style = Type.sm, color = theme.text2)
            else -> list.forEachIndexed { index, token ->
                if (index > 0) Divider()
                OptionRow(Lucide.Monitor, token.name, "${token.lastUsedAt?.let { "Last used ${dateLabel(it)}" } ?: "Not used yet"} · ${token.expiresAt?.let { "Expires ${dateLabel(it)}" } ?: "Never expires"}",
                    if (token.scope == "READ") "Read-only" else null, Tone.Neutral) {
                    HButton("Revoke", { s.error = null; revoking = token }, variant = Variant.Outline, small = true)
                }
            }
        }
        // rclone can't encrypt, so it would read ciphertext and write plaintext.
        if (model.user?.encrypted == true) Alert(E2eeUnsupported.TOKENS, Modifier.padding(top = 12.dp))
        else Row(Modifier.padding(top = 12.dp)) { HButton("Create token", { s.error = null; creating = true }, small = true, enabled = s.working == null) }
    }
    if (creating) CreateTokenSheet(s) { creating = false }
    s.createdToken?.let { TokenCreatedSheet(it) { s.createdToken = null } }
    revoking?.let { token ->
        val close = { if (s.working == null) revoking = null }
        HDialog("Revoke “${token.name}”?", close, "Anything using this token stops working at once. This can’t be undone.", actions = {
            HButton(if (s.working == "revoke") "Revoking…" else "Revoke", { s.revokeToken(token) { revoking = null; model.loadDevices() } }, Modifier.fillMaxWidth(), Variant.Danger, enabled = s.working == null)
            HButton("Cancel", close, Modifier.fillMaxWidth(), Variant.Outline, enabled = s.working == null)
        }) { CardMessages(s, "tokens") }
    }
}
@Composable private fun CreateTokenSheet(s: SecurityState, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf("FULL") }
    var expiry by remember { mutableStateOf("365") }
    val close = { if (s.working == null) { onClose(); if (s.error?.first == "create-token") s.error = null } }
    HDialog("Create an access token", close, "The token can use your files in My Drive, but not your account settings, sharing or devices.", actions = {
        HButton(if (s.working == "create") "Creating…" else "Create token", { s.createToken(name, scope, expiry.toIntOrNull(), onClose) }, Modifier.fillMaxWidth(),
            enabled = s.working == null && name.isNotBlank())
        HButton("Cancel", close, Modifier.fillMaxWidth(), Variant.Outline, enabled = s.working == null)
    }) {
        CardMessages(s, "create-token")
        Field("Name") { HInput(name, { name = it.take(60) }, Modifier.testTag("token-name"), placeholder = "e.g. Home NAS") }
        Field("Access") { Segmented(listOf("FULL" to "Read and write", "READ" to "Read-only"), scope, { scope = it }) }
        Field("Expires") { Segmented(expiries, expiry, { expiry = it }) }
    }
}
@Composable private fun TokenCreatedSheet(token: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf<String?>(null) }
    HDialog("Copy your token now", onClose, "This is the only time harbor0 shows it. If you lose it, revoke it and create another.", actions = {
        HButton("Done", onClose, Modifier.fillMaxWidth())
    }) {
        listOf("token" to ("Token" to token), "command" to ("Set up rclone with it" to setupCommand(token))).forEach { (key, value) ->
            Field(value.first) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(value.second, Modifier.weight(1f).background(theme.fill1, RoundedCornerShape(Radius.md)).padding(10.dp), style = Type.mono.copy(fontSize = 13.sp))
                    HButton(if (copied == key) "Copied" else "Copy", { copyText(context, "harbor0 token", value.second, sensitive = true); copied = key },
                        variant = Variant.Outline, small = true, icon = if (copied == key) Lucide.Check else null)
                }
            }
        }
        Text("Then try rclone lsd harbor0: to list the folders in My Drive.", style = Type.sm, color = theme.text2)
    }
}

/** Optional email the account can turn off (apps/web/components/email-settings.tsx). */
@Composable fun EmailCard(model: WorkspaceModel) {
    val user = model.user ?: return
    val on = user.emailPreferences.productUpdates
    Card(Modifier.testTag("email-settings")) {
        CardHeader("Email", "Emails about your account's security, storage and billing are always sent.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HCheckbox(on, { model.setProductUpdates(it) }, "Product updates", enabled = !model.busy)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Product updates", style = Type.label.copy(fontSize = 14.sp))
                Text("New features and changes to harbor0, a few times a year.", style = Type.xs, color = theme.text2)
            }
        }
    }
}

/** Photo backup on this phone: set it up or open it, pause or resume it. Sync and folder backups are set up on a computer. */
@Composable fun SyncBackupsCard(model: WorkspaceModel) {
    val sync = model.sync
    val paused = sync.runtime.paused
    val photos = sync.photoRoot
    val phone = model.devices.data.orEmpty().firstOrNull { sync.isThisPhone(it) }
    Card(Modifier.testTag("sync-settings")) {
        CardHeader("Photo backup", "Back up the photos and videos on this phone. To sync or back up folders, use harbor0 on your computer.")
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (photos?.remoteId != null) {
                SettingRow(Lucide.Images, "Photos from this phone", when {
                    sync.view == null -> sync.startError?.let { "Backup starts when harbor0 can reach the server." } ?: "Starting…"
                    paused -> "New photos wait until you resume."
                    else -> "New photos are saved within a few minutes."
                }, badge = if (paused) ({ Badge("Paused", Tone.Warning) }) else null) {
                    HButton("Open", { model.navigate(Section.Drive); model.drive.openBackupFolder(photos.remoteId, photos.backupId, photos.localName) }, variant = Variant.Outline, small = true)
                }
                HButton(if (paused) "Resume backup" else "Pause backup", { sync.pauseAll(!paused) }, Modifier.testTag("sync-pause-all"), Variant.Outline, small = true,
                    icon = if (paused) Lucide.Play else Lucide.Pause, enabled = sync.view != null && !model.busy)
                SyncAttention(model)
                BackgroundRestrictedAlert(model)
            } else BackUpPhotosButton(model)
            SettingRow(Place.Backups.icon, "All backups", "Folders backed up from your computers, and this phone’s photos.") {
                HButton("Open", { model.navigate(Section.Drive); if (phone != null && photos != null) model.drive.openPlace(Place.Backups, phone.id, phone.name) else model.drive.openPlace(Place.Backups) },
                    variant = Variant.Outline, small = true)
            }
        }
    }
}

@Composable private fun SettingRow(icon: ImageVector, title: String, detail: String, badge: (@Composable () -> Unit)? = null, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = theme.text2)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = Type.label.copy(fontSize = 15.sp))
                badge?.invoke()
            }
            Text(detail, style = Type.xs, color = theme.text2)
        }
        action()
    }
}

/** In place of the file pages while the account is end-to-end encrypted and this version can't open it (desktop EncryptedAccountNotice). */
@Composable fun EncryptedAccountNotice(model: WorkspaceModel) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().testTag("encrypted-notice"), verticalArrangement = Arrangement.Center) {
        EmptyState(Lucide.ShieldCheck, "Update harbor0 to open your encrypted files",
            "Your account uses end-to-end encryption, which this version of the app can’t open. Sync and backups are paused: nothing was uploaded unencrypted and the files on this phone are untouched.") {
            if (BuildConfig.WEB_URL.isNotEmpty()) HButton("Open harbor0 on the web", {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, BuildConfig.WEB_URL.toUri())) }
            }, icon = Lucide.ExternalLink)
            HButton("Check again", model::retryEncrypted, variant = Variant.Outline)
        }
    }
}
