package dev.folio.core.ink.erase

/** What an eraser gesture removes (06-ink-input.md#erasers). */
enum class EraserMode {
    /** Whole strokes the eraser path touches. */
    STROKE,

    /** Only the inputs under the eraser; the rest of each stroke stays as new strokes. */
    PARTIAL,
}

/** Eraser tool settings: [radiusPt] around the eraser path; [highlighterOnly] spares every other brush. */
data class EraserOptions(
    val mode: EraserMode = EraserMode.STROKE,
    val radiusPt: Float = SIZES_PT[1],
    val highlighterOnly: Boolean = false,
) {
    init {
        require(radiusPt > 0f) { "radius must be positive" }
    }

    /** Presets. */
    companion object {
        /** Size presets (eraser radius, pt). */
        val SIZES_PT: List<Float> = listOf(4f, 10f, 24f)

        /** Stroke eraser with the middle size. */
        val DEFAULT: EraserOptions = EraserOptions()
    }
}
