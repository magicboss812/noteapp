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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Encodes [page] (with [body]) as a WebP picture whose long side is [longSidePx]. */
internal typealias ThumbnailEncoder = (page: PageRef, body: Page, longSidePx: Int) -> ByteArray?

/**
 * Keeps the stored thumbnails of one open document current (09-storage-library.md#thumbnails): once the
 * document has been idle for [idleMs], every page an edit changed (`DocumentSession.pageEdits`) or whose
 * spec changed since its last thumbnail is rendered on the render dispatcher (<= 256 px) and written to the
 * working copy; the first page also becomes the library cover (<= 512 px) when it was edited or another page
 * became first. Decoding pages (scrolling, the page panel, LRU re-decodes) is not an edit and costs nothing.
 * Generator state is touched only from [scope]'s (single-threaded) dispatcher.
 */
internal class ThumbnailGenerator(
    private val session: DocumentSession,
    private val dispatchers: FolioDispatchers,
    private val scope: CoroutineScope,
    private val idleMs: Long = IDLE_MS,
    private val encode: ThumbnailEncoder = ::encodeWebp,
) {
    private val pending = HashSet<PageId>()
    private val refs = HashMap<PageId, PageRef>()
    private var coverId: PageId? = null
    private val changes = MutableStateFlow(0L)

    /** Starts watching the document; ends with [scope]. */
    fun start(): Job =
        scope.launch {
            val initial = session.document.value
            initial.pages.forEach { refs[it.id] = it }
            coverId = initial.pages.firstOrNull()?.id
            launch(start = CoroutineStart.UNDISPATCHED) {
                session.pageEdits.collect { ids ->
                    pending += ids
                    changes.value++
                }
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                // Spec edits (page settings) and reorders change the page list, never the bodies.
                session.document.map { it.pages }.distinctUntilChanged().collect { pages ->
                    var changed = pages.firstOrNull()?.id != coverId
                    for (ref in pages) {
                        val old = refs[ref.id]
                        if (old != null && old != ref) {
                            pending += ref.id
                            changed = true
                        }
                    }
                    if (changed) changes.value++
                }
            }
            changes.collectLatest {
                delay(idleMs)
                generate(session.document.value)
            }
        }

    private suspend fun generate(doc: Document) {
        val generation = changes.value
        val due = doc.pages.filter { it.id in pending }
        val cover = doc.pages.firstOrNull()?.takeIf { it.id != coverId || it.id in pending }
        if (due.isEmpty() && cover == null) return
        // Edited pages may have been evicted after their autosave; decoding them again is cheap and clean.
        if (session.loadPages(due.map { it.id } + listOfNotNull(cover?.id)) is Outcome.Failure) return
        val bodies = session.document.value.pageBodies
        val entries = withContext(dispatchers.render) { encodeAll(due, cover, bodies) }
        val result = session.writeThumbnails(entries)
        if (result is Outcome.Failure) {
            FolioLog.w(TAG, "thumbnail write failed: ${result.message}", result.cause)
            return
        }
        // A change that arrived meanwhile restarts generation; keep its pages pending.
        if (changes.value != generation) return
        pending.clear()
        refs.clear()
        doc.pages.forEach { refs[it.id] = it }
        if (cover != null) coverId = cover.id
    }

    // Entry name -> WebP bytes for the decoded pages among [due] and [cover]; runs on the render dispatcher.
    private fun encodeAll(
        due: List<PageRef>,
        cover: PageRef?,
        bodies: Map<PageId, Page>,
    ): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        for (ref in due) {
            val body = bodies[ref.id] ?: continue
            encode(ref, body, THUMB_PX)?.let { out[FolioEntries.pageThumb(ref.id)] = it }
        }
        cover?.let { ref -> bodies[ref.id]?.let { encode(ref, it, COVER_PX) } }?.let { out[FolioEntries.COVER] = it }
        return out
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
