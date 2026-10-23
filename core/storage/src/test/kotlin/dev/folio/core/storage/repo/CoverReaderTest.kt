package dev.folio.core.storage.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.format.container.FolioEntries
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class CoverReaderTest : RepositoryTestBase() {
    @Test
    fun read_documentWithoutCover_isNull() =
        runTest {
            val doc = documents.create(spec("Plain")).orThrow()

            assertThat(CoverReader(fs, dispatchers).read(doc.path)).isNull()
        }

    @Test
    fun read_missingFile_isNull() =
        runTest {
            assertThat(CoverReader(fs, dispatchers).read("nothing.folio")).isNull()
        }

    @Test
    fun read_documentWithStoredCover_returnsTheBytes() =
        runTest {
            val doc = documents.create(spec("Covered")).orThrow()
            val cover = byteArrayOf(1, 2, 3, 4)
            addEntry(file(doc.path), FolioEntries.COVER, cover)

            assertThat(CoverReader(fs, dispatchers).read(doc.path)).isEqualTo(cover)
        }

    private fun addEntry(
        zipFile: File,
        name: String,
        bytes: ByteArray,
    ) {
        val copy = File(zipFile.parentFile, "copy.tmp")
        ZipFile(zipFile).use { source ->
            ZipOutputStream(copy.outputStream()).use { out ->
                source.entries().asSequence().forEach { entry ->
                    out.putNextEntry(ZipEntry(entry.name))
                    source.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        check(copy.renameTo(zipFile))
    }
}
