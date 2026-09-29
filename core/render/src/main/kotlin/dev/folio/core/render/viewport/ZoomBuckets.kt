package dev.folio.core.render.viewport

import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.pow

/**
 * Tile resolution buckets: bucket k renders at scale 2^(k/2) (steps of sqrt 2). A scale maps to the
 * smallest bucket at or above it, so tiles are never magnified (05-canvas-rendering.md#viewport).
 */
object ZoomBuckets {
    /** Scales at most this far above a bucket (in half-octaves) still use that bucket (float noise). */
    private const val TOLERANCE = 1e-4

    /** Index of the bucket for [scale] (px per pt, > 0). */
    fun indexFor(scale: Float): Int = ceil(2.0 * log2(scale.toDouble()) - TOLERANCE).toInt()

    /** Scale (px per pt) of bucket [index]. */
    fun scaleOf(index: Int): Float = 2.0.pow(index / 2.0).toFloat()
}
