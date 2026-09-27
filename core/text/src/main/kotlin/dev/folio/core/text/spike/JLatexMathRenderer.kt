package dev.folio.core.text.spike

import android.content.Context
import android.graphics.Canvas
import androidx.annotation.WorkerThread
import dev.folio.core.common.Outcome
import dev.folio.core.common.outcomeOf
import dev.folio.core.text.math.MathBox
import dev.folio.core.text.math.MathLayout
import dev.folio.core.text.math.MathRenderer
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import org.scilab.forge.jlatexmath.TeXIcon
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.awt.AndroidGraphics2D
import ru.noties.jlatexmath.awt.Color

/** ADR-007 option C for spike P01-S4: jlatexmath-android (pure Java, Graphics2D over Canvas). Deleted by P01-T08 unless chosen. */
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
                    .setTrueValues(true) // no 0.18 em padding: the box is the TeX box
                    .build()
            JLatexLayout(icon)
        }

    private class JLatexLayout(
        private val icon: TeXIcon,
    ) : MathLayout {
        private val graphics = AndroidGraphics2D()
        override val box = MathBox(icon.trueIconWidth, icon.trueIconHeight - icon.trueIconDepth, icon.trueIconDepth)

        override fun draw(
            canvas: Canvas,
            xPx: Float,
            baselineYPx: Float,
        ) {
            // paintIcon puts the box top at its origin (no insets).
            val save = canvas.save()
            canvas.translate(xPx, baselineYPx - box.ascentPx)
            graphics.setCanvas(canvas)
            icon.paintIcon(null, graphics, 0, 0)
            canvas.restoreToCount(save)
        }
    }
}
