package app.harbor0.android

import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The status and controls of a synced folder, shown above its files in My Drive, as the desktop
 * `SyncFolderPanel`: synced on this phone (pause, share, settings, what needs attention), or synced on
 * other devices with “Sync to this phone”.
 */
@Composable fun SyncFolderPanel(model: WorkspaceModel, folder: DriveItem, modifier: Modifier = Modifier) {
    val sync = model.sync
    val root = sync.localRoot(folder.id)
    val owner = folder.ownerUserId == null || model.user == null || folder.ownerUserId == model.user?.id
    val share = owner && !model.drive.encrypted
    if (root == null) {
        if (!sync.canLinkHere(folder)) return
        val devices = sync.remoteFolders.firstOrNull { it.id == folder.id }?.syncDevices.orEmpty().map { it.name }
        Card(modifier.testTag("sync-panel")) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBox(Lucide.FolderSync, 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Not synced on this phone", style = Type.h2)
                    Text("${if (devices.isNotEmpty()) "Synced on ${devices.joinToString(", ")}. " else ""}Choose a folder on this phone to keep a copy here too.", style = Type.sm, color = theme.text2)
                }
            }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HButton("Sync to this phone", { sync.linkHere(folder) }, Modifier.weight(1f).testTag("sync-here"), icon = Lucide.Plus, enabled = !model.busy)
                if (share) HButton("Share", { model.error = null; model.overlay = Overlay.SyncShare(folder.id, folder.name) }, variant = Variant.Outline, icon = Lucide.UserPlus, enabled = !model.busy)
            }
        }
        return
    }
    val state = sync.state(root)
    val active = sync.runtime.active?.takeIf { it.rootId == root.id }
    val (files, _) = sync.view?.counts?.get(root.id) ?: (0 to 0)
    val issues = sync.requirements.filter { it.rootId == root.id }
    val waiting = sync.runtime.waiting.count { it.rootId == root.id }
    Card(modifier.testTag("sync-panel")) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBox(Lucide.FolderSync, 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Synced on this phone", style = Type.h2)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(state, sync.tone(state))
                    if (root.shareId != null) Badge("Shared with you")
                }
                Text(listOf(root.localName, root.lastSyncedAt?.let { "Last synced ${ago(it)}" } ?: "Not synced yet", plural(files, "file")).joinToString(" · "),
                    style = Type.sm, color = theme.text2)
                active?.let {
                    Text("${if (it.direction == "upload") "Uploading" else "Downloading"} ${baseName(it.relativePath).ifEmpty { "folder" }}", style = Type.xs, color = theme.text2,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Progress(if (it.total > 0) it.loaded.toFloat() / it.total else null, height = 4.dp)
                }
            }
        }
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton(if (root.paused) "Resume" else "Pause", { sync.togglePause(root) }, Modifier.weight(1f), Variant.Outline,
                icon = if (root.paused) Lucide.Play else Lucide.Pause, enabled = !model.busy)
            if (share && root.shareId == null && root.remoteId != null) HButton("Share", { model.error = null; model.overlay = Overlay.SyncShare(root.remoteId, root.localName) }, Modifier.weight(1f), Variant.Outline,
                icon = Lucide.UserPlus, enabled = !model.busy)
            HIconButton(Lucide.Ellipsis, "More actions for ${root.localName}", { model.error = null; model.overlay = Overlay.SyncFolderMenu(root) }, Modifier.testTag("sync-panel-menu"))
        }
        if (sync.runtime.paused) Alert("Sync and backups are paused on this phone. Resume them in Settings.", Modifier.padding(top = 12.dp), Tone.Warning, "Settings") { model.navigate(Section.Settings) }
        else BackgroundRestrictedAlert(model, Modifier.padding(top = 12.dp))
        if (waiting > 0) Text("${plural(waiting, "file")} still held on another device. They download here once it is online.", Modifier.padding(top = 10.dp), style = Type.xs, color = theme.text2)
        if (issues.isNotEmpty()) Column(Modifier.padding(top = 10.dp)) {
            Text("Needs attention", style = Type.h3)
            IssueList(model, issues.take(3))
            if (issues.size > 3) HButton("Show all ${issues.size}", { model.overlay = Overlay.SyncIssues(root) }, variant = Variant.Link)
        }
    }
}

/**
 * Shown where this phone's backups are managed while Android may stop harbor0 in the background: battery settings that
 * restrict it, the battery optimization most phones apply by default, then the phone's own auto-start setting, if it has one.
 */
@Composable fun BackgroundRestrictedAlert(model: WorkspaceModel, modifier: Modifier = Modifier) {
    val sync = model.sync
    if (sync.roots.isEmpty()) return
    val pickers = LocalPickers.current
    when {
        sync.backgroundRestricted -> Alert(BACKGROUND_RESTRICTED, modifier.testTag("background-restricted"), Tone.Warning, "Open settings") { openAppSettings(model.context) }
        sync.batteryOptimized -> Alert(BATTERY_OPTIMIZED, modifier.testTag("battery-optimized"), Tone.Warning, "Allow") { pickers.allowBackground() }
        sync.autoStart != null -> Alert(AUTO_START, modifier.testTag("auto-start"), Tone.Neutral, "Open settings") { sync.openAutoStart() }
    }
}

/** “Sync a folder” for this phone, at the top of Synced Folders, plus a pointer to any folder that needs attention. */
@Composable fun SyncPlaceActions(model: WorkspaceModel, modifier: Modifier = Modifier) {
    val sync = model.sync
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HButton("Sync a folder", { sync.setup(SyncSetup.New) }, Modifier.testTag("sync-add"), Variant.Outline, small = true, icon = Lucide.FolderPlus,
            enabled = sync.view != null && !model.busy)
        SyncAttention(model)
        BackgroundRestrictedAlert(model)
    }
}

/** Sync problems on this phone, across folders: one line that opens the list. */
@Composable fun SyncAttention(model: WorkspaceModel, modifier: Modifier = Modifier) {
    val count = model.sync.requirements.size
    if (count > 0) Alert("${plural(count, "backup problem")} on this phone ${if (count == 1) "needs" else "need"} attention.", modifier, Tone.Warning, "Review") { model.overlay = Overlay.SyncIssues(null) }
}

/** Invitations to sync someone else's folder, under Shared › Received, as the web `SyncInvitations`. */
@Composable fun SyncInvitations(model: WorkspaceModel, modifier: Modifier = Modifier) {
    val sync = model.sync
    val invitations = sync.receivedInvitations
    if (invitations.isEmpty() && sync.invitations.error == null) return
    Card(modifier.testTag("sync-invitations")) {
        CardHeader("Folders shared with you", "Two-way sync: you and the owner can both edit these files.")
        sync.invitations.error?.let { Alert(it, tone = Tone.Danger, action = "Retry") { sync.refresh(wake = false) } }
        invitations.forEachIndexed { index, invitation ->
            if (index > 0) Divider()
            Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.Users, null, Modifier.size(18.dp), tint = theme.text2)
                    Column(Modifier.weight(1f)) {
                        Text(invitation.name, style = Type.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("From ${invitation.owner.displayName.ifBlank { "@" + invitation.owner.username }} · Two-way sync", style = Type.xs, color = theme.text2)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HButton(if (invitation.syncState == "PENDING") "Accept" else "Sync on this phone", { sync.setup(SyncSetup.Invite(invitation)) }, variant = Variant.Outline, small = true,
                        enabled = sync.view != null && !model.busy)
                    if (invitation.syncState == "PENDING") HButton("Decline", { sync.decline(invitation) }, variant = Variant.Ghost, small = true, enabled = !model.busy)
                }
            }
        }
    }
}

@Composable private fun IssueList(model: WorkspaceModel, issues: List<SyncIssue>) {
    val sync = model.sync
    issues.forEachIndexed { index, issue ->
        if (index > 0) Divider()
        val root = sync.roots.firstOrNull { it.id == issue.rootId }
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (stickyIssue(issue)) Lucide.Info else Lucide.CircleAlert, null, Modifier.padding(top = 2.dp).size(16.dp),
                    tint = if (stickyIssue(issue)) theme.text2 else theme.dangerText)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(when (issue.code) { IssueCode.CONFLICT -> "Conflict"; IssueCode.FOLDER_RECOVERED -> "Folder kept on this phone"; else -> root?.localName ?: "Sync" },
                        style = Type.label)
                    Text(issueText(issue), style = Type.sm, color = theme.text2)
                    (issue.conflictPath ?: issue.relativePath)?.let { Text(listOfNotNull(root?.localName, it).joinToString(" / "), style = Type.xs, color = theme.text3, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    if (issue.code == IssueCode.SYNC_ERROR && issue.message.isNotBlank() && issueText(issue) != issue.message) Text(issue.message, style = Type.xs, color = theme.text2)
                }
            }
            Row(Modifier.padding(start = 26.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (stickyIssue(issue)) HButton("Mark reviewed", { sync.dismiss(issue) }, variant = Variant.Outline, small = true, enabled = !model.busy)
                issue.jobId?.let { job -> HButton("Retry", { sync.retry(job) }, variant = Variant.Outline, small = true, icon = Lucide.RotateCcw, enabled = !model.busy) }
                if (root != null && issue.code in listOf(IssueCode.FOLDER_MISSING, IssueCode.PERMISSION_DENIED, IssueCode.MAPPING_REQUIRED) && issue.scope == null)
                    HButton(if (issue.code == IssueCode.MAPPING_REQUIRED) "Finish setup" else "Locate folder", { sync.setup(SyncSetup.ChangeLocal(root)) }, variant = Variant.Outline, small = true)
                if (issue.code == IssueCode.STORAGE_QUOTA_EXCEEDED) HButton("Manage storage", { model.navigate(Section.Storage) }, variant = Variant.Outline, small = true)
            }
        }
    }
}

@Composable fun SyncIssuesSheet(model: WorkspaceModel, root: SyncRoot?) {
    val problems = (if (root?.mode == "backup") model.sync.runtime.issues else model.sync.requirements).filter { root == null || it.rootId == root.id }
    HSheet({ model.overlay = null }, if (root == null) "Needs attention" else "${root.localName}: needs attention", tall = problems.size > 4) {
        if (problems.isEmpty()) Text("Nothing needs your attention in this folder any more.", style = Type.sm, color = theme.text2)
        IssueList(model, problems)
    }
}

/** Choose a local folder, then start syncing. A new folder's cloud copy is created for it, as on the desktop app. */
@Composable fun SyncSetupSheet(model: WorkspaceModel, kind: SyncSetup) {
    val sync = model.sync
    val pickers = LocalPickers.current
    var local by remember { mutableStateOf<Uri?>(null) }
    var localName by remember { mutableStateOf<String?>(null) }
    val context = model.context
    val (title, description) = when (kind) {
        SyncSetup.New -> "Sync a folder" to "Choose a folder on this phone. Its files are kept in your cloud storage and sync to your linked devices."
        is SyncSetup.Remote -> "Sync “${kind.folder.name}” to this phone" to "Choose where this folder lives on this phone. Its files download from the cloud."
        is SyncSetup.Invite -> "Sync “${kind.invitation.name}”" to "Shared by ${kind.invitation.owner.displayName.ifBlank { "@" + kind.invitation.owner.username }}. Both of you can edit these files."
        is SyncSetup.ChangeLocal -> "Change local folder" to "Choose where “${kind.root.localName}” lives on this phone. It is checked again before ${if (kind.root.mode == "backup") "backups resume" else "syncing resumes"}."
    }
    val pending = kind is SyncSetup.Invite && kind.invitation.syncState == "PENDING"
    HSheet({ model.overlay = null }, title, description, footer = {
        HButton(if (model.busy) "Starting…" else when (kind) { is SyncSetup.ChangeLocal -> "Use folder and resume"; is SyncSetup.Invite -> if (pending) "Accept and start syncing" else "Start syncing"; else -> "Start syncing" },
            { sync.start(kind, local) }, Modifier.fillMaxWidth().testTag("sync-start"), enabled = local != null && !model.busy)
        if (pending && kind is SyncSetup.Invite) HButton("Decline", { sync.decline(kind.invitation) }, Modifier.fillMaxWidth(), Variant.Outline, enabled = !model.busy)
        HButton("Cancel", { model.overlay = null }, Modifier.fillMaxWidth(), Variant.Outline, enabled = !model.busy)
    }) {
        Field("Local folder") {
            val shape = RoundedCornerShape(Radius.lg)
            Row(Modifier.fillMaxWidth().clip(shape).border(1.dp, theme.line, shape).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Lucide.Smartphone, null, Modifier.size(18.dp), tint = theme.text2)
                Text(localName ?: "Choose a folder on this phone", Modifier.weight(1f), style = Type.sm, color = if (localName == null) theme.text2 else theme.text, maxLines = 2)
                HButton(if (local == null) "Choose" else "Change", {
                    pickers.chooseFolder { uri -> local = uri; localName = runCatching { SafTree(context, uri).name }.getOrNull() ?: uri.lastPathSegment }
                }, variant = Variant.Outline, small = true, enabled = !model.busy)
            }
        }
        Text(when (kind) {
            is SyncSetup.Remote -> "Choose an empty folder, or one that already holds a copy of these files. Anything else in it will sync to your other devices. "
            is SyncSetup.Invite -> "Files already in the local folder will be shared too. Use an empty folder to start with the owner’s files. "
            else -> ""
        } + if (kind is SyncSetup.ChangeLocal) "harbor0 compares the new folder with the cloud before changing anything. Nothing is deleted."
            else "Changes, including deletions, sync between linked devices. Synced files are kept in the cloud, and every device downloads changes from there. Conflicting versions are preserved.",
            style = Type.xs, color = theme.text2)
        model.error?.let { Alert(it, tone = Tone.Danger) }
    }
}

/** One sync folder's details and actions. */
@Composable fun SyncFolderMenu(model: WorkspaceModel, root: SyncRoot) {
    val sync = model.sync
    val current = sync.roots.firstOrNull { it.id == root.id } ?: root
    val state = sync.state(current)
    val (files, folders) = sync.view?.counts?.get(root.id) ?: (0 to 0)
    val owned = current.shareId == null
    fun go(action: () -> Unit) { model.overlay = null; action() }
    HSheet({ model.overlay = null }, current.localName, current.cloudPath) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { StatusPill(state, sync.tone(state)) }
        DetailRows(listOf("This phone" to current.localName, "Cloud" to (current.cloudPath ?: "Cloud folder"),
            "Tracked" to "${plural(files, "file")} · ${plural(folders, "folder")}", "Last synced" to (current.lastSyncedAt?.let { fullDate(it) } ?: "Not synced yet"),
            "Excluded" to (current.excluded.joinToString(", ").ifEmpty { "Nothing" })))
        Column {
            if (sync.requirements.any { it.rootId == root.id }) HMenuItem("Show what needs attention", Lucide.CircleAlert, { model.overlay = Overlay.SyncIssues(current) })
            HMenuItem(if (current.paused) "Resume folder sync" else "Pause folder sync", if (current.paused) Lucide.Play else Lucide.Pause, { go { sync.togglePause(current) } }, enabled = !model.busy)
            HMenuItem("Manage exclusions", Lucide.EyeOff, { model.overlay = Overlay.SyncExclusions(current) })
            HMenuItem("Change local folder", Lucide.FolderOpen, { sync.setup(SyncSetup.ChangeLocal(current)) })
            if (owned && !model.drive.encrypted && current.remoteId != null) HMenuItem("Share with another account", Lucide.UserPlus, { model.error = null; model.overlay = Overlay.SyncShare(current.remoteId, current.localName) })
            HMenuSeparator()
            HMenuItem("Stop syncing on this phone", Lucide.Unplug, { sync.stopHere(current) }, enabled = !model.busy)
            if (owned && current.remoteId != null) HMenuItem("Remove from sync everywhere", Lucide.X, { sync.removeEverywhere(current.remoteId, current.localName) }, danger = true, enabled = !model.busy)
        }
        if (owned && model.drive.encrypted) UnsupportedNote(E2eeUnsupported.SYNC_SHARE)
    }
}

/** A multi-line text field in the kit's input style. */
@Composable fun HTextArea(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "") {
    val t = theme
    val shape = RoundedCornerShape(Radius.md)
    BasicTextField(value, onChange, modifier.fillMaxWidth(), textStyle = Type.mono.copy(fontSize = 14.sp, color = t.text), cursorBrush = SolidColor(t.primary), minLines = 4) { inner ->
        Box(Modifier.fillMaxWidth().border(1.dp, t.line, shape).padding(12.dp)) {
            if (value.isEmpty()) Text(placeholder, style = Type.mono.copy(fontSize = 14.sp), color = t.text3)
            inner()
        }
    }
}

@Composable fun SyncExclusionsSheet(model: WorkspaceModel, root: SyncRoot) {
    var text by remember { mutableStateOf(root.excluded.joinToString("\n")) }
    val entries = text.lines().map { it.trim().trim('/') }.filter { it.isNotEmpty() }
    val valid = entries.all(::validRelative)
    HDialog("Manage exclusions", { model.overlay = null }, "Folders inside “${root.localName}” that should not ${if (root.mode == "backup") "be backed up" else "sync"}, one per line, like Photos/Old. Excluded files stay where they are.", actions = {
        HButton(if (model.busy) "Saving…" else "Save exclusions", { model.sync.saveExclusions(root, text) }, Modifier.fillMaxWidth(), enabled = valid && !model.busy)
        HButton("Cancel", { model.overlay = null }, Modifier.fillMaxWidth(), Variant.Outline)
    }) {
        HTextArea(text, { text = it }, Modifier.testTag("exclusions"), placeholder = "Relative folders to exclude, one per line")
        if (!valid) Text("Use paths inside this folder, without “..”.", style = Type.xs, color = theme.dangerText)
        model.error?.let { Alert(it, tone = Tone.Danger) }
    }
}

@Composable fun SyncShareSheet(model: WorkspaceModel, folderId: String, name: String) {
    val sync = model.sync
    var recipient by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { sync.refresh(wake = false) }
    HSheet({ model.overlay = null }, "Share “$name”", "Everyone you invite can add, edit, rename, and delete files. Changes sync across linked devices. Only you can manage access.",
        footer = { HButton("Done", { model.overlay = null }, Modifier.fillMaxWidth(), Variant.Outline) }) {
        Field("Email or username", hint = "Invite an existing harbor0 account. Shared files use your storage allowance while waiting for delivery.") {
            HInput(recipient, { recipient = it.take(254) }, Modifier.testTag("sync-share-recipient"), placeholder = "name@example.com or username")
        }
        HButton(if (model.busy) "Please wait…" else "Send invitation", { sync.invite(folderId, recipient); recipient = "" }, Modifier.fillMaxWidth(),
            enabled = recipient.trim().removePrefix("@").length >= 3 && !model.busy)
        model.error?.let { Alert(it, tone = Tone.Danger) }
        Text("People with access", style = Type.h3)
        val members = sync.members(folderId)
        when {
            sync.invitations.data == null -> Text("Loading access…", style = Type.sm, color = theme.text2)
            members.isEmpty() -> Text("No invitations yet.", style = Type.sm, color = theme.text2)
            else -> members.forEach { member ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatar(member.recipient.displayName.ifBlank { member.recipient.username }, 32.dp)
                    Column(Modifier.weight(1f)) {
                        Text(member.recipient.displayName.ifBlank { "@" + member.recipient.username }, style = Type.name, maxLines = 1)
                        Text("@${member.recipient.username} · ${if (member.syncState == "PENDING") "Invited" else "Can edit and sync"}", style = Type.xs, color = theme.text2)
                    }
                    HButton("Remove", { sync.removeMember(member) }, variant = Variant.Outline, small = true, enabled = !model.busy)
                }
            }
        }
    }
}
