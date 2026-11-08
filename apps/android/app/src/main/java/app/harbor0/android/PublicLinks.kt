package app.harbor0.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.time.Instant

// Public links (flag `public-links`): anyone with the link can view and download a file, or a folder's
// files, on harbor0content.online without an account. As apps/web/components/public-link-dialog.tsx
// and public-links-list.tsx. An end-to-end encrypted account's links are encrypted too (E2eeLinks.kt): the key
// travels in the link's fragment, and this phone wraps what each link shares for it.

private const val DAY_MS = 86_400_000L
/** “Expires in 6 days · Oct 22, 2026”: how long a link has left. */
fun linkEnds(expiresAt: String?, now: Long = System.currentTimeMillis()): String {
    if (expiresAt == null) return "Never expires"
    val at = runCatching { Instant.parse(expiresAt).toEpochMilli() }.getOrNull() ?: return "Expires ${dateLabel(expiresAt)}"
    val diff = at - now
    // Rounded up, as Math.ceil on the web.
    val days = if (diff > 0) (diff + DAY_MS - 1) / DAY_MS else -(-diff / DAY_MS)
    val whenText = if (days <= 0) "today" else if (days == 1L) "tomorrow" else "in $days days"
    return "Expires $whenText · ${dateLabel(expiresAt)}"
}
/** The address without its scheme, for showing; copying always takes the full URL. */
fun shownLink(url: String) = url.replace(Regex("^https?://"), "")
/** A link that stops working within two days, shown in the warning tone. */
fun expiresSoon(link: PublicLink, now: Long = System.currentTimeMillis()) =
    link.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() - now < 2 * DAY_MS }.getOrDefault(false) } == true

/** Shared → Links and the “Share with a link” sheet. */
class PublicLinksState(private val m: WorkspaceModel) {
    private val api get() = m.api
    val enabled get() = m.account?.flag("public-links") == true
    /** Every link that works now, newest first. */
    var list by mutableStateOf(Loadable<List<PublicLink>>()); private set
    // The open sheet.
    var allowance by mutableStateOf<LinkAllowance?>(null); private set
    var links by mutableStateOf<List<PublicLink>?>(null); private set
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set
    /** The link made in this sheet, which is copied at once. */
    var created by mutableStateOf<String?>(null); private set
    /** How wrapping the open link's items for it is going, and why it stopped, if it did. */
    var wrapping by mutableStateOf<WrapProgress?>(null); private set
    var wrapError by mutableStateOf<String?>(null); private set
    /** Wraps what encrypted links share, through the vault's transport so names arrive decrypted. */
    val wraps = LinkWraps(E2eeApi { path, method, body -> api.request(path, method, body) })
    private var job: Job? = null
    private var wrapJob: Job? = null
    /** New links are encrypted while the account is: unlocked here and not being turned off. */
    val encrypted get() = m.vault.current().let { it.enabled && !it.decrypting }

    fun reset() { job?.cancel(); list = Loadable(); close() }
    fun refresh() {
        if (!enabled) return
        list = list.copy(loading = true, error = null)
        m.viewModelScope.launch {
            list = try { Loadable(api.all<PublicLink>("/v1/links").sortedByDescending { it.createdAt }) }
                catch (e: CancellationException) { throw e } catch (e: Exception) { list.copy(loading = false, error = m.message(e)) }
        }
    }
    fun open(item: DriveItem) {
        close()
        m.error = null; m.overlay = Overlay.PublicLink(item)
        job = m.viewModelScope.launch {
            try {
                val allowed = api.get<LinkAllowance>("/v1/links/allowance")
                val listed = api.get<PublicLinks>(withQuery("/v1/links", "driveItemId" to item.id)).items
                allowance = allowed; links = listed
                listed.firstOrNull()?.let(::wrapFor)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = m.message(e) }
        }
    }
    fun close() { job?.cancel(); wrapJob?.cancel(); allowance = null; links = null; error = null; busy = false; created = null; wrapping = null; wrapError = null }
    /** An encrypted link shares what's wrapped for it: wrap anything new or changed now. */
    private fun wrapFor(link: PublicLink) {
        wrapJob?.cancel(); wrapping = null; wrapError = null
        if (link.e2ee == null) return
        val keys = m.vault.current().keys ?: return
        wrapJob = m.viewModelScope.launch {
            try { wrapping = wraps.sync(keys, link) { p -> m.viewModelScope.launch { wrapping = p } } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { wrapError = m.message(e) }
        }
    }
    /**
     * The address to share: an encrypted link's carries its secret, which needs this phone unlocked. Null while locked,
     * or if the secret can't be read.
     */
    fun shareable(link: PublicLink): String? {
        val e2ee = link.e2ee ?: return link.url
        val keys = m.vault.current().keys ?: return null
        return runCatching { E2eeLinks.url(link.url, E2eeLinks.unwrapSecret(keys, e2ee.secretWrap)) }.getOrNull()
    }
    fun dismiss() { close(); m.overlay = null; refresh(); m.drive.refreshLinks() }
    /** Runs a change, showing its error in the sheet. */
    private fun run(block: suspend () -> Unit) {
        if (busy) return
        m.viewModelScope.launch {
            busy = true; error = null
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = m.message(e) } finally { busy = false }
        }
    }
    fun create(item: DriveItem, expiresInDays: Int?, password: String?, context: Context) = run {
        // An encrypted account's link gets a random secret, kept by the server only wrapped by the account's key.
        val secretWrap = if (encrypted) m.vault.requireKeys().let { keys -> withContext(Dispatchers.Default) { E2eeLinks.wrapSecret(keys, E2eeLinks.newSecret()) } } else null
        val link = harborJson.decodeFromJsonElement<PublicLinkResponse>(api.request("/v1/links", "POST", buildJsonObject {
            put("driveItemId", item.id); put("expiresInDays", expiresInDays); put("password", password)
            secretWrap?.let { put("e2ee", buildJsonObject { put("secretWrap", it) }) }
        })).link
        links = listOf(link) + links.orEmpty()
        allowance = allowance?.let { it.copy(active = it.active + 1) }
        created = link.id
        // A link just made is copied at once, so sharing it is one paste away.
        copy(context, link)
        m.drive.refreshLinks()
        wrapFor(link)
    }
    /** Changes the expiry ([days] null: never) or the password ([password] null: removed). */
    fun update(link: PublicLink, message: String, change: JsonObjectBuilder.() -> Unit) = run {
        val changed = harborJson.decodeFromJsonElement<PublicLinkResponse>(api.request("/v1/links/${HarborApi.segment(link.id)}", "PATCH", buildJsonObject(change))).link
        links = links?.map { if (it.id == changed.id) changed else it }
        m.toast = message
    }
    fun revoke(link: PublicLink) = run {
        api.request("/v1/links/${HarborApi.segment(link.id)}", "DELETE")
        links = links?.filter { it.id != link.id }
        allowance = allowance?.let { it.copy(active = it.active - 1) }
        m.drive.refreshLinks()
    }
    fun copy(context: Context, link: PublicLink) {
        val url = shareable(link)
        if (url == null) { m.error = LOCKED_LINK; return }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Public link", url))
        m.toast = "Link copied."
    }
    fun share(context: Context, link: PublicLink) {
        val url = shareable(link)
        if (url == null) { m.error = LOCKED_LINK; return }
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        try { context.startActivity(Intent.createChooser(intent, "Share link")) } catch (_: android.content.ActivityNotFoundException) { m.error = "No app can share this link. Copy it instead." }
    }
}

private const val LOCKED_LINK = "Unlock your files to see this link."
private val expiries = listOf("1" to "1 day", "7" to "7 days", "30" to "30 days")
/** [shown] labels the chip instead of the chosen option: an existing link shows its end date. */
@Composable private fun ExpiryChoice(value: String, neverExpires: Boolean, onChange: (String) -> Unit, fromNow: Boolean = false, enabled: Boolean = true, shown: String? = null) {
    var open by remember { mutableStateOf(false) }
    val options = expiries.map { (k, v) -> k to if (fromNow) "$v from now" else v } + if (neverExpires) listOf("never" to "Never") else emptyList()
    Box {
        FilterChip(shown ?: options.firstOrNull { it.first == value }?.second ?: "Change…", { if (enabled) open = true })
        HMenu(open, { open = false }) {
            options.forEach { (key, label) -> HMenuItem(label, onClick = { open = false; onChange(key) }, checked = key == value) }
        }
    }
}

/** The “Share with a link” sheet: make a link, or copy, share, change or turn off the one there is. */
@Composable fun PublicLinkSheet(model: WorkspaceModel, item: DriveItem) {
    val state = model.shared.links
    val context = LocalContext.current
    val allowance = state.allowance
    val links = state.links
    val link = links?.firstOrNull()
    HSheet(state::dismiss, "Share with a link", closeLabel = "Close") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FileTile(item)
            Text(item.name, Modifier.weight(1f), style = Type.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        state.error?.let { Alert(it, tone = Tone.Danger) }
        when {
            allowance == null || links == null -> if (state.error == null) { Skeleton(Modifier.fillMaxWidth().height(40.dp)); Skeleton(Modifier.fillMaxWidth(.6f).height(16.dp)) }
            link != null -> LinkPanel(model, link, links.drop(1), allowance)
            allowance.blockedReason != null -> { Notice("Public links aren’t available yet", allowance.blockedReason); Done(state) }
            allowance.active >= allowance.limit -> {
                Notice("You’ve reached your link limit", "You can have ${allowance.limit} public links at once. Turn one off in Shared → Links to make another.")
                Done(state)
            }
            else -> CreatePanel(model, item, allowance, context)
        }
    }
}
@Composable private fun Notice(title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Lucide.Lock, null, Modifier.padding(top = 2.dp).size(18.dp), tint = theme.text2)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { Text(title, style = Type.h3); Text(text, style = Type.sm, color = theme.text2) }
    }
}
@Composable private fun Done(state: PublicLinksState) = HButton("Done", state::dismiss, Modifier.fillMaxWidth(), Variant.Outline)

@Composable private fun CreatePanel(model: WorkspaceModel, item: DriveItem, allowance: LinkAllowance, context: Context) {
    val state = model.shared.links
    var expiry by remember { mutableStateOf("7") }
    var protect by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    SettingRow("Expires") { ExpiryChoice(expiry, allowance.neverExpires, { expiry = it }) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        HCheckbox(protect, { protect = it }, "Require a password", Modifier.testTag("link-password-toggle"))
        Text("Require a password", style = Type.label)
    }
    if (protect) HInput(password, { password = it.take(128) }, Modifier.testTag("link-password"), placeholder = "At least 4 characters",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), visualTransformation = PasswordVisualTransformation())
    HButton(if (state.busy) "Working…" else "Create link", { state.create(item, if (expiry == "never") null else expiry.toInt(), if (protect) password else null, context) },
        Modifier.fillMaxWidth().testTag("link-create"), icon = Lucide.Link2, enabled = !state.busy && (!protect || password.length >= 4))
}

@Composable private fun SettingRow(label: String, trailing: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = Type.label)
        trailing()
    }

@Composable private fun LinkPanel(model: WorkspaceModel, link: PublicLink, others: List<PublicLink>, allowance: LinkAllowance) {
    val state = model.shared.links
    val context = LocalContext.current
    var editing by remember(link.id) { mutableStateOf(false) }
    var password by remember(link.id) { mutableStateOf("") }
    var confirming by remember(link.id) { mutableStateOf(false) }
    val url = remember(link, model.encryption.view.keys) { state.shareable(link) }
    Text(url?.let(::shownLink) ?: LOCKED_LINK, Modifier.fillMaxWidth().background(theme.fill2, RoundedCornerShape(Radius.md))
        .padding(horizontal = 12.dp, vertical = 10.dp).testTag("link-url"), style = Type.mono, color = if (url == null) theme.text2 else theme.text,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HButton("Copy link", { state.copy(context, link) }, Modifier.weight(1f), icon = Lucide.Copy, enabled = url != null)
        HButton("Share", { state.share(context, link) }, Modifier.weight(1f), Variant.Outline, icon = Lucide.Share2, enabled = url != null)
    }
    if (link.e2ee != null) {
        val wraps = state.wrapping
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Lock, null, Modifier.size(12.dp), tint = theme.text2)
            Text(when {
                state.wrapError != null -> "Some items aren’t shared yet. harbor0 retries while you’re signed in."
                wraps?.done != true -> "Encrypting… ${wraps?.scanned ?: 0} checked"
                wraps.waiting > 0 -> "Encrypted · ${wraps.waiting} ${if (wraps.waiting == 1) "file" else "files"} still uploading"
                else -> "End-to-end encrypted"
            }, Modifier.testTag("link-e2ee"), style = Type.xs, color = theme.text2)
        }
    }
    Divider()
    SettingRow("Expires") {
        ExpiryChoice("", allowance.neverExpires, { value ->
            state.update(link, "Expiry updated.") { if (value == "never") put("expiresInDays", JsonNull) else put("expiresInDays", value.toInt()) }
        }, fromNow = true, enabled = !state.busy, shown = link.expiresAt?.let { dateLabel(it) } ?: "Never")
    }
    SettingRow("Password") {
        if (!editing) {
            if (link.hasPassword) HButton("Remove", { state.update(link, "Password removed.") { put("password", JsonNull) } }, variant = Variant.Ghost, small = true, enabled = !state.busy)
            HButton(if (link.hasPassword) "Change" else "Add", { editing = true }, variant = Variant.Outline, small = true, enabled = !state.busy)
        }
    }
    if (editing) {
        HInput(password, { password = it.take(128) }, Modifier.testTag("link-new-password"), placeholder = "At least 4 characters",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), visualTransformation = PasswordVisualTransformation())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton("Cancel", { editing = false; password = "" }, Modifier.weight(1f), Variant.Ghost, small = true)
            HButton("Save", {
                state.update(link, "Password saved.") { put("password", password) }
                editing = false; password = ""
            }, Modifier.weight(1f), small = true, enabled = !state.busy && password.length >= 4)
        }
    }
    if (others.isNotEmpty()) {
        Divider()
        Text("Other links", style = Type.label)
        others.forEach { other ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(other.expiresAt?.let { "Expires ${dateLabel(it)}" } ?: "Never expires", Modifier.weight(1f), style = Type.sm, color = theme.text2)
                HIconButton(Lucide.Copy, "Copy this link", { state.copy(context, other) })
                HButton("Turn off", { state.revoke(other) }, variant = Variant.Ghost, small = true, enabled = !state.busy)
            }
        }
    }
    Divider()
    if (confirming) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Stop it for everyone?", Modifier.weight(1f), style = Type.label)
        HButton("Keep", { confirming = false }, variant = Variant.Ghost, small = true)
        HButton("Turn off", { state.revoke(link); confirming = false }, variant = Variant.Danger, small = true, enabled = !state.busy)
    } else HButton("Turn off link", { confirming = true }, Modifier.fillMaxWidth(), Variant.DangerGhost)
}

/** Shared → Links: every public link that works now, to copy, change or turn off. */
fun androidx.compose.foundation.lazy.LazyListScope.publicLinkItems(model: WorkspaceModel) {
    val state = model.shared.links
    val list = state.list
    val links = list.data
    when {
        links == null && list.error == null -> item { SkeletonRows() }
        list.error != null && links.isNullOrEmpty() -> item { LoadError { state.refresh() } }
        links.isNullOrEmpty() -> item {
            EmptyState(Lucide.Link2, "No public links",
                "Choose a file or folder in My Drive and select “Get public link” from its menu. Anyone with the link can download it, without an account.") {
                HButton("Browse My Drive", { model.navigate(Section.Drive) }, variant = Variant.Outline, small = true)
            }
        }
        else -> item {
            val context = LocalContext.current
            Card {
                CardHeader("Public links", "Anyone with one of these links can open what it shares. Turning a link off stops it at once.")
                links.forEachIndexed { index, link ->
                    if (index > 0) Divider()
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("link-${link.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            FileTile(null, folder = link.itemType == "FOLDER", mime = null)
                            Column(Modifier.weight(1f)) {
                                Text(link.itemName ?: "Item no longer available", style = Type.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${if (link.itemType == "FOLDER") "Folder" else "File"} · Made ${dateLabel(link.createdAt)}", style = Type.xs, color = theme.text2)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Badge(linkEnds(link.expiresAt), if (expiresSoon(link)) Tone.Warning else Tone.Neutral)
                            if (link.hasPassword) Badge("Password")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // An encrypted link's address carries its secret, which only an unlocked phone can read.
                            val usable = link.e2ee == null || model.encryption.view.keys != null
                            HButton("Copy link", { state.copy(context, link) }, variant = Variant.Outline, small = true, icon = Lucide.Copy, enabled = usable)
                            HButton("Share", { state.share(context, link) }, variant = Variant.Ghost, small = true, icon = Lucide.Share2, enabled = usable)
                            HButton("Manage", { state.open(DriveItem(link.driveItemId, link.itemName.orEmpty(), link.itemType)) }, variant = Variant.Ghost, small = true,
                                enabled = link.itemName != null)
                        }
                    }
                }
            }
        }
    }
}
