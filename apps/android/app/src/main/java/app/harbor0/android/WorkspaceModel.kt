package app.harbor0.android

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/** Pages of the phone layout. Drive, Shared and Trash are tabs (backups are in My Drive); the rest open from More, the bell or the account menu. */
enum class Section(val title: String) {
    Drive("My Drive"), Shared("Shared"), Trash("Trash"),
    Photos("Photos"), Devices("Devices"), Storage("Storage"), Settings("Settings"), Notifications("Notifications");
    /** Pages built on file routes: they need the key on an encrypted account, which older versions can't open (E2EE_UPDATE_REQUIRED). */
    val filePage get() = this in listOf(Drive, Shared, Trash, Photos)
}
enum class AuthMode { Login, Confirm, Forgot, Reset, TwoFactor }
data class Confirmation(val title: String, val description: String, val label: String, val done: String? = null,
    val danger: Boolean = true, val cancel: String = "Go back", val note: String? = null, val run: suspend () -> Unit)
data class ActivityEntry(val id: String, val message: String, val status: String, val time: Long, val read: Boolean = false)
data class TransferProgress(val label: String, val fraction: Float?)
data class Preview(val file: File, val mime: String, val item: DriveItem? = null)
/** A video or audio file played as it is read, without downloading it first. */
data class StreamPreview(val item: DriveItem, val mime: String, val source: RangeSource)
data class ZipProgress(val name: String, val phase: String, val files: Int = 0, val bytes: Long = 0, val currentFile: String? = null,
    val totalBytes: Long? = null, val fraction: Float? = null)
data class Loadable<T>(val data: T? = null, val loading: Boolean = false, val error: String? = null)

/** Sheets and dialogs. One is shown at a time; Back closes it. */
sealed interface Overlay {
    data object More : Overlay
    data object Activity : Overlay
    data object NewFolder : Overlay
    data object SelectionMenu : Overlay
    data object DeleteAccount : Overlay
    data class ItemMenu(val item: DriveItem) : Overlay
    data class TrashMenu(val item: DriveItem) : Overlay
    data class Details(val item: DriveItem) : Overlay
    data class Versions(val item: DriveItem) : Overlay
    data class Rename(val item: DriveItem) : Overlay
    data class Move(val items: List<DriveItem>) : Overlay
    data class Send(val items: List<DriveItem>) : Overlay
    data class ShareAccess(val item: DriveItem) : Overlay
    data class SyncSetupSheet(val kind: SyncSetup) : Overlay
    data class SyncFolderMenu(val root: SyncRoot) : Overlay
    data class SyncExclusions(val root: SyncRoot) : Overlay
    /** Who a synced folder is shared with; [folderId] is its cloud folder. */
    data class SyncShare(val folderId: String, val name: String) : Overlay
    data class SyncIssues(val root: SyncRoot?) : Overlay
    data class Confirm(val confirmation: Confirmation) : Overlay
    data class PublicLink(val item: DriveItem) : Overlay
    /** A file's or folder's actions inside an archive opened like a folder. */
    data class ZipEntry(val item: DriveItem) : Overlay
    data object BackupHistory : Overlay
    data object PhotoBackup : Overlay
    data class PhotoAlbums(val root: SyncRoot) : Overlay
}

class WorkspaceModel(application: Application): AndroidViewModel(application) {
    // Shared with background sync, which runs without this screen.
    val api = Harbor.api(application)
    val syncController = Harbor.sync(application)
    /** The account's end-to-end encryption keys on this phone; every request already goes through them. */
    val vault = Harbor.vault(application)
    val transfers = FileTransfers(api, keys = vault.transferKeys())
    /** Unlocking, and the encryption card in Settings. */
    val encryption = EncryptionState(this)
    val drive = DriveState(this)
    val trash = TrashState(this)
    val shared = SharedState(this)
    val backups = BackupsState(this)
    val search = SearchState(this)
    val uploads = UploadQueue(this)
    val sync = SyncState(this)
    /** A ZIP archive opened like a folder in My Drive (flag `zip-preview`). */
    val archive = ArchiveState(this)
    /** Every photo and video in the account (flag `photos`). */
    val photos = PhotosState(this)
    /** Two-step verification, access tokens and encryption status for Settings. */
    val security = SecurityState(this)
    var signedIn by mutableStateOf(false); private set
    var restoring by mutableStateOf(true); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)
    /** The toast reports something that didn't happen ("error" notices). */
    var toastAlert by mutableStateOf(false); private set
    var online by mutableStateOf(true); private set
    var section by mutableStateOf(Section.Drive); private set
    var query by mutableStateOf("")
    var overlay by mutableStateOf<Overlay?>(null)
    var account by mutableStateOf<Account?>(null); private set
    var appearance by mutableStateOf(Appearance()); private set
    var savedAppearance by mutableStateOf(Appearance()); private set
    /** idle, saving, saved or error, as the web appearance footer. */
    var appearanceStatus by mutableStateOf("idle"); private set
    var transfer by mutableStateOf<TransferProgress?>(null); private set
    var zip by mutableStateOf<ZipProgress?>(null); private set
    var preview by mutableStateOf<Preview?>(null)
    var streaming by mutableStateOf<StreamPreview?>(null)
    var activity by mutableStateOf<List<ActivityEntry>>(emptyList()); private set
    var devices by mutableStateOf(Loadable<List<Device>>()); private set
    var notices by mutableStateOf(Loadable<List<Notice>>()); private set
    // Sign-in, sign-up, confirmation and password reset.
    var authMode by mutableStateOf(AuthMode.Login); private set
    var authEmail by mutableStateOf("")
    var authNotice by mutableStateOf<Pair<AuthMode, String>?>(null); private set
    var authError by mutableStateOf<Pair<String, Boolean>?>(null)
    var cooldown by mutableIntStateOf(0); private set
    /** The code step of a two-step sign-in, and every method the account offered so the user can switch back. */
    var challenge by mutableStateOf<TwoFactorChallenge?>(null); private set
    var offeredMethods by mutableStateOf<List<String>>(emptyList()); private set
    // Held in memory only while the code step shows, to start a fresh sign-in for a new code or method (desktop).
    private var pendingPassword: String? = null
    /** Why the last session ended when the user did not sign out here; the sign-in screen says so. */
    var signedOutReason by mutableStateOf<String?>(null); private set
    /** The account is end-to-end encrypted and this version can't open it: file pages show a notice instead. */
    var encryptionBlocked by mutableStateOf(false); private set
    /** A profile save the server refused, such as USERNAME_CHANGE_LIMIT; shown in the account card. */
    var profileError by mutableStateOf<String?>(null)
    private var actionJob: Job? = null
    private var transferJob: Job? = null
    private var zipJob: Job? = null
    private var appearanceJob: Job? = null
    private var pollJob: Job? = null
    private var cooldownJob: Job? = null
    private var syncStartJob: Job? = null
    private var linkSyncJob: Job? = null
    /** When this phone last brought the account's encrypted links up to date. */
    private var linkSyncAt = 0L
    private var accountGeneration = 0
    val context: Context get() = getApplication()
    val user get() = account?.user
    val storage get() = account?.storage
    val unread get() = activity.count { !it.read }

    init {
        // Foreground transfers do not survive process death. Remove previous temporary snapshots.
        viewModelScope.launch(Dispatchers.IO) {
            File(application.cacheDir, "transfers").deleteRecursively()
            File(application.cacheDir, "previews").deleteRecursively()
            File(application.cacheDir, "zip-copies").deleteRecursively()
        }
        drive.grid = preferences.getBoolean("drive-grid", false)
        viewModelScope.launch {
            api.signedIn.collect { active ->
                signedIn = active
                if (!active) {
                    transferJob?.cancel(); zipJob?.cancel(); uploads.clear(); pollJob?.cancel(); syncStartJob?.cancel(); linkSyncJob?.cancel(); linkSyncAt = 0
                    launch { runCatching { syncController.disconnect() } }
                    sync.reset()
                    account = null; appearance = Appearance(); savedAppearance = Appearance(); appearanceStatus = "idle"
                    section = Section.Drive; overlay = null; preview = null; streaming = null; query = ""; activity = emptyList()
                    drive.reset(); trash.reset(); shared.reset(); backups.reset(); search.reset(); archive.reset(); photos.reset()
                    devices = Loadable(); notices = Loadable(); security.reset(); profileError = null
                    // Signed out: the keys are forgotten; a remembered copy stays for the next sign-in.
                    encryption.reset()
                }
            }
        }
        // Set when a session ends elsewhere, including one found ended while restoring at launch.
        viewModelScope.launch { api.ended.collect { reason -> reason?.let { signedOutReason = endedReason(it) } } }
        viewModelScope.launch {
            api.encryptionBlocked.collect { blocked ->
                encryptionBlocked = blocked
                // Sync would only repeat the refusal; it starts again once the account opens (resumed()).
                if (blocked) { syncStartJob?.cancel(); launch { runCatching { syncController.disconnect(cancelBackground = false) } } }
            }
        }
        val connectivity = application.getSystemService(ConnectivityManager::class.java)
        online = connectivity?.activeNetwork != null
        runCatching {
            connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { viewModelScope.launch { online = true } }
                override fun onLost(network: Network) { viewModelScope.launch { online = false } }
            })
        }
        restore()
    }
    private val preferences get() = context.getSharedPreferences("workspace", Context.MODE_PRIVATE)
    fun setGrid(value: Boolean) { drive.grid = value; preferences.edit().putBoolean("drive-grid", value).apply() }

    // Session ---------------------------------------------------------------
    fun restore() {
        if (busy) return
        actionJob = viewModelScope.launch {
            busy = true; restoring = true; error = null
            try { api.restore(); if (api.signedIn.value) opened() }
            catch (e: Exception) { report(e) }
            finally { busy = false; restoring = false }
        }
    }
    private suspend fun opened() {
        signedIn = true
        // A profile/network failure must leave a retryable signed-in workspace.
        try { loadAccount() } catch (e: Exception) { report(e) }
        // An encrypted account opens with this phone's remembered copy of the key, or asks for the passphrase.
        if (user?.encrypted == true) try { encryption.restore() } catch (e: Exception) { report(e) }
        section = Section.Drive
        if (!encryption.locked) drive.refresh()
        startPolling()
        startSync()
        startLinkSync()
    }
    /**
     * Encrypted public links share only what this account's apps wrapped for them: while unlocked, this phone keeps
     * them current every [LINK_SYNC_MS] for files added or changed since, as the web does.
     */
    private fun startLinkSync() {
        linkSyncJob?.cancel()
        linkSyncJob = viewModelScope.launch {
            while (isActive && signedIn) {
                val keys = vault.current().takeIf { it.enabled && !it.decrypting }?.keys
                if (keys != null && flag("public-links") && System.currentTimeMillis() - linkSyncAt >= LINK_SYNC_MS) {
                    linkSyncAt = System.currentTimeMillis()
                    try { shared.links.wraps.syncAll(keys, api.all<PublicLink>("/v1/links")) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                }
                delay(60_000)
            }
        }
    }
    /** Starts this account's sync engine, retrying while offline. */
    private fun startSync() {
        syncStartJob?.cancel()
        syncStartJob = viewModelScope.launch {
            while (isActive && signedIn) {
                try {
                    val id = user?.id ?: api.get<Account>("/v1/users/me").user.id
                    syncController.connect(id); sync.refresh(); break
                } catch (e: CancellationException) { throw e } catch (e: ApiException) {
                    if (e.status == 401 || e.code == "DEVICE_REVOKED" || e.code == "E2EE_UPDATE_REQUIRED") break
                } catch (_: Exception) {}
                delay(30_000)
            }
        }
    }
    fun logout() {
        if (transfer != null || uploads.active) { error = "Finish or cancel the transfer before signing out."; return }
        action {
            syncController.disconnect()
            try { api.logout() } catch (e: Exception) { startSync(); throw e }
        }
    }
    fun forget() = action { api.forget() }
    fun report(e: Throwable) {
        if (e is CancellationException) throw e
        // The encrypted-account notice explains this once, in place of the file pages.
        if (e is ApiException && e.code == "E2EE_UPDATE_REQUIRED") return
        error = message(e)
    }
    /** Whether a feature flag from `/v1/users/me` is on for this account and app. */
    fun flag(name: String) = account?.flag(name) == true
    fun message(e: Throwable) = if (e is java.io.IOException) "Could not connect. Check your connection and try again." else e.message ?: "This action could not be completed. Try again."
    /** Runs one action at a time. A failure shows the error banner (or the open sheet's alert) and keeps the sheet open. */
    fun action(done: String? = null, close: Boolean = false, block: suspend () -> Unit) {
        if (busy) return
        actionJob = viewModelScope.launch {
            busy = true; error = null
            try { block(); if (close) overlay = null; done?.let(::notify) } catch (e: Exception) { report(e) }
            // Any action may have changed which folders sync or back up; the next refresh reads them again.
            finally { busy = false; drive.invalidateCatalog() }
        }
    }
    fun confirm(confirmation: Confirmation) { error = null; overlay = Overlay.Confirm(confirmation) }
    fun runConfirmation(confirmation: Confirmation) = action(confirmation.done, close = true) { confirmation.run() }
    /** A toast that also lands in the activity feed, as drive notices do on the web. */
    fun notify(text: String, status: String = "info") {
        toast = text; toastAlert = status == "error"
        publish(UUID.randomUUID().toString(), text, status)
    }
    fun publish(id: String, message: String, status: String) {
        val current = activity.firstOrNull { it.id == id }
        if (current?.message == message && current.status == status) return
        activity = (listOf(ActivityEntry(id, message, status, System.currentTimeMillis())) + activity.filter { it.id != id }).take(100)
    }
    fun markActivityRead() { if (activity.any { !it.read }) activity = activity.map { it.copy(read = true) } }

    // Auth ------------------------------------------------------------------
    fun authGo(mode: AuthMode, notice: String? = null) {
        authNotice = notice?.let { mode to it }; authError = null; authMode = mode
    }
    private fun startCooldown() {
        cooldownJob?.cancel(); cooldown = 30
        cooldownJob = viewModelScope.launch { while (cooldown > 0) { delay(1000); cooldown-- } }
    }
    private fun authAction(block: suspend () -> Unit) {
        if (busy) return
        actionJob = viewModelScope.launch {
            busy = true; authError = null; authNotice = null; error = null
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { authError = message(e) to (e is ApiException && e.code == "EMAIL_NOT_VERIFIED") }
            finally { busy = false }
        }
    }
    private val signInName get() = "${Build.MANUFACTURER} ${Build.MODEL}"
    fun login(email: String, password: String) = authAction {
        authEmail = email.trim()
        val second = api.login(email, password, signInName)
        if (second != null) {
            pendingPassword = password; offeredMethods = second.methods; challenge = second
            authGo(AuthMode.TwoFactor)
            return@authAction
        }
        finishSignIn()
    }
    private suspend fun finishSignIn() {
        challenge = null; pendingPassword = null; offeredMethods = emptyList(); signedOutReason = null
        authMode = AuthMode.Login
        opened()
    }
    /** A step that can't be continued (AUTH_EXPIRED) sends the user back to the password. */
    private fun secondStepFailed(e: Exception) {
        if (e is ApiException && e.code == "AUTH_EXPIRED") { cancelTwoFactor(); authError = message(e) to false }
        else authError = message(e) to false
    }
    fun verifyCode(code: String) {
        val current = challenge ?: return
        val method = current.method ?: "TOTP"
        authAction {
            try { api.loginVerify(authEmail, current.session, method, code, signInName) }
            catch (e: ApiException) {
                // Choosing a method replaces the session; a wrong code hands the new one back.
                e.details?.get("session")?.jsonPrimitive?.contentOrNull?.let { challenge = current.copy(session = it, method = method) }
                return@authAction secondStepFailed(e)
            }
            finishSignIn()
        }
    }
    /** Moves to [method], sending a new email code when it is EMAIL. */
    fun switchMethod(method: String, notice: String? = null) {
        val current = challenge ?: return
        authAction {
            try {
                var session = current.session
                // Once a method is chosen the session is fixed to it, so start a fresh sign-in.
                if (current.method != null) {
                    val restarted = api.login(authEmail, pendingPassword ?: return@authAction cancelTwoFactor(), signInName)
                        ?: return@authAction finishSignIn()
                    if (restarted.method != null) { challenge = restarted; notice?.let { authNotice = AuthMode.TwoFactor to it }; return@authAction }
                    session = restarted.session
                }
                challenge = api.loginMethod(authEmail, session, method)
                notice?.let { authNotice = AuthMode.TwoFactor to it }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { secondStepFailed(e) }
        }
    }
    fun cancelTwoFactor() {
        challenge = null; pendingPassword = null; offeredMethods = emptyList()
        authGo(AuthMode.Login)
    }
    fun confirmEmail(email: String, code: String) = authAction {
        api.anonymous("/v1/auth/confirm", buildJsonObject { put("email", email.trim()); put("code", code.trim()) })
        authGo(AuthMode.Login, "Email verified. Sign in to continue.")
    }
    fun forgot(email: String) = authAction {
        authEmail = email.trim()
        api.anonymous("/v1/auth/forgot", buildJsonObject { put("email", email.trim()) })
        startCooldown(); authGo(AuthMode.Reset, "If ${email.trim()} has an account, a reset code is on its way.")
    }
    fun resetPassword(email: String, code: String, password: String) = authAction {
        api.anonymous("/v1/auth/reset", buildJsonObject { put("email", email.trim()); put("code", code.trim()); put("password", password) })
        authGo(AuthMode.Login, "Password updated. Sign in with your new password.")
    }
    fun resend(goConfirm: Boolean = false) {
        if (authEmail.isBlank()) { authError = "Enter your email to get a new code." to false; return }
        authAction {
            api.anonymous("/v1/auth/resend", buildJsonObject { put("email", authEmail.trim()) })
            startCooldown()
            if (goConfirm) authMode = AuthMode.Confirm
            authNotice = AuthMode.Confirm to "A new code is on its way to ${authEmail.trim()}."
        }
    }

    // Navigation ------------------------------------------------------------
    /** Bumped on every navigation so pages start scrolled to the top. */
    var epoch by mutableIntStateOf(0); private set
    fun navigate(next: Section) {
        epoch++
        overlay = null; query = ""; error = null; search.reset(); archive.close()
        drive.selected = emptySet(); trash.selected = emptySet()
        if (next == Section.Drive) {
            // Coming from another page opens My Drive fresh on Cloud, as the web remounts it.
            drive.location = Location.Cloud
            if (section != Section.Drive) drive.filters = DriveFilters()
            drive.trail = emptyList(); drive.scope = "all"; drive.leavePlace()
        }
        section = next
        refreshSection()
    }
    /** Opens a search result: folders open in My Drive, files in the preview. */
    fun openResult(item: DriveItem) {
        if (!item.isFolder) return drive.open(item)
        navigate(Section.Drive)
        drive.location = if (item.backupRootId != null) Location.Backup else Location.Cloud
        drive.enter(item)
    }
    fun clearSearch() { query = ""; search.reset(); refreshSection() }
    fun refreshSection() {
        // Locked, file pages would only list encrypted names; the unlock page shows instead.
        if (encryption.locked && (section.filePage || query.isNotBlank())) return
        if (query.isNotBlank() && section != Section.Drive && section != Section.Trash) return search.refresh()
        when (section) {
            Section.Drive -> drive.refresh()
            Section.Trash -> trash.refresh()
            Section.Shared -> { shared.refresh(); sync.refresh(wake = false) }
            Section.Photos -> photos.refresh()
            Section.Devices -> loadDevices()
            Section.Notifications -> loadNotices()
            Section.Storage -> refreshAccount()
            Section.Settings -> security.load()
        }
    }
    /** Called when the app returns to the foreground, as the web refreshes on focus. */
    fun resumed() {
        if (!signedIn) return
        sync.checkPhotoAccess()
        refreshAccount()
        if (encryptionBlocked) retryEncrypted() else refreshSection()
        // A remembered unlock that couldn't open earlier (offline, say) is tried again.
        if (encryption.locked && !encryption.removed && !encryption.restoring) viewModelScope.launch {
            try { encryption.restore(); if (!encryption.locked) unlocked() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
    }
    /** Unlocked: everything shown while locked is read again with the key, and sync picks up where it stopped. */
    fun unlocked() { refreshAccount(); refreshSection(); syncController.wake(); linkSyncAt = 0; startLinkSync() }
    /** Locked from Settings: nothing decrypted stays on screen. */
    fun locked() { preview = null; streaming = null; drive.reset(); trash.reset(); shared.reset(); search.reset(); backups.reset(); archive.reset(); photos.reset() }
    /** Saves the recovery key as a text file in Downloads, as the web's Download button. */
    fun saveRecoveryKey(value: String) = action {
        val file = File(previews, UUID.randomUUID().toString() + "/harbor0-recovery-key.txt")
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeText("harbor0 recovery key\n\n$value\n\nKeep this somewhere safe and away from this phone. With your sign-in, it unlocks your encrypted files if you forget your passphrase.\n")
        }
        save(file, "text/plain")
    }
    /** Checks whether the account opens again (encryption turned off, or the app updated), then resumes files and sync. */
    fun retryEncrypted() {
        viewModelScope.launch {
            try {
                api.request("/v1/drive/folders/root/children")
                api.clearEncryptionBlock(); refreshSection(); startSync()
            } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
    }
    private fun startPolling() {
        pollJob?.cancel()
        // Changes from other devices stay current without flashing a loading state.
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                if (!foreground || overlay != null || busy || encryption.locked) continue
                when (section) { Section.Drive -> if (!drive.loading) drive.refresh(); Section.Shared -> { shared.refresh(); sync.refresh(wake = false) }; else -> {} }
            }
        }
    }
    var foreground = true
        set(value) { field = value; syncController.foreground = value }
    /** System Back: closes sheets, clears selection or search, then goes up a level. */
    fun back(): Boolean {
        when {
            overlay != null -> overlay = null
            section == Section.Drive && archive.open && archive.selected.isNotEmpty() -> archive.selected = emptySet()
            section == Section.Drive && archive.open && query.isEmpty() -> archive.back()
            section == Section.Drive && drive.selected.isNotEmpty() -> drive.selected = emptySet()
            section == Section.Trash && trash.selected.isNotEmpty() -> trash.selected = emptySet()
            query.isNotEmpty() -> clearSearch()
            section == Section.Drive && drive.canGoUp -> drive.up()
            section != Section.Drive -> navigate(Section.Drive)
            else -> return false
        }
        return true
    }
    val canGoBack get() = overlay != null || query.isNotEmpty() || section != Section.Drive || drive.canGoUp || drive.selected.isNotEmpty() || archive.open

    // Account and appearance ---------------------------------------------------
    suspend fun loadAccount() {
        val stamp = accountGeneration
        val result = api.get<Account>("/v1/users/me")
        if (stamp != accountGeneration) return
        if (appearanceStatus != "saving" && appearance == savedAppearance) { appearance = result.user.appearance; savedAppearance = appearance }
        account = result
    }
    fun refreshAccount() { viewModelScope.launch { try { loadAccount() } catch (e: CancellationException) { throw e } catch (_: Exception) {} } }
    /** Applies a theme change at once and saves it to the account shortly after, as the web settings do. */
    fun updateAppearance(next: Appearance) {
        appearance = next
        if (account == null) return
        accountGeneration++
        appearanceJob?.cancel()
        appearanceStatus = "saving"
        appearanceJob = viewModelScope.launch {
            delay(400)
            try {
                api.request("/v1/users/me", "PATCH", buildJsonObject {
                    operation().forEach { (k, v) -> put(k, v) }; put("appearance", harborJson.encodeToJsonElement(next))
                })
                savedAppearance = next; appearanceStatus = "saved"
            } catch (e: CancellationException) { throw e } catch (_: Exception) { appearanceStatus = "error" }
        }
    }
    fun retryAppearance() = updateAppearance(appearance)
    /** Theme toggles in More and on the sign-in screen switch between light and dark. */
    fun toggleTheme(dark: Boolean) = updateAppearance(appearance.copy(preference = if (dark) "light" else "dark"))
    fun saveProfile(displayName: String, username: String) {
        val current = user ?: return
        profileError = null
        action {
            try {
                api.request("/v1/users/me", "PATCH", buildJsonObject {
                    operation().forEach { (k, v) -> put(k, v) }
                    // Only changed fields are sent, so an unchanged username never counts as a rename.
                    if (displayName.trim() != current.displayName) put("displayName", displayName.trim())
                    if (username != current.username) put("username", username)
                })
            } catch (e: ApiException) {
                // A username refused for its 30-day limit or as taken is shown under the field, not as a banner.
                if (e.code != "USERNAME_CHANGE_LIMIT" && e.code != "USERNAME_TAKEN") throw e
                profileError = e.message; return@action
            }
            loadAccount(); notify("Profile updated.")
        }
    }
    fun setProductUpdates(on: Boolean) = action(if (on) "Product updates turned on." else "Product updates turned off.") {
        api.request("/v1/users/me", "PATCH", buildJsonObject {
            operation().forEach { (k, v) -> put(k, v) }; put("emailPreferences", buildJsonObject { put("productUpdates", on) })
        })
        loadAccount()
    }
    fun deleteAccount(email: String) = action(close = true) {
        api.request("/v1/users/me/delete", "POST", buildJsonObject { operation().forEach { (k, v) -> put(k, v) }; put("email", email.trim()) })
        api.forget()
    }

    // Devices and notifications --------------------------------------------
    fun loadDevices() {
        devices = devices.copy(loading = true, error = null)
        viewModelScope.launch {
            devices = try { Loadable(api.get<Page<Device>>("/v1/devices").items) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { devices.copy(loading = false, error = message(e)) }
        }
    }
    /** This phone's sync device id, which is this session's id. */
    val currentDeviceId get() = syncController.engine?.deviceId
    /** Pauses a device's sync and backups until it signs in again (web ConnectedDevices). */
    fun signOutDevice(device: Device) {
        val here = device.id == currentDeviceId
        confirm(Confirmation("Sign out ${device.name}?",
            if (here) "This is the phone you’re using. You’ll be signed out, and its sync and backups pause until you sign in again. Nothing is deleted."
            else "${device.name} is signed out of harbor0. Its sync and backups pause, and resume when you sign in on it again. Nothing is deleted." +
                if (device.platform == "WEB") " If it’s the browser you’re using now, you’ll need to sign in again." else "",
            "Sign out", "${device.name} is signed out.") {
            api.request("/v1/devices/${HarborApi.segment(device.id)}/sign-out", "POST", buildJsonObject {})
            if (here) { syncController.disconnect(cancelBackground = false); api.forget() } else loadDevices()
        })
    }
    /** Removes a device for good; its running backups are archived in the cloud. [done] closes the confirmation. */
    fun revokeDevice(device: Device, done: () -> Unit) = action("${device.name} is revoked.") {
        api.request("/v1/devices/${HarborApi.segment(device.id)}", "DELETE")
        done()
        if (device.id == currentDeviceId) { syncController.disconnect(); api.forget() } else loadDevices()
    }
    fun loadNotices() {
        notices = notices.copy(loading = true, error = null)
        viewModelScope.launch {
            // Newest first, whatever order the server lists them in.
            notices = try { Loadable(api.get<Page<Notice>>("/v1/notifications").items.sortedByDescending { it.createdAt }) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { notices.copy(loading = false, error = message(e)) }
        }
    }
    private suspend fun readNotices(ids: List<String>) {
        coroutineScope { ids.map { id -> async { api.request("/v1/notifications/${HarborApi.segment(id)}/read", "POST") } }.awaitAll() }
        loadNotices()
    }
    fun markRead(notice: Notice) = action { readNotices(listOf(notice.id)) }
    fun markAllRead() = action { readNotices(notices.data.orEmpty().filter { it.readAt == null }.map { it.id }) }
    /** Marks a notice read and opens where it leads: Shared → Received or Sent, or Storage. */
    fun openNotice(notice: Notice) {
        val target = describeNotice(notice).target ?: return
        if (notice.readAt == null) viewModelScope.launch {
            try { readNotices(listOf(notice.id)) } catch (e: CancellationException) { throw e } catch (e: Exception) { report(e) }
        }
        when (target) {
            NoticeTarget.Storage -> navigate(Section.Storage)
            NoticeTarget.Received, NoticeTarget.Sent -> { navigate(Section.Shared); shared.select(target.name) }
        }
    }

    /** The sign-in screen's explanation for a session that ended elsewhere (desktop noteRevoked). */
    private fun endedReason(e: ApiException) = when {
        e.code == "DEVICE_REVOKED" -> "This phone was signed out from another device and removed from your account. Your local files were kept; sign in to set up sync again."
        e.message.contains("signed out", ignoreCase = true) -> "This phone was signed out from another device. Sign in again to resume syncing; your local files were kept."
        else -> "Your session ended. Sign in again to continue."
    }

    // Files on this device ------------------------------------------------------
    private fun progress(label: String, fraction: Float?) { viewModelScope.launch { transfer = TransferProgress(label, fraction) } }
    /** Updates the transfer card from any thread. */
    fun showProgress(label: String, fraction: Float?) = progress(label, fraction)
    fun startTransfer(block: suspend () -> Unit) {
        if (transferJob?.isActive == true) { error = "Wait for the current download to finish."; return }
        transferJob = viewModelScope.launch {
            transfer = TransferProgress("Preparing file…", null); error = null
            try { block() } catch (e: Exception) { if (e !is CancellationException) report(e) }
            finally { transfer = null }
        }
    }
    fun cancelTransfer() { transferJob?.cancel() }
    /** Private copies for previews and saving, removed on the next launch. */
    internal val previews get() = File(context.cacheDir, "previews")
    /**
     * Downloads, checks and shows a file; the preview offers opening in another app, sharing and saving. Video and
     * audio instead play as they are read, so they start on the first few megabytes.
     */
    fun openFile(item: DriveItem) = startTransfer {
        val mime = item.mimeType ?: "application/octet-stream"
        val kind = previewKind(item.name, mime)
        if (kind == "video" || kind == "audio") {
            progress("Opening ${item.name}…", null)
            streaming = StreamPreview(item, mime.takeUnless { it == "application/octet-stream" } ?: guessMime(item.name), transfers.openRange(item.id))
            return@startTransfer
        }
        val file = transfers.download(item, previews, ::progress)
        preview = Preview(file, mime, item)
    }
    /** Saves a verified copy to the public Downloads folder. Android 9 and earlier choose a location instead. */
    fun downloadToDevice(name: String, body: JsonObject, mime: String?) = startTransfer { fetchAndSave(name, body, mime) }
    suspend fun fetchAndSave(name: String, body: JsonObject, mime: String?) {
        val file = transfers.download(name, body, previews, ::progress)
        save(file, mime ?: "application/octet-stream")
    }
    /** Saves a private file to Downloads (Android 9 and earlier: the preview, to choose a location). */
    internal suspend fun save(file: File, mime: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name); put(MediaStore.Downloads.MIME_TYPE, mime); put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not save this file. Try again.")
                try {
                    resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: error("Could not save this file. Try again.")
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
                file.parentFile?.deleteRecursively()
            }
            notify("Saved “${file.name}” to Downloads.", "success")
        } else preview = Preview(file, mime)
    }
    fun saveDownload(uri: Uri, file: File) = action("File saved.") {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: error("Could not save this file. Choose another location.")
        }
    }
    fun savePreview() {
        val current = preview ?: return
        action { save(current.file.copyTo(File(previews, UUID.randomUUID().toString() + "/" + current.file.name)), current.mime) }
    }
    /** Folder ZIPs are built by the backend; this polls its progress, then downloads and saves the archive. */
    fun downloadFolder(item: DriveItem) {
        if (zipJob?.isActive == true) return
        zipJob = viewModelScope.launch {
            zip = ZipProgress(item.name, "queued"); error = null
            var job: FolderDownload? = null
            try {
                job = harborJson.decodeFromJsonElement<FolderDownload>(api.request("/v1/folder-downloads", "POST", buildJsonObject {
                    put("driveItemId", item.id); put("operationId", UUID.randomUUID().toString())
                }))
                while (true) {
                    val current = job!!
                    if (current.state == "READY") break
                    if (current.state in listOf("FAILED", "CANCELLED", "EXPIRED")) error(current.error ?: "ZIP preparation ended. Please try again.")
                    zip = ZipProgress(item.name, current.state.lowercase(), current.files, current.bytes, current.currentFile, current.totalBytes,
                        current.totalBytes?.let { if (it == 0L) 1f else current.bytes.toFloat() / it })
                    delay(1500)
                    job = api.get<FolderDownload>("/v1/folder-downloads/${HarborApi.segment(current.id)}")
                }
                val ready = job!!
                val url = ready.downloadUrl ?: error("The ZIP download is unavailable. Please try again.")
                val file = transfers.fetch(url, "${safeName(item.name)}.zip", ready.sizeBytes, ready.contentHash, previews) { _, fraction ->
                    viewModelScope.launch { zip = zip?.copy(phase = "downloading", fraction = fraction, bytes = ((fraction ?: 0f) * (ready.sizeBytes ?: 0)).toLong()) }
                }
                save(file, "application/zip")
            } catch (e: CancellationException) {
                job?.takeIf { it.state !in listOf("FAILED", "CANCELLED", "EXPIRED", "READY") }?.let {
                    withContext(NonCancellable) { runCatching { api.request("/v1/folder-downloads/${HarborApi.segment(it.id)}", "DELETE") } }
                }
                throw e
            } catch (e: Exception) { report(e) }
            finally { zip = null }
        }
    }
    fun cancelZip() { zipJob?.cancel() }

    // Uploads (used by the Drive FAB and tests) ----------------------------------
    fun upload(uri: Uri) = uploads.addUris(listOf(uri))
    override fun onCleared() { uploads.clear(); super.onCleared() }
}
