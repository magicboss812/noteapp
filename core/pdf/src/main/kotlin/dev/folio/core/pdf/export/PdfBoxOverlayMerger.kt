package dev.folio.core.pdf.export

import android.content.Context
import androidx.annotation.WorkerThread
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.common.outcomeOf
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * ADR-006 option A: draws one-page overlays (annotation layers rendered with PdfDocument) on top
 * of pages of an existing PDF as form XObjects, keeping the original page content untouched
 * (08-pdf.md#export). Pages without an overlay are copied as they are. The output is written
 * atomically: `<output>.tmp`, fsync, rename; on failure [output] is left as it was.
 */
class PdfBoxOverlayMerger(
    context: Context,
) {
    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    /**
     * Writes [output]: [original] with overlay page i of [overlay] appended on top of original
     * page [targetPages][i] (0-based). Overlay and target pages must have the same size.
     */
    @WorkerThread
    fun merge(
        original: File,
        overlay: File,
        targetPages: IntArray,
        output: File,
    ): Outcome<Unit> {
        val tmp = File(output.parentFile, output.name + TMP_SUFFIX)
        return try {
            outcomeOf("PDF overlay merge failed") { mergeTo(original, overlay, targetPages, tmp, output) }
        } catch (e: LinkageError) {
            // Certificate-encrypted PDFs need BouncyCastle, which is excluded (ADR-006).
            FolioLog.w(TAG, "PDF overlay merge needs a missing class", e)
            Outcome.Failure("PDF overlay merge failed: unsupported encryption", e)
        } finally {
            if (tmp.exists() && !tmp.delete()) FolioLog.w(TAG, "could not delete ${tmp.name}")
        }
    }

    private fun mergeTo(
        original: File,
        overlay: File,
        targetPages: IntArray,
        tmp: File,
        output: File,
    ) {
        PDDocument.load(original, MemoryUsageSetting.setupMixed(MAX_MAIN_MEMORY_BYTES)).use { target ->
            PDDocument.load(overlay, MemoryUsageSetting.setupMainMemoryOnly()).use { layers ->
                require(layers.numberOfPages == targetPages.size) {
                    "overlay has ${layers.numberOfPages} pages for ${targetPages.size} targets"
                }
                val utility = LayerUtility(target)
                targetPages.forEachIndexed { overlayIndex, pageIndex ->
                    val form = utility.importPageAsForm(layers, overlayIndex)
                    val page = target.getPage(pageIndex)
                    // Append mode with a reset context: the original content is wrapped in q/Q first.
                    PDPageContentStream(target, page, PDPageContentStream.AppendMode.APPEND, true, true).use { it.drawForm(form) }
                }
                target.save(tmp) // closes its stream, so the fsync reopens the file
                RandomAccessFile(tmp, "rw").use { it.channel.force(true) } // FileDescriptor.sync() fails under Robolectric
            }
        }
        if (!tmp.renameTo(output)) throw IOException("rename ${tmp.name} -> ${output.name} failed")
    }

    private companion object {
        const val TAG = "PdfOverlayMerger"
        const val TMP_SUFFIX = ".tmp"
        const val MAX_MAIN_MEMORY_BYTES = 64L * 1024 * 1024
    }
}
