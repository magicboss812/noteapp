package dev.folio.feature.editor.canvas

import com.google.common.truth.Truth.assertThat
import dev.folio.core.ink.erase.EraseResult
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.InkStroke
import dev.folio.core.model.InputTool
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.StrokeInputs
import dev.folio.core.testing.ModelFixtures
import org.junit.Test

class DryHandoffTest {
    private val commits = ArrayList<Pair<PageId, List<InkStroke>>>()
    private val rejectors = ArrayList<() -> Unit>()
    private val drawn = HashSet<ObjectId>()
    private val removed = ArrayList<Any>()

    // Invalidations and removals in call order: the wet copy must leave in the same run loop as the invalidate.
    private val calls = ArrayList<String>()
    private var ids = 0
    private val handoff =
        DryHandoff(
            commit = { page, strokes, onRejected ->
                commits += page to strokes
                rejectors += onRejected
            },
            isDrawn = { _, stroke -> stroke.id in drawn },
            invalidate = { calls += "invalidate" },
            removeWet = {
                removed.addAll(it)
                calls += "remove ${it.joinToString()}"
            },
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

        assertThat(calls).isEmpty()
        assertThat(handoff.pendingCount).isEqualTo(1)
    }

    @Test
    fun check_strokeDrawn_invalidatesAndRemovesTheWetStrokeInTheSameRunLoop() {
        handoff.onFinished(listOf(wet("w0", "p1", 10f), wet("w1", "p1", 20f)))
        drawn += ObjectId("o1")

        handoff.check()

        // androidx.ink hides a removed stroke with the next HWUI frame, the one the invalidate draws (A-046).
        assertThat(calls).containsExactly("invalidate", "remove w1").inOrder()
        assertThat(handoff.pendingCount).isEqualTo(1)
        assertThat(handoff.removedCount).isEqualTo(1)

        drawn += ObjectId("o0")
        handoff.check()
        assertThat(removed).containsExactly("w1", "w0").inOrder()
    }

    @Test
    fun check_twice_removesEachReadyStrokeOnce() {
        handoff.onFinished(listOf(wet("w0", "p1", 10f)))
        drawn += ObjectId("o0")

        handoff.check()
        handoff.check()

        assertThat(removed).containsExactly("w0")
    }

    @Test
    fun rejectedCommit_dropsTheWetStrokesOfThatBatch() {
        handoff.onFinished(listOf(wet("w0", "p1", 10f), wet("w1", "p2", 20f)))

        rejectors[0]()

        assertThat(removed).containsExactly("w0")
        assertThat(handoff.pendingCount).isEqualTo(1)
    }

    @Test
    fun withPending_appendsPendingStrokesOfThatPageNotInTheBody() {
        handoff.onFinished(listOf(wet("w0", "p1", 10f), wet("w1", "p2", 20f), wet("w2", "p1", 30f)))
        val committed = commits[0].second // o0, o1 on p1 (o2 on p2)
        val body = ModelFixtures.page("p1", listOf(committed[0])) // the session applied o0 already

        val shown = handoff.withPending(body)

        assertThat(shown.objects.map { it.id.value }).containsExactly("o0", "o1").inOrder()
        assertThat(handoff.withPending(ModelFixtures.page("p3"))).isEqualTo(ModelFixtures.page("p3"))
    }

    @Test
    fun erasing_strokeRemoved_wetCopyLeavesAtOnce() {
        handoff.onFinished(listOf(wet("w0", "p1", 10f), wet("w1", "p1", 20f)))

        handoff.erasing(EraseResult(PageId("p1"), mapOf(ObjectId("o1") to emptyList())))
        handoff.check()

        assertThat(calls).containsExactly("invalidate", "remove w1").inOrder()
        assertThat(handoff.pendingCount).isEqualTo(1)
    }

    @Test
    fun erasing_strokeSplit_wetCopyWaitsForTheFragments_cancelWaitsForTheStrokeAgain() {
        handoff.onFinished(listOf(wet("w0", "p1", 10f)))
        val pieces = listOf(piece("f0"), piece("f1"))
        handoff.erasing(EraseResult(PageId("p1"), mapOf(ObjectId("o0") to pieces)))
        drawn += ObjectId("o0")
        drawn += ObjectId("f0")

        handoff.check()
        assertThat(removed).isEmpty() // f1 not drawn yet; the stroke itself no longer counts

        handoff.erasing(null)
        handoff.check()
        assertThat(removed).containsExactly("w0")
    }

    @Test
    fun erasing_previewOnAnotherPage_keepsWaitingForTheStroke() {
        handoff.onFinished(listOf(wet("w0", "p1", 10f)))

        handoff.erasing(EraseResult(PageId("p2"), mapOf(ObjectId("o0") to emptyList())))
        handoff.check()

        assertThat(removed).isEmpty()
    }

    private fun piece(id: String) =
        InkStroke.of(
            ObjectId(id),
            SPEC,
            StrokeInputs(floatArrayOf(0f, 5f), floatArrayOf(0f, 0f), floatArrayOf(0f, 8f), null, null, null, InputTool.STYLUS),
        )

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
