package app.harbor0.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.graphics.scale
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.text.Collator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// Photos (flag `photos`): every photo and video in the account, newest first, as thumbnails or a list, by month or by
// the device each was synced or backed up from, as apps/web/components/photos-page.tsx. Not the phone's own photo
// backup (PhotoLibrary.kt): this browses what is already in the drive. Opening one shows it in the preview.

enum class MediaKind { Photo, Video }
private val photoExtensions = ("jpg jpeg jpe jfif png gif webp avif bmp tif tiff heic heif jxl svg ico dng cr2 cr3 crw nef nrw arw srf sr2 orf rw2 " +
    "raf pef srw raw rwl 3fr erf kdc mrw mos x3f iiq").split(' ').toSet()
private val videoExtensions = ("mp4 m4v mov qt avi mkv webm wmv asf flv f4v mpg mpeg mpe m2v m2ts mts ts 3gp 3g2 ogv vob mxf dv insv lrv")
    .split(' ').toSet()
private fun genericMime(mime: String) = mime.isEmpty() || mime == "application/octet-stream" || mime == "binary/octet-stream"
private fun extensionOf(name: String) = name.lowercase(Locale.ROOT).substringAfterLast('.', "")
/** Whether a file is a photo or a video, by its MIME type or else its extension (contracts `mediaKind`). */
fun mediaKind(item: DriveItem): MediaKind? {
    if (item.isFolder) return null
    val mime = item.mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (mime.startsWith("image/")) return MediaKind.Photo
    if (mime.startsWith("video/")) return MediaKind.Video
    if (!genericMime(mime)) return null
    val extension = extensionOf(item.name)
    return if (extension in photoExtensions) MediaKind.Photo else if (extension in videoExtensions) MediaKind.Video else null
}
/** When a photo was taken, as near as the drive knows: the uploading device's time for it, else when it was added. */
fun takenAt(item: DriveItem) = item.modifiedAt?.ifEmpty { null } ?: item.createdAt

/** One heading on the page: a month, or a device and its photos. */
data class PhotoSection(val key: String, val name: String, val device: Device? = null, val items: List<DriveItem>)
/** Where a photo came from: a device, “Synced folders” when no device is listed, or straight into My Drive. */
data class PhotoSource(val key: String, val name: String, val device: Device? = null)
const val PHOTOS_CLOUD = "cloud"

fun monthName(iso: String, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    runCatching { Instant.parse(iso).atZone(zone).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)) }.getOrDefault("Unknown date")
fun monthSections(items: List<DriveItem>, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): List<PhotoSection> {
    val sections = linkedMapOf<String, MutableList<DriveItem>>()
    for (item in items) sections.getOrPut(monthName(takenAt(item), zone, locale)) { mutableListOf() } += item
    return sections.map { (name, list) -> PhotoSection(name, name, items = list) }
}
/** The devices a photo came from: its backup's device, every device syncing its folder, or none. */
fun photoSources(item: DriveItem, devices: List<Device>, backups: List<BackupRoot>, syncFolders: List<DriveItem>): List<PhotoSource> {
    // Browsers never sync or back up folders.
    val candidates = devices.filter { it.platform != "WEB" }
    item.backupRootId?.let { id ->
        val root = backups.firstOrNull { it.id == id }
        val device = root?.let { r -> candidates.firstOrNull { ownsBackup(r, it) } }
        val name = device?.name ?: root?.deviceName ?: "Other device"
        return listOf(PhotoSource(device?.id ?: "other:$name", name, device))
    }
    item.syncFolderId?.let { id ->
        val found = syncFolders.firstOrNull { it.id == id }?.syncDevices.orEmpty().map { entry -> PhotoSource(entry.id, entry.name, candidates.firstOrNull { it.id == entry.id }) }
        return found.ifEmpty { listOf(PhotoSource("other:synced", "Synced folders")) }
    }
    return listOf(PhotoSource(PHOTOS_CLOUD, "Uploaded to My Drive"))
}
/**
 * One section per device, computers before phones, then devices no longer connected, then files uploaded straight to
 * My Drive. A photo in a folder synced on two devices shows on both.
 */
fun deviceSections(items: List<DriveItem>, devices: List<Device>, backups: List<BackupRoot>, syncFolders: List<DriveItem>): List<PhotoSection> {
    val sections = linkedMapOf<String, Pair<PhotoSource, MutableList<DriveItem>>>()
    for (item in items) for (source in photoSources(item, devices, backups, syncFolders)) sections.getOrPut(source.key) { source to mutableListOf() }.second += item
    val collator = Collator.getInstance()
    return sections.values.map { (source, list) -> PhotoSection(source.key, source.name, source.device, list) }.sortedWith(
        compareBy<PhotoSection> { it.key == PHOTOS_CLOUD }.thenBy { it.device == null }.thenBy { it.device?.isPhone == true }
            .then { a, b -> collator.compare(a.name, b.name) })
}
/** “My Drive”, “Backup from Pixel”, “Synced on Laptop, Phone”: the list view's From column. */
fun photoPlace(item: DriveItem, devices: List<Device>, backups: List<BackupRoot>, syncFolders: List<DriveItem>): String {
    val found = photoSources(item, devices, backups, syncFolders)
    if (found[0].key == PHOTOS_CLOUD) return "My Drive"
    val names = found.joinToString(", ") { it.name }
    return if (item.backupRootId != null) "Backup from $names" else "Synced on $names"
}
/** Every page so far, each photo once, newest first; files the server listed unseen (encrypted) decide by their decrypted name. */
fun photoList(pages: List<List<DriveItem>>): List<DriveItem> {
    val seen = mutableSetOf<String>()
    return pages.flatten().filter { mediaKind(it) != null && seen.add(it.id) }.sortedByDescending { takenAt(it) }
}

/** Formats the phone can draw, so other files (RAW, SVG) aren't fetched only to fail. HEIC from Android 9, AVIF from 12. */
private val showablePhotos = ("jpg jpeg jpe jfif png gif webp bmp ico" + (if (Build.VERSION.SDK_INT >= 28) " heic heif" else "") +
    (if (Build.VERSION.SDK_INT >= 31) " avif" else "")).split(' ').toSet()
private val showableVideos = "mp4 m4v mov webm 3gp mkv".split(' ').toSet()
/**
 * Encrypted photos are decrypted whole on the phone, so only smaller ones get thumbnails. Videos of any size do: only
 * the parts the frame needs are downloaded and decrypted ([EncryptedRangeReader]).
 */
const val MAX_ENCRYPTED_THUMBNAIL = 20_000_000L
/** Photos larger than this show their icon rather than downloading the original for a tile. */
const val MAX_THUMBNAIL = 60_000_000L
fun canShowThumbnail(item: DriveItem): Boolean {
    val kind = mediaKind(item) ?: return false
    val extension = extensionOf(item.name)
    if (extension !in (if (kind == MediaKind.Photo) showablePhotos else showableVideos)) return false
    if (kind == MediaKind.Video) return true
    return item.sizeBytes <= if (item.encrypted) MAX_ENCRYPTED_THUMBNAIL else MAX_THUMBNAIL
}

/** [thumbnailUrl]: the thumbnail an app saved for it, encrypted like the file; without one, the original is drawn and saved. */
@Serializable data class PhotoLink(val id: String, val url: String, val sizeBytes: Long = 0, val encrypted: Boolean = false, val thumbnailUrl: String? = null)
@Serializable data class PhotoLinks(val items: List<PhotoLink>, val expiresAt: String)
/** A page of `GET /v1/photos`: newest taken first when [ordered], else in no particular order. */
@Serializable data class PhotoPage(val items: List<DriveItem>, val nextCursor: String? = null, val ordered: Boolean = false)
/** Most files one `/v1/photos/links` request may ask for (contracts `MAX_PHOTO_LINKS`). */
const val MAX_PHOTO_LINKS = 60
/** Most thumbnails one `/v1/photos/thumbnails` request may save (contracts `MAX_PHOTO_THUMBNAILS`). */
const val MAX_PHOTO_THUMBNAILS = 16
/** Saved thumbnails: a JPEG this many pixels on its short side, at most 3:1 (contracts `PHOTO_THUMBNAIL_PX`). */
const val PHOTO_THUMBNAIL_PX = 360
const val PHOTO_THUMBNAIL_QUALITY = 72
private const val MAX_SAVED_THUMBNAIL = 256 * 1024
private const val SAVE_BATCH_BYTES = 600_000

/** The rotation and mirroring a photo's EXIF orientation asks for, as a matrix; null when it is upright. */
fun exifMatrix(orientation: Int): Matrix? = Matrix().takeIf { m ->
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
        else -> return@takeIf false
    }
    true
}
/** The size a saved thumbnail of a [width]×[height] picture is drawn from: centred, at most 3:1. */
fun thumbnailCrop(width: Int, height: Int): Pair<Int, Int> {
    val short = minOf(width, height)
    return minOf(width, short * 3) to minOf(height, short * 3)
}

/**
 * Thumbnails for the Photos page. Links are asked for together: tiles that come into view within a moment of each
 * other share one request, and a link is reused until a minute before it expires. A saved thumbnail is used when
 * there is one; otherwise the original is downloaded (a few at a time; encrypted ones decrypted in memory, three at a
 * time), drawn small, and that drawing saved for every app after this. Thumbnails are kept on this phone as stored:
 * an encrypted file's stays encrypted on disk and is decrypted only to show it.
 */
class PhotoThumbnails(private val m: WorkspaceModel) {
    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 10).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val links = ConcurrentHashMap<String, Pair<PhotoLink?, Long>>()
    private val waiting = mutableListOf<Pair<String, CompletableDeferred<PhotoLink?>>>()
    private var flush: Job? = null
    private val unsaved = mutableListOf<JsonObject>()
    private var saveJob: Job? = null
    private val saved = Semaphore(8)
    private val plain = Semaphore(4)
    private val sealed = Semaphore(3)
    private val disk get() = File(m.context.cacheDir, "photo-thumbnails")
    private fun key(item: DriveItem) = "${item.id}:${item.currentVersionId}"
    private fun file(item: DriveItem) = File(disk, E2ee.sha256Hex(key(item).toByteArray()) + if (item.encrypted) ".h0e" else ".jpg")
    fun cached(item: DriveItem): Bitmap? = memory.get(key(item))
    /** Forgets every thumbnail, on this phone too, as when signing out. */
    fun clear() {
        memory.evictAll(); links.clear()
        synchronized(waiting) { waiting.clear(); flush?.cancel(); flush = null }
        synchronized(unsaved) { unsaved.clear(); saveJob?.cancel(); saveJob = null }
        m.viewModelScope.launch(Dispatchers.IO) { disk.deleteRecursively() }
    }

    private suspend fun link(id: String, fresh: Boolean = false): PhotoLink? {
        if (!fresh) links[id]?.takeIf { it.second > System.currentTimeMillis() }?.let { return it.first }
        val result = CompletableDeferred<PhotoLink?>()
        synchronized(waiting) {
            waiting += id to result
            if (flush == null) flush = m.viewModelScope.launch {
                delay(50)
                val batch = synchronized(waiting) { waiting.toList().also { waiting.clear(); flush = null } }
                for (part in batch.chunked(MAX_PHOTO_LINKS)) launch {
                    try {
                        // Straight to the server: sizes stay as stored, which an encrypted original is checked against.
                        val answer = harborJson.decodeFromJsonElement<PhotoLinks>(m.api.direct.call("/v1/photos/links", "POST", buildJsonObject {
                            put("ids", JsonArray(part.map { it.first }.distinct().map(::JsonPrimitive)))
                        }))
                        // Leave a minute to finish fetching before the link expires.
                        val until = runCatching { Instant.parse(answer.expiresAt).toEpochMilli() }.getOrDefault(System.currentTimeMillis() + 240_000) - 60_000
                        for ((wanted, deferred) in part) {
                            val found = answer.items.firstOrNull { it.id == wanted }
                            links[wanted] = found to until
                            deferred.complete(found)
                        }
                    } catch (e: Exception) { part.forEach { it.second.completeExceptionally(e) } }
                }
            }
        }
        return result.await()
    }

    /** The thumbnail of a photo or video, about [px] pixels on its short side; null when it can't be shown. */
    suspend fun load(item: DriveItem, px: Int = 384): Bitmap? {
        if (mediaKind(item) == null) return null
        val key = key(item)
        memory.get(key)?.let { return it }
        val kept = file(item)
        withContext(Dispatchers.IO) { runCatching { if (kept.exists()) open(item, kept.readBytes(), px) else null }.getOrNull() }
            ?.let { memory.put(key, it); return it }
        var found = link(item.id) ?: return null
        found.thumbnailUrl?.let { url ->
            val stored = saved.withPermit { runCatching { m.transfers.storageGet(url, maxBytes = MAX_SAVED_THUMBNAIL.toLong()) }.getOrNull() }
                ?.takeIf { it.first in 200..299 }?.second
            val bitmap = stored?.let { withContext(Dispatchers.Default) { runCatching { open(item, it, px) }.getOrNull() } }
            if (bitmap != null) {
                memory.put(key, bitmap)
                keep(kept, stored)
                return bitmap
            }
        }
        if (!canShowThumbnail(item)) return null
        val original = (if (item.encrypted) sealed else plain).withPermit {
            if (mediaKind(item) == MediaKind.Video) return@withPermit withContext(Dispatchers.IO) {
                if (found.encrypted) encryptedFrame(found) else videoFrame(found.url)
            }
            val limit = if (found.encrypted) MAX_ENCRYPTED_THUMBNAIL + 1_000_000 else MAX_THUMBNAIL
            var (status, bytes) = m.transfers.storageGet(found.url, maxBytes = limit)
            // A link that expired early: ask once for a new one.
            if (status == 403 || status == 400) { found = link(item.id, fresh = true) ?: return null; m.transfers.storageGet(found.url, maxBytes = limit).let { status = it.first; bytes = it.second } }
            if (status !in 200..299) return null
            withContext(Dispatchers.Default) { decode(if (found.encrypted) m.transfers.decryptStored(bytes) else bytes, PHOTO_THUMBNAIL_PX) }
        } ?: return null
        val (bitmap, stored) = withContext(Dispatchers.Default) {
            val small = shrink(original)
            val jpeg = java.io.ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, PHOTO_THUMBNAIL_QUALITY, it) }.toByteArray()
            small to runCatching { if (item.encrypted) m.transfers.sealSmall(jpeg) else jpeg }.getOrNull()
        }
        memory.put(key, bitmap)
        if (stored != null) { keep(kept, stored); save(item, stored) }
        return bitmap
    }
    /** A kept or saved thumbnail as stored (decrypted here for an encrypted file), drawn. */
    private fun open(item: DriveItem, stored: ByteArray, px: Int) = decode(if (item.encrypted) m.transfers.decryptStored(stored) else stored, px)
    private suspend fun keep(file: File, stored: ByteArray) = withContext(Dispatchers.IO) {
        runCatching { disk.mkdirs(); file.writeBytes(stored) }
    }
    /** [bitmap] [PHOTO_THUMBNAIL_PX] on its short side, cropped to at most 3:1. */
    private fun shrink(bitmap: Bitmap): Bitmap {
        val (cropW, cropH) = thumbnailCrop(bitmap.width, bitmap.height)
        val scale = minOf(1f, PHOTO_THUMBNAIL_PX.toFloat() / maxOf(1, minOf(bitmap.width, bitmap.height)))
        val out = Bitmap.createBitmap(maxOf(1, (cropW * scale).toInt()), maxOf(1, (cropH * scale).toInt()), Bitmap.Config.ARGB_8888)
        // JPEG has no transparency: transparent parts show white, not black.
        Canvas(out).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(bitmap, android.graphics.Rect((bitmap.width - cropW) / 2, (bitmap.height - cropH) / 2, (bitmap.width + cropW) / 2, (bitmap.height + cropH) / 2),
                android.graphics.Rect(0, 0, out.width, out.height), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }
    /**
     * Saves a thumbnail this phone drew, with others drawn within a couple of seconds, for every app to use. Failures
     * only mean another app draws it again.
     */
    private fun save(item: DriveItem, stored: ByteArray) {
        val version = item.currentVersionId ?: return
        if (stored.size > MAX_SAVED_THUMBNAIL) return
        synchronized(unsaved) {
            unsaved += buildJsonObject { put("id", item.id); put("versionId", version); put("data", java.util.Base64.getEncoder().encodeToString(stored)) }
            saveJob?.cancel()
            val full = unsaved.size >= MAX_PHOTO_THUMBNAILS || unsaved.sumOf { it["data"]!!.jsonPrimitive.content.length } >= SAVE_BATCH_BYTES
            saveJob = m.viewModelScope.launch { if (!full) delay(2000); flushSaves() }
        }
    }
    private suspend fun flushSaves() {
        val all = synchronized(unsaved) { unsaved.toList().also { unsaved.clear(); saveJob = null } }
        var batch = mutableListOf<JsonObject>()
        var size = 0
        suspend fun send() {
            if (batch.isEmpty()) return
            val items = JsonArray(batch)
            batch = mutableListOf(); size = 0
            runCatching { m.api.direct.call("/v1/photos/thumbnails", "POST", buildJsonObject { put("items", items) }) }
        }
        for (entry in all) {
            val length = entry["data"]!!.jsonPrimitive.content.length
            if (batch.size >= MAX_PHOTO_THUMBNAILS || (batch.isNotEmpty() && size + length > SAVE_BATCH_BYTES)) send()
            batch += entry; size += length
        }
        send()
    }
    /** A photo drawn about [px] pixels on its short side, turned the way its EXIF orientation says. */
    private fun decode(bytes: ByteArray, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val orientation = runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = exifMatrix(orientation) ?: return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    /** A frame half a second in, read from the video's link without downloading all of it. */
    private fun videoFrame(url: String): Bitmap? {
        FileTransfers.validateUrl(url, m.api.baseUrl, BuildConfig.DEBUG)
        return frame { it.setDataSource(url, HashMap()) }
    }
    /** The same from an encrypted video: only the chunks the frame needs are downloaded and decrypted. */
    private fun encryptedFrame(link: PhotoLink): Bitmap? {
        FileTransfers.validateUrl(link.url, m.api.baseUrl, BuildConfig.DEBUG)
        var url = link.url
        // Straight from the server, the link's size is the stored one the reader needs.
        val reader = EncryptedRangeReader(m.transfers::openCipher, link.sizeBytes) { start, end ->
            var (status, bytes) = m.transfers.storageGet(url, start, end)
            // A link that expired early: ask once for a new one.
            if (status == 403 || status == 400) {
                url = link(link.id, fresh = true)?.url ?: url
                m.transfers.storageGet(url, start, end).let { status = it.first; bytes = it.second }
            }
            check(status in 200..299) { "The video couldn’t be read ($status)." }
            // Storage that ignores Range sends the whole file.
            if (status == 200 && bytes.size > end - start) bytes.copyOfRange(start.toInt(), end.toInt()) else bytes
        }
        return frame { it.setDataSource(EncryptedMediaSource(reader)) }
    }
    private fun frame(source: (MediaMetadataRetriever) -> Unit): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            source(retriever)
            val frame = retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val scale = PHOTO_THUMBNAIL_PX.toFloat() / maxOf(1, minOf(frame.width, frame.height))
            if (scale >= 1f) frame else frame.scale(maxOf(1, (frame.width * scale).toInt()), maxOf(1, (frame.height * scale).toInt()))
        } catch (_: Exception) { null } finally { runCatching { retriever.release() } }
    }
}

/** Pages loaded on their own before asking; the server returns photos in no particular order. */
const val PHOTOS_AUTO_PAGES = 20
const val PHOTOS_PAGE_SIZE = 500

/** The Photos page's state: the pages read so far, the devices to group by, and the view chosen. */
class PhotosState(private val m: WorkspaceModel) {
    val enabled get() = m.flag("photos")
    val thumbnails = PhotoThumbnails(m)
    private val preferences get() = m.context.getSharedPreferences("workspace", Context.MODE_PRIVATE)
    /** Every photo and video so far, newest first. */
    var all by mutableStateOf<List<DriveItem>>(emptyList()); private set
    var pageCount by mutableIntStateOf(0); private set
    /** The server lists newest taken first, so pages load as the list scrolls. */
    var ordered by mutableStateOf(false); private set
    var cursor by mutableStateOf<String?>(null); private set
    var loaded by mutableStateOf(false); private set
    var fetching by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var filter by mutableStateOf("all")
    var grid by mutableStateOf(true); private set
    var grouping by mutableStateOf("date"); private set
    var backups by mutableStateOf<List<BackupRoot>>(emptyList()); private set
    var syncFolders by mutableStateOf<List<DriveItem>>(emptyList()); private set
    private var pages = listOf<List<DriveItem>>()
    private var job: Job? = null
    private var loadedAt = 0L

    init {
        grid = runCatching { preferences.getString("photos-view", "grid") }.getOrNull() != "list"
        grouping = if (runCatching { preferences.getString("photos-grouping", "date") }.getOrNull() == "device") "device" else "date"
    }
    fun setView(toGrid: Boolean) { grid = toGrid; preferences.edit { putString("photos-view", if (toGrid) "grid" else "list") } }
    fun setGroup(next: String) { grouping = next; preferences.edit { putString("photos-grouping", next) } }
    fun reset() { job?.cancel(); pages = emptyList(); all = emptyList(); pageCount = 0; cursor = null; ordered = false; loaded = false; fetching = false; error = null; loadedAt = 0
        backups = emptyList(); syncFolders = emptyList(); thumbnails.clear() }

    val hasNext get() = cursor != null
    /** Still reading pages, so “newest first” may change. */
    val loadingMore get() = hasNext || fetching
    val shown get() = when (filter) { "photo" -> all.filter { mediaKind(it) == MediaKind.Photo }; "video" -> all.filter { mediaKind(it) == MediaKind.Video }; else -> all }
    fun sections(items: List<DriveItem> = shown) =
        if (grouping == "device") deviceSections(items, m.devices.data.orEmpty(), backups, syncFolders) else monthSections(items)
    fun place(item: DriveItem) = photoPlace(item, m.devices.data.orEmpty(), backups, syncFolders)

    /** Reads every page again (up to [PHOTOS_AUTO_PAGES]); a view read in the last minute is kept unless [force]. */
    fun refresh(force: Boolean = false) {
        if (!enabled) return
        if (!force && loaded && error == null && System.currentTimeMillis() - loadedAt < 60_000) return
        job?.cancel()
        error = null
        job = m.viewModelScope.launch {
            // The devices and folders photos came from, for grouping by device; failures only leave the names out.
            if (m.devices.data == null) m.loadDevices()
            launch { runCatching { backups = m.api.all<BackupRoot>("/v1/backups") } }
            launch { runCatching { syncFolders = m.api.all<DriveItem>("/v1/sync/folders") } }
            fetch(fresh = true, auto = true)
        }
    }
    /** One more page, once the automatic ones are in. */
    fun loadMore() {
        if (fetching || cursor == null) return
        job = m.viewModelScope.launch { fetch(fresh = false, auto = false) }
    }
    private suspend fun fetch(fresh: Boolean, auto: Boolean) {
        fetching = true
        var next = if (fresh) null else cursor
        var read = if (fresh) mutableListOf() else pages.toMutableList()
        try {
            do {
                val page = m.api.get<PhotoPage>(withQuery("/v1/photos", "limit" to "$PHOTOS_PAGE_SIZE", "cursor" to next))
                check(page.nextCursor == null || page.nextCursor != next) { "Could not load the remaining photos. Please retry." }
                read = (read + listOf(page.items)).toMutableList()
                // The list on screen stays until the first page of a fresh read replaces it.
                pages = read; all = photoList(read); pageCount = read.size; cursor = page.nextCursor; next = page.nextCursor
                ordered = page.ordered; loaded = true; loadedAt = System.currentTimeMillis()
            } while (auto && !page.ordered && next != null && read.size < PHOTOS_AUTO_PAGES)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = m.message(e) }
        finally { fetching = false }
    }
    fun open(item: DriveItem) = m.drive.open(item)
}

@Composable fun PhotosScreen(model: WorkspaceModel) {
    val photos = model.photos
    if (!photos.enabled) {
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            EmptyState(Lucide.FileImage, "Photos isn’t available yet", "This page isn’t turned on for your account. Your photos and videos are still in My Drive.") {
                HButton("Open My Drive", { model.navigate(Section.Drive) }, variant = Variant.Outline, small = true)
            }
        }
        return
    }
    val all = photos.all
    val shown = photos.shown
    val sections = remember(shown, photos.grouping, model.devices.data, photos.backups, photos.syncFolders) { photos.sections(shown) }
    val pending = !photos.loaded && photos.error == null
    val counts = remember(all) { all.count { mediaKind(it) == MediaKind.Photo } to all.count { mediaKind(it) == MediaKind.Video } }
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val columns = if (width >= 600.dp) 5 else 3
    Refreshable(photos.fetching && photos.loaded, { photos.refresh(force = true) }) {
        val list = remember(model.epoch) { androidx.compose.foundation.lazy.LazyListState() }
        // In order, the next page loads as the end of the list comes near.
        LaunchedEffect(list, photos.ordered) {
            if (photos.ordered) snapshotFlow {
                val near = list.layoutInfo.let { it.totalItemsCount - (it.visibleItemsInfo.lastOrNull()?.index ?: 0) < 12 }
                near && photos.hasNext && !photos.fetching && photos.error == null
            }.collect { if (it) photos.loadMore() }
        }
        LazyColumn(Modifier.fillMaxSize().testTag("photos-list"), state = list,
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp)) {
            item {
                PageTitle("Photos")
                Text(if (pending) "Finding photos and videos…" else "${countLabel(counts.first, "photo")} and ${countLabel(counts.second, "video")}${if (photos.loadingMore) " so far" else ""}",
                    Modifier.padding(top = 2.dp).testTag("photos-count"), style = Type.sm, color = theme.text2)
                Segmented(listOf("all" to "All", "photo" to "Photos", "video" to "Videos"), photos.filter, { photos.filter = it }, Modifier.padding(top = 14.dp).fillMaxWidth())
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Segmented(listOf("date" to "By date", "device" to "By device"), photos.grouping, photos::setGroup, Modifier.weight(1f))
                    ViewSwitch(photos.grid, photos::setView)
                }
            }
            when {
                pending -> item { SkeletonRows(grid = true) }
                photos.error != null && all.isEmpty() -> item { LoadError("Photos couldn’t be loaded.") { photos.refresh(force = true) } }
                shown.isEmpty() && !photos.loadingMore -> item {
                    EmptyState(if (photos.filter == "video") Lucide.Film else Lucide.Images, when (photos.filter) {
                        "video" -> "No videos yet"; "photo" -> "No photos yet"; else -> "No photos or videos yet"
                    }, "Photos and videos you upload, sync or back up from any device appear here, in every format.", Modifier.padding(top = 24.dp))
                }
                else -> {
                    photos.error?.let { message -> item { Alert(message, Modifier.padding(top = 12.dp), Tone.Danger, "Try again") { photos.refresh(force = true) } } }
                    sections.forEach { section -> photoSection(model, section, columns) }
                    item {
                        if (photos.hasNext && !photos.ordered && photos.pageCount >= PHOTOS_AUTO_PAGES && !photos.fetching) LoadMore(false, false) { photos.loadMore() }
                        else if (photos.loadingMore) Text("Looking for more photos and videos…", Modifier.fillMaxWidth().padding(top = 16.dp), style = Type.sm,
                            color = theme.text2)
                    }
                }
            }
        }
    }
}
private fun countLabel(n: Int, one: String) = "%,d %s".format(n, if (n == 1) one else "${one}s")

private fun LazyListScope.photoSection(model: WorkspaceModel, section: PhotoSection, columns: Int) {
    val photos = model.photos
    item(key = "section-${section.key}") {
        Row(Modifier.padding(top = 20.dp, bottom = 8.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            section.device?.let { Icon(if (it.isPhone) Lucide.Smartphone else Lucide.Laptop, null, Modifier.size(16.dp), tint = theme.text2) }
            Text(section.name, Modifier.weight(1f, fill = false), style = Type.h3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${section.items.size}", style = Type.sm, color = theme.text2)
        }
    }
    if (photos.grid) items(section.items.chunked(columns), key = { row -> "${section.key}/${row.first().id}" }) { row ->
        Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Keyed by photo: a row that now holds other photos (another filter or grouping) mustn't keep the old tiles'
            // pictures.
            row.forEach { key(it.id, it.currentVersionId) { PhotoTile(model, it, Modifier.weight(1f)) } }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    } else items(section.items, key = { "${section.key}/${it.id}" }) { item -> PhotoRow(item, photos.place(item)) { photos.open(item) } }
}

@Composable private fun PhotoTile(model: WorkspaceModel, item: DriveItem, modifier: Modifier) {
    val t = theme
    val thumbnails = model.photos.thumbnails
    val kind = mediaKind(item) ?: MediaKind.Photo
    // Tiles draw their thumbnail once they scroll into view; leaving cancels the download.
    val state by produceState<Pair<Bitmap?, Boolean>>(thumbnails.cached(item) to !canShowThumbnail(item), item.id, item.currentVersionId) {
        if (value.first == null && !value.second) value = runCatching { thumbnails.load(item) }.getOrNull().let { it to (it == null) }
    }
    val (bitmap, failed) = state
    val shape = RoundedCornerShape(Radius.sm)
    Box(modifier.aspectRatio(1f).clip(shape).background(t.fill2).clickable(role = Role.Button) { model.photos.open(item) }
        .semantics { contentDescription = "${item.name}, ${if (kind == MediaKind.Video) "video" else "photo"}, ${dateLabel(takenAt(item))}" }
        .testTag("photo-${item.name}"), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(if (kind == MediaKind.Video) Lucide.Film else Lucide.Images, null, Modifier.size(22.dp), tint = if (failed) t.text2 else t.text3)
            if (failed) Text(item.name.substringAfterLast('.', "").uppercase(Locale.ROOT), style = Type.xs.copy(fontSize = 10.sp), color = t.text2, maxLines = 1)
        }
        if (kind == MediaKind.Video) Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(22.dp).background(Color.Black.copy(alpha = .55f), CircleShape),
            contentAlignment = Alignment.Center) { Icon(Lucide.Play, null, Modifier.size(12.dp), tint = Color.White) }
    }
}

@Composable private fun PhotoRow(item: DriveItem, place: String, onOpen: () -> Unit) {
    val t = theme
    val video = mediaKind(item) == MediaKind.Video
    Column {
        Row(Modifier.fillMaxWidth().height(64.dp).clickable(role = Role.Button, onClick = onOpen).testTag("photo-${item.name}"), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(start = 4.dp, end = 12.dp).size(40.dp).background(androidx.compose.ui.graphics.lerp(t.surface, t.palette.kindImage, .13f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center) { Icon(if (video) Lucide.Film else Lucide.FileImage, null, Modifier.size(20.dp), tint = t.palette.kindImage) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.name, style = Type.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$place · ${dateLabel(takenAt(item))} · ${bytesLabel(item.sizeBytes)}", style = Type.xs, color = t.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Divider()
    }
}
