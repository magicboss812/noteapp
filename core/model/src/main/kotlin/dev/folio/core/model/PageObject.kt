package dev.folio.core.model

import dev.folio.core.model.geometry.Affine
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt

/** Anything placed on a page. [bounds] is the axis-aligned page-space extent used for hit tests and tiles. */
sealed interface PageObject {
    /** Stable object id. */
    val id: ObjectId

    /** Axis-aligned bounds in page space. */
    val bounds: RectPt
}

/** Brush families (05-canvas-rendering / 06-ink-input brush catalog). */
enum class BrushKind { BALLPOINT, FOUNTAIN, PENCIL, MARKER, HIGHLIGHTER }

/** Brush used for a stroke; [version] pins the brush catalog behavior the stroke was drawn with. */
data class BrushSpec(
    val kind: BrushKind,
    val argb: Int,
    val sizePt: Float,
    val version: Int,
    val pressureGamma: Float = 1f,
)

/** Source of stroke inputs. */
enum class InputTool { STYLUS, ERASER_END, SYNTHETIC }

/**
 * Stroke samples as parallel arrays (struct-of-arrays). Immutable by contract: never write into the
 * arrays after construction. Optional channels are null when the device did not report them.
 * [tMs] is relative to the first sample.
 */
class StrokeInputs(
    val x: FloatArray,
    val y: FloatArray,
    val tMs: FloatArray,
    val pressure: FloatArray?,
    val tiltDeg: FloatArray?,
    val orientationDeg: FloatArray?,
    val tool: InputTool,
) {
    init {
        require(y.size == x.size && tMs.size == x.size) { "x, y, tMs sizes differ" }
        require(pressure == null || pressure.size == x.size) { "pressure size differs" }
        require(tiltDeg == null || tiltDeg.size == x.size) { "tilt size differs" }
        require(orientationDeg == null || orientationDeg.size == x.size) { "orientation size differs" }
    }

    /** Number of samples. */
    val size: Int get() = x.size

    /** Bounds of the sample positions (without brush width). */
    fun pointBounds(): RectPt = RectPt.bounds(x, y)

    /** Copy with positions mapped by [affine] (other channels shared). */
    fun transformed(affine: Affine): StrokeInputs {
        val nx = FloatArray(size)
        val ny = FloatArray(size)
        for (i in 0 until size) {
            nx[i] = affine.mapX(x[i], y[i])
            ny[i] = affine.mapY(x[i], y[i])
        }
        return StrokeInputs(nx, ny, tMs, pressure, tiltDeg, orientationDeg, tool)
    }

    override fun equals(other: Any?): Boolean =
        other is StrokeInputs &&
            tool == other.tool &&
            x.contentEquals(other.x) &&
            y.contentEquals(other.y) &&
            tMs.contentEquals(other.tMs) &&
            pressure.contentEquals(other.pressure) &&
            tiltDeg.contentEquals(other.tiltDeg) &&
            orientationDeg.contentEquals(other.orientationDeg)

    override fun hashCode(): Int {
        var h = tool.hashCode()
        h = 31 * h + x.contentHashCode()
        h = 31 * h + y.contentHashCode()
        h = 31 * h + tMs.contentHashCode()
        h = 31 * h + pressure.contentHashCode()
        h = 31 * h + tiltDeg.contentHashCode()
        h = 31 * h + orientationDeg.contentHashCode()
        return h
    }

    override fun toString(): String = "StrokeInputs(size=$size, tool=$tool)"
}

/** A pen stroke. [bounds] include half the brush size on every side. */
data class InkStroke(
    override val id: ObjectId,
    val brush: BrushSpec,
    val inputs: StrokeInputs,
    override val bounds: RectPt,
) : PageObject {
    /** Factories. */
    companion object {
        /** Stroke with bounds computed from the inputs and brush size. */
        fun of(
            id: ObjectId,
            brush: BrushSpec,
            inputs: StrokeInputs,
        ): InkStroke = InkStroke(id, brush, inputs, inputs.pointBounds().inset(-brush.sizePt / 2f))
    }
}

/** Geometric shape kinds. */
enum class ShapeKind { LINE, ARROW, RECTANGLE, ELLIPSE, TRIANGLE, POLYGON }

/** Outline style of a shape. */
data class StrokeStyle(
    val argb: Int,
    val widthPt: Float,
    val dashed: Boolean,
)

/** A vector shape defined by control points (meaning per [kind]). */
data class Shape(
    override val id: ObjectId,
    val kind: ShapeKind,
    val points: List<PointPt>,
    val rotationDeg: Float,
    val stroke: StrokeStyle,
    val fillArgb: Int?,
    override val bounds: RectPt,
) : PageObject

/** Frame role: BODY = page text zone, BOX = free text box. */
enum class FrameRole { BODY, BOX }

/** A rectangle a text flow is laid out into; frames of one flow chain by [order]. */
data class FlowFrame(
    override val id: ObjectId,
    val flow: FlowId,
    val rect: RectPt,
    val order: Int,
    val role: FrameRole,
    val autoGrow: Boolean,
) : PageObject {
    override val bounds: RectPt get() = rect
}

/** Normalized crop rectangle (0..1 of the source image). */
data class RectF01(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** Constants. */
    companion object {
        /** No crop. */
        val FULL = RectF01(0f, 0f, 1f, 1f)
    }
}

/** A placed image asset, rotated around the center of [rect]. */
data class ImageObject(
    override val id: ObjectId,
    val asset: AssetId,
    val rect: RectPt,
    val rotationDeg: Float,
    val crop: RectF01,
) : PageObject {
    override val bounds: RectPt = rotatedBounds(rect, rotationDeg)
}

/** A sticky note with its own text flow, rotated around the center of [rect]. */
data class StickyNote(
    override val id: ObjectId,
    val flow: FlowId,
    val rect: RectPt,
    val rotationDeg: Float,
    val argb: Int,
) : PageObject {
    override val bounds: RectPt = rotatedBounds(rect, rotationDeg)
}

/** A file attachment shown as an icon at [position] (top-left). */
data class Attachment(
    override val id: ObjectId,
    val asset: AssetId,
    val displayName: String,
    val mime: String,
    val position: PointPt,
) : PageObject {
    override val bounds: RectPt get() = RectPt.ofSize(position.x, position.y, ICON_SIZE_PT, ICON_SIZE_PT)

    /** Constants. */
    companion object {
        /** Edge length of the attachment icon in pt. */
        const val ICON_SIZE_PT = 36f
    }
}

private fun rotatedBounds(
    rect: RectPt,
    rotationDeg: Float,
): RectPt {
    if (rotationDeg == 0f) return rect
    val c = rect.center
    return Affine.rotate(rotationDeg, c.x, c.y).mapRect(rect)
}
