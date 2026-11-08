package app.harbor0.android

import androidx.work.NetworkType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID

// Photo backup: the phone's photos and videos (MediaStore) backed up as a backup folder named "Photos", one folder per
// album. Nothing here touches Android APIs (see PhotoStore.kt for the MediaStore side), so it is covered by JVM tests.

/** The local location of a photo backup; [localTree] makes a [MediaStoreTree] for it instead of a document tree. */
const val PHOTOS_URI = "harbor-media://photos"
/** The backup folder's name in My Drive › Backups › this phone. */
const val PHOTOS_NAME = "Photos"
const val PHOTOS_LOCATION = "Photos on this phone"
const val BACKGROUND_RESTRICTED = "Battery settings stop harbor0 in the background, so photos are only backed up while the app is open. Set harbor0’s battery use to Unrestricted to keep backups running."
const val BATTERY_OPTIMIZED = "Android may stop photo backups soon after harbor0 is closed. Allow harbor0 to run in the background to keep them going."
const val AUTO_START = "This phone also has an auto-start setting. Turn it on for harbor0 so backups carry on after you close the app from recent apps."
const val PHOTOS_ACCESS = "harbor0 no longer has access to your photos. Allow access to keep backing them up."
const val PHOTOS_WIFI = "Waiting for Wi-Fi"
private const val PHOTOS_UNSUPPORTED = "harbor0 doesn’t move, rename or delete photos on this phone."

/** A photo backup's options, kept with its folder in the journal. */
@Serializable data class PhotoOptions(val videos: Boolean = true, val mobileData: Boolean = false)
val SyncRoot.isPhotos get() = photos != null || treeUri == PHOTOS_URI
/** Where a backup's files come from, as shown to the person. */
val SyncRoot.localLocation get() = if (isPhotos) PHOTOS_LOCATION else localName
/** Photos wait for Wi-Fi (an unmetered network) unless they may use mobile data. */
fun photoUploadsHeld(options: PhotoOptions, metered: Boolean) = metered && !options.mobileData
/**
 * The network the background photo trigger waits for: any connection. Whether it may be used is decided by the
 * engine ([photoUploadsHeld], [metered]): WorkManager's "unmetered" counts a VPN over Wi-Fi as mobile data.
 */
@Suppress("UNUSED_PARAMETER")
fun photoNetwork(options: PhotoOptions) = NetworkType.CONNECTED

/** What a network is, for [metered]. */
data class NetworkKind(val vpn: Boolean, val notMetered: Boolean, val internet: Boolean = true)
/**
 * Whether uploads would go over mobile data. A VPN is judged by the network under it: most VPNs never say they are
 * unmetered, so Android calls every VPN connection metered, even on Wi-Fi.
 */
fun metered(active: NetworkKind?, all: List<NetworkKind>): Boolean {
    if (active == null) return false
    if (active.notMetered) return false
    if (!active.vpn) return true
    return all.none { !it.vpn && it.internet && it.notMetered }
}
/** [metered] for the phone's current networks. */
fun meteredNow(connectivity: android.net.ConnectivityManager?): Boolean {
    val cm = connectivity ?: return false
    fun kind(network: android.net.Network?): NetworkKind? = network?.let { cm.getNetworkCapabilities(it) }?.let {
        NetworkKind(it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN),
            it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET))
    }
    val active = cm.activeNetwork ?: return false
    val kinds = runCatching { @Suppress("DEPRECATION") cm.allNetworks.mapNotNull { kind(it) } }.getOrDefault(emptyList())
    return kind(active)?.let { metered(it, kinds) } ?: cm.isActiveNetworkMetered
}

/** One photo or video in the phone's media library, as its MediaStore row says. [modified] is in milliseconds. */
data class MediaRow(val id: Long, val video: Boolean, val name: String, val album: String?, val relativePath: String?, val size: Long, val modified: Long,
    val mime: String? = null)
/** What harbor0 may read: everything, only the photos the person picked (Android 14), or nothing. */
enum class MediaAccess { FULL, PARTIAL, NONE }

/** The phone's media library, behind an interface so photo backups can be tested without Android. */
interface MediaSource {
    fun access(): MediaAccess
    /** Every visible photo (and video when [videos]); pending and trashed items are left out. */
    fun rows(videos: Boolean): List<MediaRow>
    fun open(row: MediaRow): InputStream
    /**
     * Saves a restored file into the library, in [album], while [write] fills it (hidden until it is complete). The new
     * row, or null when it shows up only later (Android 9 and earlier).
     */
    fun insert(album: String, name: String, mime: String, video: Boolean, write: (OutputStream) -> Unit): MediaRow?
    fun delete(row: MediaRow)
    /** Best effort: gives a restored file back the time it was saved at. */
    fun setTaken(row: MediaRow, millis: Long): Boolean = false
}

/** The runtime permissions photo backup asks for on this Android version (the manifest declares the same). */
fun photoPermissions(sdk: Int): List<String> = when {
    sdk >= 34 -> listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED", "android.permission.ACCESS_MEDIA_LOCATION")
    sdk == 33 -> listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.ACCESS_MEDIA_LOCATION")
    sdk >= 29 -> listOf("android.permission.READ_EXTERNAL_STORAGE", "android.permission.ACCESS_MEDIA_LOCATION")
    // Restores write into the public Pictures folder directly.
    else -> listOf("android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE")
}
fun photoAccess(sdk: Int, granted: (String) -> Boolean): MediaAccess = when {
    sdk >= 33 && granted("android.permission.READ_MEDIA_IMAGES") -> MediaAccess.FULL
    sdk >= 34 && granted("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") -> MediaAccess.PARTIAL
    sdk >= 33 -> MediaAccess.NONE
    granted("android.permission.READ_EXTERNAL_STORAGE") -> MediaAccess.FULL
    else -> MediaAccess.NONE
}

/**
 * A backup root this phone made earlier for its photos and that it no longer has: set up again, the backup carries on
 * in the same "Photos" folder rather than starting a second one.
 */
fun reusablePhotoRoot(roots: List<BackupRoot>, deviceId: String, devicePublicId: String?, local: Set<String>): BackupRoot? =
    roots.filter { it.localPathDisplayName == PHOTOS_NAME && it.state in setOf("ACTIVE", "PAUSED", "ERROR") && it.id !in local &&
        ((devicePublicId != null && it.devicePublicId == devicePublicId) || it.deviceId == deviceId) }.maxByOrNull { it.createdAt }

/**
 * What a reconnected photo backup already holds: the saved albums and files that match the library by path, size and
 * time, so they are not uploaded again. Recorded without a hash; a later change looks the saved hash up.
 */
fun photoSeed(rootId: String, local: Map<String, LocalStat>, remote: Map<DriveItem, List<DriveItem>>): List<Pair<LocalFile, LocalStat>> = buildList {
    for ((album, items) in remote) {
        if (!album.isFolder || local[album.name]?.folder != true) continue
        add(LocalFile(rootId, album.name, album.id, album.revision ?: 1, null, "FOLDER") to LocalStat(true, 0, 0))
        for (item in items) {
            if (item.isFolder) continue
            val path = joinPath(album.name, item.name)
            val stat = local[path] ?: continue
            val modified = item.modifiedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            if (!stat.folder && stat.size == item.sizeBytes && stat.mtime == modified) add(LocalFile(rootId, path, item.id, item.revision ?: 1, null, "FILE") to stat)
        }
    }
}

/**
 * The server side of setting up a photo backup: this phone's earlier "Photos" backup (true: reused), or a new one.
 * [local]: backup ids this phone already has.
 */
suspend fun photoBackupRoot(api: HarborApi, deviceId: String, devicePublicId: String?, local: Set<String>): Pair<BackupRoot, Boolean> {
    reusablePhotoRoot(api.all<BackupRoot>("/v1/backups"), deviceId, devicePublicId, local)?.let { return it to true }
    val response = api.request("/v1/backups", "POST", buildJsonObject {
        put("operationId", UUID.randomUUID().toString()); put("deviceId", deviceId); put("name", PHOTOS_NAME)
    })["root"]!!
    return harborJson.decodeFromJsonElement(BackupRoot.serializer(), response) to false
}
/** [photoSeed] for a reused backup: its albums and their files, as the server lists them. */
suspend fun photoSeed(api: HarborApi, rootId: String, remoteId: String, library: Map<String, LocalStat>): List<Pair<LocalFile, LocalStat>> {
    val albums = api.all<DriveItem>("/v1/drive/folders/${HarborApi.segment(remoteId)}/children").filter { it.isFolder && library[it.name]?.folder == true }
    return photoSeed(rootId, library, albums.associateWith { api.all<DriveItem>("/v1/drive/folders/${HarborApi.segment(it.id)}/children") })
}

// Paths ---------------------------------------------------------------------------------------------
private fun clip(text: String, length: Int): String {
    if (text.length <= length) return text
    val end = if (length > 0 && text[length - 1].isHighSurrogate()) length - 1 else length
    return text.substring(0, maxOf(0, end))
}
/** Part files of restores, kept in private space rather than in the library. */
fun photoScratch(relative: String) = relative.endsWith(".harbor-part") || relative.endsWith(".harbor-plain")
/** A library name as a name harbor0 accepts (as `safeName`: no separators or control characters, 240 characters at most). */
fun mediaSegment(raw: String?, fallback: String): String {
    var name = raw.orEmpty().replace(Regex("[\\\\/\\u0000-\\u001f\\u007f]"), "_").trim()
    if (name.isEmpty() || name == "." || name == "..") name = fallback
    // Names the engine would take for its own bookkeeping still get backed up, slightly renamed.
    fun reserved(text: String) = internalPath(text) || photoScratch(text)
    if (reserved(name)) name = "_$name".takeUnless(::reserved) ?: "${name}_"
    if (name.length > 240) {
        val ext = extension(name).takeIf { it.length <= 20 }.orEmpty()
        name = clip(name.dropLast(ext.length), 240 - ext.length) + ext
    }
    return name
}
/** "name (2).jpg": a name kept unique in its album. */
fun photoNumbered(name: String, number: Int): String {
    val ext = extension(name).takeIf { it.length <= 20 }.orEmpty()
    val suffix = " ($number)"
    return clip(name.dropLast(ext.length), 240 - suffix.length - ext.length) + suffix + ext
}
private val photoTypes = mapOf("jpg" to "image/jpeg", "jpeg" to "image/jpeg", "jpe" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
    "webp" to "image/webp", "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif", "dng" to "image/x-adobe-dng", "bmp" to "image/bmp",
    "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "3gp" to "video/3gpp", "3g2" to "video/3gpp2", "mkv" to "video/x-matroska",
    "webm" to "video/webm", "avi" to "video/x-msvideo")
/** The type of a photo or video by its name; null for anything else, which can't go into the photo library. */
fun mediaMime(name: String) = photoTypes[extension(name).removePrefix(".").lowercase()]
/** Where a restored file goes: the camera album back in DCIM, every other album in Pictures. */
fun photoRestoreFolder(album: String) = if (normalizedName(album) == "camera") "DCIM/Camera/" else "Pictures/$album/"

/** The library as folders and files: albums by name, then "<album>/<name>" for each item. */
class PhotoIndex(val files: Map<String, MediaRow>, val albums: Map<String, List<String>>) {
    fun with(path: String, row: MediaRow): PhotoIndex {
        val album = parentPath(path)
        return PhotoIndex(files + (path to row), albums + (album to (albums[album].orEmpty() + path)))
    }
}
/**
 * "<album>/<name>" for every item. The album is its bucket (else the last folder of its path, else "Other"); names are
 * made safe, and items sharing a name in an album (ignoring case) are numbered in the order of their ids, so the same
 * library always gives the same paths.
 */
fun photoIndex(rows: List<MediaRow>): PhotoIndex {
    val albums = linkedMapOf<String, String>()
    val taken = mutableSetOf<String>()
    val files = linkedMapOf<String, MediaRow>()
    val listed = linkedMapOf<String, MutableList<String>>()
    for (row in rows.sortedWith(compareBy({ it.id }, { it.video }))) {
        val bucket = row.album?.takeIf { it.isNotBlank() } ?: row.relativePath?.trim('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val raw = mediaSegment(bucket, "Other")
        val album = albums.getOrPut(normalizedName(raw)) { raw }
        val fallback = "${if (row.video) "Video" else "Photo"} ${row.id}" + (row.mime?.let { mime -> photoTypes.entries.firstOrNull { it.value == mime }?.key?.let { ".$it" } } ?: "")
        val base = mediaSegment(row.name, fallback)
        var name = base
        var number = 2
        while (!taken.add(normalizedName("$album/$name"))) name = photoNumbered(base, number++)
        val path = "$album/$name"
        files[path] = row
        listed.getOrPut(album) { mutableListOf() } += path
    }
    return PhotoIndex(files, listed)
}

/**
 * The phone's photo library as a [LocalTree] for a backup: one folder per album. Listing it reads the library once
 * (sizes and times straight from MediaStore, nothing is read or hashed); files are read only when they are uploaded.
 * Writes are only for restores: part files stay in [scratch] and a finished file is saved into the library. Moving,
 * renaming and deleting photos is not supported, so photo backups can't be archived.
 */
class MediaStoreTree(private val source: MediaSource, private val videos: () -> Boolean, private val scratch: File): LocalTree {
    override val name: String get() = PHOTOS_NAME
    private var index: PhotoIndex? = null
    /** Reads the library again; the next listing of the top folder does too. */
    fun refresh(): PhotoIndex = guard(null) { photoIndex(source.rows(videos())) }.also { index = it }
    private fun current() = index ?: refresh()
    private fun <T> guard(relative: String?, block: () -> T): T = try { block() } catch (e: SecurityException) {
        throw LocalFsException("EACCES", PHOTOS_ACCESS, null, e)
    } catch (e: Exception) { throw localError(e, relative) }
    private fun file(relative: String) = File(scratch, relative)
    private fun folder(name: String) = LocalEntry(name, "album:$name", true, 0, 0)
    private fun entry(path: String, row: MediaRow) = LocalEntry(baseName(path), "media:${row.id}", false, row.size, row.modified)
    private fun unsupported(relative: String): Nothing = throw LocalFsException("EIO", PHOTOS_UNSUPPORTED, relative)
    private fun depth(relative: String) = if (relative.isEmpty()) 0 else relative.count { it == '/' } + 1

    override fun check() { if (source.access() == MediaAccess.NONE) throw LocalFsException("EACCES", PHOTOS_ACCESS) }
    override fun list(relative: String): List<LocalEntry> = when {
        relative.isEmpty() -> refresh().albums.keys.map(::folder)
        depth(relative) == 1 -> current().let { index -> index.albums[relative]?.map { entry(it, index.files.getValue(it)) } }
            ?: throw LocalFsException("ENOENT", "“$relative” could not be found.", relative)
        else -> throw LocalFsException("ENOENT", "“$relative” could not be found.", relative)
    }
    override fun stat(relative: String): LocalEntry? = when {
        relative.isEmpty() -> folder(PHOTOS_NAME)
        photoScratch(relative) -> file(relative).takeIf { it.isFile }?.let { LocalEntry(it.name, it.path, false, it.length(), it.lastModified()) }
        depth(relative) == 1 -> if (relative in current().albums) folder(relative) else null
        else -> current().files[relative]?.let { entry(relative, it) }
    }
    override fun ensureParents(relative: String, create: Boolean) {
        // Albums exist as long as they hold something; a restore into a new one creates it.
        if (depth(relative) > 2) throw LocalFsException("EIO", "Photos can only be restored into an album.", relative)
    }
    override fun mkdirs(relative: String) { if (depth(relative) > 1) throw LocalFsException("EIO", "Photos can only be restored into an album.", relative) }
    override fun openRead(relative: String): InputStream {
        if (photoScratch(relative)) return guard(relative) { file(relative).inputStream() }
        val row = current().files[relative] ?: throw LocalFsException("ENOENT", "“${baseName(relative)}” could not be found.", relative)
        return try { source.open(row) } catch (e: FileNotFoundException) {
            // Deleted since the library was read.
            index = null; throw localError(e, relative)
        } catch (e: SecurityException) { throw LocalFsException("EACCES", PHOTOS_ACCESS, null, e) }
    }
    override fun createFile(relative: String) {
        if (!photoScratch(relative)) unsupported(relative)
        val target = file(relative)
        guard(relative) { target.parentFile?.mkdirs(); if (!target.createNewFile()) throw LocalFsException("EEXIST", "“${baseName(relative)}” already exists.", relative) }
    }
    override fun openWrite(relative: String, append: Boolean): OutputStream {
        if (!photoScratch(relative)) unsupported(relative)
        return guard(relative) { FileOutputStream(file(relative), append) }
    }
    override fun rename(from: String, to: String) {
        if (!photoScratch(from)) unsupported(from)
        if (photoScratch(to)) {
            if (file(to).exists()) throw LocalFsException("EEXIST", "“${baseName(to)}” already exists.", to)
            if (!file(from).renameTo(file(to))) throw LocalFsException("ENOENT", "“${baseName(from)}” could not be found.", from)
            return
        }
        publish(from, to)
    }
    /** A restored file, downloaded and checked, saved into the library under its album. */
    private fun publish(from: String, to: String) {
        if (depth(to) != 2) throw LocalFsException("EIO", "Photos can only be restored into an album.", to)
        val name = baseName(to)
        val mime = mediaMime(name) ?: throw LocalFsException("EIO", "“$name” isn’t a photo or video, so it can’t be restored to the photo library. Download it instead.", to)
        if (stat(to) != null) throw LocalFsException("EEXIST", "“$name” already exists.", to)
        val part = file(from)
        if (!part.isFile) throw LocalFsException("ENOENT", "“$name” could not be found.", from)
        val row = guard(to) { source.insert(parentPath(to), name, mime, mime.startsWith("video/")) { out -> part.inputStream().use { it.copyTo(out) } } }
        part.delete()
        // Kept in the index at once: reading the whole library after each restored file would be slow.
        index = if (row != null) index?.takeIf { to !in it.files }?.with(to, row) else null
    }
    override fun delete(relative: String) {
        require(relative.isNotEmpty()) { "The photo library itself is never deleted." }
        if (photoScratch(relative)) { file(relative).delete(); return }
        if (depth(relative) == 1) unsupported(relative)
        val row = current().files[relative] ?: return
        try { source.delete(row) } catch (e: SecurityException) {
            // The restore can't finish, so what it downloaded goes too.
            file("$relative.harbor-part").delete(); file("$relative.harbor-plain").delete()
            throw LocalFsException("EACCES", "“${baseName(relative)}” is still on this phone, and harbor0 can’t replace photos it didn’t save. Delete it on the phone first, or download this version instead.", relative, e)
        } catch (e: IOException) { throw localError(e, relative) }
        index = null
    }
    override fun setModified(relative: String, mtime: Long): Boolean = current().files[relative]?.let { row -> runCatching { source.setTaken(row, mtime) }.getOrDefault(false) } ?: false
}
