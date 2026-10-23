package dev.folio.feature.editor.state

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ToolbarDockStoreTest {
    private val settings = FakeSettingsStore()
    private val store = ToolbarDockStore(settings)

    @Test
    fun docks_nothingStored_defaultsBottomInBothOrientations() =
        runTest {
            val docks = store.docks.first()

            assertThat(docks).isEqualTo(ToolbarDocks())
            assertThat(docks.landscape.mode).isEqualTo(DockMode.BOTTOM)
            assertThat(docks.portrait.mode).isEqualTo(DockMode.BOTTOM)
        }

    @Test
    fun decode_version1_topMovesToBottomOnce_otherModesStay() {
        val v1 = """{"version":1,"landscape":{"mode":"TOP"},"portrait":{"mode":"LEFT"}}"""
        val unversioned = """{"landscape":{"mode":"TOP"}}"""

        assertThat(ToolbarDockStore.decode(v1)).isEqualTo(ToolbarDocks(portrait = ToolbarPlacement(DockMode.LEFT)))
        assertThat(ToolbarDockStore.decode(unversioned).landscape.mode).isEqualTo(DockMode.BOTTOM)
        // Stored again as version 2, a TOP chosen afterwards stays TOP.
        val chosen = ToolbarDocks(landscape = ToolbarPlacement(DockMode.TOP))
        assertThat(ToolbarDockStore.decode(ToolbarDockStore.encode(chosen))).isEqualTo(chosen)
    }

    @Test
    fun save_thenRead_roundTripsEveryField() =
        runTest {
            val docks =
                ToolbarDocks(
                    handedness = Handedness.LEFT,
                    landscape = ToolbarPlacement(DockMode.FLOATING, floatX = 0.2f, floatY = 0.7f, collapsed = true),
                    portrait = ToolbarPlacement(DockMode.TOP),
                    optionsHidden = true,
                )

            store.save(docks)

            assertThat(ToolbarDockStore(settings).docks.first()).isEqualTo(docks)
        }

    @Test
    fun decode_missingAndUnknownFields_defaultsKeepKnownOnes() {
        val decoded = ToolbarDockStore.decode("""{"portrait":{"mode":"LEFT"},"futureSetting":true}""")

        assertThat(decoded).isEqualTo(ToolbarDocks(portrait = ToolbarPlacement(DockMode.LEFT)))
    }

    @Test
    fun decode_invalidValues_fallBackToDefaults() {
        assertThat(ToolbarDockStore.decode("{not json")).isEqualTo(ToolbarDocks())
        assertThat(ToolbarDockStore.decode("""{"landscape":{"mode":"SIDEWAYS"}}""")).isEqualTo(ToolbarDocks())
        assertThat(ToolbarDockStore.decode("""{"landscape":{"floatX":1.5}}""")).isEqualTo(ToolbarDocks())
    }
}
