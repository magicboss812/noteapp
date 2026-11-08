package dev.folio.feature.editor.canvas

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.Document
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FrameRole
import dev.folio.core.model.ObjectId
import dev.folio.core.model.TextZones
import dev.folio.core.render.viewport.pageOriginViewY
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.text.flow.BlockPlacement
import dev.folio.core.text.flow.FlowLayout
import dev.folio.core.text.flow.FlowLayouter
import dev.folio.core.text.flow.PageTexts
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.layout.BlockLayouter
import dev.folio.feature.editor.text.CanvasText
import dev.folio.feature.editor.text.TextFieldSpec
import dev.folio.feature.editor.text.TextFocus
import dev.folio.feature.editor.text.TextSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The dashed text box outlines while the Text tool is active (P07-T12, A-059). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1164dp-h777dp-land-440dpi")
class BoxOutlinesTest {
    @get:Rule
    val compose = createComposeRule()

    private val controller = FakeCanvasController(pages = 3)
    private var host: CanvasHostView? = null
    private val template = ModelFixtures.LINED_BACKGROUND.template
    private val spacing = template.spacingPt
    private val zone = TextZones.boxZone(ModelFixtures.A4, template)
    private val continued = FlowId("continued")
    private val note = FlowId("note")

    private val layouter =
        ApplicationProvider.getApplicationContext<Context>().let {
            val resolver = createFontFamilyResolver(it)
            FlowLayouter(BlockLayouter(resolver, FontMetricsCache(resolver)))
        }

    // A box at the bottom of the first page whose text goes on at the top of the second, and a short box lower there.
    private fun document(): Document {
        val pages = controller.document.value.pages
        val bottom = TextZones.grownBox(TextZones.newBoxRect(ModelFixtures.A4, template, 120f, zone.bottom - 2 * spacing + 1f), 2, spacing)
        val top = TextZones.grownBox(TextZones.newBoxRect(ModelFixtures.A4, template, 120f, zone.top + 1f), 4, spacing)
        val short = TextZones.newBoxRect(ModelFixtures.A4, template, 160f, zone.top + 5 * spacing + 1f)
        val first = FlowFrame(ObjectId("c0"), continued, bottom, 0, FrameRole.BOX, autoGrow = false)
        val next = FlowFrame(ObjectId("c1"), continued, top, 1, FrameRole.BOX, autoGrow = false)
        val shortFrame = FlowFrame(ObjectId("n0"), note, short, 0, FrameRole.BOX, autoGrow = true)
        return ModelFixtures.document(
            listOf(
                ModelFixtures.page(pages[0].id.value, listOf(first)),
                ModelFixtures.page(pages[1].id.value, listOf(next, shortFrame)),
                ModelFixtures.page(pages[2].id.value),
            ),
            flows =
                listOf(
                    ModelFixtures.flow(
                        continued.value,
                        "A box near the bottom of the page whose lines go on at the top of the next page, where the " +
                            "dashed outline of its continuation is as tall as the lines it holds there.",
                    ),
                    ModelFixtures.flow(note.value, "A short box"),
                ),
        )
    }

    private fun outlinesOf(layouts: Map<FlowId, FlowLayout>) =
        layouts.values.flatMap { layout -> layout.slots.indices.map { layout.slots[it].pageId to layout.outlineOf(it) } }

    @Test
    fun boxOutlines_everyPageSlot_outlinedOnItsPageByUsedLines() {
        val doc = document()
        val layouts = PageTexts.layouts(doc, layouter)

        val outlines = boxOutlines(TextSnapshot(layouts, PageTexts.of(doc, layouts)), doc)

        assertThat(outlines.flatMap { (page, rects) -> rects.map { page to it } }).containsExactlyElementsIn(outlinesOf(layouts))
        assertThat(outlines.getValue(doc.pages[1].id)).hasSize(2)
    }

    @Test
    fun boxOutlines_focusedFlow_isLeftToItsHandles() {
        val doc = document()
        val layouts = PageTexts.layouts(doc, layouter)
        val layout = checkNotNull(layouts[note])
        val block = layout.markdown.blocks.first()
        val rect = layout.slots[0].rect
        val field =
            TextFieldSpec(
                TextFocus(note, block.id, 0, block.source.length, 1),
                block,
                checkNotNull(layout.layoutOf(0)),
                BlockPlacement(0, doc.pages[1].id, rect.left, rect.top, rect.widthPt),
            )

        val outlines = boxOutlines(TextSnapshot(layouts, PageTexts.of(doc, layouts), field = field), doc)

        assertThat(
            outlines.values.flatten(),
        ).containsExactlyElementsIn(
            outlinesOf(mapOf(continued to checkNotNull(layouts[continued]))).map {
                it.second
            },
        )
    }

    @Test
    fun textTool_showsDashedOutlinesOverAPageBreak_andHidesThemAgain() {
        compose.setContent {
            CanvasHost(controller, Modifier.fillMaxSize(), onHost = { host = it }) { context ->
                CanvasHostView(context, controller) { FakeWetSurface(it) }
            }
        }
        settle()
        val doc = document()
        val layouts = PageTexts.layouts(doc, layouter)
        val pages = PageTexts.of(doc, layouts)
        val outlines = boxOutlines(TextSnapshot(layouts, pages), doc)
        compose.runOnUiThread {
            controller.document.value = doc
            val vp = controller.viewport
            vp.panBy(0f, -(vp.pageOriginViewY(1).toFloat() - checkNotNull(host).height / 2f))
            controller.canvasText.value = CanvasText(pages, null, outlines = outlines)
        }
        settle()

        assertThat(checkNotNull(host).boxOutlines.shown).isEqualTo(outlines)
        compose.onRoot().captureRoboImage("src/test/screenshots/CanvasHost_textToolBoxOutlines.png")

        compose.runOnUiThread { controller.canvasText.value = CanvasText(pages, null) }
        settle()
        assertThat(checkNotNull(host).boxOutlines.shown).isEmpty()
    }

    private fun settle() {
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CanvasHostView.IDLE_MS * 2))
        compose.waitForIdle()
    }
}
