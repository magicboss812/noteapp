package dev.folio.feature.editor.text

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.FlowId
import org.junit.Test

/** Every transition of the box modes (10-editor-ui.md#text-boxes, Modes). */
class BoxModesTest {
    private val a = FlowId("a")
    private val b = FlowId("b")
    private val none = BoxMode.None
    private val previewA = BoxMode.Preview(a)
    private val editA = BoxMode.Edit(a)

    private fun BoxMode.after(event: BoxEvent) = BoxModes.next(this, event)

    @Test
    fun tap_selectsTheBoxInPreview_fromEveryMode() {
        assertThat(none.after(BoxEvent.Tap(a))).isEqualTo(previewA)
        assertThat(previewA.after(BoxEvent.Tap(b))).isEqualTo(BoxMode.Preview(b))
        assertThat(editA.after(BoxEvent.Tap(b))).isEqualTo(BoxMode.Preview(b))
    }

    @Test
    fun tap_onTheSelectedBox_staysInItsMode() {
        assertThat(previewA.after(BoxEvent.Tap(a))).isEqualTo(previewA)
        assertThat(editA.after(BoxEvent.Tap(a))).isEqualTo(editA)
    }

    @Test
    fun editButton_togglesPreviewAndEdit_nothingWithoutABox() {
        assertThat(previewA.after(BoxEvent.EditButton)).isEqualTo(editA)
        assertThat(editA.after(BoxEvent.EditButton)).isEqualTo(previewA)
        assertThat(none.after(BoxEvent.EditButton)).isEqualTo(none)
    }

    @Test
    fun back_stepsEditPreviewNone() {
        assertThat(editA.after(BoxEvent.Back)).isEqualTo(previewA)
        assertThat(previewA.after(BoxEvent.Back)).isEqualTo(none)
        assertThat(none.after(BoxEvent.Back)).isEqualTo(none)
        assertThat(BoxModes.takesBack(editA)).isTrue()
        assertThat(BoxModes.takesBack(previewA)).isTrue()
        assertThat(BoxModes.takesBack(none)).isFalse()
    }

    @Test
    fun tapOutsideOrGone_endsTheSelection() {
        for (mode in listOf(none, previewA, editA)) {
            assertThat(mode.after(BoxEvent.TapOutside)).isEqualTo(none)
            assertThat(mode.after(BoxEvent.Gone)).isEqualTo(none)
        }
    }

    @Test
    fun caret_entersEditOfThatBox_fromEveryMode() {
        for (mode in listOf(none, previewA, editA)) assertThat(mode.after(BoxEvent.Caret(b))).isEqualTo(BoxMode.Edit(b))
    }

    @Test
    fun penOver_dropsEditToPreview_leavesOthers() {
        assertThat(editA.after(BoxEvent.PenOver)).isEqualTo(previewA)
        assertThat(previewA.after(BoxEvent.PenOver)).isEqualTo(previewA)
        assertThat(none.after(BoxEvent.PenOver)).isEqualTo(none)
    }

    @Test
    fun flowId_isTheSelectedBox() {
        assertThat(none.flowId).isNull()
        assertThat(previewA.flowId).isEqualTo(a)
        assertThat(editA.flowId).isEqualTo(a)
    }
}
