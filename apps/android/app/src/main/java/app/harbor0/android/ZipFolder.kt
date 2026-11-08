package app.harbor0.android

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

// A ZIP archive in the drive browsed like a folder (flag `zip-preview`), as apps/web/components/zip-folder.tsx and
// apps/web/lib/zip-drive.ts: read in place, never changed. Its listing comes from the central directory, so only the
// archive's last few kilobytes are read; previews, downloads and copies read only the files they need.

/** An archive open in My Drive, from the folder that holds it. */
class ArchiveState(private val m: WorkspaceModel) {
    val enabled get() = m.flag("zip-preview")
    /** The archive open now, if any. */
    var item by mutableStateOf<DriveItem?>(null); private set
    /** The folder open inside the archive; "" is its top level. */
    var path by mutableStateOf(""); private set
    var tree by mutableStateOf<ZipTree?>(null); private set
    var loadError by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var selected by mutableStateOf<Set<String>>(emptySet())
    /** Files unpacked so far while copying, and how many there are. */
    var copying by mutableStateOf<Pair<Int, Int>?>(null); private set
    private var source: RangeSource? = null
    // One listing per archive version; moving around inside it reads nothing new. A new version shows on the next visit.
    private var cached: Triple<String, ZipTree, RangeSource>? = null
    private var cachedAt = 0L
    private var job: Job? = null
    private var copyJob: Job? = null

    val open get() = item != null
    fun reset() { close(); cached = null }
    fun close() { job?.cancel(); copyJob?.cancel(); item = null; path = ""; tree = null; source = null; loadError = null; error = null; notice = null; selected = emptySet(); copying = null }
    /** Opens an archive like a folder. */
    fun open(archive: DriveItem) {
        close()
        item = archive
        load()
    }
    fun load() {
        val archive = item ?: return
        job?.cancel()
        loadError = null
        val key = "${archive.id}:${archive.currentVersionId}"
        cached?.takeIf { it.first == key && System.currentTimeMillis() - cachedAt < 10 * 60_000 }?.let { tree = it.second; source = it.third; return }
        tree = null
        job = m.viewModelScope.launch {
            try {
                val opened = m.transfers.openRange(archive.id)
                val listed = withContext(Dispatchers.IO) { zipTree(readZipDirectory(opened)) }
                source = opened; tree = listed
                cached = Triple(key, listed, opened); cachedAt = System.currentTimeMillis()
            } catch (e: CancellationException) { throw e }
            catch (e: ZipException) { loadError = e.message }
            catch (e: E2eeException) { loadError = e.message }
            catch (e: Exception) { loadError = if (e is java.io.IOException) "Could not read this archive. Check your connection and try again." else m.message(e) }
        }
    }
    val folderMissing get() = tree?.let { path.isNotEmpty() && path !in it.folders } == true
    /** What the open folder lists, folders first by name, as the web's `driveView` with name-asc. */
    val items: List<DriveItem> get() {
        val listed = tree ?: return emptyList()
        val archive = item ?: return emptyList()
        if (folderMissing) return emptyList()
        return driveView(listZipFolder(listed, path, archive), DriveFilters(sort = "name-asc", showSystem = true))
    }
    val selection get() = items.filter { it.id in selected }
    val title get() = if (path.isNotEmpty()) path.substringAfterLast('/') else item?.name ?: "Archive"
    /** Where copies go: the folder that holds the archive, or My Drive where it can't take files (backups, places, shares). */
    private val destinationId get() = if (m.drive.canWriteHere) m.drive.folderId else null
    val destination get() = if (m.drive.canWriteHere) m.drive.trail.lastOrNull()?.name ?: "My Drive" else "My Drive"
    /** The path's crumbs after My Drive's: the archive, then its folders. */
    val crumbs get() = m.drive.crumbs + listOf(item?.name ?: "Archive") + path.split('/').filter { it.isNotEmpty() }
    fun openCrumb(depth: Int) {
        val driveDepth = m.drive.crumbs.size
        if (depth < driveDepth) { close(); m.drive.openCrumb(depth); return }
        enter(path.split('/').filter { it.isNotEmpty() }.take(depth - driveDepth).joinToString("/"))
    }
    fun enter(next: String) { path = next; selected = emptySet(); error = null }
    /** Back: up a folder inside the archive, then out of it. */
    fun back() { if (path.isNotEmpty()) enter(path.substringBeforeLast('/', "")) else close() }
    fun toggle(entry: DriveItem) { selected = if (entry.id in selected) selected - entry.id else selected + entry.id }

    private fun fileOf(entry: DriveItem) = tree?.byPath?.get(zipEntryPath(entry.id))
    fun openEntry(entry: DriveItem) { if (entry.isFolder) enter(zipEntryPath(entry.id)) else preview(entry) }
    /** Unpacks one file to a private copy, with the transfer card's progress and Cancel. */
    private suspend fun unpack(file: ZipFile, limit: Long = Long.MAX_VALUE, label: String): File {
        val from = source ?: error("Open the archive again.")
        val folder = File(m.previews, UUID.randomUUID().toString()).apply { check(mkdirs()) }
        val out = File(folder, safeName(file.name))
        try {
            withContext(Dispatchers.IO) {
                out.outputStream().buffered().use { stream ->
                    extractZipFile(from, file, stream, limit) { n -> m.showProgress("$label ${file.name}…", if (file.size == 0L) null else n.toFloat() / minOf(file.size, limit)) }
                }
            }
        } catch (e: Exception) { folder.deleteRecursively(); throw e }
        return out
    }
    /** Shows a file from the archive in the preview sheet. */
    fun preview(entry: DriveItem) {
        val file = fileOf(entry) ?: return
        unreadable(file)?.let { error = it; return }
        val text = previewKind(entry.name, entry.mimeType) == "text"
        if (!text && file.size > ZIP_PREVIEW_MAX) { error = "This file is too large to preview from inside an archive. Download it to open it."; return }
        error = null
        m.startTransfer {
            // Text previews read only their first 1 MB (and a byte, to know there is more).
            val out = unpack(file, if (text) TEXT_PREVIEW_LIMIT + 1L else Long.MAX_VALUE, "Opening")
            m.preview = Preview(out, entry.mimeType ?: guessMime(entry.name))
        }
    }
    /** Saves one file from the archive to Downloads under its own name. */
    fun download(entry: DriveItem) {
        val file = fileOf(entry) ?: return
        unreadable(file)?.let { error = it; return }
        error = null
        m.startTransfer { m.save(unpack(file, label = "Unpacking"), entry.mimeType ?: guessMime(entry.name)) }
    }
    /** Unpacks files and folders and uploads them to [destination], laid out as they are below the open folder. */
    fun copy(entries: List<DriveItem>) {
        val listed = tree ?: return
        val from = source ?: return
        if (copying != null) return
        val plan = copyPlan(listed, entries.map { zipEntryPath(it.id) }, path)
        if (plan.files.isEmpty()) {
            error = if (plan.skipped > 0) "These files are password-protected or compressed in a way harbor0 can’t unpack. Download the archive to open them."
                else "There are no files to copy here."
            return
        }
        if (plan.size > ZIP_COPY_MAX) {
            error = "That’s ${bytesLabel(plan.size)} unpacked, more than the ${bytesLabel(ZIP_COPY_MAX)} that can be copied out at once. Copy fewer files, or download the archive and upload what you need."
            return
        }
        error = null; notice = null
        val parent = destinationId
        val name = destination
        copying = 0 to plan.files.size
        copyJob = m.viewModelScope.launch {
            val folder = File(m.context.cacheDir, "zip-copies/${UUID.randomUUID()}")
            val picked = mutableListOf<PickedFile>()
            try {
                for ((index, file) in plan.files.withIndex()) {
                    val (folders, fileName) = plan.layout(file)
                    val out = File(folder, "$index/${safeName(fileName)}")
                    withContext(Dispatchers.IO) {
                        check(out.parentFile!!.mkdirs() || out.parentFile!!.isDirectory)
                        out.outputStream().buffered().use { extractZipFile(from, file, it) }
                    }
                    picked += PickedFile(Uri.fromFile(out), fileName, file.size, guessMime(fileName), folders, temporary = out)
                    copying = index + 1 to plan.files.size
                }
                m.uploads.addCopies(picked, parent)
                selected = emptySet()
                notice = "Copying ${plural(plan.files.size, "file")} to $name." + if (plan.skipped > 0)
                    " ${plan.skipped} password-protected or unsupported ${if (plan.skipped == 1) "file was" else "files were"} left out." else ""
            } catch (e: CancellationException) { withContext(NonCancellable + Dispatchers.IO) { folder.deleteRecursively() }; throw e }
            catch (e: Exception) {
                withContext(Dispatchers.IO) { folder.deleteRecursively() }
                error = if (e is ZipException) e.message else m.message(e).takeIf { e is java.io.IOException } ?: "Could not unpack these files. Try again."
            } finally { copying = null }
        }
    }
    fun downloadArchive() { item?.let { m.drive.download(listOf(it)) } }
}

/** The archive open in My Drive: its folders and files, read-only. */
@Composable fun ZipFolderScreen(model: WorkspaceModel) {
    val archive = model.archive
    val tree = archive.tree
    val items = archive.items
    val selection = archive.selection
    val t = theme
    LazyColumn(Modifier.fillMaxSize().testTag("zip-list"), state = remember(archive.item?.id, archive.path) { androidx.compose.foundation.lazy.LazyListState() },
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 104.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PageTitle(archive.title, Modifier.weight(1f, fill = false))
                Badge("Read-only archive")
            }
            Breadcrumbs(archive.crumbs) { archive.openCrumb(it) }
            if (tree != null) Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HButton("${if (archive.path.isNotEmpty()) "Copy folder contents" else "Copy all"} to ${archive.destination}", { archive.copy(items) },
                    Modifier.fillMaxWidth().testTag("zip-copy-all"), icon = Lucide.CopyPlus, enabled = archive.copying == null && items.isNotEmpty())
                HButton("Download archive", archive::downloadArchive, Modifier.fillMaxWidth(), Variant.Outline, icon = Lucide.FileArchive,
                    enabled = model.transfer == null)
            }
            archive.error?.let { Alert(it, Modifier.padding(top = 12.dp), Tone.Danger, "Dismiss") { archive.error = null } }
            archive.notice?.let { Alert(it, Modifier.padding(top = 12.dp), Tone.Success, "Dismiss") { archive.notice = null } }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selection.isNotEmpty()) {
                    Text("${selection.size} selected", Modifier.weight(1f), style = Type.label.copy(fontSize = 15.sp, fontWeight = FontWeight(600)))
                    HIconButton(Lucide.X, "Clear selection", { archive.selected = emptySet() })
                } else {
                    val copying = archive.copying
                    Text(when {
                        copying != null -> "Unpacking ${copying.first} of ${plural(copying.second, "file")}…"
                        tree == null && archive.loadError == null -> "Reading archive…"
                        tree != null -> "${items.size} ${if (items.size == 1) "item" else "items"}"
                        else -> ""
                    }, Modifier.weight(1f), style = Type.body, color = t.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ViewSwitch(model.drive.grid, model::setGrid)
                }
            }
        }
        when {
            archive.loadError != null -> item {
                EmptyState(Lucide.FileArchive, "Can’t open this archive", archive.loadError.orEmpty()) {
                    HButton("Try again", archive::load, variant = Variant.Outline, small = true, icon = Lucide.RefreshCw)
                }
            }
            tree == null -> item { SkeletonRows(model.drive.grid) }
            archive.folderMissing -> item {
                EmptyState(Lucide.Folder, "This folder isn’t in the archive", "The archive may have changed since you opened it.") {
                    HButton("Open the archive", { archive.enter("") }, variant = Variant.Outline, small = true)
                }
            }
            items.isEmpty() -> item { EmptyState(Lucide.Folder, "This folder is empty", "There are no files here in the archive.") }
            else -> fileList(items, model.drive.grid, archive.selected, onSelect = { archive.selected = it }, onOpen = archive::openEntry,
                onMenu = { model.overlay = Overlay.ZipEntry(it) }, size = { if (it.isFolder) bytesLabel(it.sizeBytes) else null })
        }
    }
}

/** The selection dock inside an archive: Download (one file) and Copy. */
@Composable fun ZipFloating(model: WorkspaceModel) {
    val archive = model.archive
    val selection = archive.selection
    if (selection.isEmpty()) return
    SelectionDock {
        if (selection.size == 1 && !selection[0].isFolder) DockButton(Lucide.Download, "Download", { archive.download(selection[0]) }, model.transfer == null)
        DockButton(Lucide.CopyPlus, "Copy to ${archive.destination}", { archive.copy(selection) }, archive.copying == null)
    }
}

/** A file's or folder's actions inside an archive. */
@Composable fun ZipEntrySheet(model: WorkspaceModel, entry: DriveItem) {
    val archive = model.archive
    fun go(action: () -> Unit) { model.overlay = null; action() }
    HSheet({ model.overlay = null }, entry.name) {
        Column {
            HMenuItem(if (entry.isFolder) "Open" else "Preview", Lucide.ExternalLink, { go { archive.openEntry(entry) } })
            HMenuSeparator()
            if (!entry.isFolder) HMenuItem("Download", Lucide.Download, { go { archive.download(entry) } }, enabled = model.transfer == null)
            HMenuItem("Copy to ${archive.destination}", Lucide.CopyPlus, { go { archive.copy(listOf(entry)) } }, enabled = archive.copying == null)
        }
    }
}
