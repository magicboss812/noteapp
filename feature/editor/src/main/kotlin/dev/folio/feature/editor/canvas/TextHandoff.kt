package dev.folio.feature.editor.canvas

import androidx.annotation.MainThread
import dev.folio.core.common.PerfMonitor
import dev.folio.feature.editor.text.TextFieldSpec

/**
 * Switches a text block between the page tiles and the live field without a frame that draws it twice or not at all
 * (P06-T18, 07-text-engine.md#live-field-as-built). The page text that leaves the focused block out arrives together
 * with the field ([dev.folio.feature.editor.text.CanvasText]), but the tiles keep drawing the block until they are
 * rendered again. So a newly focused field is [isMasked] (caret only) until [isDrawn] finds the tiles under it fresh,
 * and the block that left the field stays as a captured picture ([leaving], taken by [capture]) until the tiles draw it.
 * [apply] runs whenever either changes: the host invalidates the tiles and updates the field and the picture in the
 * same run loop, like the dry handoff (A-046).
 */
@MainThread
internal class TextHandoff(
    private val isDrawn: (TextFieldSpec) -> Boolean,
    private val capture: (TextFieldSpec) -> Unit,
    private val apply: () -> Unit,
) {
    /** The block the field shows, null without a field. */
    var field: TextFieldSpec? = null
        private set

    /** True while the tiles still draw [field]'s block: the field draws its caret but no text. */
    var isMasked = false
        private set

    /** The block that left the field and that the tiles do not draw yet; the host shows its captured picture. */
    var leaving: TextFieldSpec? = null
        private set

    /** True while the field or the picture waits for the tiles. */
    val isPending: Boolean get() = isMasked || leaving != null

    private var startNs = 0L

    /** The field shows [next] from now on (null: no field); call after the page text that goes with it was applied. */
    fun show(next: TextFieldSpec?) {
        val previous = field
        if (previous == null && next == null) return
        field = next
        if (previous != null && next != null && next.sameBlockAs(previous)) {
            settle(changed = true)
            return
        }
        // A masked field draws no text to capture; the tiles still drew its block, unless they were already rerendered.
        if (previous != null && !isMasked) {
            capture(previous)
            leaving = previous
        }
        isMasked = next != null
        startNs = PerfMonitor.clock.monotonicNs()
        settle(changed = true)
    }

    /** Tiles arrived or were requested: ends the masking and drops the picture where the tiles are fresh. */
    fun check() = settle(changed = false)

    /** The tiles did not come in time (off screen, a slow render): shows the field and drops the picture anyway. */
    fun expire() {
        if (!isPending) return
        isMasked = false
        leaving = null
        apply()
    }

    /** Forgets everything (the view detached). */
    fun clear() {
        field = null
        isMasked = false
        leaving = null
    }

    private fun settle(changed: Boolean) {
        var applied = changed
        val shown = field
        if (isMasked && (shown == null || isDrawn(shown))) {
            isMasked = false
            applied = true
        }
        val left = leaving
        if (left != null && isDrawn(left)) {
            leaving = null
            applied = true
        }
        if (!applied) return
        apply()
        if (!isPending && startNs != 0L) {
            PerfMonitor.record(TRACE_CLEAN, PerfMonitor.clock.monotonicNs() - startNs)
            startNs = 0L
        }
    }

    companion object {
        /** From a focus change until the frame that draws the blocks once each (tiles and field agree). */
        const val TRACE_CLEAN = "text:clean"
    }
}
