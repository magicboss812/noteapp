package dev.folio.core.text.fonts

import androidx.compose.ui.text.font.FontFamily
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
    fun bundled_set_matchesSpecGroups() {
        val byGroup = FontRegistry.bundled.groupingBy { it.group }.eachCount()

        assertThat(FontRegistry.bundled).hasSize(20)
        assertThat(FontRegistry.bundled.map { it.name }.toSet()).hasSize(20)
        assertThat(byGroup).containsExactly(FontGroup.SANS, 7, FontGroup.SERIF, 6, FontGroup.MONO, 2, FontGroup.HANDWRITING, 5)
    }

    @Test
    fun resolve_unknownOrNullFamily_fallsBackToInter() {
        assertThat(FontRegistry.Bundled.resolve("Comic Sans").name).isEqualTo(FontRegistry.DEFAULT_FAMILY)
        assertThat(FontRegistry.Bundled.resolve(null).name).isEqualTo(FontRegistry.DEFAULT_FAMILY)
        assertThat(FontRegistry.Bundled.resolve("Lora").name).isEqualTo("Lora")
    }

    @Test
    fun resolve_caseAndSpacesDiffer_findsFamily() {
        assertThat(FontRegistry.Bundled.resolve(" eb garamond ").name).isEqualTo("EB Garamond")
        assertThat(FontRegistry.Bundled.contains("ibm plex mono")).isTrue()
        assertThat(FontRegistry.Bundled.contains("Comic Sans")).isFalse()
    }

    @Test
    fun codeFamily_nonMonoOrUnknown_fallsBackToJetBrainsMono() {
        assertThat(FontRegistry.Bundled.codeFamily("IBM Plex Mono").name).isEqualTo("IBM Plex Mono")
        assertThat(FontRegistry.Bundled.codeFamily("Lora").name).isEqualTo(FontRegistry.DEFAULT_MONO_FAMILY)
        assertThat(FontRegistry.Bundled.codeFamily(null).name).isEqualTo(FontRegistry.DEFAULT_MONO_FAMILY)
    }

    @Test
    fun imported_newName_resolvesAndClashingNameIsDropped() {
        val custom = TextFontFamily("Fira Sans", FontGroup.IMPORTED, FontFamily.Default)
        val clash = TextFontFamily("lora", FontGroup.IMPORTED, FontFamily.Default)
        val registry = FontRegistry(listOf(custom, clash))

        assertThat(registry.families).hasSize(21)
        assertThat(registry.resolve("Fira Sans")).isSameInstanceAs(custom)
        assertThat(registry.resolve("lora").group).isEqualTo(FontGroup.SERIF)
    }

    @Test
    fun proportions_everyFamilyAndWeight_plausible() {
        val cache = FontMetricsCache(createFontFamilyResolver(ApplicationProvider.getApplicationContext()))

        for (family in FontRegistry.bundled) {
            for (bold in listOf(false, true)) {
                val p = cache.proportions(family, bold)
                val label = "${family.name} bold=$bold"
                assertWithMessage("$label cap").that(p.capRatio).isWithin(0.25f).of(0.65f)
                assertWithMessage("$label x-height").that(p.xHeightRatio).isIn(Range.open(0.25f, p.capRatio))
                assertWithMessage("$label descent").that(p.descentRatio).isIn(Range.open(0.1f, 0.6f))
                assertWithMessage("$label ascent").that(p.ascentRatio).isLessThan(0f)
            }
        }
    }
}
