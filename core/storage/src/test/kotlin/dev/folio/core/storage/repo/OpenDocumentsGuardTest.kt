package dev.folio.core.storage.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenDocumentsGuardTest : RepositoryTestBase() {
    private fun assertRefused(result: Outcome<*>) {
        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat((result as Outcome.Failure).cause).isInstanceOf(DocumentOpenException::class.java)
    }

    @Test
    fun documentOps_openDocument_refusedAndFileAndIndexUntouched() =
        runTest {
            val doc = documents.create(spec("Open one")).orThrow()
            documents.create(spec("Elsewhere", "Target")).orThrow()
            val bytes = file(doc.path).readBytes()
            val rowBefore = db.dao().document(doc.path)
            openPaths += doc.path

            assertRefused(library.setFavorite(doc.path, true))
            assertRefused(library.setTags(doc.path, setOf("a")))
            assertRefused(documents.rename(doc.path, "Renamed"))
            assertRefused(documents.move(doc.path, "Target"))
            assertRefused(documents.delete(doc.path))

            assertThat(file(doc.path).readBytes()).isEqualTo(bytes)
            assertThat(db.dao().document(doc.path)).isEqualTo(rowBefore)
            assertThat(documents.bin.value).isEmpty()
            assertThat(file("Renamed.folio").exists()).isFalse()
            assertThat(file("Target/Open one.folio").exists()).isFalse()
        }

    @Test
    fun documentOps_afterClose_succeed() =
        runTest {
            val doc = documents.create(spec("Was open")).orThrow()
            openPaths += doc.path
            assertRefused(documents.rename(doc.path, "New name"))
            openPaths.clear()

            val renamed = documents.rename(doc.path, "New name").orThrow()

            assertThat(renamed).isEqualTo("New name.folio")
            library.setFavorite(renamed, true).orThrow()
            assertThat(db.dao().document(renamed)!!.favorite).isTrue()
        }

    @Test
    fun folderOps_folderWithOpenDocument_refused() =
        runTest {
            val doc = documents.create(spec("Inside", "School")).orThrow()
            library.createFolder("", "Other").orThrow()
            scanner.incrementalScan()
            openPaths += doc.path

            assertRefused(library.renameFolder("School", "Uni"))
            assertRefused(library.moveFolder("School", "Other"))
            assertRefused(library.deleteFolder("School"))

            assertThat(file(doc.path).exists()).isTrue()
            assertThat(documents.bin.value).isEmpty()
        }
}
