package app.harbor0.android

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID

// The storage audit (apps/web/lib/storage-audit.ts, components/storage-audit.tsx): a spreadsheet of
// every stored file version behind the account's storage total.

/** One stored file version, the unit storage is charged in. [countedBytes] is what it adds to `usedBytes`. */
@Serializable data class StorageAuditRow(val itemId: String, val versionId: String, val path: String, val location: String,
    val locationDetail: String? = null, val state: String, val versionNumber: Int = 0, val sizeBytes: Long = 0, val countedBytes: Long = 0,
    val contentHash: String = "", val uploadedAt: String = "", val uploadedFrom: String? = null)
@Serializable data class StorageAuditPage(val rows: List<StorageAuditRow>, val storage: Storage, val nextCursor: String? = null)

private val auditLocations = linkedMapOf("MY_DRIVE" to "My Drive", "BACKUP" to "Backups", "SYNC" to "Synced folders", "TRASH" to "Trash", "DELETING" to "Being deleted")
private val auditStates = mapOf("CURRENT" to "Current version", "PREVIOUS_VERSION" to "Previous version",
    "RETAINED_FOR_TRANSFER" to "Kept for a sent transfer", "ON_DEVICES_ONLY" to "On devices only (not counted)")
private fun auditSize(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val unit = minOf(4, (Math.log10(bytes.toDouble()) / 3).toInt())
    return "%.2f %s".format(Locale.US, bytes / Math.pow(1000.0, unit.toDouble()), listOf("B", "KB", "MB", "GB", "TB")[unit])
}
private fun auditCell(value: Any?): String {
    if (value == null) return ""
    var text = value.toString()
    // Keep spreadsheet apps from evaluating file names as formulas.
    if (value is String && text.isNotEmpty() && text[0] in "=+-@\t\r") text = "'$text"
    return if (text.any { it in ",\"\n\r" }) "\"${text.replace("\"", "\"\"")}\"" else text
}
private fun auditLine(values: List<Any?>) = values.joinToString(",", transform = ::auditCell)

/** The audit CSV, byte for byte as the web builds it: a summary, then every version, largest first. */
fun storageAuditCsv(rows: List<StorageAuditRow>, storage: Storage, at: Instant = Instant.now()): String {
    val counted = rows.sumOf { it.countedBytes }
    val byLocation = rows.groupBy { it.location }.mapValues { (_, list) -> list.sumOf { it.countedBytes } }
    val previous = rows.filter { it.state == "PREVIOUS_VERSION" }.sumOf { it.countedBytes }
    val summary = auditLocations.filterKeys { it in byLocation }.map { (key, label) -> label to byLocation.getValue(key) } + listOf(
        "  of which previous versions" to previous, "Total of files listed below" to counted, "Storage used (account total)" to storage.usedBytes,
        "Unaccounted difference" to storage.usedBytes - counted, "Uploads in progress (reserved)" to storage.reservedBytes,
        "Available" to storage.availableBytes, "Quota" to storage.quotaBytes)
    val out = mutableListOf(auditLine(listOf("harbor0 storage audit", at.toString())), auditLine(listOf("Summary", "Bytes", "Size")))
    summary.forEach { (label, bytes) -> out += auditLine(listOf(label, bytes, auditSize(Math.abs(bytes)))) }
    out += ""
    out += auditLine(listOf("Path", "Location", "Location detail", "Version state", "Version", "Size (bytes)", "Counted (bytes)", "Size",
        "Uploaded at", "Uploaded from", "SHA-256", "File ID", "Version ID"))
    rows.sortedWith(compareByDescending<StorageAuditRow> { it.countedBytes }.thenBy { it.path }).forEach { r ->
        out += auditLine(listOf(r.path, auditLocations[r.location], r.locationDetail, auditStates[r.state], r.versionNumber, r.sizeBytes, r.countedBytes,
            auditSize(r.sizeBytes), r.uploadedAt, r.uploadedFrom, r.contentHash, r.itemId, r.versionId))
    }
    return out.joinToString("\r\n") + "\r\n"
}

/** Reads every audit page and saves the CSV to Downloads. */
class StorageAuditState(private val m: WorkspaceModel) {
    /** idle, running, done or error, as the web card. */
    var phase by mutableStateOf("idle"); private set
    var count by mutableIntStateOf(0); private set
    var message by mutableStateOf<String?>(null); private set
    fun run() {
        if (phase == "running") return
        phase = "running"; message = null
        m.viewModelScope.launch {
            try {
                val rows = mutableListOf<StorageAuditRow>()
                var cursor: String? = null
                var storage: Storage
                val seen = mutableSetOf<String>()
                do {
                    val page = m.api.get<StorageAuditPage>(withQuery("/v1/storage/audit", "limit" to "200", "cursor" to cursor))
                    rows += page.rows; storage = page.storage; cursor = page.nextCursor
                    check(cursor == null || seen.add(cursor)) { "Could not load the whole audit. Try again." }
                } while (cursor != null)
                val at = Instant.now()
                val name = "harbor0-storage-audit-${at.toString().take(10)}.csv"
                // BOM so spreadsheet apps read non-ASCII file names as UTF-8.
                val text = Char(0xFEFF) + storageAuditCsv(rows, storage, at)
                save(name, text)
                count = rows.size; phase = "done"
            } catch (e: CancellationException) { phase = "idle"; throw e } catch (e: Exception) { message = m.message(e); phase = "error" }
        }
    }
    private suspend fun save(name: String, text: String) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = m.context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "text/csv"); put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not save this file. Try again.")
            try {
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("Could not save this file. Try again.")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
        } else {
            // Android 9 and earlier choose where to save it from the preview.
            val file = File(m.context.cacheDir, "previews/${UUID.randomUUID()}/$name").apply { parentFile?.mkdirs(); writeText(text) }
            withContext(Dispatchers.Main) { m.preview = Preview(file, "text/csv") }
        }
    }
}

/** The Storage page's audit card. */
@Composable fun StorageAuditCard(state: StorageAuditState) {
    Card(Modifier.testTag("storage-audit")) {
        CardHeader("Storage audit", "Download a spreadsheet of every file version counted toward your storage — My Drive, backups and synced folders from all your devices, trash, and version history.")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (state.phase) {
                "done" -> Alert("Downloaded an audit of ${"%,d".format(state.count)} file ${if (state.count == 1) "version" else "versions"}.", tone = Tone.Success)
                "error" -> Alert(state.message ?: "", tone = Tone.Danger)
            }
            HButton(if (state.phase == "running") "Preparing audit…" else "Download audit (CSV)", state::run, Modifier.fillMaxWidth(), Variant.Outline,
                enabled = state.phase != "running", icon = Lucide.FileSpreadsheet)
        }
    }
}
