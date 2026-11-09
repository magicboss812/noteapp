package dev.folio.feature.editor.state

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.WorkerThread
import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.model.AssetId
import dev.folio.core.model.Attachment
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** A picked file ready to attach (A-070): its [bytes], [mime] type and display [name]. */
class ImportedFile(
    val bytes: ByteArray,
    val mime: String,
    val name: String,
)

/** The attachment Open asks the editor to open: its [asset], file [name] and [mime] type. */
data class AttachmentTarget(
    val asset: AssetId,
    val name: String,
    val mime: String,
)

/** Reading picked files, placing their chips and writing them out for other apps (A-070). */
internal object AttachmentFiles {
    /** Largest file attached (the asset entry limit of the container). */
    const val MAX_BYTES = 64L * 1024 * 1024

    /** Where Open writes attachments, under the app cache (the editor FileProvider's `attachments/` path). */
    const val CACHE_DIR = "attachments"

    private const val ID_DIR_CHARS = 16
    private const val MAX_NAME_CHARS = 120
    private const val MAX_EXT_CHARS = 16
    private const val FALLBACK_MIME = "application/octet-stream"
    private const val FALLBACK_NAME = "attachment"
    private const val TAG = "AttachmentFiles"
    private val UNSAFE = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

    /** The file at [uri], or a failure when it cannot be read or is larger than [MAX_BYTES]. */
    @WorkerThread
    fun read(
        resolver: ContentResolver,
        uri: Uri,
    ): Outcome<ImportedFile> =
        try {
            val bytes =
                resolver.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("file larger than $MAX_BYTES bytes")
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                } ?: throw IOException("no stream for $uri")
            val name =
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            Outcome.Success(ImportedFile(bytes, resolver.getType(uri) ?: FALLBACK_MIME, name?.trim().orEmpty().ifEmpty { FALLBACK_NAME }))
        } catch (e: IOException) {
            FolioLog.w(TAG, "read failed: ${e.message}", e)
            Outcome.Failure("file not readable", e)
        } catch (e: SecurityException) {
            FolioLog.w(TAG, "read refused: ${e.message}", e)
            Outcome.Failure("file not readable", e)
        }

    /**
     * Top-left of a new chip: centered in the [visible] part of a page of [pageWidthPt] x [pageHeightPt] (the view's
     * edges are under the toolbar pills), kept on the page.
     */
    fun place(
        visible: RectPt,
        pageWidthPt: Float,
        pageHeightPt: Float,
    ): PointPt {
        val maxX = (pageWidthPt - Attachment.CHIP_WIDTH_PT).coerceAtLeast(0f)
        val maxY = (pageHeightPt - Attachment.CHIP_HEIGHT_PT).coerceAtLeast(0f)
        val c = visible.center
        return PointPt((c.x - Attachment.CHIP_WIDTH_PT / 2).coerceIn(0f, maxX), (c.y - Attachment.CHIP_HEIGHT_PT / 2).coerceIn(0f, maxY))
    }

    /**
     * [name] as a file name another app can show: path separators and control characters replaced, at most
     * [MAX_NAME_CHARS] characters keeping the extension, never empty, ".", or "..".
     */
    fun safeName(name: String): String {
        val cleaned = name.replace(UNSAFE, "_").trim().trimStart('.')
        val base = cleaned.ifEmpty { FALLBACK_NAME }
        if (base.length <= MAX_NAME_CHARS) return base
        val ext = base.substringAfterLast('.', "").take(MAX_EXT_CHARS)
        val keep = MAX_NAME_CHARS - ext.length - 1
        return if (ext.isEmpty() || keep <= 0) base.take(MAX_NAME_CHARS) else base.take(keep) + "." + ext
    }

    /**
     * Writes [bytes] of attachment [target] to `<cacheRoot>/attachments/<asset id prefix>/<safe name>` (the same file
     * again for the same asset and name) and returns it.
     */
    @WorkerThread
    fun write(
        cacheRoot: File,
        target: AttachmentTarget,
        bytes: ByteArray,
    ): File {
        val dir = File(File(cacheRoot, CACHE_DIR), target.asset.value.take(ID_DIR_CHARS)).apply { mkdirs() }
        val file = File(dir, safeName(target.name))
        // A whole new file each time: the viewer may still hold the previous one open.
        val temp = File(dir, ".${file.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("cannot write ${file.name}")
        }
        return file
    }
}
