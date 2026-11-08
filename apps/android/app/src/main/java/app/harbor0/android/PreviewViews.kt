package app.harbor0.android

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

// Inline previews for PDFs (PdfRenderer), video and audio (MediaPlayer) and long text, in the preview sheets.

const val PREVIEW_FAILED = "This file could not be displayed. Its format may not be supported. Open it in another app or download the file."

/** An open PDF whose pages render one at a time; closing waits for a page being drawn. */
class PdfPages(file: File): Closeable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(descriptor) } catch (e: Exception) { descriptor.close(); throw e }
    private var closed = false
    val count = renderer.pageCount
    fun render(index: Int, width: Int): Bitmap? = synchronized(this) {
        if (closed) return null
        renderer.openPage(index).use { page ->
            val bitmap = createBitmap(width, maxOf(1, width * page.height / maxOf(1, page.width)))
            bitmap.eraseColor(AndroidColor.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }
    override fun close() = synchronized(this) { if (!closed) { closed = true; renderer.close(); descriptor.close() } }
}

/** Every page of a PDF, scrollable; pages draw as they scroll into view. */
@Composable fun PdfPreview(file: File) {
    var failed by remember(file) { mutableStateOf(false) }
    val pages by produceState<PdfPages?>(null, file) {
        val opened = withContext(Dispatchers.IO) { runCatching { PdfPages(file) }.getOrNull() }
        if (opened == null) failed = true
        value = opened
        awaitDispose { opened?.close() }
    }
    val document = pages
    when {
        failed -> Text(PREVIEW_FAILED, Modifier.padding(16.dp), style = Type.sm, color = theme.text2)
        document == null -> Text("Loading preview…", Modifier.padding(16.dp), style = Type.sm, color = theme.text2)
        else -> BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceIn(200, 1400)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).testTag("pdf-preview"), contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items((0 until document.count).toList()) { index ->
                    val page by produceState<Bitmap?>(null, document, index, width) { value = withContext(Dispatchers.IO) { runCatching { document.render(index, width) }.getOrNull() } }
                    page?.let { Image(it.asImageBitmap(), "Page ${index + 1} of ${document.count}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
                        ?: Box(Modifier.fillMaxWidth().aspectRatio(.77f), contentAlignment = Alignment.Center) { Text("Page ${index + 1}", style = Type.xs, color = theme.text3) }
                }
            }
        }
    }
}

private fun clock(ms: Int): String { val s = maxOf(0, ms) / 1000; return "%d:%02d".format(s / 60, s % 60) }
/** Plays a downloaded video or audio file. */
@Composable fun MediaPreview(file: File, video: Boolean) = MediaPreview(file, file.name, video) { it.setDataSource(file.path) }

/**
 * Plays a video, or an audio file without a picture, with a play/pause button and its position. [open] gives the
 * player its source (a file, or a [StreamingMediaSource] that reads the file as it plays); [key] is what it plays.
 */
@Composable fun MediaPreview(key: Any, name: String, video: Boolean, open: (MediaPlayer) -> Unit) {
    val player = remember(key) { MediaPlayer() }
    var prepared by remember(key) { mutableStateOf(false) }
    var playing by remember(key) { mutableStateOf(false) }
    var position by remember(key) { mutableIntStateOf(0) }
    var duration by remember(key) { mutableIntStateOf(0) }
    var ratio by remember(key) { mutableFloatStateOf(16 / 9f) }
    var failed by remember(key) { mutableStateOf(false) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player) {
        player.setOnPreparedListener { duration = it.duration; prepared = true; it.seekTo(1) }
        player.setOnVideoSizeChangedListener { _, width, height -> if (width > 0 && height > 0) ratio = width.toFloat() / height }
        player.setOnCompletionListener { playing = false; position = duration }
        player.setOnErrorListener { _, _, _ -> failed = true; playing = false; true }
        try { withContext(Dispatchers.IO) { open(player) }; player.prepareAsync() } catch (e: Exception) { failed = true }
    }
    LaunchedEffect(player, playing) { while (playing) { runCatching { position = player.currentPosition }; delay(250) } }
    if (failed) return Text(PREVIEW_FAILED, Modifier.padding(16.dp), style = Type.sm, color = theme.text2)
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(if (video) Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(.5f, 3f)) else Modifier.fillMaxWidth().height(1.dp), contentAlignment = Alignment.Center) {
            AndroidView({ context ->
                TextureView(context).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        var surface: Surface? = null
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                            surface = Surface(texture).also { runCatching { player.setSurface(it) } }
                        }
                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            runCatching { player.setSurface(null) }; surface?.release(); surface = null; return true
                        }
                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                    }
                }
            }, Modifier.matchParentSize().semantics { contentDescription = "${if (video) "Video" else "Audio"} preview: $name" })
            if (!prepared && video) Text("Loading preview…", style = Type.sm, color = theme.text2)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HIconButton(if (playing) Lucide.Pause else Lucide.Play, if (playing) "Pause" else "Play", {
                if (!prepared) return@HIconButton
                if (playing) player.pause() else player.start()
                playing = !playing
            }, Modifier.testTag("media-play"))
            Progress(if (duration > 0) position.toFloat() / duration else 0f, Modifier.weight(1f), height = 4.dp)
            Text("${clock(position)} / ${clock(duration)}", style = Type.xs, color = theme.text2)
        }
    }
}

/** The first 1 MB of a text file, in chunks so a long file stays quick to scroll. */
@Composable fun TextPreviewView(preview: TextPreview) {
    val chunks = remember(preview) { preview.text.lines().chunked(40).map { it.joinToString("\n") } }
    Column(Modifier.fillMaxWidth()) {
        if (preview.truncated) Text("Showing the first 1 MB. Download to read the full file.", Modifier.padding(start = 12.dp, top = 10.dp, end = 12.dp), style = Type.xs, color = theme.text2)
        if (preview.text.isEmpty()) Text("This text file is empty.", Modifier.padding(16.dp), style = Type.sm, color = theme.text2)
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).testTag("text-preview"), contentPadding = PaddingValues(12.dp)) {
            items(chunks) { Text(it, style = Type.mono.copy(fontSize = 12.sp)) }
        }
    }
}
