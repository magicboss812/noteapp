// Width presets are the spec table of 06-ink-input.md#brushes; naming each would hide the table.
@file:Suppress("MagicNumber")

package dev.folio.core.ink.brush

import dev.folio.core.model.BrushKind

/** Width presets, width limits and default palettes per brush kind (06-ink-input.md#brushes, #colors). */
object BrushPresets {
    /** Smallest custom width in pt. */
    const val MIN_WIDTH_PT = 0.3f

    /** Largest custom width in pt. */
    const val MAX_WIDTH_PT = 30f

    /** Default pen palette: ink black, blue, red, green, purple. */
    val PEN_PALETTE: List<Int> =
        listOf(0xFF1A1A1A, 0xFF2563EB, 0xFFDC2626, 0xFF16A34A, 0xFF7C3AED).map { it.toInt() }

    /** Default highlighter palette: yellow, green, pink, blue, orange (opaque; the brush applies its alpha). */
    val HIGHLIGHTER_PALETTE: List<Int> =
        listOf(0xFFFDE047, 0xFF86EFAC, 0xFFF9A8D4, 0xFF93C5FD, 0xFFFDBA74).map { it.toInt() }

    /** Small, medium and large width in pt for [kind]. */
    fun widthsPt(kind: BrushKind): List<Float> =
        when (kind) {
            BrushKind.BALLPOINT -> listOf(0.6f, 0.9f, 1.3f)
            BrushKind.FOUNTAIN -> listOf(0.8f, 1.2f, 1.8f)
            BrushKind.PENCIL -> listOf(0.8f, 1.2f, 2.0f)
            BrushKind.MARKER -> listOf(1.5f, 2.5f, 4.0f)
            BrushKind.HIGHLIGHTER -> listOf(8f, 12f, 18f)
        }

    /** Default palette for [kind]. */
    fun palette(kind: BrushKind): List<Int> = if (kind == BrushKind.HIGHLIGHTER) HIGHLIGHTER_PALETTE else PEN_PALETTE

    /** [widthPt] limited to the custom width range. */
    fun clampWidth(widthPt: Float): Float = if (widthPt.isNaN()) MIN_WIDTH_PT else widthPt.coerceIn(MIN_WIDTH_PT, MAX_WIDTH_PT)
}
