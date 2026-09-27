package dev.folio.app.spikes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

// SpikeTilesView needs androidx.ink native code, so it is measured on the device (P01-S2).
class SpikeTilesCommandTest {
    @Test
    fun spikeTilesCommand_routeHidden_notOk() {
        val reply = spikeTilesCommand(view = null, arg = "a")

        assertThat(reply.ok).isFalse()
        assertThat(reply.json.toString()).contains("spike-tiles is not shown")
    }

    @Test
    fun zoomAnimCommand_routeHidden_notOk() {
        assertThat(zoomAnimCommand(view = null, arg = "1.0,3.0,800").ok).isFalse()
    }

    @Test
    fun zoomAnimParse_validArg_parsesAllParts() {
        assertThat(ZoomAnim.parse("1.0,3.0,800")).isEqualTo(ZoomAnim(1f, 3f, 800L))
    }

    @Test
    fun zoomAnimParse_badArgs_null() {
        assertThat(ZoomAnim.parse(null)).isNull()
        assertThat(ZoomAnim.parse("1.0,3.0")).isNull()
        assertThat(ZoomAnim.parse("1.0,x,800")).isNull()
        assertThat(ZoomAnim.parse("1.0,20,800")).isNull()
        assertThat(ZoomAnim.parse("1.0,3.0,0")).isNull()
    }

    @Test
    fun tileStrategyParse_anyCase_matches() {
        assertThat(TileStrategy.parse("a")).isEqualTo(TileStrategy.A)
        assertThat(TileStrategy.parse("B")).isEqualTo(TileStrategy.B)
        assertThat(TileStrategy.parse("c")).isNull()
    }

    @Test
    fun pixelDiff_oneChannelOverThreshold_countsPixel() {
        val diff = PixelDiff(threshold = 32)
        val a = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF808080.toInt(), 0xFF101010.toInt())
        val b = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000040.toInt(), 0xFF809080.toInt(), 0xFF101010.toInt())

        diff.add(a, b)

        assertThat(diff.pixels).isEqualTo(4)
        assertThat(diff.differing).isEqualTo(1) // 0x40 = 64 > 32; 0x10 = 16 is not
        assertThat(diff.differingPct).isWithin(1e-9).of(25.0)
        assertThat(diff.meanChannelDiff).isWithin(1e-9).of((64.0 + 16.0) / 12)
    }
}
