package app.harbor0.android

import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Shows the current sheet or dialog. */
@Composable fun Overlays(model: WorkspaceModel) {
    val close = { model.overlay = null }
    when (val overlay = model.overlay) {
        null -> {}
        Overlay.More -> MoreSheet(model)
        Overlay.Activity -> ActivitySheet(model)
        Overlay.NewFolder -> NameSheet(model, "New folder", "Create a folder in ${model.drive.trail.lastOrNull()?.name ?: "My Drive"}.", "", "Create", isFile = false) { model.drive.createFolder(it) }
        Overlay.SelectionMenu -> SelectionSheet(model)
        Overlay.DeleteAccount -> DeleteAccountSheet(model)
        is Overlay.ItemMenu -> ItemMenu(model, overlay.item)
        is Overlay.TrashMenu -> HSheet(close, overlay.item.name) {
            MenuRows {
                HMenuItem("Restore", Lucide.RotateCcw, { model.trash.restore(listOf(overlay.item)) }, enabled = !model.busy)
                HMenuItem("Delete permanently", Lucide.Trash, { model.trash.deletePermanently(listOf(overlay.item)) }, danger = true, enabled = !model.busy)
            }
        }
        is Overlay.Details -> DetailsSheet(model, overlay.item)
        is Overlay.Versions -> VersionsSheet(model, overlay.item)
        is Overlay.Rename -> NameSheet(model, "Rename ${overlay.item.name}", null, overlay.item.name, "Save", isFile = !overlay.item.isFolder) { model.drive.rename(overlay.item, it) }
        is Overlay.Move -> MoveSheet(model, overlay.items)
        is Overlay.Send -> RecipientSheet(model, "Send ${if (overlay.items.size == 1) overlay.items[0].name else "${overlay.items.size} items"}",
            "Send a copy to a person. They must sign in to receive it.", "Send", hint = "New recipients can verify their email and find the transfer after signup.") { to, _ ->
            model.drive.send(overlay.items, to)
        }
        is Overlay.ShareAccess -> RecipientSheet(model, "Share ${overlay.item.name}", "Give a registered person access to the original item.", "Share access", permissions = true) { to, permission ->
            model.drive.share(overlay.item, to, permission)
        }
        is Overlay.SyncSetupSheet -> SyncSetupSheet(model, overlay.kind)
        is Overlay.SyncFolderMenu -> SyncFolderMenu(model, overlay.root)
        is Overlay.SyncExclusions -> SyncExclusionsSheet(model, overlay.root)
        is Overlay.SyncShare -> SyncShareSheet(model, overlay.folderId, overlay.name)
        is Overlay.SyncIssues -> SyncIssuesSheet(model, overlay.root)
        is Overlay.PublicLink -> PublicLinkSheet(model, overlay.item)
        is Overlay.ZipEntry -> ZipEntrySheet(model, overlay.item)
        Overlay.BackupHistory -> BackupHistorySheet(model)
        Overlay.PhotoBackup -> PhotoBackupSheet(model)
        is Overlay.PhotoAlbums -> PhotoAlbumsSheet(model, overlay.root)
        is Overlay.Confirm -> {
            val c = overlay.confirmation
            HDialog(c.title, close, c.description, actions = {
                HButton(if (model.busy) "Working…" else c.label, { model.runConfirmation(c) }, Modifier.fillMaxWidth().testTag("confirm"), if (c.danger) Variant.Danger else Variant.Primary, enabled = !model.busy)
                HButton(c.cancel, close, Modifier.fillMaxWidth(), Variant.Outline, enabled = !model.busy)
            }) {
                c.note?.let { Text(it, style = Type.sm, color = theme.text2) }
                SheetError(model)
            }
        }
    }
    model.preview?.let { PreviewSheet(model, it) }
    model.streaming?.let { StreamSheet(model, it) }
}

@Composable private fun SheetError(model: WorkspaceModel) { model.error?.let { Alert(it, tone = Tone.Danger) } }
@Composable private fun MenuRows(content: @Composable ColumnScope.() -> Unit) = Column(Modifier.padding(horizontal = 0.dp), content = content)

@Composable private fun MoreSheet(model: WorkspaceModel) {
    val dark = theme.palette.dark
    HSheet({ model.overlay = null }, "More", divider = true) {
        Column {
            // While the `photos` flag is on, Photos has Shared's tab and Shared is listed here instead.
            (listOfNotNull(Triple(Section.Shared, Lucide.Users, "Files sent to you and by you, and links").takeIf { model.photos.enabled }) +
                listOf(Triple(Section.Devices, Lucide.Laptop, "Computers and phones signed in"), Triple(Section.Storage, Lucide.Cloud, "Usage and plan"),
                Triple(Section.Settings, Lucide.Settings, "Profile and appearance"))).forEach { (section, icon, hint) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button) { model.navigate(section) }.padding(horizontal = 4.dp, vertical = 8.dp).testTag("more-${section.name}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    IconBox(icon, active = model.section == section)
                    Column {
                        Text(section.name, style = Type.body.copy(fontSize = 16.sp))
                        Text(hint, style = Type.sm.copy(fontSize = 14.sp), color = theme.text2)
                    }
                }
                Divider()
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Theme", Modifier.weight(1f), style = Type.body.copy(fontSize = 16.sp))
            HIconButton(if (dark) Lucide.Sun else Lucide.Moon, if (dark) "Switch to light theme" else "Switch to dark theme", { model.toggleTheme(dark) })
        }
        StorageCard(model.storage, { model.navigate(Section.Storage) }, onTrash = { model.navigate(Section.Trash) })
    }
}

@Composable private fun ActivitySheet(model: WorkspaceModel) {
    LaunchedEffect(Unit) { model.markActivityRead() }
    HSheet({ model.overlay = null }, "Activity", "File activity, invitations, and required actions.", closeLabel = "Close activity", footer = {
        HButton("All notifications", { model.navigate(Section.Notifications) }, Modifier.fillMaxWidth(), Variant.Outline)
    }) {
        if (model.activity.isEmpty()) EmptyState(Lucide.Bell, "No activity yet", "File activity and transfer updates will appear here.", compact = true)
        model.activity.forEach { entry ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val (icon, tint) = when (entry.status) {
                    "error" -> Lucide.CircleAlert to theme.dangerText; "success" -> Lucide.CircleCheck to theme.successText
                    "progress" -> Lucide.LoaderCircle to theme.accentText; else -> Lucide.Clock to theme.text2
                }
                Icon(icon, null, Modifier.padding(top = 2.dp).size(17.dp), tint = tint)
                Column {
                    Text(entry.message, style = Type.sm.copy(fontSize = 14.sp))
                    Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(entry.time)), style = Type.xs, color = theme.text2)
                }
            }
        }
    }
}

@Composable private fun ItemMenu(model: WorkspaceModel, item: DriveItem) {
    val drive = model.drive
    val modify = drive.canModify(item)
    val share = drive.canShare(item)
    val sync = drive.isSyncFolder(item)
    val pickVersion = LocalVersionPicker.current
    fun go(action: () -> Unit) { model.overlay = null; action() }
    HSheet({ model.overlay = null }, item.name) {
        MenuRows {
            HMenuItem("Open", Lucide.ExternalLink, { go { if (model.section == Section.Drive) drive.open(item) else model.openResult(item) } })
            HMenuSeparator()
            // The server can't read an encrypted account's files, so it can't build their ZIP.
            if (!item.isFolder || !drive.encrypted) HMenuItem(if (item.isFolder) "Download as ZIP" else "Download", Lucide.Download, { go { drive.download(listOf(item)) } },
                enabled = !model.busy && model.zip == null)
            if (share && !drive.encrypted) {
                HMenuItem("Send", Lucide.Send, { drive.show("send", listOf(item)) })
                if (!sync) HMenuItem("Share access", Lucide.UserPlus, { drive.show("share", listOf(item)) })
            }
            if (drive.canLink(item)) HMenuItem(if (drive.isLinked(item)) "Manage public link" else "Get public link", Lucide.Link2, { model.shared.links.open(item) })
            if (drive.canEdit(item) && !item.isFolder) HMenuItem("Upload new version", Lucide.FileUp, { go { pickVersion(item) } }, enabled = !model.busy)
            if (modify || share) {
                HMenuSeparator()
                if (modify) HMenuItem("Rename", Lucide.Pencil, { drive.show("rename", listOf(item)) })
                if (modify && !sync) HMenuItem("Move", Lucide.FolderInput, { drive.show("move", listOf(item)) })
                if (share && !sync) HMenuItem(if (item.favorite) "Remove favorite" else "Add to favorites", Lucide.Star, { go { drive.favorite(listOf(item)) } })
            }
            HMenuSeparator()
            HMenuItem("View details", Lucide.Info, { drive.show("details", listOf(item)) })
            if (!item.isFolder) HMenuItem("Version history", Lucide.Clock, { drive.show("versions", listOf(item)) })
            if (drive.restorable(item)) HMenuItem(if (item.isFolder) "Restore folder" else "Restore", Lucide.RotateCcw, { drive.restoreBackup(item) }, enabled = !model.busy)
            val disconnect = drive.isBackupRoot(item)
            val removeSync = modify && sync
            val trash = drive.canTrash(item)
            if (disconnect || removeSync || trash) HMenuSeparator()
            if (disconnect) HMenuItem("Disconnect backup", Lucide.Archive, { drive.show("disconnect", listOf(item)) })
            if (removeSync) HMenuItem("Remove from sync", Lucide.X, { if (model.sync.isLocal(item)) model.sync.removeEverywhere(item.id, item.name) else drive.show("remove-sync", listOf(item)) }, danger = true)
            if (trash) HMenuItem("Move to trash", Lucide.Trash, { drive.show("trash", listOf(item)) }, danger = true)
        }
        if (drive.encrypted && share) UnsupportedNote(E2EE_LIMITS, Modifier.padding(horizontal = 4.dp))
    }
}

@Composable private fun SelectionSheet(model: WorkspaceModel) {
    val drive = model.drive
    val selection = drive.selection
    val modify = selection.all(drive::canModify)
    val share = selection.all(drive::canShare)
    HSheet({ model.overlay = null }, "${selection.size} selected") {
        MenuRows {
            if (selection.none(drive::isSyncFolder)) HMenuItem("Toggle favorites", Lucide.Star, { model.overlay = null; drive.favorite(selection) }, enabled = share && !model.busy)
            if (selection.size == 1) {
                HMenuItem("Rename", Lucide.Pencil, { drive.show("rename", selection) }, enabled = modify && !model.busy)
                HMenuItem("View details", Lucide.Info, { drive.show("details", selection) })
            }
            if (share && !drive.encrypted && selection.size == 1 && selection.none(drive::isSyncFolder)) HMenuItem("Share access", Lucide.UserPlus, { drive.show("share", selection) })
        }
    }
}

/** Name entry for new folders and renames. Renames select the name without its extension. */
@Composable private fun NameSheet(model: WorkspaceModel, title: String, description: String?, initial: String, label: String, isFile: Boolean, submit: (String) -> Unit) {
    val dot = if (isFile) initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length else initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, dot))) }
    val valid = runCatching { safeName(value.text.trim()) }.isSuccess && value.text.trim().length <= 240
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun done() { if (valid && !model.busy && value.text.trim() != initial) submit(value.text.trim()) }
    HDialog(title, { model.overlay = null }, description, actions = {
        HButton(if (model.busy) "Working…" else label, ::done, Modifier.fillMaxWidth().testTag("name-submit"), enabled = valid && !model.busy && value.text.trim() != initial)
        HButton("Cancel", { model.overlay = null }, Modifier.fillMaxWidth(), Variant.Outline)
    }) {
        Field("Name") {
            androidx.compose.foundation.text.BasicTextField(value, { value = it }, Modifier.fillMaxWidth().focusRequester(focus).testTag("folder-name"),
                textStyle = Type.body.copy(fontSize = 16.sp, color = theme.text), singleLine = true, cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { done() })) { inner ->
                Box(Modifier.height(44.dp).border(1.dp, theme.primary, RoundedCornerShape(Radius.md)).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.text.isEmpty()) Text(if (isFile) "Name" else "Folder name", style = Type.body, color = theme.text3)
                    inner()
                }
            }
        }
        SheetError(model)
    }
}

@Composable private fun RecipientSheet(model: WorkspaceModel, title: String, description: String, label: String, hint: String? = null, permissions: Boolean = false,
    submit: (String, String) -> Unit) {
    var recipient by remember { mutableStateOf("") }
    var permission by remember { mutableStateOf("VIEWER") }
    val valid = recipient.trim().removePrefix("@").length >= 3
    HDialog(title, { model.overlay = null }, description, actions = {
        HButton(if (model.busy) "Working…" else label, { submit(recipient, permission) }, Modifier.fillMaxWidth().testTag("recipient-submit"), enabled = valid && !model.busy)
        HButton("Cancel", { model.overlay = null }, Modifier.fillMaxWidth(), Variant.Outline)
    }) {
        Field("To", hint = hint) {
            HInput(recipient, { recipient = it }, Modifier.testTag("recipient"), placeholder = "@username or email address",
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email, autoCorrectEnabled = false))
        }
        if (permissions) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Permission", style = Type.label)
            Choice("Viewer", "Browse and download", permission == "VIEWER") { permission = "VIEWER" }
            Choice("Editor", "Also rename, move, and trash", permission == "EDITOR") { permission = "EDITOR" }
        }
        SheetError(model)
    }
}

@Composable private fun MoveSheet(model: WorkspaceModel, items: List<DriveItem>) {
    var trail by remember { mutableStateOf<List<DriveItem>>(emptyList()) }
    var folders by remember { mutableStateOf<List<DriveItem>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(trail) {
        folders = null; failure = null
        try { folders = model.drive.destinations(trail.lastOrNull()?.id, items) } catch (e: CancellationException) { throw e } catch (e: Exception) { failure = model.message(e) }
    }
    HSheet({ model.overlay = null }, "Move ${if (items.size == 1) items[0].name else "${items.size} items"}", tall = true, footer = {
        HButton(if (model.busy) "Working…" else "Move here", { model.drive.move(items, trail) }, Modifier.fillMaxWidth().testTag("move-here"), enabled = !model.busy && folders != null && failure == null)
        HButton("Cancel", { model.overlay = null }, Modifier.fillMaxWidth(), Variant.Outline)
    }) {
        Text("Destination: ${(listOf("My Drive") + trail.map { it.name }).last()}", style = Type.sm, color = theme.text2)
        Breadcrumbs(listOf("My Drive") + trail.map { it.name }) { trail = trail.take(it) }
        when {
            failure != null -> Alert(failure!!, tone = Tone.Danger)
            folders == null -> Text("Loading folders…", style = Type.sm, color = theme.text2)
            folders!!.isEmpty() -> EmptyState(Lucide.Folder, "No subfolders here", "You can use this folder as the destination, or choose a parent folder.", compact = true)
            else -> Column {
                folders!!.forEach { folder ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).clickable(role = Role.Button) { trail = trail + folder }.heightIn(min = 48.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Lucide.Folder, null, Modifier.size(18.dp), tint = theme.accentText)
                        Text(folder.name, Modifier.weight(1f), style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Lucide.ChevronRight, null, Modifier.size(16.dp), tint = theme.text3)
                    }
                }
            }
        }
        SheetError(model)
    }
}

@Composable private fun DetailsSheet(model: WorkspaceModel, item: DriveItem) {
    val location by produceState("Loading…", item.id) { value = runCatching { model.drive.locationPath(item) }.getOrElse { if (it is CancellationException) throw it; "Location unavailable" } }
    val sharing by produceState("Loading…", item.id) { value = runCatching { model.drive.sharing(item) }.getOrElse { if (it is CancellationException) throw it; "Sharing status unavailable" } }
    HSheet({ model.overlay = null }, "File details", "Information about this item.", footer = {
        HButton("Open", { model.overlay = null; if (model.section == Section.Drive) model.drive.open(item) else model.openResult(item) }, Modifier.fillMaxWidth(), Variant.Outline)
    }) {
        Text(item.name, style = Type.h3)
        if (item.uploadMismatch) Alert(UPLOAD_DAMAGED, tone = Tone.Warning)
        val sync = model.drive.syncBadge(item)?.first?.let { label -> model.drive.syncDetail(item)?.let { "$label — $it" } ?: label }
        DetailRows(listOfNotNull("Type" to fileKind(item), "Size" to if (item.isFolder) "—" else bytesLabel(item.sizeBytes), "Location" to location,
            "Created" to fullDate(item.createdAt), "Modified" to fullDate(item.updatedAt),
            "Owner" to if (item.ownerUserId == null || item.ownerUserId == model.user?.id) "You" else "Shared with you", "Sharing" to sharing,
            sync?.let { "Sync" to it }, if (model.drive.isLinked(item)) "Public link" to "Shared with a public link" else null))
    }
}

@Composable private fun VersionsSheet(model: WorkspaceModel, item: DriveItem) {
    var versions by remember { mutableStateOf<List<Version>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(item.id) { try { versions = model.drive.versions(item) } catch (e: CancellationException) { throw e } catch (e: Exception) { failure = model.message(e) } }
    HSheet({ model.overlay = null }, "Version history", tall = versions.orEmpty().size > 4) {
        SheetError(model)
        when {
            failure != null -> Alert(failure!!, tone = Tone.Danger)
            versions == null -> Text("Loading versions…", style = Type.sm, color = theme.text2)
            versions!!.isEmpty() -> EmptyState(Lucide.Clock, "No versions to show", "Saved versions will appear here when available.", compact = true)
            else -> versions!!.forEachIndexed { index, version ->
                if (index > 0) Divider()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Version ${version.versionNumber}", style = Type.h3)
                    Text("${fullDate(version.createdAt)} · ${bytesLabel(version.sizeBytes)}${if (version.cloudState == "RELEASED") " · Not stored in the cloud" else ""}", style = Type.sm, color = theme.text2)
                    if (version.uploadMismatch) DamagedNote()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HButton("Download", { model.drive.downloadVersion(item, version) }, variant = Variant.Outline, small = true, enabled = !model.busy && version.cloudState != "RELEASED")
                        if (model.drive.restorable(item)) HButton("Restore locally", { model.drive.restoreLocally(item, version) }, variant = Variant.Ghost, small = true, enabled = !model.busy)
                        else if (item.backupRootId == null && model.drive.access(item).edit && version.id != item.currentVersionId) HButton("Restore", { model.drive.restoreVersion(item, version) }, variant = Variant.Ghost, small = true,
                            enabled = !model.busy && version.cloudState != "RELEASED")
                    }
                }
            }
        }
    }
}

/** A version or file whose stored bytes did not match: it must be uploaded again. */
@Composable fun DamagedNote() {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Lucide.TriangleAlert, null, Modifier.size(14.dp), tint = theme.warningText)
        Text(UPLOAD_DAMAGED, style = Type.xs, color = theme.warningText)
    }
}

@Composable private fun DeleteAccountSheet(model: WorkspaceModel) {
    val email = model.user?.email.orEmpty()
    var typed by remember { mutableStateOf("") }
    val matches = typed.trim().equals(email, ignoreCase = true)
    HDialog("Delete your account?", { if (!model.busy) model.overlay = null },
        "This can’t be undone. You’ll be signed out on every device, and your data will be permanently deleted after 30 days.", actions = {
        HButton(if (model.busy) "Deleting…" else "Delete account", { model.deleteAccount(typed) }, Modifier.fillMaxWidth(), Variant.Danger, enabled = matches && !model.busy)
        HButton("Go back", { model.overlay = null }, Modifier.fillMaxWidth(), Variant.Outline, enabled = !model.busy)
    }) {
        SheetError(model)
        Field("Type $email to confirm") {
            HInput(typed, { typed = it }, Modifier.testTag("delete-email"), keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email, autoCorrectEnabled = false))
        }
    }
}

/** A video or audio file playing as it is read, with file information and saving. */
@Composable private fun StreamSheet(model: WorkspaceModel, preview: StreamPreview) {
    val item = preview.item
    val close = { model.streaming = null }
    HSheet(close, item.name, tall = true, closeLabel = "Close preview", footer = {
        HButton("Download", { model.downloadToDevice(item.name, buildJsonObject { put("driveItemId", item.id) }, preview.mime) },
            Modifier.fillMaxWidth(), icon = Lucide.ArrowDownToLine)
        HButton("Done", close, Modifier.fillMaxWidth(), Variant.Ghost)
    }) {
        val shape = RoundedCornerShape(Radius.lg)
        Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 440.dp).clip(shape).background(theme.fill1).border(1.dp, theme.line, shape), contentAlignment = Alignment.Center) {
            // Encrypted files are read a whole 4 MiB chunk at a time anyway; others in smaller blocks start sooner.
            val block = if (item.encrypted) E2ee.CHUNK_SIZE else 1024 * 1024
            MediaPreview(preview, item.name, previewKind(item.name, preview.mime) == "video") { it.setDataSource(StreamingMediaSource(preview.source, block)) }
        }
        SheetError(model)
        Text("File information", style = Type.h3)
        DetailRows(listOfNotNull("Type" to fileKind(item), "Size" to bytesLabel(preview.source.size),
            item.createdAt?.takeIf { it.isNotBlank() }?.let { "Created" to fullDate(it) }, item.updatedAt?.takeIf { it.isNotBlank() }?.let { "Modified" to fullDate(it) }))
    }
}

/** A verified local copy: an inline preview for images and text, file information, and open, share or save. */
@Composable private fun PreviewSheet(model: WorkspaceModel, preview: Preview) {
    val context = LocalContext.current
    val pickers = LocalPickers.current
    val mime = preview.mime
    fun launch(share: Boolean) {
        try {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", preview.file)
            val intent = if (share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri) else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri(preview.file.name, uri)
            context.startActivity(Intent.createChooser(intent, if (share) "Share file" else "Open file"))
        } catch (_: android.content.ActivityNotFoundException) { model.error = "No app can open this file. Use Download to keep a copy." }
    }
    val kind = previewKind(preview.item?.name ?: preview.file.name, mime)
    val image by produceState<android.graphics.Bitmap?>(null, preview.file) {
        if (kind == "image") value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(preview.file.path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                BitmapFactory.decodeFile(preview.file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
    }
    // Text is capped at 1 MB and refused when it holds binary data, as on the web.
    val text by produceState<Result<TextPreview>?>(null, preview.file) {
        if (kind == "text") value = withContext(Dispatchers.IO) { runCatching { readTextPreview(preview.file) } }
    }
    HSheet({ model.preview = null }, preview.file.name, tall = true, closeLabel = "Close preview", footer = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton("Open file", { launch(false) }, Modifier.weight(1f), Variant.Outline, icon = Lucide.ExternalLink)
            HButton("Share file", { launch(true) }, Modifier.weight(1f), Variant.Outline, icon = Lucide.Share2)
        }
        HButton("Download", { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) model.savePreview() else pickers.saveAs(preview.file) }, Modifier.fillMaxWidth(), icon = Lucide.ArrowDownToLine)
        HButton("Done", { model.preview = null }, Modifier.fillMaxWidth(), Variant.Ghost)
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Badge("Download verified", Tone.Success)
            Text(bytesLabel(preview.file.length()), style = Type.xs, color = theme.text2)
        }
        val shape = RoundedCornerShape(Radius.lg)
        Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = if (kind == "pdf") 540.dp else 440.dp).clip(shape).background(theme.fill1).border(1.dp, theme.line, shape), contentAlignment = Alignment.Center) {
            val loadedText = text
            when {
                image != null -> Image(image!!.asImageBitmap(), preview.file.name, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                kind == "pdf" -> PdfPreview(preview.file)
                kind == "video" || kind == "audio" -> MediaPreview(preview.file, kind == "video")
                kind == "text" && loadedText == null -> Text("Loading preview…", style = Type.sm, color = theme.text2)
                loadedText != null && loadedText.isSuccess -> TextPreviewView(loadedText.getOrThrow())
                loadedText != null -> Text(loadedText.exceptionOrNull()?.message?.takeIf { it.contains("binary data") } ?: "Could not load this preview. Try again.",
                    Modifier.padding(16.dp), style = Type.sm, color = theme.text2)
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FileTile(preview.item, 48.dp, mime = mime)
                    Text("No preview available. Open it in another app.", style = Type.sm, color = theme.text2)
                }
            }
        }
        SheetError(model)
        Text("File information", style = Type.h3)
        val item = preview.item
        DetailRows(listOfNotNull("Type" to (item?.let(::fileKind) ?: mime), "Size" to bytesLabel(preview.file.length()),
            item?.createdAt?.takeIf { it.isNotBlank() }?.let { "Created" to fullDate(it) }, item?.updatedAt?.takeIf { it.isNotBlank() }?.let { "Modified" to fullDate(it) }))
    }
}
