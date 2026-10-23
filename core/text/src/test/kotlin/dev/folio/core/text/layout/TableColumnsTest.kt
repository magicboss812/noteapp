package dev.folio.core.text.layout

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TableColumnsTest {
    @Test
    fun widths_unwrappedContentFits_proportionalToItAndFillTheWidth() {
        val widths = TableColumns.widths(floatArrayOf(40f, 40f), floatArrayOf(50f, 150f), 400f)

        assertThat(widths[0]).isWithin(TOLERANCE).of(100f)
        assertThat(widths[1]).isWithin(TOLERANCE).of(300f)
    }

    @Test
    fun widths_contentTooWide_everyColumnKeepsItsWidestWord_restSharedByWhatEachWantsMore() {
        val widths = TableColumns.widths(floatArrayOf(40f, 60f, 60f), floatArrayOf(40f, 260f, 460f), 400f)

        // The first column wants nothing more; the others get 60 + 240 * 200/600 and 60 + 240 * 400/600.
        assertThat(widths[0]).isWithin(TOLERANCE).of(40f)
        assertThat(widths[1]).isWithin(TOLERANCE).of(140f)
        assertThat(widths[2]).isWithin(TOLERANCE).of(220f)
        assertThat(widths.sum()).isWithin(TOLERANCE).of(400f)
    }

    @Test
    fun widths_narrowColumn_raisedToTheMinimum() {
        val widths = TableColumns.widths(floatArrayOf(1f, 100f, 100f), floatArrayOf(1f, 500f, 500f), 400f)

        assertThat(widths[0]).isWithin(TOLERANCE).of(TableColumns.MIN_COLUMN_PT)
        assertThat(widths[1]).isWithin(TOLERANCE).of((400f - TableColumns.MIN_COLUMN_PT) / 2f)
    }

    @Test
    fun widths_wordsWiderThanTheFrame_shrinkInProportion() {
        val widths = TableColumns.widths(floatArrayOf(100f, 300f), floatArrayOf(100f, 300f), 200f)

        assertThat(widths[0]).isWithin(TOLERANCE).of(50f)
        assertThat(widths[1]).isWithin(TOLERANCE).of(150f)
    }

    @Test
    fun widths_emptyCells_equalShares() {
        val widths = TableColumns.widths(floatArrayOf(0f, 0f), floatArrayOf(0f, 0f), 200f)

        assertThat(widths.toList()).containsExactly(100f, 100f)
    }

    @Test
    fun edges_noSnap_cumulativeSums() {
        val edges = TableColumns.edges(floatArrayOf(10f, 20f, 30f))

        assertThat(edges.toList()).containsExactly(0f, 10f, 30f, 60f).inOrder()
    }

    @Test
    fun edges_snapped_innerEdgesOnGridColumns_outerEdgesKept() {
        val edges = TableColumns.edges(floatArrayOf(13f, 22f, 30.5f), snapPt = 5f)

        assertThat(edges.first()).isEqualTo(0f)
        assertThat(edges.last()).isWithin(TOLERANCE).of(65.5f)
        assertThat(edges[1] % 5f).isEqualTo(0f)
        assertThat(edges[2] % 5f).isEqualTo(0f)
        assertThat(edges[1]).isEqualTo(15f)
        assertThat(edges[2]).isEqualTo(35f)
    }

    @Test
    fun edges_snapped_everyColumnKeepsAtLeastOneGridColumn() {
        val edges = TableColumns.edges(floatArrayOf(1f, 1f, 30f), snapPt = 5f)

        for (c in 0 until edges.size - 1) assertThat(edges[c + 1] - edges[c]).isAtLeast(5f)
    }

    private companion object {
        const val TOLERANCE = 1e-3f
    }
}
