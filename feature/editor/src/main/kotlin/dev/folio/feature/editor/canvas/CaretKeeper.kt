package dev.folio.feature.editor.canvas

import android.view.View
import androidx.annotation.MainThread
import kotlin.math.max

/**
 * Scrolls the canvas ([scrollBy], px, positive moves the content down) so the caret line stays between [marginPx]
 * below the top of [host] and [marginPx] above what the chrome covers ([coveredBottomPx]), whenever the caret moved
 * (a new block, typing) or the covered height changed (the keyboard came up). [caretLine] fills the line's top and
 * bottom in host px, false without a shown field. A pan by the user moves nothing (P06-T18).
 */
@MainThread
internal class CaretKeeper(
    private val host: View,
    private val marginPx: Float,
    private val caretLine: (FloatArray) -> Boolean,
    private val scrollBy: (dyPx: Float) -> Unit,
) {
    private val location = IntArray(2)
    private val line = FloatArray(2)

    /** How far up from the window bottom the chrome covers the canvas (keyboard, bottom-docked toolbox), px. */
    var coveredBottomPx = 0
        set(value) {
            if (value == field) return
            field = value
            check()
        }

    /** Scrolls if the caret line of a shown field is outside the clear band. */
    fun check() {
        if (host.height == 0 || !caretLine(line)) return
        host.getLocationInWindow(location)
        val belowHostPx = host.rootView.height - (location[1] + host.height)
        val coveredPx = max(0, coveredBottomPx - belowHostPx)
        val clearBottomPx = host.height - coveredPx - marginPx
        val dy = scrollIntoBand(line[0], line[1], marginPx, clearBottomPx)
        if (dy != 0f) scrollBy(dy)
    }
}

/**
 * The vertical scroll, px (positive moves the content down), that brings the line [topPx]..[bottomPx] into the band
 * [clearTopPx]..[clearBottomPx]; 0 when it is inside. A line taller than the band gets its top at the band top.
 */
internal fun scrollIntoBand(
    topPx: Float,
    bottomPx: Float,
    clearTopPx: Float,
    clearBottomPx: Float,
): Float =
    when {
        bottomPx > clearBottomPx -> max(clearBottomPx - bottomPx, clearTopPx - topPx).coerceAtMost(0f)
        topPx < clearTopPx -> clearTopPx - topPx
        else -> 0f
    }
