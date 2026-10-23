package dev.folio.core.render.tiles

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.render.PageRenderer
import dev.folio.core.render.RenderTarget
import dev.folio.core.render.TextPages
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.text.flow.PageText
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TileLayerTextTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val layer = TileLayer(RenderTarget.SCREEN_CONTENT, BUDGET, BitmapPool(), scope, dispatcher, { PageRenderer() }) {}
    private val pages = TextPages()
    private val page = pages.page()
    private val frame = RectPt(0f, 0f, ModelFixtures.A4.widthPt, ModelFixtures.A4.heightPt)

    private fun markdown(top: String): String = top + "\n\n" + (1..FILLER).joinToString("\n\n") { "Filler $it" } + "\n\nBottom"

    private fun settle(text: PageText) {
        layer.setPage(page.toRef(), page, text)
        layer.request(listOf(VisiblePage("p", frame, frame.center.x, frame.center.y, frame)), scale = 1f)
        scope.testScheduler.advanceUntilIdle()
    }

    private fun isDrawn(rect: RectPt) = layer.isDrawn("p", rect.left, rect.top, rect.right, rect.bottom, scale = 1f)

    @Test
    fun setPage_editedBlock_rerendersOnlyTheTilesUnderIt() {
        val before = pages.text(markdown("Top"))
        settle(before)
        val top = before.runs.first().bounds
        val bottom = before.runs.last().bounds
        // Precondition: the first and last block sit in different 512 pt tile rows.
        assertThat(top.bottom).isLessThan(TILE_PT)
        assertThat(bottom.top).isGreaterThan(TILE_PT)

        layer.setPage(page.toRef(), page, pages.text(markdown("Top edited")))

        assertThat(isDrawn(top)).isFalse()
        assertThat(isDrawn(bottom)).isTrue()
    }

    @Test
    fun setPage_insertedLine_alsoRerendersTheTilesOfTheShiftedBlocks() {
        val before = pages.text(markdown("Top"))
        settle(before)
        val bottom = before.runs.last().bounds

        layer.setPage(page.toRef(), page, pages.text(markdown("Top\\\nsecond line")))

        assertThat(isDrawn(bottom)).isFalse()
        scope.testScheduler.advanceUntilIdle()
        layer.request(listOf(VisiblePage("p", frame, frame.center.x, frame.center.y, frame)), scale = 1f)
        scope.testScheduler.advanceUntilIdle()
        assertThat(isDrawn(bottom)).isTrue()
    }

    @Test
    fun setPage_sameText_keepsEveryTile() {
        val text = pages.text(markdown("Top"))
        settle(text)

        layer.setPage(page.toRef(), page, pages.text(markdown("Top")))

        assertThat(isDrawn(frame)).isTrue()
    }

    private companion object {
        const val BUDGET = 64 * TileGrid.TILE_BYTES
        const val FILLER = 28 // one line each, so the last block starts well below the first tile row
        const val TILE_PT = 512f
    }
}
