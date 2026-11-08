package dev.folio.feature.editor.canvas

import android.content.Context
import android.os.Looper
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FrameRole
import dev.folio.core.model.ObjectId
import dev.folio.core.model.TextZones
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.render.viewport.toViewPx
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.text.flow.BlockPlacement
import dev.folio.core.text.flow.FlowLayouter
import dev.folio.core.text.flow.PageTexts
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.layout.BlockLayouter
import dev.folio.feature.editor.canvas.TouchEvents.event
import dev.folio.feature.editor.text.CanvasText
import dev.folio.feature.editor.text.TextFieldSpec
import dev.folio.feature.editor.text.TextFocus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.math.abs
import kotlin.math.roundToInt

/** The grip and width handles of a focused free text box (P06-T17). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1164dp-h777dp-land-440dpi")
class BoxHandlesTest {
    @get:Rule
    val compose = createComposeRule()

    private val controller = FakeCanvasController(pages = 3)
    private var host: CanvasHostView? = null
    private val template = ModelFixtures.LINED_BACKGROUND.template
    private val boxFlow = FlowId("box")
    private val boxId = ObjectId("box-frame")

    private fun show() {
        compose.setContent {
            CanvasHost(controller, Modifier.fillMaxSize(), onHost = { host = it }) { context ->
                CanvasHostView(context, controller) { FakeWetSurface(it) }
            }
        }
        settle()
    }

    private fun settle() {
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CanvasHostView.IDLE_MS * 2))
        compose.waitForIdle()
    }

    private fun hostView(): CanvasHostView = requireNotNull(host)

    // A two-line box on the first page with its first block focused.
    private fun showFocusedBox(): RectPt {
        val pages = controller.document.value.pages
        val page = pages[0].id
        val resolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext<Context>())
        val layouter = FlowLayouter(BlockLayouter(resolver, FontMetricsCache(resolver)))
        val start = TextZones.newBoxRect(ModelFixtures.A4, template, 120f, 180f)
        val rect = TextZones.grownBox(start, 2, template.spacingPt)
        val frame = FlowFrame(boxId, boxFlow, rect, 0, FrameRole.BOX, autoGrow = true)
        val doc =
            ModelFixtures.document(
                List(pages.size) { if (it == 0) ModelFixtures.page(page.value, listOf(frame)) else ModelFixtures.page(pages[it].id.value) },
                flows = listOf(ModelFixtures.flow(boxFlow.value, "A free text box that holds two lines of text.")),
            )
        val layouts = PageTexts.layouts(doc, layouter)
        val layout = checkNotNull(layouts[boxFlow])
        val block = layout.markdown.blocks.first()
        val blockLayout = checkNotNull(layout.layoutOf(0))
        val spec =
            TextFieldSpec(
                TextFocus(boxFlow, block.id, 0, block.source.length, 1),
                block,
                blockLayout,
                BlockPlacement(0, page, rect.left, rect.top, rect.widthPt),
            )
        compose.runOnUiThread {
            controller.document.value = doc
            controller.canvasText.value = CanvasText(PageTexts.of(doc, layouts), spec)
        }
        settle()
        return rect
    }

    private fun onRule(yPt: Float): Boolean {
        val n = (yPt - template.marginTopPt) / template.spacingPt
        return abs(n - n.roundToInt()) < 1e-3f
    }

    @Test
    fun focusedBox_showsOutlineGripAndWidthHandles() {
        controller.textController = FakeTextController()
        show()

        val rect = showFocusedBox()

        assertThat(hostView().boxHandles.shown).isEqualTo(
            controller.document.value.pages[0]
                .id to rect,
        )
        compose.onRoot().captureRoboImage("src/test/screenshots/CanvasHost_focusedBoxHandles.png")
    }

    @Test
    fun fingerOnTheGrip_movesTheBoxSnappedInsteadOfPanning() {
        val texts = FakeTextController().also { controller.textController = it }
        show()
        val rect = showFocusedBox()
        val page =
            controller.document.value.pages[0]
                .id
        val vp = controller.viewport
        val grip = vp.toViewPx(page, PointPt((rect.left + rect.right) / 2, rect.top))
        val offsetBefore = vp.offsetYPx
        val dy = 3.4f * template.spacingPt * vp.scale

        compose.runOnUiThread {
            val view = hostView()
            view.dispatchTouchEvent(event(ACTION_DOWN, 0, grip.x to grip.y))
            view.dispatchTouchEvent(event(ACTION_MOVE, 50, grip.x + 60f to grip.y + dy))
            view.dispatchTouchEvent(event(ACTION_UP, 100, grip.x + 60f to grip.y + dy))
        }

        assertThat(vp.offsetYPx).isEqualTo(offsetBefore)
        assertThat(texts.focusCalls).isEmpty()
        val placed = texts.placedBoxes.single()
        assertThat(placed.frameId).isEqualTo(boxId)
        assertThat(placed.toPage).isEqualTo(page)
        assertThat(onRule(placed.rect.top)).isTrue()
        assertThat(placed.rect.top).isWithin(1e-2f).of(rect.top + 3f * template.spacingPt)
        assertThat(placed.rect.widthPt).isWithin(1e-3f).of(rect.widthPt)
        val mm = (placed.rect.left / (72f / 25.4f))
        assertThat(abs(mm - mm.roundToInt())).isLessThan(1e-3f)
    }

    @Test
    fun penOnTheGrip_dragsTheBoxOntoTheNextPage() {
        val texts = FakeTextController().also { controller.textController = it }
        show()
        val rect = showFocusedBox()
        val pages = controller.document.value.pages
        val vp = controller.viewport
        val grip = vp.toViewPx(pages[0].id, PointPt((rect.left + rect.right) / 2, rect.top))
        val target = vp.toViewPx(pages[1].id, PointPt((rect.left + rect.right) / 2, 100f))

        compose.runOnUiThread {
            val view = hostView()
            view.dispatchTouchEvent(event(ACTION_DOWN, 0, grip.x to grip.y, toolType = TOOL_TYPE_STYLUS))
            view.dispatchTouchEvent(event(ACTION_MOVE, 50, target.x to target.y, toolType = TOOL_TYPE_STYLUS))
            view.dispatchTouchEvent(event(ACTION_UP, 100, target.x to target.y, toolType = TOOL_TYPE_STYLUS))
        }

        val placed = texts.placedBoxes.single()
        assertThat(placed.fromPage).isEqualTo(pages[0].id)
        assertThat(placed.toPage).isEqualTo(pages[1].id)
        assertThat(onRule(placed.rect.top)).isTrue()
        assertThat(texts.blurCount).isEqualTo(0)
    }

    @Test
    fun rightHandle_resizesTheWidthInMillimeters_leftEdgeStays() {
        val texts = FakeTextController().also { controller.textController = it }
        show()
        val rect = showFocusedBox()
        val page =
            controller.document.value.pages[0]
                .id
        val vp = controller.viewport
        val handle = vp.toViewPx(page, PointPt(rect.right, (rect.top + rect.bottom) / 2))

        compose.runOnUiThread {
            val view = hostView()
            view.dispatchTouchEvent(event(ACTION_DOWN, 0, handle.x to handle.y))
            view.dispatchTouchEvent(event(ACTION_MOVE, 50, handle.x - 150f to handle.y + 5f))
            view.dispatchTouchEvent(event(ACTION_UP, 100, handle.x - 150f to handle.y + 5f))
        }

        val placed = texts.placedBoxes.single()
        assertThat(placed.rect.left).isEqualTo(rect.left)
        assertThat(placed.rect.top).isEqualTo(rect.top)
        assertThat(placed.rect.right).isLessThan(rect.right)
        val mm = placed.rect.right / (72f / 25.4f)
        assertThat(abs(mm - mm.roundToInt())).isLessThan(1e-3f)
    }
}
