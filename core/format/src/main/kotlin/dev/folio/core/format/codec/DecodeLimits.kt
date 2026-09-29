package dev.folio.core.format.codec

import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import kotlin.math.abs

/**
 * Value ranges for decoded geometry (untrusted input, 04-file-format.md#protobuf-schema): NaN, infinite or
 * absurdly large values make the entry corrupt instead of reaching layout, tiles and the ink mesher.
 */
internal object DecodeLimits {
    /** Largest |coordinate| in page space, pt (about 350 m; tile indices stay far inside Int at 8x zoom). */
    const val MAX_COORD_PT = 1_000_000f

    /** Largest custom page edge, pt (200 in, the PDF user-space limit). */
    const val MAX_PAGE_PT = 14_400f

    /** Largest brush or outline width, pt. */
    const val MAX_WIDTH_PT = 500f

    /** Throws [CorruptDataException] unless [v] is finite with |v| <= [MAX_COORD_PT]. */
    fun coord(
        v: Float,
        what: String,
    ): Float {
        corruptIf(!(abs(v) <= MAX_COORD_PT)) { "$what $v out of range" }
        return v
    }

    /** Throws unless [v] is finite. */
    fun finite(
        v: Float,
        what: String,
    ): Float {
        corruptIf(!v.isFinite()) { "$what $v is not finite" }
        return v
    }

    /** Throws unless 0 < [v] <= [max]. */
    fun positive(
        v: Float,
        max: Float,
        what: String,
    ): Float {
        corruptIf(!(v > 0f && v <= max)) { "$what $v outside (0, $max]" }
        return v
    }

    /** Throws unless 0 <= [v] <= [MAX_WIDTH_PT] (an outline may be 0 wide). */
    fun width(
        v: Float,
        what: String,
    ): Float {
        corruptIf(!(v >= 0f && v <= MAX_WIDTH_PT)) { "$what $v outside [0, $MAX_WIDTH_PT]" }
        return v
    }

    /** A rect from edges: inverted edges (the encoding of [RectPt.EMPTY]) give EMPTY; NaN or huge edges throw. */
    fun rect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        what: String,
    ): RectPt {
        corruptIf(left.isNaN() || top.isNaN() || right.isNaN() || bottom.isNaN()) { "$what has NaN edges" }
        if (!(left <= right && top <= bottom)) return RectPt.EMPTY
        return RectPt(coord(left, what), coord(top, what), coord(right, what), coord(bottom, what))
    }

    /** A point with both coordinates checked by [coord]. */
    fun point(
        x: Float,
        y: Float,
        what: String,
    ): PointPt = PointPt(coord(x, what), coord(y, what))
}
