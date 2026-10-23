package dev.folio.core.text.flow

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FlowDistributorTest {
    private fun distribute(
        items: List<FlowItem>,
        capacities: List<Int>,
        gapUnits: Int = 0,
    ): Distribution = FlowDistributor.distribute(items.size, gapUnits, { capacities.getOrNull(it) }, { b, _ -> items[b] })

    @Test
    fun paragraph_tooTallForTheFrame_splitsAtALineBoundary() {
        val d = distribute(listOf(FlowItem.lines(5, 1)), listOf(3, 10))
        assertThat(d.frames[0]).containsExactly(FlowSlice(0, 0, 3, 0))
        assertThat(d.frames[1]).containsExactly(FlowSlice(0, 3, 5, 0))
        assertThat(d.overflows).isFalse()
        assertThat(d.startFrames.toList()).containsExactly(0)
    }

    @Test
    fun tableRows_splitBetweenRowsOnly() {
        // Four rows of two U each in a five U frame: two rows fit, the half row does not.
        val d = distribute(listOf(FlowItem(8, intArrayOf(2, 4, 6))), listOf(5, 10))
        assertThat(d.frames[0]).containsExactly(FlowSlice(0, 0, 4, 0))
        assertThat(d.frames[1]).containsExactly(FlowSlice(0, 4, 8, 0))
    }

    @Test
    fun mathBlock_neverSplits_movesWhole() {
        val d = distribute(listOf(FlowItem.lines(2, 1), FlowItem.whole(3)), listOf(4, 10))
        assertThat(d.frames[0]).containsExactly(FlowSlice(0, 0, 2, 0))
        assertThat(d.frames[1]).containsExactly(FlowSlice(1, 0, 3, 0))
        assertThat(d.startFrames.toList()).containsExactly(0, 1).inOrder()
    }

    @Test
    fun heading_keepsWithTheNextBlock() {
        val items = listOf(FlowItem.lines(2, 1), FlowItem.whole(2, keepWithNext = true), FlowItem.lines(3, 1))
        val d = distribute(items, listOf(4, 10))
        assertThat(d.frames[0]).containsExactly(FlowSlice(0, 0, 2, 0))
        assertThat(d.frames[1]).containsExactly(FlowSlice(1, 0, 2, 0), FlowSlice(2, 0, 3, 2)).inOrder()
    }

    @Test
    fun headingAtTheEnd_staysWhereItFits() {
        val d = distribute(listOf(FlowItem.lines(2, 1), FlowItem.whole(2, keepWithNext = true)), listOf(4))
        assertThat(d.frames[0]).hasSize(2)
        assertThat(d.overflows).isFalse()
    }

    @Test
    fun paragraphGap_betweenBlocks_neverAtAFrameTop() {
        val items = listOf(FlowItem.lines(2, 1), FlowItem.lines(2, 1))
        assertThat(distribute(items, listOf(10), gapUnits = 1).frames[0][1]).isEqualTo(FlowSlice(1, 0, 2, 3))
        val split = distribute(items, listOf(3, 10), gapUnits = 1)
        assertThat(split.frames[0]).containsExactly(FlowSlice(0, 0, 2, 0))
        assertThat(split.frames[1]).containsExactly(FlowSlice(1, 0, 2, 0))
        assertThat(split.usedUnits(0)).isEqualTo(2)
    }

    @Test
    fun oversizeBlock_inAnEmptyFrame_takesItAndHangsOut() {
        val d = distribute(listOf(FlowItem.whole(5), FlowItem.lines(1, 1)), listOf(3, 3))
        assertThat(d.frames[0]).containsExactly(FlowSlice(0, 0, 5, 0))
        assertThat(d.frames[1]).containsExactly(FlowSlice(1, 0, 1, 0))
        assertThat(d.overflows).isFalse()
    }

    @Test
    fun unboundedFrame_holdsEverything() {
        val d = distribute(listOf(FlowItem.lines(500, 1), FlowItem.whole(4)), listOf(FlowDistributor.UNBOUNDED))
        assertThat(d.frames).hasSize(1)
        assertThat(d.usedUnits(0)).isEqualTo(504)
    }

    @Test
    fun framesRunOut_reportsTheFirstUnplacedUnit() {
        val d = distribute(listOf(FlowItem.lines(5, 1), FlowItem.lines(1, 1)), listOf(3))
        assertThat(d.overflows).isTrue()
        assertThat(d.overflowBlock).isEqualTo(0)
        assertThat(d.overflowUnit).isEqualTo(3)
        assertThat(d.startFrames.toList()).containsExactly(0, -1).inOrder()
    }

    @Test
    fun noFrames_overflowsAtTheStart_emptyFlow_doesNot() {
        assertThat(distribute(listOf(FlowItem.lines(1, 1)), emptyList()).overflowBlock).isEqualTo(0)
        assertThat(distribute(emptyList(), emptyList()).overflows).isFalse()
    }

    @Test
    fun capacity_isAskedFrameByFrame_soFramesCanBeCreatedOnDemand() {
        val asked = ArrayList<Int>()
        val d =
            FlowDistributor.distribute(1, 0, {
                asked += it
                2
            }, { _, _ -> FlowItem.lines(7, 1) })
        assertThat(d.frames).hasSize(4)
        assertThat(asked).containsExactly(0, 1, 2, 3).inOrder()
    }
}
