package dev.folio.app.spikes

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class SpikePackerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val work by lazy {
        tmp
            .newFolder(
                "work",
            ).also { SpikePacker.prepareWorkingCopy(it, assetCount = 2, assetBytes = 4096, pageCount = 3, pageBytes = 2048) }
    }
    private val target by lazy { File(tmp.newFolder("library"), "doc.folio") }

    @Test
    fun pack_workingCopy_mimetypeFirstAssetsStoredPagesDeflated() {
        val result = SpikePacker.pack(work, target)

        assertThat(result.bytes).isEqualTo(target.length())
        ZipFile(target).use { zip ->
            val entries = zip.entries().toList()
            assertThat(entries.first().name).isEqualTo("mimetype")
            assertThat(entries.first().method).isEqualTo(ZipEntry.STORED)
            assertThat(entries.filter { it.name.startsWith("assets/") }.map { it.method }.toSet()).containsExactly(ZipEntry.STORED)
            assertThat(entries.filter { it.name.startsWith("pages/") }.map { it.method }.toSet()).containsExactly(ZipEntry.DEFLATED)
            assertThat(entries).hasSize(1 + 1 + 3 + 2)
        }
        assertThat(SpikePacker.verify(target).ok).isTrue()
        assertThat(File(target.parentFile, target.name + SpikePacker.TMP_SUFFIX).exists()).isFalse()
    }

    @Test
    fun pack_failsMidway_previousTargetUntouchedAndTmpLeftForCleanup() {
        SpikePacker.pack(work, target)
        val before = target.readBytes()

        val failure = runCatching { SpikePacker.pack(work, target) { name -> if (name.startsWith("assets/")) throw IOException("killed") } }

        assertThat(failure.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(target.readBytes()).isEqualTo(before)
        assertThat(SpikePacker.removeStaleTmp(target.parentFile!!)).containsExactly("doc.folio.tmp")
        assertThat(SpikePacker.verify(target).ok).isTrue()
    }

    @Test
    fun verify_truncatedFile_notOk() {
        SpikePacker.pack(work, target)
        target.writeBytes(target.readBytes().copyOf(target.length().toInt() / 2))

        assertThat(SpikePacker.verify(target).ok).isFalse()
    }
}
