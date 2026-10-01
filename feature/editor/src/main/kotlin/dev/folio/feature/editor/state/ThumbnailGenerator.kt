package dev.folio.feature.editor.state

import android.graphics.Bitmap
import android.graphics.Canvas
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.format.container.FolioEntries
import dev.folio.core.model.Document
import dev.folio.core.model.Page
import dev.folio.core.model.PageId
import dev.folio.core.model.PageRef
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.render.PageContent
import dev.folio.core.render.PageRenderer
import dev.folio.core.render.RenderTarget
import dev.folio.core.storage.session.DocumentSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Encodes [page] (with [body]) as a WebP picture whose long side is [longSidePx]. */
internal typealias ThumbnailEncoder = (page: PageRef, body: Page, longSidePx: Int) -> ByteArray?

/**
 * Keeps the stored thumbnails of one open document current (09-storage-library.md#thumbnails): once the
 * document has been idle for [idleMs], every page whose content or frame changed since its last thumbnail is
 * rendered on the render dispatcher (<= 256 px) and written to the working copy; the first page also becomes
 * the library cover (<= 512 px). The pages open at start count as already pictured, so only edits cost work.
 */
internal class ThumbnailGenerator(
    private val session: DocumentSession,
    private val dispatchers: FolioDispatchers,
    private val scope: CoroutineScope,
    private val idleMs: Long = IDLE_MS,
    private val encode: ThumbnailEncoder = ::encodeWebp,
) {
    private val seen = HashMap<PageId, Pictured>()
    private var cover: Pictured? = null

    private class Pictured(
        val ref: PageRef,
        val body: Page,
    ) {
        fun sameAs(
            ref: PageRef,
            body: Page,
        ) = this.ref == ref && this.body === body
    }

    /** Starts watching the document; ends with [scope]. */
    fun start(): Job =
        scope.launch {
            remember(session.document.value)
            session.document.collectLatest { doc ->
                delay(idleMs)
                generate(doc)
            }
        }

    // A page whose body is not decoded cannot be pictured (and cannot have changed).
    private fun pictured(
        doc: Document,
        ref: PageRef,
    ): Pictured? = doc.pageBodies[ref.id]?.let { Pictured(ref, it) }

    private fun remember(doc: Document) {
        doc.pages.forEach { ref -> pictured(doc, ref)?.let { seen[ref.id] = it } }
        cover = doc.pages.firstOrNull()?.let { pictured(doc, it) }
    }

    private suspend fun generate(doc: Document) {
        seen.keys.retainAll(doc.pages.map { it.id }.toSet())
        val changed = doc.pages.mapNotNull { pictured(doc, it) }.filter { seen[it.ref.id]?.sameAs(it.ref, it.body) != true }
        val first =
            doc.pages
                .firstOrNull()
                ?.let { pictured(doc, it) }
                ?.takeIf { cover?.sameAs(it.ref, it.body) != true }
        if (changed.isEmpty() && first == null) return
        val entries =
            withContext(dispatchers.render) {
                val out = LinkedHashMap<String, ByteArray>()
                for (page in changed) encode(page.ref, page.body, THUMB_PX)?.let { out[FolioEntries.pageThumb(page.ref.id)] = it }
                if (first != null) encode(first.ref, first.body, COVER_PX)?.let { out[FolioEntries.COVER] = it }
                out
            }
        val result = session.writeThumbnails(entries)
        if (result is Outcome.Failure) {
            FolioLog.w(TAG, "thumbnail write failed: ${result.message}", result.cause)
            return
        }
        changed.forEach { seen[it.ref.id] = it }
        if (first != null) cover = first
    }

    /** Defaults from 09-storage-library.md#thumbnails. */
    companion object {
        /** Editing idle before thumbnails are made. */
        const val IDLE_MS = 2_000L

        /** Long side of a page thumbnail. */
        const val THUMB_PX = 256

        /** Long side of the cover. */
        const val COVER_PX = 512
        private const val TAG = "Thumbnails"
        private const val WEBP_QUALITY = 80

        // One renderer per call: it is not thread-safe and the render dispatcher runs two threads.
        private fun encodeWebp(
            page: PageRef,
            body: Page,
            longSidePx: Int,
        ): ByteArray? {
            val widthPt = page.spec.widthPt
            val heightPt = page.spec.heightPt
            val scale = longSidePx / max(widthPt, heightPt)
            val bitmap =
                Bitmap.createBitmap(
                    (widthPt * scale).toInt().coerceAtLeast(1),
                    (heightPt * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
            return try {
                PageRenderer().draw(
                    Canvas(bitmap),
                    page,
                    PageContent.of(body),
                    RectPt.ofSize(0f, 0f, widthPt, heightPt),
                    scale,
                    RenderTarget.EXPORT_RASTER,
                )
                ByteArrayOutputStream().use { out ->
                    if (bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, out)) out.toByteArray() else null
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
