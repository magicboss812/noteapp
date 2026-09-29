package dev.folio.core.render.tiles

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import dev.folio.core.common.FolioLog
import dev.folio.core.common.PerfMonitor
import dev.folio.core.model.Page
import dev.folio.core.model.PageRef
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.render.PageContent
import dev.folio.core.render.PageRenderer
import dev.folio.core.render.RenderTarget
import dev.folio.core.render.viewport.ZoomBuckets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** What a tile request needs to know about one visible page. */
data class VisiblePage(
    /** Page id string ([TileKey.page]). */
    val page: String,
    /** Visible part of the page in page pt (clamped to the frame for fixed pages). */
    val visibleRectPt: RectPt,
    /** Page point under the view center, for center-out ordering. */
    val centerXPt: Float,
    /** See [centerXPt]. */
    val centerYPt: Float,
    /** Prefetch area (visible rect plus one tile ring, clamped like [visibleRectPt]). */
    val prefetchRectPt: RectPt,
)

/**
 * One tile cache (background or content, 05-canvas-rendering.md#tiles): renders missing and stale
 * tiles of the current bucket on the render dispatcher (visible first, center-out, then one prefetch
 * ring), draws cached tiles with the viewport transform, and keeps other-bucket tiles on screen where
 * the current bucket has no tile yet. Main thread only, except the renders.
 */
@MainThread
@Suppress("TooManyFunctions") // page updates, request, draw, trim and their private helpers
class TileLayer(
    /** [RenderTarget.SCREEN_BACKGROUND] or [RenderTarget.SCREEN_CONTENT]. */
    val target: RenderTarget,
    budgetBytes: Long,
    private val pool: BitmapPool,
    private val scope: CoroutineScope,
    private val renderDispatcher: CoroutineDispatcher,
    private val newRenderer: () -> PageRenderer,
    private val onTileReady: () -> Unit,
) {
    private class PageState(
        val ref: PageRef,
        val content: PageContent?,
    )

    // Evictions are logged once per request (debug builds), not per tile.
    private val cache = TileCache<Bitmap>(budgetBytes, pool::release)
    private var loggedEvictions = 0
    private var warmUp: Job? = null
    private val pages = HashMap<String, PageState>()
    private val inFlight = HashMap<TileKey, Job>()
    private val waitingVisible = HashSet<TileKey>()
    private val renderers = ThreadLocal<PageRenderer>()
    private val canvases = ThreadLocal<Canvas>()
    private val section = if (target == RenderTarget.SCREEN_BACKGROUND) SECTION_RENDER_BACKGROUND else SECTION_RENDER_CONTENT

    // Per-frame scratch (draw path allocates nothing).
    private val range = TileRange()
    private var covered = BooleanArray(INITIAL_SLOTS)
    private val dst = RectF()
    private val slot = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Cached entries (empty tiles included). */
    val tileCount: Int get() = cache.size

    /** Bytes held by cached tiles. */
    val bytes: Long get() = cache.bytes

    /** Renders queued or running. */
    val pendingCount: Int get() = inFlight.size

    /** Tiles evicted by the budget so far. */
    val evictions: Int get() = cache.evictions

    /** True when every visible tile of the latest [request] has been rendered. */
    val isSettled: Boolean get() = waitingVisible.isEmpty()

    /**
     * Updates page [ref] (and its decoded [body], null if not loaded) and invalidates what changed:
     * background tiles on a spec or background change, content tiles by the bounds of changed objects.
     */
    fun setPage(
        ref: PageRef,
        body: Page?,
    ) {
        val key = ref.id.value
        val old = pages[key]
        val content = contentFor(old?.content, body)
        if (old != null && old.ref == ref && old.content === content) return
        pages[key] = PageState(ref, content)
        when {
            old == null -> Unit
            old.ref.spec != ref.spec || old.ref.background != ref.background -> cache.invalidatePage(key)
            else -> invalidateContent(key, old.content, content)
        }
    }

    /** Content snapshot for [body]; keeps [old] while the body is not loaded (its tiles stay valid). */
    private fun contentFor(
        old: PageContent?,
        body: Page?,
    ): PageContent? =
        when {
            target != RenderTarget.SCREEN_CONTENT -> null
            body == null -> old
            old == null -> PageContent.of(body)
            else -> old.next(body)
        }

    private fun invalidateContent(
        key: String,
        before: PageContent?,
        after: PageContent?,
    ) {
        if (after == null || before === after) return
        if (before == null) {
            cache.invalidatePage(key) // tiles cached before the body loaded showed no objects
            return
        }
        for (rect in PageContent.changedBounds(before.page, after.page)) cache.invalidate(key, rect)
    }

    /** Forgets pages not in [keep] and drops their tiles. */
    fun retainPages(keep: Set<String>) {
        pages.keys.retainAll(keep)
        cache.retainPages(keep)
        val gone = inFlight.keys.filter { it.page !in keep }
        for (key in gone) inFlight.remove(key)?.cancel()
        waitingVisible.removeAll { it.page !in keep }
    }

    /**
     * Starts renders at [scale]'s bucket for the missing or stale tiles on screen of [visible] pages,
     * center-out across pages, and cancels renders of other buckets. Call [prefetch] after the
     * requests of every layer, so all visible tiles queue before any prefetch tile.
     */
    fun request(
        visible: List<VisiblePage>,
        scale: Float,
    ) {
        val bucket = ZoomBuckets.indexFor(scale)
        val stale = inFlight.entries.iterator()
        while (stale.hasNext()) {
            val (key, job) = stale.next()
            if (key.bucket == bucket) continue
            job.cancel()
            stale.remove()
        }
        warmUp?.cancel()
        if (cache.evictions != loggedEvictions) {
            FolioLog.d(TAG, "${target.name}: evicted ${cache.evictions - loggedEvictions} tiles since the last request")
            loggedEvictions = cache.evictions
        }
        waitingVisible.clear()
        for (key in ordered(visible, bucket, ring = false)) {
            ensure(key)
            if (key in inFlight) waitingVisible += key
        }
    }

    /** Starts renders for the ring of tiles around [visible] pages (center-out) while the budget has room. */
    fun prefetch(
        visible: List<VisiblePage>,
        scale: Float,
    ) {
        for (key in ordered(visible, ZoomBuckets.indexFor(scale), ring = true)) {
            if (cache.bytes + (inFlight.size + 1) * TileGrid.TILE_BYTES > cache.budgetBytes) break
            ensure(key)
        }
    }

    /**
     * Once everything visible is rendered: prepares painter caches (ink meshes) of every object of
     * [visible] pages on the render dispatcher, so strokes panned into view later do not mesh during
     * the tile render. Cancelled by the next [request].
     */
    fun warmUp(visible: List<VisiblePage>) {
        if (target != RenderTarget.SCREEN_CONTENT || inFlight.isNotEmpty()) return
        val contents = visible.mapNotNull { pages[it.page]?.content }
        if (contents.isEmpty()) return
        warmUp?.cancel()
        warmUp =
            scope.launch(renderDispatcher) {
                val renderer = renderers.get() ?: newRenderer().also(renderers::set)
                for (content in contents) {
                    for (i in 0 until content.size) {
                        ensureActive()
                        renderer.prepare(content, i)
                    }
                }
            }
    }

    /** Tile keys of [visible] pages at [bucket], center-out: the tiles on screen, or ([ring]) those around them. */
    private fun ordered(
        visible: List<VisiblePage>,
        bucket: Int,
        ring: Boolean,
    ): List<TileKey> {
        val sizePt = TileGrid.tileSizePt(bucket)
        val keys = ArrayList<Pair<Float, TileKey>>()
        for (v in visible) {
            val inView = TileGrid.range(v.visibleRectPt, bucket)
            val area = if (ring) TileGrid.range(v.prefetchRectPt, bucket) else inView
            for (ty in area.tyMin..area.tyMax) {
                for (tx in area.txMin..area.txMax) {
                    if (ring && inView.contains(tx, ty)) continue
                    val dx = (tx + HALF) * sizePt - v.centerXPt
                    val dy = (ty + HALF) * sizePt - v.centerYPt
                    keys += (dx * dx + dy * dy) to TileKey(v.page, bucket, tx, ty)
                }
            }
        }
        keys.sortBy { it.first }
        return keys.map { it.second }
    }

    /** Clears the pin of the previous frame; call once before the [draw] calls of a frame. */
    fun beginFrame() = cache.beginFrame()

    /**
     * Draws the tiles of page [page] whose page origin sits at view px ([originXPx], [originYPx]) at
     * [scale] px per pt, limited to the visible page rect ([leftPt], [topPt], [rightPt], [bottomPt]).
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod") // HOT PATH: plain numbers, no RectPt per frame
    fun draw(
        canvas: Canvas,
        page: String,
        originXPx: Float,
        originYPx: Float,
        scale: Float,
        leftPt: Float,
        topPt: Float,
        rightPt: Float,
        bottomPt: Float,
    ) {
        // HOT PATH: every frame during pan and zoom; no allocation (covered grows only for a larger view).
        val bucket = ZoomBuckets.indexFor(scale)
        TileGrid.rangeInto(range, leftPt, topPt, rightPt, bottomPt, bucket)
        if (range.isEmpty) return
        val slots = range.count
        if (covered.size < slots) covered = BooleanArray(slots * 2)
        covered.fill(false, 0, slots)
        val entries = cache.entries(page)
        var missing = slots
        for (i in entries.indices) {
            val e = entries[i]
            val k = e.key
            if (k.bucket != bucket || !range.contains(k.tx, k.ty)) continue
            covered[slotIndex(k.tx, k.ty)] = true
            missing--
            cache.markUsed(e)
            e.payload?.let { drawTile(canvas, it, k, originXPx, originYPx, scale) }
        }
        if (missing > 0) drawFallbacks(canvas, entries, bucket, originXPx, originYPx, scale)
    }

    /** Drops tiles that are not on screen and the idle bitmaps (onTrimMemory). */
    fun trimMemory() {
        cache.trimUnpinned()
        pool.clear()
    }

    /** Cancels renders and drops every tile. */
    fun clear() {
        warmUp?.cancel()
        inFlight.values.forEach(Job::cancel)
        inFlight.clear()
        waitingVisible.clear()
        cache.clear()
    }

    /** Where the current bucket has no tile yet: draws the nearest other bucket's tiles, clipped to the slot. */
    @Suppress("LongParameterList")
    private fun drawFallbacks(
        canvas: Canvas,
        entries: List<TileCache.Entry<Bitmap>>,
        bucket: Int,
        originXPx: Float,
        originYPx: Float,
        scale: Float,
    ) {
        val sizePx = TileGrid.TILE_PX * scale / ZoomBuckets.scaleOf(bucket)
        for (ty in range.tyMin..range.tyMax) {
            for (tx in range.txMin..range.txMax) {
                if (covered[slotIndex(tx, ty)]) continue
                slot.set(originXPx + tx * sizePx, originYPx + ty * sizePx, originXPx + (tx + 1) * sizePx, originYPx + (ty + 1) * sizePx)
                val best = nearestBucket(entries, bucket, originXPx, originYPx, scale)
                if (best != Int.MIN_VALUE) drawSlotFrom(canvas, entries, best, originXPx, originYPx, scale)
            }
        }
    }

    /** Draws the entries of bucket [from] that overlap [slot], clipped to it. */
    @Suppress("LongParameterList")
    private fun drawSlotFrom(
        canvas: Canvas,
        entries: List<TileCache.Entry<Bitmap>>,
        from: Int,
        originXPx: Float,
        originYPx: Float,
        scale: Float,
    ) {
        canvas.save()
        canvas.clipRect(slot)
        for (i in entries.indices) {
            val e = entries[i]
            if (e.key.bucket == from && RectF.intersects(tileRectInto(dst, e.key, originXPx, originYPx, scale), slot)) {
                cache.markUsed(e)
                e.payload?.let { canvas.drawBitmap(it, null, dst, bitmapPaint) }
            }
        }
        canvas.restore()
    }

    /** Bucket closest to [bucket] among other-bucket entries overlapping [slot]; MIN_VALUE if none. */
    private fun nearestBucket(
        entries: List<TileCache.Entry<Bitmap>>,
        bucket: Int,
        originXPx: Float,
        originYPx: Float,
        scale: Float,
    ): Int {
        var best = Int.MIN_VALUE
        for (i in entries.indices) {
            val b = entries[i].key.bucket
            if (b == bucket || (best != Int.MIN_VALUE && abs(b - bucket) >= abs(best - bucket))) continue
            if (RectF.intersects(tileRectInto(dst, entries[i].key, originXPx, originYPx, scale), slot)) best = b
        }
        return best
    }

    private fun drawTile(
        canvas: Canvas,
        bitmap: Bitmap,
        key: TileKey,
        originXPx: Float,
        originYPx: Float,
        scale: Float,
    ) {
        canvas.drawBitmap(bitmap, null, tileRectInto(dst, key, originXPx, originYPx, scale), bitmapPaint)
    }

    private fun tileRectInto(
        out: RectF,
        key: TileKey,
        originXPx: Float,
        originYPx: Float,
        scale: Float,
    ): RectF {
        val sizePx = TileGrid.TILE_PX * scale / ZoomBuckets.scaleOf(key.bucket)
        out.set(
            originXPx + key.tx * sizePx,
            originYPx + key.ty * sizePx,
            originXPx + (key.tx + 1) * sizePx,
            originYPx + (key.ty + 1) * sizePx,
        )
        return out
    }

    private fun slotIndex(
        tx: Int,
        ty: Int,
    ): Int = (ty - range.tyMin) * (range.txMax - range.txMin + 1) + (tx - range.txMin)

    /** Makes sure [key] is cached and fresh or being rendered. */
    private fun ensure(key: TileKey) {
        val entry = cache[key]
        if ((entry != null && !entry.stale) || key in inFlight) return
        val state = pages[key.page] ?: return
        val rect = TileGrid.tileRectPt(key.bucket, key.tx, key.ty)
        when {
            target == RenderTarget.SCREEN_CONTENT && state.content == null -> {
                return
            }

            // body not loaded yet

            PageRenderer.isPlainPaper(state.ref, target) ||
                (target == RenderTarget.SCREEN_CONTENT && state.content?.isEmptyIn(rect) == true) -> {
                cache.put(key, null, 0)
                return
            }
        }
        val bitmap = pool.acquire()
        var cached = false
        val job =
            scope.launch {
                withContext(renderDispatcher) { render(state, key, rect, bitmap) }
                inFlight.remove(key)
                val fresh = cache.put(key, bitmap, TileGrid.TILE_BYTES)
                cached = true
                if (pages[key.page] !== state) fresh.stale = true // the page changed while rendering
                waitingVisible.remove(key)
                onTileReady()
            }
        // Cancelled before it started or while rendering: the job completes only after withContext returned,
        // so the bitmap is no longer being drawn into.
        job.invokeOnCompletion { if (!cached) pool.release(bitmap) }
        if (job.isActive) inFlight[key] = job
    }

    @WorkerThread
    private fun render(
        state: PageState,
        key: TileKey,
        rect: RectPt,
        bitmap: Bitmap,
    ) = PerfMonitor.trace(section) {
        bitmap.eraseColor(0)
        val canvas = canvases.get() ?: Canvas().also(canvases::set)
        val renderer = renderers.get() ?: newRenderer().also(renderers::set)
        canvas.setBitmap(bitmap)
        renderer.draw(canvas, state.ref, state.content, rect, ZoomBuckets.scaleOf(key.bucket), target)
        canvas.setBitmap(null)
    }

    /** PerfMonitor sections. */
    companion object {
        /** One background tile render (render thread). */
        const val SECTION_RENDER_BACKGROUND = "tiles:render:bg"

        /** One content tile render (render thread). */
        const val SECTION_RENDER_CONTENT = "tiles:render:content"

        private const val TAG = "Tiles"
        private const val HALF = 0.5f
        private const val INITIAL_SLOTS = 64
    }
}
