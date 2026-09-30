package dev.folio.core.ink.erase

import androidx.annotation.WorkerThread
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableTriangle
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.Intersection.intersects
import androidx.ink.geometry.PartitionedMesh
import dev.folio.core.ink.StrokeBuilder
import dev.folio.core.model.BrushKind
import dev.folio.core.model.InkStroke
import dev.folio.core.model.ObjectId
import dev.folio.core.model.Page
import dev.folio.core.model.PageId
import dev.folio.core.model.PageObject
import dev.folio.core.model.edit.Batch
import dev.folio.core.model.edit.EditCommand
import dev.folio.core.model.edit.ReplaceObjects
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.model.geometry.UniformGridIndex
import kotlinx.collections.immutable.toPersistentList
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Outcome of an erase gesture on page [pageId]: every touched original stroke (in page z-order) with
 * the fragments that replace it (empty = removed).
 */
class EraseResult(
    val pageId: PageId,
    val replacements: Map<ObjectId, List<InkStroke>>,
) {
    /** True when the gesture touched nothing. */
    val isEmpty: Boolean get() = replacements.isEmpty()

    /**
     * The gesture as one undo step: a [Batch] of one [ReplaceObjects] per touched stroke, so every
     * fragment takes the z-index of its original. Its inverse restores the originals exactly.
     */
    fun command(): EditCommand = Batch(replacements.map { (id, pieces) -> ReplaceObjects(pageId, listOf(id), pieces) })

    /** [page] with the replacements applied (the same objects the [command] produces); ids not on it are skipped. */
    fun applyTo(page: Page): Page {
        if (isEmpty) return page
        val out = ArrayList<PageObject>(page.objects.size)
        for (o in page.objects) {
            val pieces = replacements[o.id]
            if (pieces == null) out += o else out += pieces
        }
        return page.copy(objects = out.toPersistentList())
    }
}

/**
 * One eraser gesture on [page] (06-ink-input.md#erasers). Feed eraser centers in page pt with [moveTo];
 * each move erases along the segment from the previous center with [EraserOptions.radiusPt]:
 * - STROKE: strokes whose mesh (androidx.ink geometry) intersects the padded segment are removed.
 * - PARTIAL: inputs are split with [StrokeSplitter]; fragments get new ids and keep brush and z position,
 *   and later moves erase from the fragments.
 * With [EraserOptions.highlighterOnly] only HIGHLIGHTER strokes are touched. Other object kinds are never
 * erased (shapes join in P07). Not thread-safe: one worker thread per gesture.
 */
@WorkerThread
class EraseSession(
    private val page: Page,
    private val options: EraserOptions,
    private val meshOf: (InkStroke) -> PartitionedMesh = { StrokeBuilder.build(it).shape },
    private val newId: () -> ObjectId = ObjectId::random,
) {
    private val live = HashMap<ObjectId, InkStroke>()
    private val originOf = HashMap<ObjectId, ObjectId>()
    private val index = UniformGridIndex<ObjectId>()
    private val meshes = HashMap<ObjectId, PartitionedMesh>()
    private val replaced = HashMap<ObjectId, List<InkStroke>>()
    private var lastX = Float.NaN
    private var lastY = Float.NaN

    init {
        for (o in page.objects) {
            if (o !is InkStroke || (options.highlighterOnly && o.brush.kind != BrushKind.HIGHLIGHTER)) continue
            add(o, o.id)
        }
    }

    /** Page the gesture erases on. */
    val pageId: PageId get() = page.id

    /** Original strokes touched so far. */
    val touchedCount: Int get() = replaced.size

    /** Erases from the previous center to ([xPt], [yPt]) (the first call erases a dot); true if anything changed. */
    fun moveTo(
        xPt: Float,
        yPt: Float,
    ): Boolean {
        val ax = if (lastX.isNaN()) xPt else lastX
        val ay = if (lastY.isNaN()) yPt else lastY
        lastX = xPt
        lastY = yPt
        return erase(ax, ay, xPt, yPt)
    }

    /** The replacements so far, in page z-order. */
    fun result(): EraseResult {
        val ordered = LinkedHashMap<ObjectId, List<InkStroke>>(replaced.size * 2)
        if (replaced.isNotEmpty()) {
            for (o in page.objects) replaced[o.id]?.let { ordered[o.id] = it }
        }
        return EraseResult(page.id, ordered)
    }

    private fun erase(
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ): Boolean {
        val r = options.radiusPt
        val box = RectPt(min(ax, bx) - r, min(ay, by) - r, max(ax, bx) + r, max(ay, by) + r)
        var changed = false
        for (id in index.query(box)) {
            val stroke = live[id]
            val pieces = if (stroke == null) null else piecesAfter(stroke, ax, ay, bx, by)
            if (stroke != null && pieces != null) {
                replace(stroke, pieces)
                changed = true
            }
        }
        return changed
    }

    /** What remains of [stroke] after erasing the segment; null if it is not touched. */
    private fun piecesAfter(
        stroke: InkStroke,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ): List<InkStroke>? =
        when (options.mode) {
            EraserMode.STROKE -> {
                if (hitsMesh(stroke, ax, ay, bx, by)) emptyList() else null
            }

            EraserMode.PARTIAL -> {
                StrokeSplitter
                    .split(stroke.inputs, ax, ay, bx, by, options.radiusPt + stroke.brush.sizePt / 2f)
                    ?.map { InkStroke.of(newId(), stroke.brush, it) }
            }
        }

    /**
     * Cheap centerline pre-check (widest brush behavior), then the exact mesh test against the segment's
     * rectangle padded by r on all sides, as two triangles. Not `ImmutableParallelogram.fromSegmentAndPadding`:
     * in ink 1.1.0-alpha09 its intersection gives false hits for rotations other than 0 (docs/notes/gotchas.md).
     */
    private fun hitsMesh(
        stroke: InkStroke,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ): Boolean {
        val r = options.radiusPt
        if (centerlineDistance(stroke, ax, ay, bx, by) > r + halfReach(stroke)) return false
        val mesh = meshes.getOrPut(stroke.id) { meshOf(stroke) }
        val length = hypot(bx - ax, by - ay)
        // Unit direction (u) scaled by r, and its left normal (n).
        val ux = if (length == 0f) r else (bx - ax) / length * r
        val uy = if (length == 0f) 0f else (by - ay) / length * r
        val nx = -uy
        val ny = ux
        val p0 = ImmutableVec(ax - ux + nx, ay - uy + ny)
        val p1 = ImmutableVec(bx + ux + nx, by + uy + ny)
        val p2 = ImmutableVec(bx + ux - nx, by + uy - ny)
        val p3 = ImmutableVec(ax - ux - nx, ay - uy - ny)
        return mesh.intersects(ImmutableTriangle(p0, p1, p2), AffineTransform.IDENTITY) ||
            mesh.intersects(ImmutableTriangle(p0, p2, p3), AffineTransform.IDENTITY)
    }

    private fun replace(
        stroke: InkStroke,
        pieces: List<InkStroke>,
    ) {
        live.remove(stroke.id)
        index.remove(stroke.id)
        meshes.remove(stroke.id)
        val origin = originOf.remove(stroke.id) ?: stroke.id
        val current = replaced[origin] ?: listOf(stroke)
        replaced[origin] = current.flatMap { if (it.id == stroke.id) pieces else listOf(it) }
        for (piece in pieces) add(piece, origin)
    }

    private fun add(
        stroke: InkStroke,
        origin: ObjectId,
    ) {
        live[stroke.id] = stroke
        originOf[stroke.id] = origin
        val b = stroke.inputs.pointBounds()
        val reach = halfReach(stroke)
        index.insert(stroke.id, RectPt(b.left - reach, b.top - reach, b.right + reach, b.bottom + reach))
    }

    private companion object {
        /** Widest brush behavior relative to the nominal size (pencil tilt, 06-ink-input.md#brushes). */
        const val MAX_WIDTH_FACTOR = 2.5f

        fun halfReach(stroke: InkStroke): Float = stroke.brush.sizePt * MAX_WIDTH_FACTOR / 2f

        fun centerlineDistance(
            stroke: InkStroke,
            ax: Float,
            ay: Float,
            bx: Float,
            by: Float,
        ): Float {
            val x = stroke.inputs.x
            val y = stroke.inputs.y
            if (x.size == 1) return StrokeSplitter.pointSegmentDistance(x[0], y[0], ax, ay, bx, by)
            var best = Float.MAX_VALUE
            for (i in 1 until x.size) {
                best = min(best, StrokeSplitter.segmentDistance(x[i - 1], y[i - 1], x[i], y[i], ax, ay, bx, by))
            }
            return best
        }
    }
}
