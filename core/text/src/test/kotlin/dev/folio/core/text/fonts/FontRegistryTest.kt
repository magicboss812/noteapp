package dev.folio.core.text.fonts

import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FontRegistryTest {
    @Test
    fun families_bundledSet_matchesSpecGroups() {
        val byGroup = FontRegistry.families.groupingBy { it.group }.eachCount()

        assertThat(FontRegistry.families).hasSize(20)
        assertThat(FontRegistry.families.map { it.name }.toSet()).hasSize(20)
        assertThat(byGroup).containsExactly(FontGroup.SANS, 7, FontGroup.SERIF, 6, FontGroup.MONO, 2, FontGroup.HANDWRITING, 5)
    }

    @Test
    fun byName_unknownFamily_fallsBackToInter() {
        assertThat(FontRegistry.byName("Comic Sans").name).isEqualTo(FontRegistry.DEFAULT_FAMILY)
        assertThat(FontRegistry.byName("Lora").name).isEqualTo("Lora")
    }

    @Test
    fun proportions_everyFamily_plausibleCapAndDescent() {
        val cache = FontMetricsCache(createFontFamilyResolver(ApplicationProvider.getApplicationContext()))

        for (family in FontRegistry.families) {
            val p = cache.proportions(family)
            assertWithMessage("${family.name} cap").that(p.capRatio).isWithin(0.25f).of(0.65f)
            assertWithMessage("${family.name} descent").that(p.descentRatio).isIn(Range.open(0.1f, 0.6f))
            assertWithMessage("${family.name} ascent").that(p.ascentRatio).isLessThan(0f)
        }
    }
}
