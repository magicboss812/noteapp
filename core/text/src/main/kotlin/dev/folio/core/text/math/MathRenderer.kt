package dev.folio.core.text.math

import android.graphics.Canvas
import androidx.annotation.ColorInt
import androidx.annotation.WorkerThread
import dev.folio.core.common.Outcome

/** Size of a laid out formula in px: [ascentPx] above and [depthPx] below the baseline. */
data class MathBox(
    val widthPx: Float,
    val ascentPx: Float,
    val depthPx: Float,
)

/**
 * A laid out formula: its [box] and vector drawing at a baseline (07-text-engine.md#math). One layout may be shared
 * by several blocks and drawn from several threads; implementations serialize [draw].
 */
interface MathLayout {
    /** Metrics the text layout reserves for the formula. */
    val box: MathBox

    /**
     * Draws with the box's left edge at [xPx] and its baseline at [baselineYPx]; vector operations only. [argb]
     * replaces the color the formula was laid out with (explicit `\color` parts keep theirs); [KEEP_COLOR] keeps it.
     */
    fun draw(
        canvas: Canvas,
        xPx: Float,
        baselineYPx: Float,
        @ColorInt argb: Int = KEEP_COLOR,
    )

    companion object {
        /** [draw] color that keeps the layout's own color (fully transparent ink is never wanted). */
        const val KEEP_COLOR = 0
    }
}

/** Native LaTeX renderer behind ADR-007; adapters exist per spike option (P01-S4). */
interface MathRenderer {
    /** Short name for logs and probe tables. */
    val name: String

    /** Lays out [latex] (math mode, no delimiters) at [fontSizePx]; [display] selects block style. Fails on invalid LaTeX. */
    @WorkerThread
    fun layout(
        latex: String,
        fontSizePx: Float,
        @ColorInt argb: Int,
        display: Boolean,
    ): Outcome<MathLayout>
}
