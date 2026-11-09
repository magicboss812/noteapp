package dev.folio.core.ink.ruler

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The ruler on the glass (06-ink-input.md#ruler) in view px: a straight edge [lengthPx] long and [widthPx] wide around
 * ([centerXPx], [centerYPx]), turned clockwise on screen (y down) by [angleDeg]. "Along" runs from the ruler's start to
 * its end, "across" from its top edge (-[widthPx] / 2) to its bottom edge (+[widthPx] / 2). Mutable, so finger moves and
 * pen samples allocate nothing.
 */
class Ruler(
    /** Length of the ruler, px. */
    val lengthPx: Float,
    /** Width between the two edges, px. */
    val widthPx: Float,
) {
    /** Center x, view px. */
    var centerXPx = 0f
        private set

    /** Center y, view px. */
    var centerYPx = 0f
        private set

    /** Clockwise turn on screen, degrees in (-180, 180]. */
    var angleDeg = 0f
        private set

    /** Unit vector along the ruler, x. */
    var dirX = 1f
        private set

    /** Unit vector along the ruler, y. */
    var dirY = 0f
        private set

    /** Places the ruler's center at ([xPx], [yPx]) turned by [deg]. */
    fun set(
        xPx: Float,
        yPx: Float,
        deg: Float,
    ) {
        centerXPx = xPx
        centerYPx = yPx
        angleDeg = RulerAngles.normalize(deg)
        val rad = angleDeg * DEG_TO_RAD
        dirX = cos(rad)
        dirY = sin(rad)
    }

    /** Along-the-ruler coordinate of ([xPx], [yPx]) from the center, px. */
    fun alongPx(
        xPx: Float,
        yPx: Float,
    ): Float = (xPx - centerXPx) * dirX + (yPx - centerYPx) * dirY

    /** Across-the-ruler coordinate of ([xPx], [yPx]) from the center line, px (positive toward the bottom edge). */
    fun acrossPx(
        xPx: Float,
        yPx: Float,
    ): Float = (yPx - centerYPx) * dirX - (xPx - centerXPx) * dirY

    /** True when ([xPx], [yPx]) lies on the ruler or within [slopPx] around it. */
    fun contains(
        xPx: Float,
        yPx: Float,
        slopPx: Float = 0f,
    ): Boolean =
        abs(alongPx(xPx, yPx)) <= lengthPx / 2f + slopPx &&
            abs(acrossPx(xPx, yPx)) <= widthPx / 2f + slopPx

    /**
     * The edge a pen down at ([xPx], [yPx]) follows: [TOP_EDGE] or [BOTTOM_EDGE] when the point is within [snapPx] of
     * that edge's line (on either side) beside the ruler's length, the nearer one when both are; else [NO_EDGE].
     */
    fun edgeNear(
        xPx: Float,
        yPx: Float,
        snapPx: Float,
    ): Int {
        if (abs(alongPx(xPx, yPx)) > lengthPx / 2f) return NO_EDGE
        val across = acrossPx(xPx, yPx)
        val toTop = abs(across + widthPx / 2f)
        val toBottom = abs(across - widthPx / 2f)
        return when {
            toTop > snapPx && toBottom > snapPx -> NO_EDGE
            toTop <= toBottom -> TOP_EDGE
            else -> BOTTOM_EDGE
        }
    }

    /** x of ([xPx], [yPx]) projected onto the line of [edge] ([TOP_EDGE] or [BOTTOM_EDGE]), view px. */
    fun projectX(
        xPx: Float,
        yPx: Float,
        edge: Int,
    ): Float = centerXPx + alongPx(xPx, yPx) * dirX - edge * widthPx / 2f * dirY

    /** y of ([xPx], [yPx]) projected onto the line of [edge] ([TOP_EDGE] or [BOTTOM_EDGE]), view px. */
    fun projectY(
        xPx: Float,
        yPx: Float,
        edge: Int,
    ): Float = centerYPx + alongPx(xPx, yPx) * dirY + edge * widthPx / 2f * dirX

    /** Edge ids of [edgeNear]; the value is the edge's side across the ruler. */
    companion object {
        /** No edge near. */
        const val NO_EDGE = 0

        /** The edge at across = -width / 2. */
        const val TOP_EDGE = -1

        /** The edge at across = +width / 2. */
        const val BOTTOM_EDGE = 1

        private const val DEG_TO_RAD = (PI / 180.0).toFloat()
    }
}

/** Ruler angles: normalizing, the 15 degree detents a two-finger turn rests in, and the readout value. */
object RulerAngles {
    /** Detent spacing, degrees. */
    const val DETENT_DEG = 15f

    /** A turn within this many degrees of a detent rests in it. */
    const val SNAP_DEG = 2.5f

    private const val HALF_TURN = 180f
    private const val FULL_TURN = 360f

    /** [deg] in (-180, 180]. */
    fun normalize(deg: Float): Float {
        val m = deg.mod(FULL_TURN)
        return if (m > HALF_TURN) m - FULL_TURN else m
    }

    /** [deg] (normalized) moved onto the nearest detent when within [SNAP_DEG] of it. */
    fun snap(deg: Float): Float {
        val n = normalize(deg)
        val detent = (n / DETENT_DEG).roundToInt() * DETENT_DEG
        return if (abs(n - detent) <= SNAP_DEG) normalize(detent) else n
    }

    /** True when [deg] rests exactly in a detent (after [snap]). */
    fun isDetent(deg: Float): Boolean = normalize(deg).mod(DETENT_DEG) == 0f

    /**
     * The readout of a ruler turned clockwise by [deg] on screen: its slope counterclockwise from the horizontal, whole
     * degrees in 0..179 (a ruler has no direction, so 180 reads 0).
     */
    fun readoutDeg(deg: Float): Int = (-deg).roundToInt().mod(HALF_TURN.toInt())
}
