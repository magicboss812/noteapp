package dev.folio.feature.editor.state

import com.google.common.truth.Truth.assertThat
import dev.folio.core.ink.brush.BrushPresets
import dev.folio.core.model.BrushKind
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test

class ToolOptionsTest {
    private val five = Swatches(BrushPresets.PEN_PALETTE.toImmutableList())

    @Test
    fun swatches_addNewColor_appendsAndSelects() {
        val next = five.add(CUSTOM)

        assertThat(next.colors).hasSize(6)
        assertThat(next.argb).isEqualTo(CUSTOM)
    }

    @Test
    fun swatches_addExistingColor_selectsItWithoutDuplicate() {
        val next = five.add(BrushPresets.PEN_PALETTE[2])

        assertThat(next.colors).isEqualTo(five.colors)
        assertThat(next.selected).isEqualTo(2)
    }

    @Test
    fun swatches_addWhenFull_replacesSelected() {
        val full = Swatches((1..Swatches.MAX).toList().toImmutableList(), selected = 3)

        val next = full.add(CUSTOM)

        assertThat(next.colors).hasSize(Swatches.MAX)
        assertThat(next.colors[3]).isEqualTo(CUSTOM)
        assertThat(next.selected).isEqualTo(3)
    }

    @Test
    fun swatches_remove_keepsSelectionOnSameColorAndNeverEmpties() {
        val selectedLast = five.select(4)

        assertThat(selectedLast.remove(1).argb).isEqualTo(BrushPresets.PEN_PALETTE[4])
        assertThat(selectedLast.remove(4).selected).isEqualTo(3)
        assertThat(Swatches(persistentListOf(CUSTOM)).remove(0).colors).containsExactly(CUSTOM)
    }

    @Test
    fun widthPresets_set_clampsToCustomRange() {
        val presets = WidthPresets.of(BrushKind.MARKER)

        assertThat(presets.set(0, 99f).widthsPt[0]).isEqualTo(BrushPresets.MAX_WIDTH_PT)
        assertThat(presets.set(2, 0f).widthsPt[2]).isEqualTo(BrushPresets.MIN_WIDTH_PT)
    }

    @Test
    fun pen_brushUsesKindWidthsColorAndGamma() {
        val pen =
            PenOptions()
                .copy(kind = BrushKind.FOUNTAIN, swatches = five.select(1))
                .let { it.withKindWidths(it.kindWidths.select(2)) }
                .withPressureGamma(1.43f)

        val brush = pen.brush(version = 1)

        assertThat(brush.kind).isEqualTo(BrushKind.FOUNTAIN)
        assertThat(brush.sizePt).isEqualTo(BrushPresets.widthsPt(BrushKind.FOUNTAIN)[2])
        assertThat(brush.argb).isEqualTo(BrushPresets.PEN_PALETTE[1])
        assertThat(brush.pressureGamma).isEqualTo(1.45f)
        // Other kinds keep their own selection.
        assertThat(pen.widths.getValue(BrushKind.BALLPOINT).selected).isEqualTo(1)
    }

    @Test
    fun recentColors_mostRecentFirstWithoutDuplicates_capped() {
        var options = ToolOptions()
        (1..10).forEach { options = options.withRecentColor(it) }
        options = options.withRecentColor(5)

        assertThat(options.recentColors).containsExactly(5, 10, 9, 8, 7, 6, 4, 3).inOrder()
    }

    @Test
    fun perTool_highlighterHasOwnDotsAndWidths_penEditsSelectedKind() {
        val options = ToolOptions(pen = PenOptions(kind = BrushKind.PENCIL))

        val edited =
            options
                .withSwatches(EditorTool.HIGHLIGHTER, options.swatches(EditorTool.HIGHLIGHTER).add(CUSTOM))
                .withWidths(EditorTool.PEN, options.widths(EditorTool.PEN).set(0, 5f))

        assertThat(edited.highlighter.swatches.argb).isEqualTo(CUSTOM)
        assertThat(edited.pen.swatches).isEqualTo(options.pen.swatches)
        assertThat(
            edited.pen.widths
                .getValue(BrushKind.PENCIL)
                .widthsPt[0],
        ).isEqualTo(5f)
        assertThat(edited.pen.widths.getValue(BrushKind.BALLPOINT)).isEqualTo(options.pen.widths.getValue(BrushKind.BALLPOINT))
        assertThat(edited.widths(EditorTool.HIGHLIGHTER)).isEqualTo(options.highlighter.widths)
    }

    @Test
    fun pickedColor_editsDotOrAdds_staleIndexAdds_becomesRecent() {
        val options = ToolOptions()

        val edited = options.withPickedColor(EditorTool.PEN, 2, CUSTOM)
        val added = options.withPickedColor(EditorTool.HIGHLIGHTER, null, CUSTOM)
        val stale = options.withPickedColor(EditorTool.PEN, 9, CUSTOM)

        assertThat(edited.pen.swatches.colors).hasSize(5)
        assertThat(edited.pen.swatches.colors[2]).isEqualTo(CUSTOM)
        assertThat(edited.pen.swatches.selected).isEqualTo(2)
        assertThat(
            added.highlighter.swatches.colors
                .last(),
        ).isEqualTo(CUSTOM)
        assertThat(stale.pen.swatches.colors).hasSize(6)
        assertThat(edited.recentColors).containsExactly(CUSTOM)
    }

    private companion object {
        const val CUSTOM = 0xFF123456.toInt()
    }
}
