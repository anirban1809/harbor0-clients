package app.harbor0.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException

/** Android's page for harbor0's permissions, for access refused with “Don’t ask again”. */
fun openAppSettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** “Back up photos”, shown wherever “Back up a folder” is until this phone backs its photos up. */
@Composable fun BackUpPhotosButton(model: WorkspaceModel, modifier: Modifier = Modifier, variant: Variant = Variant.Outline) {
    val sync = model.sync
    if (sync.view != null && sync.photoRoot != null) return
    HButton("Back up photos", { sync.setupPhotos() }, modifier.testTag("backup-photos"), variant, small = true, icon = Lucide.Images, enabled = sync.view != null && !model.busy)
}

@Composable private fun Option(title: String, hint: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HCheckbox(checked, onChange, title, Modifier.testTag(tag), enabled = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Type.label.copy(fontSize = 14.sp))
            Text(hint, style = Type.xs, color = theme.text2)
        }
    }
}
/** Setup: what is saved where, the two options, then Android's permission request. */
@Composable fun PhotoBackupSheet(model: WorkspaceModel) {
    val sync = model.sync
    val pickers = LocalPickers.current
    val context = model.context
    // Not everyone has Wi-Fi: mobile data is the starting choice, asked for here rather than left in a checkbox.
    var options by remember { mutableStateOf(PhotoOptions(mobileData = true)) }
    var denied by remember { mutableStateOf(false) }
    val phone = sync.phoneName
    HSheet({ model.overlay = null }, "Back up photos", "Photos and videos from this phone are saved to Backups › $phone › $PHOTOS_NAME, one folder per album. They stay there even if you delete them from the phone.",
        footer = {
            HButton(if (model.busy) "Starting…" else "Start backup", {
                pickers.photoAccess { granted ->
                    denied = !granted
                    // Asked right away: on most phones backups otherwise stop soon after harbor0 is closed.
                    if (granted) { sync.backUpPhotos(options); if (sync.batteryOptimized) pickers.allowBackground() }
                }
            }, Modifier.fillMaxWidth().testTag("photos-start"), enabled = sync.view != null && !model.busy)
            HButton("Cancel", { model.overlay = null }, Modifier.fillMaxWidth(), Variant.Outline, enabled = !model.busy)
        }) {
        Text("Back up on", style = Type.label.copy(fontSize = 15.sp))
        Column(Modifier.testTag("photos-network"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Wi-Fi and mobile data", "Photos are saved wherever you are. Uses your data plan.", options.mobileData) { options = options.copy(mobileData = true) }
            Choice("Wi-Fi only", "Photos wait until this phone is on Wi-Fi.", !options.mobileData) { options = options.copy(mobileData = false) }
        }
        Option("Include videos", "Back up videos as well as photos.${if (options.mobileData) " Videos can use a lot of mobile data." else ""}", options.videos, !model.busy, "photos-videos") {
            options = options.copy(videos = it)
        }
        Text("You can change these later in the backup’s settings.", style = Type.xs, color = theme.text2)
        Text("New photos are saved within a few minutes of being taken, while harbor0 is open and in the background when Android allows. Nothing on this phone is changed or deleted.",
            style = Type.xs, color = theme.text2)
        if (denied) Alert("harbor0 needs access to your photos and videos to back them up. Allow it in Android settings, under Permissions.", tone = Tone.Warning,
            action = "Open settings") { openAppSettings(context) }
        model.error?.let { Alert(it, tone = Tone.Danger) }
    }
}

/** Which albums are backed up: everything else is in the folder's exclusions. */
@Composable fun PhotoAlbumsSheet(model: WorkspaceModel, root: SyncRoot) {
    val sync = model.sync
    var albums by remember { mutableStateOf<List<String>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var excluded by remember { mutableStateOf(root.excluded.toSet()) }
    LaunchedEffect(root.id) {
        try { albums = sync.photoAlbums(root) } catch (e: CancellationException) { throw e } catch (e: Exception) { failure = model.message(e) }
    }
    // Albums left out earlier stay listed after they empty, so they can be included again.
    val shown = (albums.orEmpty() + root.excluded.filter { '/' !in it }).distinct().sortedWith { a, b -> naturalCompare(a, b) }
    HDialog("Albums to back up", { model.overlay = null }, "Photos in albums you turn off aren’t backed up. Photos already saved from them stay in the backup.", actions = {
        HButton(if (model.busy) "Saving…" else "Save albums", { sync.saveAlbums(root, excluded.toList()) }, Modifier.fillMaxWidth().testTag("photos-albums-save"),
            enabled = albums != null && !model.busy)
        HButton("Cancel", { model.overlay = null }, Modifier.fillMaxWidth(), Variant.Outline)
    }) {
        when {
            failure != null -> Alert(failure!!, tone = Tone.Danger)
            albums == null -> Text("Loading albums…", style = Type.sm, color = theme.text2)
            shown.isEmpty() -> Text("No photos on this phone yet.", style = Type.sm, color = theme.text2)
            else -> shown.forEach { album ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HCheckbox(album !in excluded, { on -> excluded = if (on) excluded - album else excluded + album }, album)
                    Text(album, Modifier.weight(1f), style = Type.body)
                }
            }
        }
        model.error?.let { Alert(it, tone = Tone.Danger) }
    }
}

/** The photo library's access, when harbor0 can't see all of it, with the way to fix it. */
@Composable fun PhotoAccessAlert(model: WorkspaceModel) {
    val pickers = LocalPickers.current
    val context = model.context
    var denied by remember { mutableStateOf(false) }
    fun ask() = pickers.photoAccess { granted -> denied = !granted }
    when (model.sync.photoAccess) {
        MediaAccess.NONE -> Alert(PHOTOS_ACCESS, Modifier.testTag("photos-access"), Tone.Danger, if (denied) "Open settings" else "Allow access") { if (denied) openAppSettings(context) else ask() }
        MediaAccess.PARTIAL -> Alert("harbor0 can see only some of your photos. Only those are backed up.", Modifier.testTag("photos-access"), Tone.Warning,
            if (denied) "Open settings" else "Choose more") { if (denied) openAppSettings(context) else ask() }
        MediaAccess.FULL -> {}
    }
}
