package dev.folio.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.Document
import dev.folio.core.model.FlowId
import dev.folio.core.model.GridUnit
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.StickyNote
import dev.folio.core.model.TextZones
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.text.flow.FlowLayouter
import dev.folio.core.text.flow.FocusedBlock
import dev.folio.core.text.flow.PageTexts
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.layout.BlockLayouter
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

@RunWith(AndroidJUnit4::class)
class StickyScreenshotTest {
    private val resolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext())
    private val layouter = FlowLayouter(BlockLayouter(resolver, FontMetricsCache(resolver)))
    private val template = ModelFixtures.LINED_BACKGROUND.template
    private val unitPt = GridUnit.of(template).unitPt

    // The five colors in a row of straight stickies, below them a tilted one each way and an empty one.
    private val stickies: List<StickyNote> =
        StickyNote.COLORS.mapIndexed { i, argb -> sticky("c$i", MARGIN_PT + i * STEP_PT, MARGIN_PT, 0f, argb) } +
            listOf(
                sticky("tilt-left", MARGIN_PT, ROW2_PT, StickyNote.TILT_DEG, StickyNote.COLORS[0]),
                sticky("tilt-right", MARGIN_PT + STEP_PT, ROW2_PT, StickyNote.MAX_TILT_DEG, StickyNote.COLORS[2]),
                sticky("empty", MARGIN_PT + 2 * STEP_PT, ROW2_PT, 0f, StickyNote.COLORS[3]),
            )

    // A sticky of SIZE_UNITS x SIZE_UNITS U with its top-left at ([xPt], [yPt]).
    private fun sticky(
        id: String,
        xPt: Float,
        yPt: Float,
        deg: Float,
        argb: Int,
    ): StickyNote = StickyNote(ObjectId(id), FlowId(id), RectPt.ofSize(xPt, yPt, SIZE_UNITS * unitPt, SIZE_UNITS * unitPt), deg, argb)

    private fun doc(): Document {
        val flows =
            stickies.map { s ->
                val text = if (s.id.value == "empty") "" else TEXT
                ModelFixtures.flow(s.flow.value, text).copy(autoContinue = false)
            }
        return ModelFixtures.document(
            listOf(ModelFixtures.page("p", stickies).copy(background = ModelFixtures.LINED_BACKGROUND)),
            flows = flows,
        )
    }

    @Test
    fun screenshot_stickyVariants_fiveColorsTiltedAndEmpty_lined() {
        val d = doc()
        val text = PageTexts.of(d, PageTexts.layouts(d, layouter)).getValue(PageId("p"))
        val content = PageContent.of(d.pages.single().let { checkNotNull(d.page(it.id)) }, text)

        val bitmap = render(d, content, RenderTarget.EXPORT_RASTER)

        bitmap.captureRoboImage("src/test/screenshots/Sticky_variants_lined.png")
        // Each straight sticky shows its paper color in the middle of an empty lower line, the empty one included.
        for (s in stickies.filter { it.rotationDeg == 0f }) {
            assertThat(bitmap.getPixel(px(s.rect.left + 2f), px(s.rect.bottom - unitPt))).isEqualTo(s.argb)
        }
        // The shadow darkens the paper just below a sticky; the paper above it is untouched.
        val first = stickies.first()
        val below = bitmap.getPixel(px(first.rect.centerX()), px(first.rect.bottom + StickyNote.SHADOW_DY_PT))
        assertThat(below).isNotEqualTo(ModelFixtures.LINED_BACKGROUND.paperArgb)
        // A sticky's text sits inside it: ink inside the content rect, none outside it to the right.
        assertThat(hasInk(bitmap, TextZones.stickyContentRect(first, unitPt), first.argb)).isTrue()
    }

    @Test
    fun screenshot_tiltedStickyWhoseTextIsEdited_isDrawnStraight_lined() {
        val d = doc()
        val tilted = stickies.first { it.id.value == "tilt-right" }
        val layouts = PageTexts.layouts(d, layouter)
        val lastBlock =
            layouts
                .getValue(tilted.flow)
                .markdown.blocks
                .last()
                .id
        val page = checkNotNull(d.page(PageId("p")))
        val turned = render(d, PageContent.of(page, PageTexts.of(d, layouts).getValue(page.id)), RenderTarget.EXPORT_RASTER)
        val edited = PageTexts.of(d, layouts, FocusedBlock(tilted.flow, lastBlock)).getValue(page.id)

        val straight = render(d, PageContent.of(page, edited), RenderTarget.EXPORT_RASTER)

        straight.captureRoboImage("src/test/screenshots/Sticky_editedStraight_lined.png")
        // Just inside the round top-left corner of the straight rect: sticky paper only when not turned.
        val cornerX = px(tilted.rect.left + CORNER_INSET_PT)
        val cornerY = px(tilted.rect.top + CORNER_INSET_PT)
        assertThat(straight.getPixel(cornerX, cornerY)).isEqualTo(tilted.argb)
        assertThat(turned.getPixel(cornerX, cornerY)).isNotEqualTo(tilted.argb)
    }

    private fun hasInk(
        bitmap: Bitmap,
        rect: RectPt,
        paperArgb: Int,
    ): Boolean {
        for (y in px(rect.top) until px(rect.bottom)) {
            for (x in px(rect.left) until px(rect.right)) if (bitmap.getPixel(x, y) != paperArgb) return true
        }
        return false
    }

    private fun RectPt.centerX(): Float = (left + right) / 2f

    private fun px(pt: Float): Int = (pt * SCALE).toInt()

    private fun render(
        d: Document,
        content: PageContent,
        target: RenderTarget,
    ): Bitmap {
        val spec = ModelFixtures.A4
        val bitmap = Bitmap.createBitmap(ceil(spec.widthPt * SCALE).toInt(), ceil(HEIGHT_PT * SCALE).toInt(), Bitmap.Config.ARGB_8888)
        val ref = d.pages.single()
        PageRenderer().draw(Canvas(bitmap), ref, content, RectPt(0f, 0f, spec.widthPt, HEIGHT_PT), SCALE, target)
        return bitmap
    }

    private companion object {
        const val SCALE = 1.5f
        const val SIZE_UNITS = 5
        const val MARGIN_PT = 24f
        const val STEP_PT = 112f
        const val ROW2_PT = 150f
        const val HEIGHT_PT = 280f
        const val CORNER_INSET_PT = 3f
        const val TEXT = "**Call Ana**\n\n- milk\n- eggs\n\nFriday 3 pm"
    }
}
