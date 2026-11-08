package app.harbor0.android

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Reading archives in place (ZipReader.kt), against archives made by java.util.zip. */
class ZipReaderTest {
    /** An archive in memory that counts what is read from it. */
    private class Bytes(val data: ByteArray): RangeSource {
        var reads = 0; var bytesRead = 0L
        override val size = data.size.toLong()
        override suspend fun read(begin: Long, end: Long): ByteArray {
            reads++; bytesRead += end - begin
            return data.copyOfRange(begin.toInt(), minOf(end, size).toInt())
        }
    }
    private fun zip(block: ZipOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use(block) }.toByteArray()
    private fun ZipOutputStream.file(name: String, data: ByteArray, stored: Boolean = false) {
        val entry = ZipEntry(name)
        if (stored) { entry.method = ZipEntry.STORED; entry.size = data.size.toLong(); entry.crc = CRC32().apply { update(data) }.value }
        putNextEntry(entry); write(data); closeEntry()
    }
    private fun ZipOutputStream.folder(name: String) { putNextEntry(ZipEntry(name)); closeEntry() }
    private fun text(n: Int, seed: Int = 0) = ByteArray(n) { ((it * 7 + seed) % 26 + 'a'.code).toByte() }
    private suspend fun extract(source: RangeSource, file: ZipFile, limit: Long = Long.MAX_VALUE) =
        ByteArrayOutputStream().also { extractZipFile(source, file, it, limit) }.toByteArray()

    private val big = text(3 * 1024 * 1024 + 17, 3)
    private val archive = zip {
        file("readme.txt", "Hello from the archive\n".toByteArray())
        folder("docs/")
        file("docs/report.pdf", text(9000), stored = true)
        file("docs/nested/deep/notes.md", text(5000, 1))
        file("photos/big.bin", big)
        file("Café/menu.txt", "é".toByteArray())
        file("__MACOSX/._readme.txt", text(10))
        folder("empty/")
    }

    @Test fun listsFromTheEndAndArrangesFolders() = runBlocking {
        val source = Bytes(archive)
        val tree = zipTree(readZipDirectory(source))
        // Only the tail is read for a listing.
        assertEquals(1, source.reads)
        assertEquals(listOf("readme.txt", "docs", "photos", "Café", "empty"), tree.children[""])
        assertEquals(listOf("docs/report.pdf", "docs/nested"), tree.children["docs"])
        assertEquals(listOf("docs/nested/deep"), tree.children["docs/nested"])
        assertTrue("__MACOSX resource forks are hidden", tree.files.none { it.path.startsWith("__MACOSX") })
        assertEquals(14_000L, tree.folders["docs"]!!.size)
        assertEquals(0L, tree.folders["empty"]!!.size)
        val report = tree.byPath["docs/report.pdf"]!!
        assertEquals(0, report.method); assertEquals(9000L, report.size)
        assertEquals(8, tree.byPath["photos/big.bin"]!!.method)
        assertNotNull(report.modified)
        val archiveItem = DriveItem("zip", "Archive.zip", "FILE", updatedAt = "2026-10-01T00:00:00Z", ownerUserId = "u")
        val listed = listZipFolder(tree, "docs", archiveItem)
        assertEquals(listOf(zipFileId("docs/report.pdf"), zipFolderId("docs/nested")), listed.map { it.id })
        assertEquals("application/pdf", listed[0].mimeType)
        assertEquals("FOLDER", listed[1].type)
        assertEquals(5000L, listed[1].sizeBytes)
        assertEquals("docs/nested", zipEntryPath(listed[1].id))
    }
    @Test fun extractsStoredAndDeflatedFilesReadingOnlyThem() = runBlocking {
        val source = Bytes(archive)
        val tree = zipTree(readZipDirectory(source))
        source.bytesRead = 0
        assertArrayEquals(text(9000), extract(source, tree.byPath["docs/report.pdf"]!!))
        assertTrue(source.bytesRead < 9500)
        assertArrayEquals(big, extract(source, tree.byPath["photos/big.bin"]!!))
        assertEquals("é", String(extract(source, tree.byPath["Café/menu.txt"]!!)))
        // A preview reads only as much as it shows.
        assertArrayEquals(big.copyOf(1000), extract(source, tree.byPath["photos/big.bin"]!!, limit = 1000))
    }
    @Test fun damagedEntriesAndArchivesAreRefused() = runBlocking {
        val tree = zipTree(readZipDirectory(Bytes(archive)))
        val report = tree.byPath["docs/report.pdf"]!!
        // Flip one byte of the stored file's data: its CRC-32 no longer matches.
        val damaged = archive.copyOf()
        val dataAt = (report.offset + 30 + "docs/report.pdf".length + 100).toInt()
        damaged[dataAt] = (damaged[dataAt] + 1).toByte()
        assertEquals(ZIP_ENTRY_DAMAGED, assertThrows(ZipException::class.java) { runBlocking { extract(Bytes(damaged), report) } }.message)
        assertEquals(ZIP_NOT_ARCHIVE, assertThrows(ZipException::class.java) { runBlocking { readZipDirectory(Bytes(text(5000))) } }.message)
        assertEquals(ZIP_NOT_ARCHIVE, assertThrows(ZipException::class.java) { runBlocking { readZipDirectory(Bytes(ByteArray(10))) } }.message)
        val locked = report.copy(encrypted = true)
        assertNotNull(unreadable(locked))
        assertNotNull(unreadable(report.copy(method = 14)))
        assertNull(unreadable(report))
    }
    @Test fun copyPlansKeepTheLayoutBelowTheOpenFolder() = runBlocking {
        val tree = zipTree(readZipDirectory(Bytes(archive)))
        val plan = copyPlan(tree, listOf("docs/nested", "docs/report.pdf", "docs/nested/deep/notes.md"), "docs")
        assertEquals(listOf("docs/nested/deep/notes.md", "docs/report.pdf"), plan.files.map { it.path })
        assertEquals(0, plan.skipped)
        assertEquals(14_000L, plan.size)
        assertEquals(listOf("nested", "deep") to "notes.md", plan.layout(plan.files[0]))
        assertEquals(emptyList<String>() to "report.pdf", plan.layout(plan.files[1]))
        val all = copyPlan(tree, tree.children[""]!!, "")
        assertEquals(5, all.files.size)
        assertEquals(listOf("photos") to "big.bin", all.layout(all.files.first { it.name == "big.bin" }))
    }
    @Test fun treeFollowsHowArchivesUnpack() {
        fun f(path: String, size: Long = 1, directory: Boolean = false) = ZipFile(path, directory, size, size, 0, false, 0)
        val tree = zipTree(listOf(f("a/b.txt"), f("a/b.txt", 5), f("a"), f("x", 0, directory = true), f("x")))
        // A later entry with the same path wins; a file named like a folder is left out.
        assertEquals(listOf("a/b.txt"), tree.files.map { it.path })
        assertEquals(5L, tree.byPath["a/b.txt"]!!.size)
        assertEquals(listOf("a", "x"), tree.children[""])
        // Paths can't climb out of the folder they unpack into.
        assertEquals(listOf("etc/passwd", "C:/win/x", "a/b"), listOf("../../etc/passwd", "C:\\win\\x", "./a//b/").map(::cleanZipPath))
    }
    @Test fun zip64ArchivesList() = runBlocking {
        // More than 65,535 entries makes java.util.zip write ZIP64 end records.
        val many = zip { for (i in 0 until 65_600) file("f/$i.txt", byteArrayOf(i.toByte()), stored = true) }
        val source = Bytes(many)
        val files = readZipDirectory(source)
        assertEquals(65_600, files.size)
        val last = files.last()
        assertEquals("f/65599.txt", last.path)
        assertArrayEquals(byteArrayOf(65_599.toByte()), extract(source, last))
    }
    @Test fun namesWithoutTheUtf8FlagFallBackToCp437() {
        assertEquals("Café", decodeZipName("Café".toByteArray(), false))
        assertEquals("Ç½", decodeZipName(byteArrayOf(0x80.toByte(), 0xab.toByte()), false))
        assertTrue(isZip(DriveItem("1", "Backup.ZIP", "FILE")))
        assertTrue(isZip(DriveItem("1", "download", "FILE", mimeType = "application/zip")))
        assertFalse(isZip(DriveItem("1", "photos.zip", "FOLDER")))
    }
}
