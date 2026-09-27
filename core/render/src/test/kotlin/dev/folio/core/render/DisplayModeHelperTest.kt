package dev.folio.core.render

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DisplayModeHelperTest {
    private val native60 = DisplayModeSpec(id = 1, widthPx = 2136, heightPx = 3200, refreshHz = 60f)
    private val native144 = DisplayModeSpec(id = 2, widthPx = 2136, heightPx = 3200, refreshHz = 144f)
    private val native120 = DisplayModeSpec(id = 3, widthPx = 2136, heightPx = 3200, refreshHz = 120f)
    private val lowRes165 = DisplayModeSpec(id = 4, widthPx = 1068, heightPx = 1600, refreshHz = 165f)

    @Test
    fun highestRefreshModeId_severalRates_picksFastestAtCurrentResolution() {
        val modes = listOf(native60, native144, native120)

        assertThat(highestRefreshModeId(modes, current = native60)).isEqualTo(2)
    }

    @Test
    fun highestRefreshModeId_fasterModeAtOtherResolution_ignoresIt() {
        val modes = listOf(native60, lowRes165, native120)

        assertThat(highestRefreshModeId(modes, current = native60)).isEqualTo(3)
    }

    @Test
    fun highestRefreshModeId_noModeAtCurrentResolution_returnsNull() {
        assertThat(highestRefreshModeId(listOf(lowRes165), current = native60)).isNull()
        assertThat(highestRefreshModeId(emptyList(), current = native60)).isNull()
    }
}
