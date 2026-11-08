package app.harbor0.android

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

// Reads ZIP archives in place, per PKWARE APPNOTE 6.3.10, as apps/web/lib/zip-reader.ts: the central directory at the
// end lists every entry, so a listing reads only the archive's last few kilobytes, and one entry reads only its own
// bytes. Nothing is written back; archives are browsed read-only.

/** Bytes [begin, end) of a file in the drive, read in place (plaintext for an encrypted file). */
interface RangeSource {
    val size: Long
    suspend fun read(begin: Long, end: Long): ByteArray
}
/** An archive the app can't open, with the reason to show. */
class ZipException(message: String): IOException(message)

data class ZipFile(
    /** Path inside the archive, without a leading or trailing slash. */
    val path: String, val directory: Boolean, val size: Long, val compressedSize: Long,
    /** 0 is stored, 8 is deflate; anything else can be listed but not opened. */
    val method: Int, val encrypted: Boolean,
    /** Where the entry's local header starts. */
    val offset: Long, val modified: String? = null, val crc: Long = 0,
) { val name get() = path.substringAfterLast('/') }

private const val EOCD = 0x06054b50
private const val EOCD64 = 0x06064b50
private const val LOCATOR64 = 0x07064b50
private const val CENTRAL = 0x02014b50
private const val LOCAL = 0x04034b50
// The end record is 22 bytes plus a comment of up to 64 KB; the ZIP64 locator sits before it.
private const val TAIL = 22 + 0xffff + 20
/** Central directories past this are not read: about a million entries. */
private const val MAX_DIRECTORY = 128L * 1024 * 1024
private const val READ_WINDOW = 4 * 1024 * 1024
const val ZIP_NOT_ARCHIVE = "This file is not a ZIP archive, or it is damaged."
const val ZIP_DAMAGED = "This archive is damaged. Download it to open it."
const val ZIP_ENTRY_DAMAGED = "This file in the archive is damaged and can’t be opened."

private fun ByteArray.u16(at: Int) = (this[at].toInt() and 255) or ((this[at + 1].toInt() and 255) shl 8)
private fun ByteArray.u32(at: Int) = (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)
private fun ByteArray.u64(at: Int): Long = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).getLong(at)

/** MS-DOS date and time, as written by most zip tools, in local time. */
fun dosDate(time: Int, date: Int, zone: ZoneId = ZoneId.systemDefault()): String? {
    if (date == 0) return null
    return runCatching {
        LocalDateTime.of(1980 + (date shr 9), (date shr 5) and 15, date and 31, time shr 11, (time shr 5) and 63, (time and 31) * 2)
            .atZone(zone).toInstant().toString()
    }.getOrNull()
}

// Names without the UTF-8 flag are CP437, though many tools write UTF-8 regardless.
private const val CP437 = "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒáíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ "
fun decodeZipName(bytes: ByteArray, flaggedUtf8: Boolean): String {
    if (flaggedUtf8) return String(bytes, Charsets.UTF_8)
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        buildString { bytes.forEach { b -> val v = b.toInt() and 255; append(if (v < 128) v.toChar() else CP437[v - 128]) } }
    }
}

/** Folder-safe path: no leading slashes, no `.` or `..` segments, forward slashes only. */
fun cleanZipPath(path: String) = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")

/** Lists the entries of an archive from its central directory. */
suspend fun readZipDirectory(source: RangeSource): List<ZipFile> {
    if (source.size < 22) throw ZipException(ZIP_NOT_ARCHIVE)
    val tailStart = maxOf(0L, source.size - TAIL)
    val tail = source.read(tailStart, source.size)
    var end = -1
    for (i in tail.size - 22 downTo 0) {
        if (tail.u32(i) == EOCD.toLong() && i + 22 + tail.u16(i + 20) <= tail.size) { end = i; break }
    }
    if (end < 0) throw ZipException(ZIP_NOT_ARCHIVE)
    var count = tail.u16(end + 10).toLong()
    var dirSize = tail.u32(end + 12)
    var dirOffset = tail.u32(end + 16)
    val locator = end - 20
    if (locator >= 0 && tail.u32(locator) == LOCATOR64.toLong()) {
        val at = tail.u64(locator + 8)
        val record = if (at >= tailStart && at - tailStart + 56 <= tail.size) tail.copyOfRange((at - tailStart).toInt(), (at - tailStart).toInt() + 56)
            else if (at in 0 until source.size) source.read(at, minOf(source.size, at + 56)) else ByteArray(0)
        if (record.size < 56 || record.u32(0) != EOCD64.toLong()) throw ZipException(ZIP_NOT_ARCHIVE)
        count = record.u64(32); dirSize = record.u64(40); dirOffset = record.u64(48)
    }
    if (dirSize > MAX_DIRECTORY) throw ZipException("This archive has too many files to list here. Download it to open it.")
    if (dirOffset < 0 || dirSize < 0 || dirOffset + dirSize > source.size) throw ZipException(ZIP_NOT_ARCHIVE)
    val dir = if (dirOffset >= tailStart) tail.copyOfRange((dirOffset - tailStart).toInt(), (dirOffset - tailStart + dirSize).toInt())
        else source.read(dirOffset, dirOffset + dirSize)
    val files = mutableListOf<ZipFile>()
    var at = 0
    var n = 0L
    while (n < count && at + 46 <= dir.size) {
        if (dir.u32(at) != CENTRAL.toLong()) throw ZipException(ZIP_NOT_ARCHIVE)
        val flags = dir.u16(at + 8)
        val method = dir.u16(at + 10)
        val nameLength = dir.u16(at + 28)
        val extraLength = dir.u16(at + 30)
        val commentLength = dir.u16(at + 32)
        val external = dir.u32(at + 38)
        var compressedSize = dir.u32(at + 20)
        var size = dir.u32(at + 24)
        var offset = dir.u32(at + 42)
        if (at + 46 + nameLength + extraLength > dir.size) throw ZipException(ZIP_NOT_ARCHIVE)
        val rawName = decodeZipName(dir.copyOfRange(at + 46, at + 46 + nameLength), flags and 0x800 != 0)
        // Sizes and offsets too large for 32 bits are in the ZIP64 extra field, in this order.
        var extra = at + 46 + nameLength
        val extraEnd = extra + extraLength
        while (extra + 4 <= extraEnd) {
            val id = dir.u16(extra)
            val length = dir.u16(extra + 2)
            if (id == 1) {
                var field = extra + 4
                if (size == 0xffffffffL && field + 8 <= extraEnd) { size = dir.u64(field); field += 8 }
                if (compressedSize == 0xffffffffL && field + 8 <= extraEnd) { compressedSize = dir.u64(field); field += 8 }
                if (offset == 0xffffffffL && field + 8 <= extraEnd) offset = dir.u64(field)
            }
            extra += 4 + length
        }
        // Directories end in a slash; some Windows tools mark them only with the attribute.
        val directory = rawName.endsWith('/') || rawName.endsWith('\\') || (external and 0x10 != 0L && size == 0L)
        val path = cleanZipPath(rawName)
        if (path.isNotEmpty()) files += ZipFile(path, directory, if (directory) 0 else size, compressedSize, method, flags and 1 != 0, offset,
            dosDate(dir.u16(at + 12), dir.u16(at + 14)), dir.u32(at + 16))
        at = extraEnd + commentLength
        n++
    }
    return files
}

/** Why an entry can't be opened on this phone, if it can't. */
fun unreadable(file: ZipFile): String? = when {
    file.encrypted -> "This file is password-protected. Download the archive to open it."
    file.method != 0 && file.method != 8 -> "This file uses a compression method harbor0 can’t open. Download the archive to open it."
    else -> null
}

/**
 * Writes an entry's contents to [out], decompressed as they are read, and checks them against the archive's CRC-32.
 * Stops quietly after [limit] bytes (for text previews); returns the bytes written.
 */
suspend fun extractZipFile(source: RangeSource, file: ZipFile, out: OutputStream, limit: Long = Long.MAX_VALUE,
    onProgress: (Long) -> Unit = {}): Long {
    unreadable(file)?.let { throw ZipException(it) }
    // The local header repeats the name and has its own extra field; the data follows them.
    val header = source.read(file.offset, minOf(source.size, file.offset + 30))
    if (header.size < 30 || header.u32(0) != LOCAL.toLong()) throw ZipException(ZIP_DAMAGED)
    var at = file.offset + 30 + header.u16(26) + header.u16(28)
    val end = at + file.compressedSize
    if (end > source.size) throw ZipException(ZIP_DAMAGED)
    val crc = CRC32()
    var written = 0L
    fun emit(bytes: ByteArray, length: Int): Boolean {
        val take = minOf(length.toLong(), limit - written).toInt()
        if (take > 0) { out.write(bytes, 0, take); crc.update(bytes, 0, take); written += take; onProgress(written) }
        return written < limit
    }
    val inflater = if (file.method == 8) Inflater(true) else null
    try {
        val buffer = ByteArray(256 * 1024)
        var more = true
        while (more && at < end) {
            val next = minOf(end, at + READ_WINDOW)
            val chunk = source.read(at, next)
            if (chunk.size.toLong() != next - at) throw ZipException(ZIP_ENTRY_DAMAGED)
            at = next
            if (inflater == null) { more = emit(chunk, chunk.size); continue }
            inflater.setInput(chunk)
            while (more && !inflater.needsInput() && !inflater.finished()) {
                val n = try { inflater.inflate(buffer) } catch (e: DataFormatException) { throw ZipException(ZIP_ENTRY_DAMAGED) }
                if (n == 0 && inflater.needsDictionary()) throw ZipException(ZIP_ENTRY_DAMAGED)
                if (n > 0) more = emit(buffer, n)
            }
            if (inflater.finished()) break
        }
        // A deflate stream may finish its last block only once all input is in.
        if (inflater != null && more && !inflater.finished()) {
            while (more && !inflater.finished()) {
                val n = try { inflater.inflate(buffer) } catch (e: DataFormatException) { throw ZipException(ZIP_ENTRY_DAMAGED) }
                if (n == 0) break
                more = emit(buffer, n)
            }
        }
    } finally { inflater?.end() }
    // Only a whole file can be checked: one cut short for a preview is taken as read.
    if (written < limit && (written != file.size || crc.value != file.crc)) throw ZipException(ZIP_ENTRY_DAMAGED)
    return written
}

data class ZipFolder(val path: String, val name: String, var size: Long = 0, var modified: String? = null)
class ZipTree(val files: List<ZipFile>,
    /** Every folder, including ones only implied by the paths under them. */
    val folders: Map<String, ZipFolder>,
    /** Paths directly inside each folder, by the folder's path ('' is the archive root). */
    val children: Map<String, List<String>>, val byPath: Map<String, ZipFile>)
private fun parentOf(path: String) = path.substring(0, maxOf(0, path.lastIndexOf('/')))

/** Arranges a listing into folders, as the archive would unpack. */
fun zipTree(entries: List<ZipFile>): ZipTree {
    val folders = linkedMapOf<String, ZipFolder>()
    val children = linkedMapOf<String, MutableList<String>>("" to mutableListOf())
    val byPath = linkedMapOf<String, ZipFile>()
    fun addFolder(path: String, modified: String? = null): ZipFolder {
        folders[path]?.let { if (modified != null && it.modified == null) it.modified = modified; return it }
        val folder = ZipFolder(path, path.substringAfterLast('/'), modified = modified)
        folders[path] = folder; children[path] = mutableListOf()
        val parent = parentOf(path)
        if (path.contains('/')) addFolder(parent)
        // A file and a folder of the same name can't both unpack; the file is listed.
        if (path !in byPath) children.getValue(parent) += path
        return folder
    }
    val files = mutableListOf<ZipFile>()
    for (entry in entries) {
        // macOS adds resource forks under __MACOSX; Archive Utility hides them too.
        if (entry.path == "__MACOSX" || entry.path.startsWith("__MACOSX/")) continue
        if (entry.directory) { addFolder(entry.path, entry.modified); continue }
        // A later entry with the same path replaces the earlier one when unpacked.
        val earlier = byPath[entry.path]
        if (earlier != null) { files[files.indexOf(earlier)] = entry; byPath[entry.path] = entry; continue }
        if (entry.path in folders) continue
        byPath[entry.path] = entry
        files += entry
        val parent = parentOf(entry.path)
        if (parent.isNotEmpty()) addFolder(parent)
        children.getValue(parent) += entry.path
    }
    for (file in files) {
        var p = parentOf(file.path)
        while (p.isNotEmpty()) { folders.getValue(p).size += file.size; p = parentOf(p) }
    }
    return ZipTree(files, folders, children, byPath)
}

/** Every file at or under `path`, for copying or extracting a folder. */
fun filesUnder(tree: ZipTree, path: String): List<ZipFile> = tree.byPath[path]?.let { listOf(it) }
    ?: if (path.isEmpty()) tree.files else tree.files.filter { it.path.startsWith("$path/") }

/** Copies are unpacked on the phone before they upload; past this, unpack on a computer. */
const val ZIP_COPY_MAX = 2L * 1024 * 1024 * 1024
/** Files larger than this aren't previewed from an archive. */
const val ZIP_PREVIEW_MAX = 512L * 1024 * 1024
data class ZipCopyPlan(val files: List<ZipFile>, val skipped: Int, val size: Long, val base: String) {
    /** Where a file lands below the destination: its folders, then its name. */
    fun layout(file: ZipFile): Pair<List<String>, String> {
        val relative = if (base.isNotEmpty()) file.path.removePrefix("$base/") else file.path
        val segments = relative.split('/')
        return segments.dropLast(1) to segments.last()
    }
}
/** The files under `paths`, for unpacking into the drive, laid out as they are below `base`. */
fun copyPlan(tree: ZipTree, paths: List<String>, base: String): ZipCopyPlan {
    val all = paths.flatMap { filesUnder(tree, it) }.distinct()
    // Password-protected files, and ones in formats the phone can't unpack, are left out.
    val files = all.filter { unreadable(it) == null }
    return ZipCopyPlan(files, all.size - files.size, files.sumOf { it.size }, base)
}

/** IDs of listed entries: folders and files are kept apart in case a path is both. */
fun zipFolderId(path: String) = "zip-folder:$path"
fun zipFileId(path: String) = "zip-file:$path"
fun zipEntryPath(id: String) = id.substringAfter(':')

/** What a folder in the archive lists, as drive items (with the archive's owner and date when an entry has none). */
fun listZipFolder(tree: ZipTree, path: String, archive: DriveItem): List<DriveItem> = tree.children[path].orEmpty().map { child ->
    val file = tree.byPath[child]
    val name = child.substringAfterLast('/')
    val modified = file?.modified ?: tree.folders[child]?.modified ?: archive.updatedAt
    if (file != null) DriveItem(zipFileId(child), name, "FILE", mimeType = guessMime(name), sizeBytes = file.size, updatedAt = modified,
        createdAt = modified, ownerUserId = archive.ownerUserId)
    else DriveItem(zipFolderId(child), name, "FOLDER", sizeBytes = tree.folders[child]?.size ?: 0, updatedAt = modified, createdAt = modified,
        ownerUserId = archive.ownerUserId)
}

fun isZip(item: DriveItem) = !item.isFolder && (item.name.lowercase().endsWith(".zip") || item.mimeType in listOf("application/zip", "application/x-zip-compressed"))
