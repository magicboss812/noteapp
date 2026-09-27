package dev.folio.core.render

import android.view.Display
import android.view.Window

/** One display mode, reduced to what mode selection needs. */
data class DisplayModeSpec(
    val id: Int,
    val widthPx: Int,
    val heightPx: Int,
    val refreshHz: Float,
)

/** Id of the fastest mode in [modes] with the resolution of [current], or null if there is none. */
fun highestRefreshModeId(
    modes: List<DisplayModeSpec>,
    current: DisplayModeSpec,
): Int? =
    modes
        .filter { it.widthPx == current.widthPx && it.heightPx == current.heightPx }
        .maxByOrNull { it.refreshHz }
        ?.id

/**
 * Requests the highest refresh-rate display mode at the current resolution for [window] while the
 * editor is visible, and releases the request when hidden (06-ink-input.md#wet-ink). Main thread only.
 */
class DisplayModeHelper(
    private val window: Window,
) {
    /** Requests the fastest mode of [display]; returns its refresh rate in Hz, or null if none fits. */
    fun request(display: Display): Float? {
        val modes = display.supportedModes.map { it.toSpec() }
        val id = highestRefreshModeId(modes, display.mode.toSpec()) ?: return null
        setPreferredMode(id)
        return modes.first { it.id == id }.refreshHz
    }

    /** Drops the request so the system chooses the mode again. */
    fun release() = setPreferredMode(NO_PREFERENCE)

    private fun setPreferredMode(modeId: Int) {
        val params = window.attributes
        if (params.preferredDisplayModeId == modeId) return
        params.preferredDisplayModeId = modeId
        window.attributes = params
    }

    private fun Display.Mode.toSpec() = DisplayModeSpec(modeId, physicalWidth, physicalHeight, refreshRate)

    private companion object {
        const val NO_PREFERENCE = 0
    }
}
