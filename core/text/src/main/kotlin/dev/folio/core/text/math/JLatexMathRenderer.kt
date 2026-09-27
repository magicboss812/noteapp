package dev.folio.core.text.math

import android.content.Context
import android.graphics.Canvas
import androidx.annotation.WorkerThread
import dev.folio.core.common.Outcome
import dev.folio.core.common.outcomeOf
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import org.scilab.forge.jlatexmath.TeXIcon
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.awt.AndroidGraphics2D
import ru.noties.jlatexmath.awt.Color

/**
 * ADR-007 renderer: jlatexmath-android (TeX layout in Java, drawn through a Graphics2D over the
 * Canvas, so PDF canvases get vectors). The box uses true TeX metrics plus [PADDING_EM] on every
 * side because glyph ink (accents, vector arrows, big operators) exceeds the TeX box by up to about
 * 0.04 em (A-007).
 */
class JLatexMathRenderer(
    context: Context,
) : MathRenderer {
    init {
        JLatexMathAndroid.init(context.applicationContext)
    }

    override val name: String = "jlatexmath"

    @WorkerThread
    override fun layout(
        latex: String,
        fontSizePx: Float,
        argb: Int,
        display: Boolean,
    ): Outcome<MathLayout> =
        outcomeOf("jlatexmath could not lay out the formula") {
            val icon =
                TeXFormula(latex)
                    .TeXIconBuilder()
                    .setStyle(if (display) TeXConstants.STYLE_DISPLAY else TeXConstants.STYLE_TEXT)
                    .setSize(fontSizePx)
                    .setFGColor(Color(argb))
                    .setTrueValues(true) // no 0.18 em insets: the box is the TeX box
                    .build()
            JLatexLayout(icon, PADDING_EM * fontSizePx)
        }

    private class JLatexLayout(
        private val icon: TeXIcon,
        paddingPx: Float,
    ) : MathLayout {
        private val graphics = AndroidGraphics2D()
        private val texAscentPx = icon.trueIconHeight - icon.trueIconDepth
        private val padPx = paddingPx
        override val box = MathBox(icon.trueIconWidth + 2 * paddingPx, texAscentPx + paddingPx, icon.trueIconDepth + paddingPx)

        override fun draw(
            canvas: Canvas,
            xPx: Float,
            baselineYPx: Float,
        ) {
            // paintIcon puts the TeX box top-left at its origin (no insets); the padding surrounds it.
            val save = canvas.save()
            canvas.translate(xPx + padPx, baselineYPx - texAscentPx)
            graphics.setCanvas(canvas)
            icon.paintIcon(null, graphics, 0, 0)
            canvas.restoreToCount(save)
        }
    }

    companion object {
        /** Extra ascent and depth so drawn ink stays inside [MathBox] (P01-S4: overshoot up to 1.85 px at 50 px). */
        const val PADDING_EM = 0.04f
    }
}
