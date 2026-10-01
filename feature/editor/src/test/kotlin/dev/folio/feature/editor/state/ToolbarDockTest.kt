package dev.folio.feature.editor.state

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToolbarDockTest {
    private val floating = ToolbarPlacement(DockMode.FLOATING, floatX = 0.5f, floatY = 0.5f)

    // A 600 x 60 pill in a 1000 x 800 area, 48 px snap distance: free space 400 x 740.
    private fun release(
        leftPx: Float,
        topPx: Float,
        current: ToolbarPlacement = floating,
    ) = DockSnap.release(current, leftPx, topPx, 600f, 60f, 1000f, 800f, 48f)

    @Test
    fun release_awayFromEdges_floatsAtReleaseFraction() {
        val placed = release(100f, 370f)

        assertThat(placed.mode).isEqualTo(DockMode.FLOATING)
        assertThat(placed.floatX).isWithin(1e-6f).of(0.25f)
        assertThat(placed.floatY).isWithin(1e-6f).of(0.5f)
    }

    @Test
    fun release_withinSnapDistance_docksAtNearestEdge() {
        assertThat(release(200f, 48f).mode).isEqualTo(DockMode.TOP)
        assertThat(release(30f, 300f).mode).isEqualTo(DockMode.LEFT)
        assertThat(release(370f, 300f).mode).isEqualTo(DockMode.RIGHT)
        // Near the top-left corner the closer edge wins.
        assertThat(release(10f, 40f).mode).isEqualTo(DockMode.LEFT)
        assertThat(release(40f, 10f).mode).isEqualTo(DockMode.TOP)
    }

    @Test
    fun release_nearBottom_floatsBecauseThereIsNoBottomDock() {
        val placed = release(200f, 740f)

        assertThat(placed.mode).isEqualTo(DockMode.FLOATING)
        assertThat(placed.floatY).isEqualTo(1f)
    }

    @Test
    fun release_outsideArea_clampedInside_keepsCollapsed() {
        val placed = release(250f, 5000f, floating.copy(collapsed = true))

        assertThat(placed).isEqualTo(ToolbarPlacement(DockMode.FLOATING, floatX = 0.625f, floatY = 1f, collapsed = true))
        assertThat(release(-500f, 300f).mode).isEqualTo(DockMode.LEFT)
    }

    @Test
    fun release_pillWiderThanArea_keepsHorizontalFraction() {
        val placed = DockSnap.release(floating.copy(floatX = 0.3f), 0f, 400f, 1200f, 60f, 1000f, 800f, 48f)

        // No horizontal free space: the pill touches both side edges, the left one wins.
        assertThat(placed.mode).isEqualTo(DockMode.LEFT)
        assertThat(placed.floatX).isEqualTo(0.3f)
    }

    @Test
    fun docks_perOrientation_independent() {
        val docks = ToolbarDocks().with(ScreenOrientation.PORTRAIT, floating)

        assertThat(docks.placement(ScreenOrientation.PORTRAIT)).isEqualTo(floating)
        assertThat(docks.placement(ScreenOrientation.LANDSCAPE)).isEqualTo(ToolbarPlacement())
    }

    @Test
    fun handedness_sideDockOppositeTheHand_switchMovesSideDocksOnly() {
        assertThat(Handedness.RIGHT.sideDock).isEqualTo(DockMode.LEFT)
        assertThat(Handedness.LEFT.sideDock).isEqualTo(DockMode.RIGHT)

        val docks = ToolbarDocks(landscape = ToolbarPlacement(DockMode.LEFT), portrait = floating)
        val left = docks.withHandedness(Handedness.LEFT)

        assertThat(left.handedness).isEqualTo(Handedness.LEFT)
        assertThat(left.landscape.mode).isEqualTo(DockMode.RIGHT)
        assertThat(left.portrait).isEqualTo(floating)
        assertThat(left.dockedToSide(ScreenOrientation.PORTRAIT).portrait.mode).isEqualTo(DockMode.RIGHT)
        assertThat(ToolbarDocks().dockedToSide(ScreenOrientation.LANDSCAPE).landscape.mode).isEqualTo(DockMode.LEFT)
    }
}
