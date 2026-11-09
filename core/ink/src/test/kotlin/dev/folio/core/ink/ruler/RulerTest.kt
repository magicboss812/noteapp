package dev.folio.core.ink.ruler

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.hypot

class RulerTest {
    private val ruler = Ruler(lengthPx = 1000f, widthPx = 100f).apply { set(500f, 400f, 0f) }

    @Test
    fun edgeNear_withinSnapOnEitherSideOfAnEdge_picksThatEdge() {
        assertThat(ruler.edgeNear(300f, 340f, SNAP)).isEqualTo(Ruler.TOP_EDGE) // 10 px above the top edge
        assertThat(ruler.edgeNear(300f, 362f, SNAP)).isEqualTo(Ruler.TOP_EDGE) // 12 px inside
        assertThat(ruler.edgeNear(300f, 455f, SNAP)).isEqualTo(Ruler.BOTTOM_EDGE)
        assertThat(ruler.edgeNear(300f, 400f, SNAP)).isEqualTo(Ruler.NO_EDGE) // the middle
        assertThat(ruler.edgeNear(300f, 320f, SNAP)).isEqualTo(Ruler.NO_EDGE) // 30 px away
        assertThat(ruler.edgeNear(1010f, 350f, SNAP)).isEqualTo(Ruler.NO_EDGE) // past the end
    }

    @Test
    fun project_horizontalRuler_keepsXAndMovesOntoTheEdgeLine() {
        assertThat(ruler.projectX(300f, 340f, Ruler.TOP_EDGE)).isWithin(EPS).of(300f)
        assertThat(ruler.projectY(300f, 340f, Ruler.TOP_EDGE)).isWithin(EPS).of(350f)
        assertThat(ruler.projectX(720f, 461f, Ruler.BOTTOM_EDGE)).isWithin(EPS).of(720f)
        assertThat(ruler.projectY(720f, 461f, Ruler.BOTTOM_EDGE)).isWithin(EPS).of(450f)
    }

    @Test
    fun project_turnedRuler_landsOnTheEdgeLineAndKeepsTheAlongPosition() {
        for (deg in listOf(-75f, -30f, 15f, 37f, 90f, 135f, 180f)) {
            ruler.set(640f, 420f, deg)
            for (edge in listOf(Ruler.TOP_EDGE, Ruler.BOTTOM_EDGE)) {
                // A wobbly pen path near the edge: every sample ends on the edge line, in order along it.
                var lastAlong = Float.NEGATIVE_INFINITY
                for (i in 0..20) {
                    val along = -400f + i * 40f
                    val off = edge * 50f + if (i % 2 == 0) 9f else -7f
                    val x = 640f + along * ruler.dirX - off * ruler.dirY
                    val y = 420f + along * ruler.dirY + off * ruler.dirX
                    val px = ruler.projectX(x, y, edge)
                    val py = ruler.projectY(x, y, edge)
                    assertThat(ruler.acrossPx(px, py)).isWithin(EPS).of(edge * 50f)
                    assertThat(ruler.alongPx(px, py)).isWithin(EPS).of(along)
                    assertThat(hypot(px - x, py - y)).isWithin(EPS).of(if (i % 2 == 0) 9f else 7f)
                    assertThat(ruler.alongPx(px, py)).isGreaterThan(lastAlong)
                    lastAlong = ruler.alongPx(px, py)
                }
            }
        }
    }

    @Test
    fun contains_turnedRuler_followsItsOutline() {
        ruler.set(500f, 500f, 90f) // vertical: 100 px wide in x, 1000 px tall in y
        assertThat(ruler.contains(540f, 950f)).isTrue()
        assertThat(ruler.contains(560f, 500f)).isFalse()
        assertThat(ruler.contains(560f, 500f, slopPx = 12f)).isTrue()
        assertThat(ruler.contains(500f, 1010f)).isFalse()
    }

    @Test
    fun snap_withinTheDetentWindow_restsOnMultiplesOf15() {
        assertThat(RulerAngles.snap(44f)).isEqualTo(45f)
        assertThat(RulerAngles.snap(-31.5f)).isEqualTo(-30f)
        assertThat(RulerAngles.snap(41f)).isEqualTo(41f)
        assertThat(RulerAngles.snap(179f)).isEqualTo(180f)
        assertThat(RulerAngles.snap(-179f)).isEqualTo(180f)
        assertThat(RulerAngles.isDetent(RulerAngles.snap(59f))).isTrue()
        assertThat(RulerAngles.isDetent(52f)).isFalse()
    }

    @Test
    fun normalize_andReadout() {
        assertThat(RulerAngles.normalize(270f)).isEqualTo(-90f)
        assertThat(RulerAngles.normalize(-180f)).isEqualTo(180f)
        assertThat(RulerAngles.readoutDeg(-30f)).isEqualTo(30f.toInt()) // rising to the right
        assertThat(RulerAngles.readoutDeg(30f)).isEqualTo(150)
        assertThat(RulerAngles.readoutDeg(180f)).isEqualTo(0)
        assertThat(RulerAngles.readoutDeg(-90f)).isEqualTo(90)
    }

    private companion object {
        const val SNAP = 24f
        const val EPS = 1e-2f
    }
}
