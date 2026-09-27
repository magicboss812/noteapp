package dev.folio.core.text.math

import android.content.Context
import android.graphics.Canvas
import androidx.annotation.WorkerThread
import dev.folio.core.common.Outcome
import dev.folio.core.common.outcomeOf
import io.ratex.RaTeXEngine
import io.ratex.RaTeXFontLoader
import io.ratex.RaTeXRenderer

/**
 * ADR-007 option A: RaTeX (Rust core, KaTeX layout, KaTeX fonts drawn with Canvas text, rects and
 * paths). Construct off the main thread: the KaTeX fonts load from assets on first use.
 */
class RaTeXMathRenderer
    @WorkerThread
    constructor(
        context: Context,
    ) : MathRenderer {
        init {
            RaTeXFontLoader.ensureLoaded(context.applicationContext)
        }

        override val name: String = "RaTeX"

        @WorkerThread
        override fun layout(
            latex: String,
            fontSizePx: Float,
            argb: Int,
            display: Boolean,
        ): Outcome<MathLayout> =
            outcomeOf("RaTeX could not lay out the formula") {
                val list = RaTeXEngine.parseBlocking(latex, displayMode = display, color = argb)
                RaTeXLayout(RaTeXRenderer(list, fontSizePx) { RaTeXFontLoader.getTypeface(it) })
            }

        private class RaTeXLayout(
            private val renderer: RaTeXRenderer,
        ) : MathLayout {
            override val box = MathBox(renderer.widthPx, renderer.layoutHeightPx, renderer.layoutDepthPx)

            override fun draw(
                canvas: Canvas,
                xPx: Float,
                baselineYPx: Float,
            ) {
                // RaTeXRenderer.draw puts the box top at its origin plus a 1 px antialiasing bleed.
                val save = canvas.save()
                canvas.translate(xPx, baselineYPx - renderer.layoutHeightPx - renderer.glyphVerticalBleedPx)
                renderer.draw(canvas)
                canvas.restoreToCount(save)
            }
        }
    }
