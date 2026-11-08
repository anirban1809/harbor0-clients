package app.harbor0.android

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/** Reading an encrypted file by range (EncryptedMedia.kt), as video frames are drawn from encrypted videos. */
class EncryptedRangeReaderTest {
    private val keys = E2ee.accountKeys(ByteArray(32) { it.toByte() })
    private val plain = Random(1).nextBytes(10 * 1024 * 1024 + 123)
    private val stored = ByteArrayOutputStream().also { FileCipher.create(keys).encrypt(plain.inputStream(), plain.size.toLong(), it) }.toByteArray()

    @Test fun readsAnyRangeDownloadingOnlyTheChunksItTouches() = runBlocking {
        val fetched = mutableListOf<Pair<Long, Long>>()
        val reader = EncryptedRangeReader({ FileCipher.open(keys, it) }, stored.size.toLong()) { start, end ->
            fetched += start to end
            stored.copyOfRange(start.toInt(), end.toInt())
        }
        assertEquals(plain.size.toLong(), reader.size())
        // Across the first chunk boundary, then the very end.
        val chunk = E2ee.CHUNK_SIZE.toLong()
        assertArrayEquals(plain.copyOfRange((chunk - 10).toInt(), (chunk + 10).toInt()), reader.read(chunk - 10, chunk + 10))
        assertArrayEquals(plain.copyOfRange(plain.size - 50, plain.size), reader.read(plain.size - 50L, plain.size + 100L))
        // Header, chunks 0 and 1, then the last chunk; reading chunk 0 again downloads nothing.
        assertEquals(4, fetched.size)
        reader.read(5, 15)
        assertEquals(4, fetched.size)
        assertEquals(0, reader.read(plain.size.toLong(), plain.size + 1L).size)
    }

    @Test fun manyRandomReadsOfASmallFileMatchTheOriginal() = runBlocking {
        val small = Random(2).nextBytes(2_394_045)
        val sealed = ByteArrayOutputStream().also { FileCipher.create(keys).encrypt(small.inputStream(), small.size.toLong(), it) }.toByteArray()
        val reader = EncryptedRangeReader({ FileCipher.open(keys, it) }, sealed.size.toLong()) { start, end -> sealed.copyOfRange(start.toInt(), end.toInt()) }
        val random = Random(3)
        repeat(500) {
            val begin = random.nextLong(small.size.toLong())
            val end = begin + random.nextLong(1, 300_000)
            val expected = small.copyOfRange(begin.toInt(), minOf(end, small.size.toLong()).toInt())
            assertArrayEquals("read $begin..$end", expected, reader.read(begin, end))
        }
    }
}
