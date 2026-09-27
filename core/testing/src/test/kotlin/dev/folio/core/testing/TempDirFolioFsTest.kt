package dev.folio.core.testing

import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.common.getOrNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

class TempDirFolioFsTest {
    @get:Rule
    val fs = TempDirFolioFs()

    @Test
    fun writeBytesAtomic_newNestedPath_createsParentsAndLeavesNoTempFile() {
        assertThat(fs.writeBytesAtomic("Physics/a.folio", byteArrayOf(1, 2, 3))).isEqualTo(Outcome.Success(Unit))

        assertThat(fs.readBytes("Physics/a.folio").getOrNull()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(fs.list("Physics").getOrNull()?.map { it.path }).containsExactly("Physics/a.folio")
    }

    @Test
    fun list_root_returnsSortedEntriesWithTypes() {
        fs.mkdirs("b")
        fs.writeBytesAtomic("a.txt", ByteArray(4))

        val entries = fs.list("").getOrNull().orEmpty()

        assertThat(entries.map { Triple(it.path, it.isDirectory, it.sizeBytes) })
            .containsExactly(Triple("a.txt", false, 4L), Triple("b", true, 0L))
            .inOrder()
    }

    @Test
    fun move_targetExists_failsAndKeepsBoth() {
        fs.writeBytesAtomic("x", byteArrayOf(1))
        fs.writeBytesAtomic("y", byteArrayOf(2))

        assertThat(fs.move("x", "y")).isInstanceOf(Outcome.Failure::class.java)
        assertThat(fs.readBytes("y").getOrNull()).isEqualTo(byteArrayOf(2))
    }

    @Test
    fun delete_missingPath_succeeds() {
        assertThat(fs.delete("nothing")).isEqualTo(Outcome.Success(Unit))
        assertThat(fs.exists("nothing")).isFalse()
    }

    @Test
    fun readBytes_missingFile_fails() {
        assertThat(fs.readBytes("missing")).isInstanceOf(Outcome.Failure::class.java)
    }

    @Test
    fun resolve_parentSegment_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { fs.exists("../etc") }
    }
}
