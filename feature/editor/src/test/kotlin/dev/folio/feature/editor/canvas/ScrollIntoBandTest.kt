package dev.folio.feature.editor.canvas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScrollIntoBandTest {
    @Test
    fun lineInsideTheBand_doesNotScroll() {
        assertThat(scrollIntoBand(topPx = 300f, bottomPx = 360f, clearTopPx = 50f, clearBottomPx = 1000f)).isEqualTo(0f)
    }

    @Test
    fun lineBelowTheBand_scrollsUpUntilItsBottomIsAtTheBandBottom() {
        assertThat(scrollIntoBand(topPx = 1100f, bottomPx = 1160f, clearTopPx = 50f, clearBottomPx = 1000f)).isEqualTo(-160f)
    }

    @Test
    fun lineAboveTheBand_scrollsDownUntilItsTopIsAtTheBandTop() {
        assertThat(scrollIntoBand(topPx = -200f, bottomPx = -140f, clearTopPx = 50f, clearBottomPx = 1000f)).isEqualTo(250f)
    }

    @Test
    fun lineTallerThanTheBand_putsItsTopAtTheBandTop() {
        assertThat(scrollIntoBand(topPx = 400f, bottomPx = 1400f, clearTopPx = 50f, clearBottomPx = 600f)).isEqualTo(-350f)
    }
}
