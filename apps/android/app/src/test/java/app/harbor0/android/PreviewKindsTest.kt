package app.harbor0.android

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PreviewKindsTest {
    @Test fun kindsFromMimeThenExtension() {
        assertEquals("image", previewKind("a.bin", "image/png"))
        assertEquals("video", previewKind("clip", "video/mp4"))
        assertEquals("audio", previewKind("song", "audio/mpeg"))
        assertEquals("text", previewKind("a", "application/ld+json"))
        assertEquals("pdf", previewKind("a", "application/pdf"))
        assertNull(previewKind("a.pdf", "application/zip"))
        assertEquals("pdf", previewKind("Report.PDF", "application/octet-stream"))
        assertEquals("text", previewKind("notes.md", null))
        assertEquals("video", previewKind("movie.mov", ""))
        assertNull(previewKind("archive.zip", null))
    }
    @Test fun textPreviewIsCappedAndRefusesBinary() {
        val file = File.createTempFile("preview", ".txt")
        try {
            file.writeText("hello")
            assertEquals(TextPreview("hello", false), readTextPreview(file))
            file.writeText("abcdef")
            assertEquals(TextPreview("abcd", true), readTextPreview(file, limit = 4))
            file.writeBytes(byteArrayOf(65, 0, 66))
            assertEquals(BINARY_TEXT_MESSAGE, runCatching { readTextPreview(file) }.exceptionOrNull()?.message)
        } finally { file.delete() }
    }
}
