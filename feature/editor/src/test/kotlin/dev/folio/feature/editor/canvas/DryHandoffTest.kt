package dev.folio.feature.editor.canvas

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.InkStroke
import dev.folio.core.model.InputTool
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.StrokeInputs
import org.junit.Test

class DryHandoffTest {
    private val commits = ArrayList<Pair<PageId, List<InkStroke>>>()
    private val rejectors = ArrayList<() -> Unit>()
    private val drawn = HashSet<ObjectId>()
    private val frameCallbacks = ArrayList<Runnable>()
    private val removed = ArrayList<Any>()
    private var invalidations = 0
    private var ids = 0
    private val handoff =
        DryHandoff(
            commit = { page, strokes, onRejected ->
                commits += page to strokes
                rejectors += onRejected
            },
            isDrawn = { _, stroke -> stroke.id in drawn },
            invalidate = { invalidations++ },
            afterFrameCommit = { frameCallbacks += it },
            removeWet = { removed.addAll(it) },
            newId = { ObjectId("o${ids++}") },
        )

    @Test
    fun onFinished_strokesOnTwoPages_commitsOneBatchPerPageWithNewIdsAndTheWetInputs() {
        val a = wet("w0", "p1", 10f)
        val b = wet("w1", "p2", 20f)
        val c = wet("w2", "p1", 30f)

        handoff.onFinished(listOf(a, b, c))

        assertThat(commits.map { it.first.value }).containsExactly("p1", "p2").inOrder()
        assertThat(commits[0].second.map { it.id.value }).containsExactly("o0", "o1").inOrder()
        assertThat(commits[0].second[1].inputs).isEqualTo(c.inputs)
        assertThat(commits[0].second[0].brush).isEqualTo(SPEC)
        assertThat(
            commits[1]
                .second
                .single()
                .id.value,
        ).isEqualTo("o2")
        assertThat(handoff.pendingCount).isEqualTo(3)
        assertThat(removed).isEmpty()
    }

    @Test
    fun check_strokeNotDrawnYet_keepsTheWetStroke() {
        handoff.onFinished(listOf(wet("w0", "p1", 10f)))

        handoff.check()

        assertThat(frameCallbacks).isEmpty()
        assertThat(removed).isEmpty()
        assertThat(handoff.pendingCount).isEqualTo(1)
    }

    @Test
    fun check_strokeDrawn_invalidatesAndRemovesTheWetStrokeOnlyAfterTheFrameCommit() {
        handoff.onFinished(listOf(wet("w0", "p1", 10f), wet("w1", "p1", 20f)))
        drawn += ObjectId("o1")

        handoff.check()

        assertThat(invalidations).isEqualTo(1)
        assertThat(removed).isEmpty()
        frameCallbacks.single().run()
        assertThat(removed).containsExactly("w1")
        assertThat(handoff.pendingCount).isEqualTo(1)
        assertThat(handoff.removedCount).isEqualTo(1)

        drawn += ObjectId("o0")
        handoff.check()
        frameCallbacks.last().run()
        assertThat(removed).containsExactly("w1", "w0").inOrder()
    }

    @Test
    fun check_twice_registersOneFrameCallbackPerReadyStroke() {
        handoff.onFinished(listOf(wet("w0", "p1", 10f)))
        drawn += ObjectId("o0")

        handoff.check()
        handoff.check()

        assertThat(frameCallbacks).hasSize(1)
    }

    @Test
    fun rejectedCommit_dropsTheWetStrokesOfThatBatch() {
        handoff.onFinished(listOf(wet("w0", "p1", 10f), wet("w1", "p2", 20f)))

        rejectors[0]()

        assertThat(removed).containsExactly("w0")
        assertThat(handoff.pendingCount).isEqualTo(1)
    }

    private fun wet(
        key: String,
        page: String,
        x: Float,
    ) = WetStroke(
        key,
        PageId(page),
        SPEC,
        StrokeInputs(floatArrayOf(x, x + 5f), floatArrayOf(x, x), floatArrayOf(0f, 8f), null, null, null, InputTool.STYLUS),
    )

    private companion object {
        val SPEC = BrushSpec(BrushKind.BALLPOINT, 0xFF1A1A1A.toInt(), 0.9f, 1)
    }
}
