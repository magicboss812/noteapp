package dev.folio.core.text.fonts

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.testing.TempDirFolioFs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FontLibraryTest {
    @get:Rule val temp = TempDirFolioFs()

    private val libraryFs by lazy { temp.sub("library") }
    private val library by lazy { FontLibrary(CustomFontStore(temp.sub("app")) { libraryFs }) }

    @Test
    fun noStore_bundledOnly_importFails() {
        val library = FontLibrary(null)

        assertThat(library.sync()).isEqualTo(Outcome.Success(0))
        assertThat(library.current()).isSameInstanceAs(FontRegistry.Bundled)
        assertThat(library.import(font("kalam_regular.ttf"))).isInstanceOf(Outcome.Failure::class.java)
    }

    @Test
    fun import_validFont_publishesRegistryWithTheFamily() {
        val before = library.current()

        val imported = (library.import(font("ibm_plex_mono_regular.ttf")) as Outcome.Success).value

        assertThat(library.registry.value).isNotSameInstanceAs(before)
        assertThat(library.current().families.map { it.name }).contains(imported.info.familyName)
    }

    @Test
    fun import_invalidBytes_keepsRegistry() {
        val before = library.current()

        assertThat(library.import("junk".toByteArray())).isInstanceOf(Outcome.Failure::class.java)
        assertThat(library.current()).isSameInstanceAs(before)
    }

    @Test
    fun sync_fontInLibraryFolder_installedAndPublished() {
        libraryFs.writeBytesAtomic(".fonts/hand.ttf", font("patrick_hand_regular.ttf"))
        val before = library.current()

        assertThat(library.sync()).isEqualTo(Outcome.Success(1))
        // The test fonts are bundled ones, so the bundled family keeps the name; the registry is still new.
        assertThat(library.current()).isNotSameInstanceAs(before)
    }

    private fun font(name: String): ByteArray = File("src/main/res/font", name).readBytes()
}
