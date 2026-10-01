package dev.folio.feature.editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.feature.editor.state.DockMode
import dev.folio.feature.editor.state.EditorStatus
import dev.folio.feature.editor.state.EditorUiState
import dev.folio.feature.editor.state.ScreenOrientation
import dev.folio.feature.editor.state.ToolbarDocks
import dev.folio.feature.editor.state.ToolbarPlacement
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Dragging and double-tapping the toolbar grip on the editor screen (10-editor-ui.md#toolbar-docking). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1164dp-h777dp-land-440dpi")
class ToolbarDockingTest {
    @get:Rule
    val compose = createComposeRule()

    private var docks by mutableStateOf(ToolbarDocks())
    private val placed = mutableListOf<Pair<ScreenOrientation, ToolbarPlacement>>()

    private fun show(initial: ToolbarPlacement) {
        docks = ToolbarDocks(landscape = initial)
        compose.setContent {
            FolioTheme(darkTheme = false) {
                EditorScreen(
                    state = EditorUiState(EditorStatus.READY, docks = docks),
                    onBack = {},
                    onSelectTool = {},
                    onPlaceToolbar = { orientation, placement ->
                        placed += orientation to placement
                        docks = docks.with(orientation, placement)
                    },
                ) { Box(it) }
            }
        }
        compose.waitForIdle()
    }

    private fun dragGrip(by: Offset) {
        compose.onNodeWithContentDescription(GRIP, substring = true).performTouchInput {
            swipe(center, center + by, durationMillis = 400)
        }
        compose.waitForIdle()
    }

    @Test
    fun dragTopGrip_releasedMidCanvas_floatsThere() {
        show(ToolbarPlacement())

        dragGrip(Offset(-1500f, 900f))

        val (orientation, placement) = placed.single()
        assertThat(orientation).isEqualTo(ScreenOrientation.LANDSCAPE)
        assertThat(placement.mode).isEqualTo(DockMode.FLOATING)
        assertThat(placement.floatY).isGreaterThan(0.2f)
        assertThat(placement.floatX).isIn(Range.open(0f, 1f))
    }

    @Test
    fun dragFloatingGrip_toLeftEdge_docksLeft_thenToRightEdge_docksRight() {
        show(ToolbarPlacement(DockMode.FLOATING, floatX = 0.5f, floatY = 0.5f))

        dragGrip(Offset(-3000f, 0f))
        assertThat(docks.landscape.mode).isEqualTo(DockMode.LEFT)

        dragGrip(Offset(3000f, 0f))
        assertThat(docks.landscape.mode).isEqualTo(DockMode.RIGHT)
        assertThat(placed.map { it.second.mode }).containsExactly(DockMode.LEFT, DockMode.RIGHT).inOrder()
    }

    @Test
    fun doubleTapFloatingGrip_collapsesThenExpands() {
        show(ToolbarPlacement(DockMode.FLOATING, floatX = 0.5f, floatY = 0.5f))

        compose.onNodeWithContentDescription(GRIP, substring = true).performTouchInput { doubleClick() }
        compose.waitForIdle()
        assertThat(docks.landscape.collapsed).isTrue()

        compose.onNodeWithContentDescription(GRIP, substring = true).performTouchInput { doubleClick() }
        compose.waitForIdle()
        assertThat(docks.landscape).isEqualTo(ToolbarPlacement(DockMode.FLOATING, floatX = 0.5f, floatY = 0.5f))
    }

    @Test
    fun toolbarDrag_startedPastTheEdge_gripCatchesUpWithTheFinger() {
        val drag = ToolbarDrag()
        drag.areaSize = IntSize(1000, 800)
        drag.pillSize = IntSize(600, 60)

        // A grip at the right end of row 1: the preview would stick out, so it shows clamped at x = 400.
        drag.start(Offset(900f, 0f))
        assertThat(drag.offset).isEqualTo(Offset(400f, 0f))

        // Moving 700 px left puts the pill where the finger leads it, not 700 px left of the clamped spot.
        drag.move(Offset(-700f, 300f))
        assertThat(drag.offset).isEqualTo(Offset(200f, 300f))
    }

    private companion object {
        const val GRIP = "Move toolbar"
    }
}
