package dev.folio.core.text.layout

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.sp
import dev.folio.core.common.Outcome
import dev.folio.core.text.markdown.InlineSpan
import dev.folio.core.text.markdown.InlineText
import dev.folio.core.text.markdown.SpanKind
import dev.folio.core.text.math.MathBox
import dev.folio.core.text.math.MathLayout
import dev.folio.core.text.math.MathRenderer
import kotlin.math.max
import kotlin.math.min

/**
 * Formulas laid out by [renderer] for [BlockLayouter] (07-text-engine.md#math). Typing in a block with formulas
 * lays the block out again; its formulas come from a least recently used cache keyed by (LaTeX, size, display).
 * Thread-safe.
 */
internal class MathFormulas(
    private val renderer: MathRenderer,
) {
    private data class Key(
        val latex: String,
        val sizePx: Float,
        val display: Boolean,
    )

    private val cache =
        object : LinkedHashMap<Key, Outcome<MathLayout>>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Outcome<MathLayout>>): Boolean = size > MAX_FORMULAS
        }

    fun clear() {
        synchronized(cache) { cache.clear() }
    }

    /** [latex] laid out at [sizePx] (layout px), shared by every block that shows it. */
    fun layout(
        latex: String,
        sizePx: Float,
        display: Boolean,
    ): Outcome<MathLayout> {
        val key = Key(latex, sizePx, display)
        synchronized(cache) { cache[key] }?.let { return it }
        val out = renderer.layout(latex, sizePx, LAYOUT_ARGB, display)
        synchronized(cache) { cache[key] = out }
        return out
    }

    /** The inline formulas of [inline] in text order, at [fontSizePx] and fit to a line box of [boxPx] layout px. */
    fun inline(
        inline: InlineText,
        fontSizePx: Float,
        boxPx: Float,
    ): List<InlineFormula> {
        if (inline.spans.none { it.kind == SpanKind.MATH }) return emptyList()
        val length = inline.plain.length
        return inline.spans
            .filter { it.kind == SpanKind.MATH && it.plainStart in 0 until it.plainEnd && it.plainEnd <= length }
            .sortedBy { it.plainStart }
            .map { span ->
                when (val out = layout(span.target ?: inline.plain.substring(span.plainStart, span.plainEnd), fontSizePx, false)) {
                    is Outcome.Failure -> {
                        InlineFormula(span, null, 1f, tall = false, error = out.errorMessage(), boxPx = boxPx)
                    }

                    is Outcome.Success -> {
                        val fit = fitScale(out.value.box, boxPx)
                        InlineFormula(span, out.value, max(fit, MIN_INLINE_SCALE), fit < MIN_INLINE_SCALE, null, boxPx)
                    }
                }
            }
    }

    companion object {
        private const val INITIAL_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
        private const val MAX_FORMULAS = 512
        private const val LAYOUT_ARGB = 0xFF000000.toInt() // drawing recolors formulas to the text color

        // Inline math: ascent <= 0.78 and depth <= 0.30 line box, never below 0.55 scale.
        private const val INLINE_ASCENT_MAX = 0.78f
        private const val INLINE_DEPTH_MAX = 0.30f
        private const val MIN_INLINE_SCALE = 0.55f

        private fun fitScale(
            box: MathBox,
            boxPx: Float,
        ): Float {
            val byAscent = if (box.ascentPx > 0f) INLINE_ASCENT_MAX * boxPx / box.ascentPx else 1f
            val byDepth = if (box.depthPx > 0f) INLINE_DEPTH_MAX * boxPx / box.depthPx else 1f
            return min(1f, min(byAscent, byDepth))
        }
    }
}

/**
 * One inline formula: its [layout] at the role's font size drawn at [scale] (fit to the line box of [boxPx]
 * layout px), or the parser [error] of LaTeX that does not parse.
 */
internal class InlineFormula(
    val span: InlineSpan,
    val layout: MathLayout?,
    val scale: Float,
    val tall: Boolean,
    val error: String?,
    private val boxPx: Float,
) {
    /** Space the text reserves above the baseline: the scaled box, never taller than the line box. */
    fun placeholder(offset: Int): AnnotatedString.Range<Placeholder> {
        val box = checkNotNull(layout).box
        val widthPx = max(MIN_PLACEHOLDER_PX, box.widthPx * scale)
        val heightPx = max(MIN_PLACEHOLDER_PX, min(box.ascentPx * scale, boxPx))
        val placeholder = Placeholder(widthPx.sp, heightPx.sp, PlaceholderVerticalAlign.AboveBaseline)
        return AnnotatedString.Range(placeholder, span.plainStart + offset, span.plainEnd + offset)
    }

    private companion object {
        const val MIN_PLACEHOLDER_PX = 0.01f
    }
}

/** The parser's message of LaTeX that does not parse. */
internal fun Outcome.Failure.errorMessage(): String = cause?.message ?: message
