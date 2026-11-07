package dev.folio.app.debug

import android.view.MotionEvent
import android.view.Window
import dev.folio.core.common.FolioLog

/**
 * Logs every gesture start and window focus change the activity window receives (tag FolioTouch), to find input
 * that never reaches the canvas (P06-T18: the first tap and stroke after `open`). Debug builds only.
 */
internal class WindowTouchProbe(
    private val window: Window,
    private val wrapped: Window.Callback,
) : Window.Callback by wrapped {
    private var downs = 0

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val handled = wrapped.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downs++
            val focused = window.decorView.hasWindowFocus()
            FolioLog.i(TAG, "down #$downs tool=${event.getToolType(0)} focus=$focused handled=$handled t=${event.eventTime}")
        } else if (event.actionMasked == MotionEvent.ACTION_UP) {
            FolioLog.i(TAG, "up #$downs t=${event.eventTime}")
        }
        return handled
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        FolioLog.i(TAG, "window focus=$hasFocus")
        wrapped.onWindowFocusChanged(hasFocus)
    }

    internal companion object {
        const val TAG = "FolioTouch"

        /** Wraps [window]'s callback once. */
        fun install(window: Window) {
            val current = window.callback ?: return
            if (current !is WindowTouchProbe) window.callback = WindowTouchProbe(window, current)
        }
    }
}
