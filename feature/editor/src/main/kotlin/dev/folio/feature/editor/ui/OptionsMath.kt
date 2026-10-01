package dev.folio.feature.editor.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.folio.core.ink.brush.BrushPresets
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private const val DEGREES = 360f
private const val SECTOR_DEG = 60f
private const val SECTORS = 6f
private const val PEAK = 4f
private const val RED_OFFSET = 5f
private const val GREEN_OFFSET = 3f
private const val BLUE_OFFSET = 1f
private const val GREEN_HUE_SECTOR = 2f
private const val BLUE_HUE_SECTOR = 4f
private const val CHANNEL_MAX = 255
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val OPAQUE = 0xFF000000.toInt()
private const val RGB_MASK = 0xFFFFFF
private const val HEX_DIGITS = 6
private const val HEX_RADIX = 16
private const val WIDTH_STEPS_PER_PT = 20f
private val GLYPH_MIN = 2.dp
private val GLYPH_MEDIUM = 4.dp
private val GLYPH_MAX = 12.dp

/** A color as the picker edits it: hue in degrees (0..360), saturation and value (0..1). Always opaque. */
@Immutable
internal data class Hsv(
    val hueDeg: Float,
    val saturation: Float,
    val value: Float,
) {
    /** The opaque ARGB color. */
    fun toArgb(): Int = argbOf(channel(RED_OFFSET), channel(GREEN_OFFSET), channel(BLUE_OFFSET))

    // f(n) = V - V S max(0, min(k, 4 - k, 1)), k = (n + H / 60) mod 6.
    private fun channel(offset: Float): Float {
        val k = (offset + hueDeg.mod(DEGREES) / SECTOR_DEG) % SECTORS
        return value - value * saturation * max(0f, min(min(k, PEAK - k), 1f))
    }

    /** Conversions. */
    companion object {
        /** The HSV of [argb] (alpha ignored); grays get hue 0. */
        fun of(argb: Int): Hsv {
            val r = channelOf(argb shr RED_SHIFT)
            val g = channelOf(argb shr GREEN_SHIFT)
            val b = channelOf(argb)
            val high = max(r, max(g, b))
            val delta = high - min(r, min(g, b))
            val sector =
                when {
                    delta == 0f -> 0f
                    high == r -> ((g - b) / delta).mod(SECTORS)
                    high == g -> (b - r) / delta + GREEN_HUE_SECTOR
                    else -> (r - g) / delta + BLUE_HUE_SECTOR
                }
            return Hsv(sector * SECTOR_DEG, if (high == 0f) 0f else delta / high, high)
        }

        private fun channelOf(shifted: Int): Float = (shifted and CHANNEL_MAX) / CHANNEL_MAX.toFloat()

        private fun argbOf(
            r: Float,
            g: Float,
            b: Float,
        ): Int = OPAQUE or (byteOf(r) shl RED_SHIFT) or (byteOf(g) shl GREEN_SHIFT) or byteOf(b)

        private fun byteOf(channel: Float): Int = (channel.coerceIn(0f, 1f) * CHANNEL_MAX).roundToInt()
    }
}

/** "RRGGBB" of [argb] (alpha dropped), as the hex field shows it. */
internal fun hexOf(argb: Int): String = (argb and RGB_MASK).toString(HEX_RADIX).uppercase().padStart(HEX_DIGITS, '0')

/** The opaque color of six hex digits (an optional leading '#'), null for anything else. */
internal fun parseHex(text: String): Int? {
    val digits = text.trim().removePrefix("#")
    if (digits.length != HEX_DIGITS) return null
    return digits.toIntOrNull(HEX_RADIX)?.let { OPAQUE or it }
}

/** Keeps hex digits only, upper case, at most six (the hex field's input filter). */
internal fun hexInput(text: String): String = text.filter { it.digitToIntOrNull(HEX_RADIX) != null }.uppercase().take(HEX_DIGITS)

/**
 * Logarithmic width scale over the custom width range (0.3..30 pt): width sliders give thin pens as much
 * room as wide highlighters.
 */
internal object WidthScale {
    private val ratio = ln(BrushPresets.MAX_WIDTH_PT / BrushPresets.MIN_WIDTH_PT)

    /** Slider position (0..1) of [widthPt]. */
    fun fraction(widthPt: Float): Float = ln(BrushPresets.clampWidth(widthPt) / BrushPresets.MIN_WIDTH_PT) / ratio

    /** Width at slider position [fraction], rounded to 0.05 pt. */
    fun widthPt(fraction: Float): Float {
        val raw = BrushPresets.MIN_WIDTH_PT * (BrushPresets.MAX_WIDTH_PT / BrushPresets.MIN_WIDTH_PT).pow(fraction.coerceIn(0f, 1f))
        return BrushPresets.clampWidth((raw * WIDTH_STEPS_PER_PT).roundToInt() / WIDTH_STEPS_PER_PT)
    }

    /**
     * Line thickness of the width glyph for [widthPt] in a row whose medium preset is [mediumPt]: proportional,
     * so the three presets of any brush read as small, medium and large.
     */
    fun glyph(
        widthPt: Float,
        mediumPt: Float,
    ): Dp = (GLYPH_MEDIUM * (widthPt / mediumPt.coerceAtLeast(BrushPresets.MIN_WIDTH_PT))).coerceIn(GLYPH_MIN, GLYPH_MAX)
}
