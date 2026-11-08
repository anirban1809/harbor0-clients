package app.harbor0.android

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** The phone's media library through MediaStore: every volume, photos and (optionally) videos. */
class AndroidMediaSource(private val context: Context): MediaSource {
    private val resolver get() = context.contentResolver
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    override fun access() = photoAccess(Build.VERSION.SDK_INT, ::granted)
    private fun collection(video: Boolean): Uri = when {
        Build.VERSION.SDK_INT >= 29 && video -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        Build.VERSION.SDK_INT >= 29 -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        video -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    private fun uri(row: MediaRow) = ContentUris.withAppendedId(collection(row.video), row.id)

    override fun rows(videos: Boolean): List<MediaRow> = query(false, null) + if (videos) query(true, null) else emptyList()
    @Suppress("DEPRECATION")
    private fun query(video: Boolean, only: Uri?): List<MediaRow> {
        val path = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME, path,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.Images.ImageColumns.DATE_TAKEN, MediaStore.MediaColumns.MIME_TYPE)
        // Items still being written and items in the trash are left out (newer versions also do so by default).
        val selection = when {
            Build.VERSION.SDK_INT >= 30 -> "${MediaStore.MediaColumns.IS_PENDING}=0 AND ${MediaStore.MediaColumns.IS_TRASHED}=0"
            Build.VERSION.SDK_INT >= 29 -> "${MediaStore.MediaColumns.IS_PENDING}=0"
            else -> null
        }
        val cursor = resolver.query(only ?: collection(video), projection, selection, null, null) ?: return emptyList()
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    if (c.isNull(4)) continue
                    // DATA is a full path before Android 10: its folder stands in for the relative path.
                    val folder = c.getString(3)?.let { if (Build.VERSION.SDK_INT >= 29) it else it.substringBeforeLast('/', "") }
                    val modified = c.getLong(5) * 1000
                    add(MediaRow(c.getLong(0), video, c.getString(1).orEmpty(), c.getString(2), folder, c.getLong(4),
                        if (modified > 0) modified else c.getLong(6), c.getString(7)))
                }
            }
        }
    }
    override fun open(row: MediaRow): InputStream {
        var target = uri(row)
        // Without this, photos are read with their location removed, and the backup would not be the original.
        if (Build.VERSION.SDK_INT >= 29 && granted(Manifest.permission.ACCESS_MEDIA_LOCATION)) target = MediaStore.setRequireOriginal(target)
        return try { resolver.openInputStream(target) } catch (e: SecurityException) { if (target == uri(row)) throw e; resolver.openInputStream(uri(row)) }
            ?: throw FileNotFoundException(row.name)
    }
    override fun insert(album: String, name: String, mime: String, video: Boolean, write: (OutputStream) -> Unit): MediaRow? {
        if (Build.VERSION.SDK_INT < 29) return legacyInsert(album, name, mime, write)
        val volume = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val created = resolver.insert(volume, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, photoRestoreFolder(album)); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: throw IOException("The photo library didn’t accept “$name”.")
        try {
            (resolver.openOutputStream(created, "w") ?: throw IOException("The photo library didn’t accept “$name”.")).use(write)
            resolver.update(created, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) { runCatching { resolver.delete(created, null, null) }; throw e }
        return query(video, created).firstOrNull()
    }
    /** Android 9 and earlier: written straight into the public folder, then added to the library by the media scanner. */
    @Suppress("DEPRECATION")
    private fun legacyInsert(album: String, name: String, mime: String, write: (OutputStream) -> Unit): MediaRow? {
        val folder = File(Environment.getExternalStorageDirectory(), photoRestoreFolder(album))
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("“${photoRestoreFolder(album).trimEnd('/')}” could not be created.")
        val target = File(folder, name)
        if (target.exists()) throw LocalFsException("EEXIST", "“$name” already exists.", "$album/$name")
        try { target.outputStream().use(write) } catch (e: Exception) { target.delete(); throw e }
        MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mime), null)
        return null
    }
    override fun delete(row: MediaRow) { resolver.delete(uri(row), null, null) }
    override fun setTaken(row: MediaRow, millis: Long) = Build.VERSION.SDK_INT >= 29 &&
        resolver.update(uri(row), ContentValues().apply { put(MediaStore.Images.ImageColumns.DATE_TAKEN, millis) }, null, null) > 0
}

/** The local folder for a sync or backup root: the photo library for a photo backup, else its document tree. */
fun localTree(context: Context, root: SyncRoot, options: () -> PhotoOptions? = { root.photos }): LocalTree =
    if (root.isPhotos) MediaStoreTree(AndroidMediaSource(context), { options()?.videos ?: true }, File(context.noBackupFilesDir, "photo-restores/${root.id}"))
    else SafTree(context, root.treeUri.toUri())
