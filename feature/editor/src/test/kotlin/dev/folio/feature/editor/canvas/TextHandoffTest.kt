package dev.folio.feature.editor.canvas

import android.content.Context
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.FlowId
import dev.folio.core.model.PageId
import dev.folio.core.text.flow.BlockPlacement
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.layout.BlockLayouter
import dev.folio.core.text.layout.FlowTextStyle
import dev.folio.core.text.layout.LayoutGrid
import dev.folio.core.text.markdown.MarkdownDocument
import dev.folio.feature.editor.text.TextFieldSpec
import dev.folio.feature.editor.text.TextFocus
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every frame (the end of each main-thread run that calls [TextHandoff.apply]) must draw each block exactly once:
 * by the tiles, by the unmasked field, or by the leaving block's picture.
 */
@RunWith(AndroidJUnit4::class)
class TextHandoffTest {
    private val page = PageId("p0")
    private val blocks = MarkdownDocument.parse("alpha\n\nbeta").blocks
    private val specA = spec(0)
    private val specB = spec(1)
    private val a = specA.focus.blockId
    private val b = specB.focus.blockId

    // What the page text leaves out (the focused block) and what the tiles under each block were rendered from.
    private val wanted = mutableSetOf(a, b)
    private val tiles = mutableSetOf(a, b)
    private var ghost: TextFieldSpec? = null
    private val frames = ArrayList<Map<Long, Int>>()
    private val handoff =
        TextHandoff(
            isDrawn = { spec -> (spec.focus.blockId in tiles) == (spec.focus.blockId in wanted) },
            capture = { ghost = it },
            apply = ::frame,
        )

    private fun spec(index: Int): TextFieldSpec {
        val resolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext<Context>())
        val block = blocks[index]
        val layout =
            BlockLayouter(resolver, FontMetricsCache(resolver))
                .layout(block, 400f, FlowTextStyle(), LayoutGrid(unitPt = 20f, indentStepPt = 20f))
        return TextFieldSpec(
            TextFocus(FlowId("body"), block.id, 0, 0, 1),
            block,
            layout,
            BlockPlacement(0, page, 40f, 100f + 40f * index, 400f),
        )
    }

    private fun frame() {
        val field =
            handoff.field
                ?.takeIf { !handoff.isMasked }
                ?.focus
                ?.blockId
        val left = handoff.leaving?.let { checkNotNull(ghost).focus.blockId }
        frames += listOf(a, b).associateWith { id -> listOf(id in tiles, field == id, left == id).count { it } }
    }

    // The page text and the field change together (CanvasText), then the handoff hears of the field.
    private fun focus(spec: TextFieldSpec?) {
        wanted.clear()
        wanted += listOf(a, b)
        spec?.let { wanted -= it.focus.blockId }
        handoff.show(spec)
    }

    // The tiles under [id] finish rendering from the current page text.
    private fun render(id: Long) {
        if (id in wanted) tiles += id else tiles -= id
        handoff.check()
        frame() // the new tiles show in this run's frame whether or not the handoff changed
    }

    private fun assertEveryFrameDrawsEachBlockOnce() {
        assertThat(frames).isNotEmpty()
        for (frame in frames) assertThat(frame.values).containsExactly(1, 1)
    }

    @Test
    fun focusThenBlur_tilesLate_noFrameDrawsABlockTwiceOrNotAtAll() {
        focus(specA)
        assertThat(handoff.isMasked).isTrue()
        render(a)
        assertThat(handoff.isMasked).isFalse()
        focus(null)
        assertThat(handoff.leaving).isSameInstanceAs(specA)
        render(a)

        assertThat(handoff.isPending).isFalse()
        assertEveryFrameDrawsEachBlockOnce()
    }

    @Test
    fun moveToAnotherBlock_tilesInEitherOrder_noFrameDrawsABlockTwiceOrNotAtAll() {
        focus(specA)
        render(a)
        focus(specB)
        assertThat(handoff.leaving).isSameInstanceAs(specA)
        assertThat(handoff.isMasked).isTrue()
        render(a)
        assertThat(handoff.leaving).isNull()
        assertThat(handoff.isMasked).isTrue()
        render(b)
        focus(specA)
        render(a)
        render(b)

        assertThat(handoff.isPending).isFalse()
        assertEveryFrameDrawsEachBlockOnce()
    }

    @Test
    fun sameBlock_newSpec_staysUnmaskedWithoutAPicture() {
        focus(specA)
        render(a)
        handoff.show(spec(0))

        assertThat(handoff.isMasked).isFalse()
        assertThat(handoff.leaving).isNull()
        assertEveryFrameDrawsEachBlockOnce()
    }

    @Test
    fun expire_tilesNeverCome_showsTheFieldAndDropsThePicture() {
        focus(specA)
        render(a)
        focus(specB)
        handoff.expire()

        assertThat(handoff.isMasked).isFalse()
        assertThat(handoff.leaving).isNull()
    }

    @Test
    fun blur_whileStillMasked_needsNoPicture() {
        focus(specA)
        focus(null)

        assertThat(handoff.leaving).isNull()
        assertThat(handoff.isPending).isFalse()
    }
}
