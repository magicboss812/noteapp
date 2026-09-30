package dev.folio.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TokensTest {
    @Test
    fun folderTint_dark_isHueAt22PercentOverDarkSurface() {
        // #FCE7A0 at 22% over #171A21: 0.22 * 252 + 0.78 * 23 = 73.4 (and so on per channel).
        val tint = FolioColors.Dark.folderTint(FolderTint.Yellow)

        assertThat(tint.red * 255).isWithin(1f).of(73.4f)
        assertThat(tint.green * 255).isWithin(1f).of(71.1f)
        assertThat(tint.blue * 255).isWithin(1f).of(60.9f)
        assertThat(tint.alpha).isEqualTo(1f)
    }

    @Test
    fun folderTint_light_isTheTableHue() {
        assertThat(FolioColors.Light.folderTint(FolderTint.Blue)).isEqualTo(Color(0xFFDBEAFE))
        assertThat(FolioColors.Light.folderTint(FolderTint.Gray)).isEqualTo(FolioColors.Light.surfaceMuted)
    }

    @Test
    fun pageBorder_bothModes_isTheSame() {
        // Dark mode never changes paper (R-PAGE-03).
        assertThat(FolioColors.Dark.pageBorder).isEqualTo(FolioColors.Light.pageBorder)
    }

    @Test
    fun motion_reduceMotion_halvesDurations() {
        val normal = FolioMotion()
        val reduced = FolioMotion(reduceMotion = true)

        assertThat(listOf(normal.fastMs, normal.standardMs, normal.emphasizedMs)).containsExactly(120, 200, 320).inOrder()
        assertThat(listOf(reduced.fastMs, reduced.standardMs, reduced.emphasizedMs)).containsExactly(60, 100, 160).inOrder()
    }

    @Test
    fun motion_longestDuration_staysWithinRuleLimit() {
        // .claude/rules/compose-ui.md: nothing longer than 350 ms.
        assertThat(FolioMotion().emphasizedMs).isAtMost(350)
    }
}
