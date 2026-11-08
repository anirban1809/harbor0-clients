package app.harbor0.android

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun statusIcon(tone: Tone, label: String): ImageVector = when {
    label == "Archived" -> Lucide.Archive
    label == "Paused" -> Lucide.Pause
    label == "Stopped" -> Lucide.X
    tone == Tone.Success -> Lucide.CircleCheck
    tone == Tone.Accent -> Lucide.LoaderCircle
    else -> Lucide.CircleAlert
}
/** A backup status pill with its icon, as the web `StatusBadge`. */
@Composable fun StatusPill(label: String, tone: Tone) {
    val t = theme
    val (fill, ink) = when (tone) { Tone.Success -> t.successSoft to t.successText; Tone.Accent -> t.accentSoft to t.accentText; Tone.Danger -> t.dangerSoft to t.dangerText; else -> t.fill2 to t.text2 }
    Row(Modifier.clip(RoundedCornerShape(50)).background(fill).padding(horizontal = 9.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        val icon = statusIcon(tone, label)
        // Work under way turns, as the web badge's spinner does.
        val turn = if (icon == Lucide.LoaderCircle) rememberInfiniteTransition(label = "pill").animateFloat(0f, 360f,
            infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "turn").value else 0f
        Icon(icon, null, Modifier.size(13.dp).graphicsLayer { rotationZ = turn }, tint = ink)
        Text(label, style = Type.xs.copy(fontSize = 13.sp, fontWeight = FontWeight(500)), color = ink, maxLines = 1)
    }
}

/**
 * The status and controls of a backed-up or archived folder, shown above its files in My Drive, as the
 * web `BackupFolderPanel`: back up now, pause, archive or stop on this phone, plus the history of
 * backup runs and restores.
 */
@Composable fun BackupFolderPanel(model: WorkspaceModel, modifier: Modifier = Modifier) {
    val backups = model.backups
    val root = backups.root ?: return
    val status = backups.status() ?: return
    val local = backups.local
    val archived = backups.archived
    val photos = local?.isPhotos == true
    // The file on its way, under the run's own progress: one bar for the whole backup.
    val sending = local?.let { l -> model.sync.runtime.active?.takeIf { it.rootId == l.id } }
    Card(modifier.testTag("backup-panel")) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBox(if (photos) Lucide.Images else if (archived) Place.Archives.icon else Place.Backups.icon, 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // The pill goes under the title when both don't fit ("Backup paused · storage full"), rather than cutting the title short.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(if (photos) "Photo backup" else if (archived) "Archived backup" else "Backup", style = Type.h2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    StatusPill(status.label, status.tone)
                }
                Text(if (photos) status.detail else "${if (local != null) "This phone" else root.deviceName ?: "Another computer"} · ${status.detail}", style = Type.sm, color = theme.text2)
                status.fraction?.let { Progress(it, height = 4.dp) }
                sending?.takeIf { status.fraction != null }?.let {
                    Text(baseName(it.relativePath), Modifier.testTag("backup-sending"), style = Type.xs, color = theme.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            backups.loadError?.let { Alert(it, tone = Tone.Danger, action = "Try again") { backups.refresh() } }
            when {
                backups.removed -> HButton("Remove from Backups", backups::remove, Modifier.fillMaxWidth(), Variant.Outline, icon = Lucide.Trash, enabled = !model.busy)
                local != null -> LocalBackupControls(model, local)
                else -> {
                    Text(if (archived) "To restore this folder, open the desktop app on ${backups.deviceName}."
                        else "To back up now, pause or archive, open the desktop app on ${backups.deviceName}.", style = Type.xs, color = theme.text2)
                    HButton("Stop backing up", backups::stop, Modifier.fillMaxWidth(), Variant.DangerGhost, enabled = !model.busy)
                }
            }
            if (local == null || backups.removed) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HButton("History", { model.error = null; model.overlay = Overlay.BackupHistory }, Modifier.testTag("backup-history"), Variant.Ghost, small = true, icon = Lucide.RotateCcwClock)
                if (backups.waiting > 0) Badge("${backups.waiting} waiting", Tone.Accent)
            }
            else if (backups.waiting > 0) Text("${plural(backups.waiting, "restore")} waiting. See History.", style = Type.xs, color = theme.text2)
        }
    }
}

/** Every backup run and file restore for the open backup folder. */
@Composable fun BackupHistorySheet(model: WorkspaceModel) {
    val backups = model.backups
    val local = backups.local
    HSheet({ model.overlay = null }, "${backups.name} history", "Every backup run and file restore for this folder.", tall = true, closeLabel = "Close history") {
        model.error?.let { Alert(it, tone = Tone.Danger) }
        val restores = if (backups.historyLoading) emptyList() else backups.restores
        if (restores.isNotEmpty()) {
            Text("Restores", style = Type.h3)
            restores.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Lucide.RotateCcw, null, Modifier.padding(top = 2.dp).size(18.dp), tint = theme.text2)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(if (r.folder) "${r.relativePath.ifEmpty { backups.name }} · whole folder" else r.relativePath, style = Type.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("Requested ${dateLabel(r.requestedAt, time = true)}${r.completedAt?.let { " · Finished ${dateLabel(it, time = true)}" } ?: ""}", style = Type.xs, color = theme.text2)
                        if (r.state == "PENDING") Text(when {
                            local == null -> "Will be restored the next time ${backups.deviceName} is online."
                            local.paused -> "Resume backups to finish this restore."
                            else -> "Will be restored in a moment."
                        }, style = Type.xs, color = theme.text2)
                        r.error?.let { Text(it, style = Type.xs, color = theme.dangerText) }
                    }
                    StatusPill(when (r.state) { "PENDING" -> "Waiting"; "COMPLETED" -> "Restored"; else -> "Failed" },
                        when (r.state) { "PENDING" -> Tone.Accent; "FAILED" -> Tone.Danger; else -> Tone.Success })
                }
            }
        }
        Text("Backups", style = Type.h3)
        val runs = if (backups.historyLoading) null else backups.runs
        if (runs.isNullOrEmpty()) Text(if (runs == null) "Loading history…" else "No backups have run yet.", style = Type.sm, color = theme.text2)
        else runs.forEach { run ->
            val shape = RoundedCornerShape(Radius.lg)
            Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, theme.line, shape)) {
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { backups.toggleRun(run) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Lucide.Archive, null, Modifier.size(18.dp), tint = theme.text2)
                    Column(Modifier.weight(1f)) {
                        Text(dateLabel(run.startedAt, time = true), style = Type.name)
                        Text("${if (run.trigger == "MANUAL") "Backed up manually" else "Automatic backup"} · ${plural(run.fileCount, "file")} saved · ${bytesLabel(run.sizeBytes)}",
                            style = Type.xs, color = theme.text2)
                    }
                    StatusPill(when (run.state) { "RUNNING" -> "In progress"; "COMPLETED" -> "Done"; "PARTIAL" -> "Some files skipped"; else -> "Failed" },
                        when (run.state) { "RUNNING" -> Tone.Accent; "COMPLETED" -> Tone.Success; else -> Tone.Danger })
                }
                if (backups.expandedRun == run.id) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    run.error?.let { Alert(it, tone = Tone.Danger) }
                    val entries = backups.runFiles
                    when {
                        entries == null -> Text("Loading files…", style = Type.sm, color = theme.text2)
                        entries.isEmpty() -> Text("No files were saved in this backup.", style = Type.sm, color = theme.text2)
                        else -> entries.forEach { entry ->
                            Divider()
                            Text(entry.relativePath, style = Type.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${bytesLabel(entry.sizeBytes)} · Edited ${dateLabel(entry.modifiedAt, time = true)}", style = Type.xs, color = theme.text2)
                            VersionActions(model, entry.itemId, entry.versionId, entry.relativePath.substringAfterLast('/'), entry.savedAt)
                        }
                    }
                }
            }
        }
    }
}

@Composable fun VersionActions(model: WorkspaceModel, itemId: String, versionId: String, name: String, createdAt: String) {
    val backups = model.backups
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!backups.removed && !backups.archived) HButton("Restore", { backups.restore(itemId, versionId, name, createdAt) }, variant = Variant.Outline, small = true,
            icon = Lucide.RotateCcw, enabled = !model.busy)
        HButton("Download", { backups.download(itemId, versionId, name) }, variant = Variant.Ghost, small = true, icon = Lucide.Download, enabled = !model.busy)
    }
}

/**
 * Controls for a backup folder on this phone: a note when there is something to say, Back up now and Pause, and a
 * menu with history, albums or exclusions, the photo options, archive and disconnect.
 */
@Composable private fun LocalBackupControls(model: WorkspaceModel, root: SyncRoot) {
    val sync = model.sync
    val queued = sync.jobs.count { it.rootId == root.id }
    val failed = sync.jobs.count { it.rootId == root.id && it.error != null }
    val active = sync.runtime.active?.takeIf { it.rootId == root.id }
    val issues = sync.runtime.issues.filter { it.rootId == root.id }
    val paused = sync.runtime.paused || root.paused
    val photos = root.isPhotos
    val options = root.photos ?: PhotoOptions()
    val wifi = photos && queued > 0 && root.id in sync.runtime.waitingForWifi
    // A run under way: Back up now would only be refused, and files failed before are being tried again.
    val running = active != null || sync.runtime.backupRuns.containsKey(root.id)
    // Nothing fits in the storage left: Back up now could only say so again (the status above already does).
    val full = backupStorageLabel(root, sync.runtime)?.first == BACKUP_STORAGE_FULL
    // The status above already says "Backed up" or shows the run: these only add what it can't.
    val note = when (root.archive) {
        "pending" -> "Archiving: making a final backup before removing the local files."
        "removing" -> "Archiving: removing local files whose content is saved…"
        "archived" -> "Archived. The local files were removed from this phone; everything is kept here."
        "restoring" -> "Restoring this folder to this phone…"
        else -> when {
            paused -> "Paused on this phone. No new versions are saved until you resume."
            running || wifi -> null
            photos && queued > 0 -> "${plural(queued, "photo")} waiting to be saved."
            photos -> null
            queued > 0 -> "${plural(queued, "file")} waiting. Files are saved after they have been unchanged for ${backupWaitLabel(sync.backupChoices.waitMinutes)}, or right away with Back up now."
            else -> "Files are saved about ${backupWaitLabel(sync.backupChoices.waitMinutes)} after they last change."
        }
    }
    note?.let { Text(it, style = Type.xs, color = theme.text2) }
    if (wifi && !paused) Alert("${plural(queued, "photo")} waiting for Wi-Fi.", Modifier.testTag("photos-wifi"), Tone.Neutral, "Use mobile data") {
        sync.photoOptions(root, options.copy(mobileData = true))
    }
    root.archiveError?.let { Alert("Archive stopped: $it", tone = Tone.Danger) }
    // Battery settings that stop the app in the background also stop the "Backing up" service.
    if (!paused && root.archive == null) BackgroundRestrictedAlert(model)
    if (failed > 0 && !running) Alert("${plural(failed, "file")} could not be backed up yet. ${sync.jobs.firstOrNull { it.rootId == root.id && it.error != null }?.error ?: ""}".trim(), tone = Tone.Warning)
    // The photo library's access has its own fix.
    if (photos) PhotoAccessAlert(model)
    issues.firstOrNull { it.scope == null && !(photos && it.code == IssueCode.PERMISSION_DENIED) }?.let { issue ->
        Alert(issueText(issue), tone = Tone.Danger, action = if (!photos && issue.code in listOf(IssueCode.FOLDER_MISSING, IssueCode.PERMISSION_DENIED)) "Locate folder" else null) {
            sync.setup(SyncSetup.ChangeLocal(root))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when (root.archive) {
            "archived" -> HButton("Restore archived folder", { sync.restoreArchive(root) }, Modifier.weight(1f), icon = Lucide.ArchiveRestore, enabled = !model.busy && !paused)
            null -> {
                HButton("Back up now", { sync.backupNow(root) }, Modifier.weight(1f).testTag("backup-now"), small = true, icon = Lucide.CloudUpload, enabled = !model.busy && !paused && !running && !full)
                HButton(if (root.paused) "Resume" else "Pause", { sync.togglePause(root) }, Modifier.weight(1f), Variant.Outline, small = true,
                    icon = if (root.paused) Lucide.Play else Lucide.Pause, enabled = !model.busy)
            }
            else -> Spacer(Modifier.weight(1f))
        }
        var open by remember { mutableStateOf(false) }
        Box {
            HIconButton(Lucide.Ellipsis, "More backup options", { open = true }, Modifier.testTag("backup-menu"))
            HMenu(open, { open = false }) {
                fun go(action: () -> Unit) { open = false; model.error = null; action() }
                HMenuItem("History", Lucide.RotateCcwClock, { go { model.overlay = Overlay.BackupHistory } }, Modifier.testTag("backup-history"))
                if (root.archive == null) {
                    if (photos) HMenuItem("Albums", Lucide.Images, { go { model.overlay = Overlay.PhotoAlbums(root) } }, Modifier.testTag("photos-albums"))
                    else HMenuItem("Exclusions", Lucide.EyeOff, { go { model.overlay = Overlay.SyncExclusions(root) } })
                }
                if (photos) {
                    HMenuSeparator()
                    HMenuItem("Include videos", Lucide.Film, { go { sync.photoOptions(root, options.copy(videos = !options.videos)) } }, Modifier.testTag("photos-videos"),
                        enabled = !model.busy, checked = options.videos)
                    HMenuItem("Back up over mobile data", Lucide.Smartphone, { go { sync.photoOptions(root, options.copy(mobileData = !options.mobileData)) } },
                        Modifier.testTag("photos-mobile-data"), enabled = !model.busy, checked = options.mobileData)
                }
                HMenuSeparator()
                // Archiving removes local files; harbor0 never deletes photos on the phone.
                if (!photos && root.archive == null) HMenuItem("Archive folder", Lucide.Archive, { go { sync.archive(root) } }, enabled = !model.busy && !paused)
                HMenuItem("Disconnect backup", Lucide.Unplug, { go { sync.disconnectBackup(root) } }, danger = true, enabled = !model.busy)
            }
        }
    }
}
