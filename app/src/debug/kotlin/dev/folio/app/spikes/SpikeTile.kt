package dev.folio.app.spikes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RenderNode
import androidx.annotation.WorkerThread
import dev.folio.core.common.PerfMonitor

/** Committed-content strategies compared by P01-S2 (decisions.md#adr-003-committed-content-rendering). */
internal enum class TileStrategy {
    /** Software ARGB_8888 bitmap per tile. */
    A,

    /** RenderNode per tile with a compositing layer (GPU-cached display list). */
    B,
    ;

    companion object {
        fun parse(name: String): TileStrategy? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** One rendered 512 px tile; exactly one of [bitmap] (A) and [node] (B) is set. */
internal class SpikeTile(
    val bucket: Int,
    val tx: Int,
    val ty: Int,
    val bitmap: Bitmap?,
    val node: RenderNode?,
) {
    val bucketScale: Float = TileMath.bucketScale(bucket)
    val key: Long get() = tileKey(bucket, tx, ty)
}

/** Packs a tile address into one Long; bucket and indices must lie in 0 until 2^21. */
internal fun tileKey(
    bucket: Int,
    tx: Int,
    ty: Int,
): Long = ((bucket.toLong() and MASK) shl SHIFT_BUCKET) or ((tx.toLong() and MASK) shl SHIFT_TX) or (ty.toLong() and MASK)

/** Bucket of a key made by [tileKey]. */
internal fun tileBucket(key: Long): Int = ((key ushr SHIFT_BUCKET) and MASK).toInt()

private const val MASK = 0x1FFFFFL
private const val SHIFT_BUCKET = 42
private const val SHIFT_TX = 21

/** Renders tiles of a [SpikePage] with either strategy; safe to call from several render threads. */
internal class SpikeTileRenderer(
    private val page: SpikePage,
) {
    private val painters = ThreadLocal.withInitial { SpikePage.Painter() }

    /** Renders tile ([tx], [ty]) at [bucket] into [bitmap] (A, reused from the pool) or a new RenderNode (B). */
    @WorkerThread
    fun render(
        strategy: TileStrategy,
        bucket: Int,
        tx: Int,
        ty: Int,
        bitmap: Bitmap?,
        section: String,
    ): SpikeTile =
        PerfMonitor.trace(section) {
            val scale = TileMath.bucketScale(bucket)
            val painter = painters.get() ?: SpikePage.Painter()
            val px = TileMath.TILE_PX.toFloat()
            val toDevice =
                painter.matrix.apply {
                    setScale(scale, scale)
                    postTranslate(-tx * px, -ty * px)
                }
            val leftPt = tx * px / scale
            val topPt = ty * px / scale
            val sizePt = px / scale
            when (strategy) {
                TileStrategy.A -> {
                    val target = checkNotNull(bitmap) { "strategy A needs a pooled bitmap" }
                    target.eraseColor(0)
                    val canvas = Canvas(target)
                    canvas.concat(toDevice)
                    page.draw(canvas, painter, leftPt, topPt, leftPt + sizePt, topPt + sizePt, toDevice)
                    SpikeTile(bucket, tx, ty, target, null)
                }

                TileStrategy.B -> {
                    val node = RenderNode("tile")
                    node.setPosition(0, 0, TileMath.TILE_PX, TileMath.TILE_PX)
                    node.setUseCompositingLayer(true, null)
                    val canvas = node.beginRecording(TileMath.TILE_PX, TileMath.TILE_PX)
                    try {
                        canvas.concat(toDevice)
                        page.draw(canvas, painter, leftPt, topPt, leftPt + sizePt, topPt + sizePt, toDevice)
                    } finally {
                        node.endRecording()
                    }
                    SpikeTile(bucket, tx, ty, null, node)
                }
            }
        }

    /** Draws the page directly (no tiles) as seen by a view at [scale] and offset: the verify reference. */
    @WorkerThread
    fun renderReference(
        canvas: Canvas,
        scale: Float,
        offsetXPx: Float,
        offsetYPx: Float,
    ) {
        val painter = painters.get() ?: SpikePage.Painter()
        val toDevice =
            painter.matrix.apply {
                setScale(scale, scale)
                postTranslate(offsetXPx, offsetYPx)
            }
        canvas.save()
        canvas.concat(toDevice)
        page.draw(
            canvas,
            painter,
            -offsetXPx / scale,
            -offsetYPx / scale,
            (canvas.width - offsetXPx) / scale,
            (canvas.height - offsetYPx) / scale,
            toDevice,
        )
        canvas.restore()
    }
}
