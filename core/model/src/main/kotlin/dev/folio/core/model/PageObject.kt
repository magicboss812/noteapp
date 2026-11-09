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

    /**
     * Where painting the sticky can put pixels (11-design-system.md#sticky-notes): its turned and its straight rect
     * (it is drawn straight while its text is edited, A-068), grown by the shadow.
     */
    val paintBounds: RectPt
        get() =
            bounds.union(rect).let {
                RectPt(
                    it.left - SHADOW_BLUR_PT,
                    it.top - SHADOW_BLUR_PT,
                    it.right + SHADOW_BLUR_PT,
                    it.bottom + SHADOW_DY_PT + SHADOW_BLUR_PT,
                )
            }

    /** Sticky note look (11-design-system.md#sticky-notes). */
    companion object {
        /** Yellow, pink, blue, green, lavender. */
        val COLORS: List<Int> =
            listOf(0xFFFDE68A.toInt(), 0xFFFBCFE8.toInt(), 0xFFBFDBFE.toInt(), 0xFFBBF7D0.toInt(), 0xFFDDD6FE.toInt())

        /** Text color on every sticky color. */
        const val INK_ARGB: Int = 0xFF1F2937.toInt()

        /** Corner radius, pt. */
        const val RADIUS_PT = 4f

        /** Shadow offset down, pt. */
        const val SHADOW_DY_PT = 2f

        /** Shadow blur, pt. */
        const val SHADOW_BLUR_PT = 6f

        /** Shadow color: black at 12 %. */
        const val SHADOW_ARGB: Int = 0x1F000000

        /** Rotation of a new sticky with the tilt option on, degrees (at most [MAX_TILT_DEG] either way). */
        const val TILT_DEG = -2f

        /** Largest tilt either way, degrees. */
        const val MAX_TILT_DEG = 4f

        /** Edge of a new sticky, pt (76 mm, snapped to whole U by `TextZones.newStickyAt`). */
        const val DEFAULT_SIZE_PT = 216f
    }
}

/**
 * A file attachment shown as a chip (file type icon, name, size) with its top-left at [position] (A-070). [sizeBytes]
 * is the file's size for the chip label (0 when unknown).
 */
data class Attachment(
    override val id: ObjectId,
    val asset: AssetId,
    val displayName: String,
    val mime: String,
    val position: PointPt,
    val sizeBytes: Long = 0L,
) : PageObject {
    override val bounds: RectPt get() = RectPt.ofSize(position.x, position.y, CHIP_WIDTH_PT, CHIP_HEIGHT_PT)

    /** Chip size; the name is cut to fit. */
    companion object {
        /** Width of the chip in pt (70 mm). */
        const val CHIP_WIDTH_PT = 198f

        /** Height of the chip in pt (15 mm). */
        const val CHIP_HEIGHT_PT = 42f
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
