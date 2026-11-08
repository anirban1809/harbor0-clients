package app.harbor0.android

import java.io.File

// What the preview sheet can show inline, as apps/web/lib/file-preview.ts.
private val previewExtensions = mapOf(
    "text" to ("txt md markdown csv tsv json jsonl ndjson yaml yml xml html htm css scss sass less js jsx mjs cjs ts tsx py rb go rs java c h cpp hpp cs sh bash zsh " +
        "sql log ini conf cfg toml env gitignore dockerfile makefile").split(' ').toSet(),
    "image" to "png jpg jpeg gif webp avif bmp ico svg".split(' ').toSet(),
    "video" to "mp4 m4v webm ogv mov".split(' ').toSet(),
    "audio" to "mp3 wav wave ogg oga opus m4a aac flac weba aif aiff".split(' ').toSet(),
    "pdf" to setOf("pdf"),
)
private val textMime = Regex("^application/(json|.+\\+json|xml|.+\\+xml|javascript|x-javascript|yaml|x-yaml|toml)$")

/** "text", "image", "video", "audio" or "pdf"; null when there is no inline preview. Generic MIME types fall back to the extension. */
fun previewKind(name: String, mimeType: String?): String? {
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    for (kind in listOf("image", "video", "audio")) if (mime.startsWith("$kind/")) return kind
    if (mime.startsWith("text/") || textMime.matches(mime)) return "text"
    if (mime == "application/pdf" || mime == "application/x-pdf") return "pdf"
    if (mime.isNotEmpty() && mime !in listOf("application/octet-stream", "binary/octet-stream")) return null
    val extension = name.lowercase().substringAfterLast('.')
    return listOf("text", "image", "video", "audio", "pdf").firstOrNull { extension in previewExtensions.getValue(it) }
}

const val TEXT_PREVIEW_LIMIT = 1_000_000
const val BINARY_TEXT_MESSAGE = "This file contains binary data and cannot be shown as text."
/** The first 1 MB of a text file, and whether there was more. Throws when it holds NUL bytes, as the web text preview does. */
data class TextPreview(val text: String, val truncated: Boolean)
fun readTextPreview(file: File, limit: Int = TEXT_PREVIEW_LIMIT): TextPreview {
    val bytes = file.inputStream().use { input ->
        val buffer = ByteArray(limit + 1)
        var n = 0
        while (n < buffer.size) { val read = input.read(buffer, n, buffer.size - n); if (read < 0) break; n += read }
        buffer.copyOf(n)
    }
    val truncated = bytes.size > limit
    // A cut in the middle of a character is dropped by decoding the bytes before it.
    val text = String(bytes, 0, minOf(bytes.size, limit), Charsets.UTF_8).let { if (truncated) it.trimEnd('�') else it }
    if (text.contains('\u0000')) throw IllegalStateException(BINARY_TEXT_MESSAGE)
    return TextPreview(text, truncated)
}
