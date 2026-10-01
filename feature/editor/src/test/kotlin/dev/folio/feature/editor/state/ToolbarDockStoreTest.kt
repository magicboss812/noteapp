package dev.folio.feature.editor.state

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ToolbarDockStoreTest {
    private val settings = FakeSettingsStore()
    private val store = ToolbarDockStore(settings)

    @Test
    fun docks_nothingStored_defaultsTopInBothOrientations() =
        runTest {
            val docks = store.docks.first()

            assertThat(docks).isEqualTo(ToolbarDocks())
            assertThat(docks.landscape.mode).isEqualTo(DockMode.TOP)
            assertThat(docks.portrait.mode).isEqualTo(DockMode.TOP)
        }

    @Test
    fun save_thenRead_roundTripsEveryField() =
        runTest {
            val docks =
                ToolbarDocks(
                    handedness = Handedness.LEFT,
                    landscape = ToolbarPlacement(DockMode.FLOATING, floatX = 0.2f, floatY = 0.7f, collapsed = true),
                    portrait = ToolbarPlacement(DockMode.RIGHT),
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
        assertThat(ToolbarDockStore.decode("""{"landscape":{"mode":"BOTTOM"}}""")).isEqualTo(ToolbarDocks())
        assertThat(ToolbarDockStore.decode("""{"landscape":{"floatX":1.5}}""")).isEqualTo(ToolbarDocks())
    }
}
