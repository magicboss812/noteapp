package dev.folio.core.text.fonts

import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.FolioFs
import dev.folio.core.common.Outcome
import dev.folio.core.testing.TempDirFolioFs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CustomFontStoreTest {
    @get:Rule val temp = TempDirFolioFs()

    private val appFs: FolioFs by lazy { temp.sub("app") }
    private val libraryFs: FolioFs by lazy { temp.sub("library") }
    private var libraryAvailable = true
    private val store by lazy { CustomFontStore(appFs) { if (libraryAvailable) libraryFs else null } }

    @Test
    fun import_validFont_storedInAppFilesAndMirroredToLibrary() {
        val font = (store.import(font("ibm_plex_mono_bold.ttf")) as Outcome.Success).value

        assertThat(font.fileName).isEqualTo("IBM Plex Mono-Bold.ttf")
        assertThat(appFs.exists("fonts/IBM Plex Mono-Bold.ttf")).isTrue()
        assertThat(libraryFs.exists(".fonts/IBM Plex Mono-Bold.ttf")).isTrue()
        assertThat(store.installed().map { it.fileName }).containsExactly("IBM Plex Mono-Bold.ttf")
    }

    @Test
    fun import_sameFaceTwice_replacesFile() {
        store.import(font("kalam_regular.ttf"))
        store.import(font("kalam_regular.ttf"))

        assertThat(store.installed()).hasSize(1)
    }

    @Test
    fun import_invalidBytes_failsAndWritesNothing() {
        val result = store.import("hello".toByteArray())

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat(appFs.exists("fonts")).isFalse()
        assertThat(libraryFs.exists(".fonts")).isFalse()
    }

    @Test
    fun import_libraryUnavailable_keepsAppCopy() {
        libraryAvailable = false

        assertThat(store.import(font("kalam_regular.ttf"))).isInstanceOf(Outcome.Success::class.java)
        assertThat(store.installed()).hasSize(1)
        assertThat(libraryFs.exists(".fonts")).isFalse()
    }

    @Test
    fun syncFromLibrary_fontsFromOtherDevice_installedAndJunkSkipped() {
        libraryFs.writeBytesAtomic(".fonts/handmade name.ttf", font("patrick_hand_regular.ttf"))
        libraryFs.writeBytesAtomic(".fonts/broken.otf", "junk".toByteArray())
        libraryFs.writeBytesAtomic(".fonts/readme.txt", "x".toByteArray())

        assertThat(store.syncFromLibrary()).isEqualTo(Outcome.Success(1))
        assertThat(store.installed().map { it.fileName }).containsExactly("Patrick Hand-Regular.ttf")
        assertThat(store.syncFromLibrary()).isEqualTo(Outcome.Success(0))
    }

    @Test
    fun syncFromLibrary_noLibraryAccess_noop() {
        libraryAvailable = false

        assertThat(store.syncFromLibrary()).isEqualTo(Outcome.Success(0))
    }

    @Test
    fun registry_importedFaces_oneFamilyThatResolvesToTypefaces() {
        store.import(font("ibm_plex_mono_regular.ttf"))
        store.import(font("ibm_plex_mono_bold.ttf"))
        val families = store.families()

        assertThat(families.map { it.name }).containsExactly("IBM Plex Mono")
        assertThat(families.single().group).isEqualTo(FontGroup.IMPORTED)
        val metrics = FontMetricsCache(createFontFamilyResolver(ApplicationProvider.getApplicationContext()))
        assertThat(metrics.proportions(families.single()).capRatio).isGreaterThan(0.5f)
        assertThat(metrics.proportions(families.single(), bold = true).capRatio).isGreaterThan(0.5f)
        // Bundled names win over imported ones.
        assertThat(store.registry().resolve("IBM Plex Mono").group).isEqualTo(FontGroup.MONO)
    }

    private fun font(name: String): ByteArray = File("src/main/res/font", name).readBytes()
}
