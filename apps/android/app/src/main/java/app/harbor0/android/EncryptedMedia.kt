package app.harbor0.android

import android.media.MediaDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * A stored encrypted file read by range (web `openEncryptedLink`): only the 4 MiB chunks a read touches are downloaded
 * and decrypted, and the first and latest few are kept, as video readers ask for many small ranges around the same
 * places. [fetch] returns stored bytes [start, end).
 */
class EncryptedRangeReader(private val open: (ByteArray) -> FileCipher, private val storedSize: Long,
    private val fetch: suspend (start: Long, end: Long) -> ByteArray) {
    private var cipher: FileCipher? = null
    private val chunks = LinkedHashMap<Long, CompletableDeferred<ByteArray>>()
    private suspend fun cipher() = cipher ?: open(fetch(0, E2ee.HEADER_SIZE.toLong())).also { cipher = it }
    /** The original file's size. */
    suspend fun size() = cipher().plainSize(storedSize)

    private suspend fun chunk(index: Long): ByteArray {
        val cipher = cipher()
        val size = cipher.plainSize(storedSize)
        val (found, mine) = synchronized(chunks) {
            chunks.remove(index)?.let { chunks[index] = it; it to false } ?: CompletableDeferred<ByteArray>().let { fresh ->
                chunks[index] = fresh
                // The first chunk holds most indexes; keep it and the latest three.
                while (chunks.size > 4) chunks.remove(chunks.keys.firstOrNull { it != index && it != 0L } ?: break)
                fresh to true
            }
        }
        if (mine) try {
            val begin = index * cipher.chunkSize
            val end = minOf(size, begin + cipher.chunkSize)
            val range = cipher.cipherRange(size, begin, end)
            found.complete(cipher.decryptRange(size, begin, end, fetch(range.start, range.end)))
        } catch (e: Throwable) {
            synchronized(chunks) { chunks.remove(index) }
            found.completeExceptionally(e)
        }
        return found.await()
    }

    /** Original bytes [begin, end), or fewer at the end of the file. */
    suspend fun read(begin: Long, end: Long): ByteArray {
        val cipher = cipher()
        val last = minOf(end, cipher.plainSize(storedSize))
        if (last <= begin) return ByteArray(0)
        val out = ByteArray((last - begin).toInt())
        var at = begin
        while (at < last) {
            val index = at / cipher.chunkSize
            val plain = chunk(index)
            val offset = (at - index * cipher.chunkSize).toInt()
            val length = minOf(plain.size - offset, (last - at).toInt())
            plain.copyInto(out, (at - begin).toInt(), offset, offset + length)
            at += length
        }
        return out
    }
}

/** [EncryptedRangeReader] for MediaMetadataRetriever, which reads on its own thread. */
class EncryptedMediaSource(private val reader: EncryptedRangeReader) : MediaDataSource() {
    private val total by lazy { runBlocking { reader.size() } }
    override fun getSize() = total
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= total) return -1
        val bytes = runBlocking { reader.read(position, position + size) }
        bytes.copyInto(buffer, offset)
        return bytes.size
    }
    override fun close() {}
}

/**
 * A drive file for MediaPlayer to play as it reads (the web's media worker): [source] is read a [block] at a time,
 * keeping the first block (which holds most videos' index) and the latest three, as players ask for many small ranges
 * around the same places. Each read also starts on the next block, so playback doesn't wait at every block's end.
 */
class StreamingMediaSource(private val source: RangeSource, private val block: Int) : MediaDataSource() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kept = LinkedHashMap<Long, Deferred<ByteArray>>()
    private fun block(index: Long): Deferred<ByteArray> = synchronized(kept) {
        val found = kept.remove(index) ?: scope.async { source.read(index * block, minOf(source.size, (index + 1) * block)) }
        kept[index] = found
        while (kept.size > 4) kept.remove(kept.keys.firstOrNull { it != index && it != 0L } ?: break)
        found
    }
    override fun getSize() = source.size
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= source.size) return -1
        if (size <= 0) return 0
        val index = position / block
        if ((index + 1) * block < source.size) block(index + 1)
        // MediaPlayer reports a failed read as a playback error; the block is read again next time.
        val plain = try { runBlocking { block(index).await() } } catch (e: Exception) {
            synchronized(kept) { kept.remove(index) }
            throw java.io.IOException(e.message, e)
        }
        val from = (position - index * block).toInt()
        val length = minOf(size, plain.size - from)
        if (length <= 0) return -1
        plain.copyInto(buffer, offset, from, from + length)
        return length
    }
    override fun close() { scope.cancel() }
}
