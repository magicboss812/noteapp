package dev.folio.feature.editor.canvas

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.annotation.WorkerThread
import androidx.core.content.FileProvider
import dev.folio.core.common.FolioLog
import dev.folio.core.model.Page
import dev.folio.core.model.PageRef
import dev.folio.core.model.edit.ObjectCopies
import dev.folio.core.render.PageContent
import dev.folio.core.render.PageRenderer
import dev.folio.core.render.RenderTarget
import dev.folio.core.text.flow.PageText
import java.io.File
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The lasso selection as a picture on the system clipboard (06-ink-input.md#lasso): a PNG on the page's paper, shared
 * through the app's FileProvider from the cache. Rendering and writing run off the main thread ([render]); [publish]
 * sets the clip on the main thread.
 */
internal object SelectionImage {
    private const val DIR = "clipboard"
    private const val FILE = "selection.png"
    private const val LABEL = "Folio selection"
    private const val MIME = "image/png"
    private const val SCALE = 2f
    private const val MAX_SIDE_PX = 4096f
    private const val MARGIN_PT = 4f
    private const val PNG_QUALITY = 100

    /** The authority of the clipboard FileProvider (feature/editor AndroidManifest). */
    fun authority(context: Context): String = "${context.packageName}.clipboard"

    /** Renders [page]'s objects (its [text] for text boxes) on its paper into the cache file; null if there is nothing. */
    @WorkerThread
    fun render(
        context: Context,
        ref: PageRef,
        page: Page,
        text: PageText,
    ): File? {
        val region = ObjectCopies.bounds(page.objects).inset(-MARGIN_PT)
        if (region.isEmpty) return null
        val scale = min(SCALE, MAX_SIDE_PX / max(region.widthPt, region.heightPt))
        val bitmap =
            Bitmap.createBitmap(
                ceil(region.widthPt * scale).toInt().coerceAtLeast(1),
                ceil(region.heightPt * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
        val canvas = Canvas(bitmap)
        canvas.drawColor(ref.background.paperArgb)
        PageRenderer().draw(canvas, ref, PageContent.of(page, text), region, scale, RenderTarget.SCREEN_CONTENT)
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val file = File(dir, FILE)
        return try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
            file
        } catch (e: IOException) {
            FolioLog.w(TAG, "selection image not written: ${e.message}", e)
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** Puts [file] on the system clipboard as an image. */
    fun publish(
        context: Context,
        file: File,
    ) {
        val uri: Uri = FileProvider.getUriForFile(context, authority(context), file)
        val clip = ClipData(LABEL, arrayOf(MIME), ClipData.Item(uri))
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }

    private const val TAG = "SelectionImage"
}
