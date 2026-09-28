package dev.folio.core.storage.library

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryAccessTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun check_notGranted_needsPermissionAndCreatesNothing() {
        val dir = File(tmp.root, "Documents/Folio-Debug")
        val access = LibraryAccess({ false }, LibraryRoot(dir))
        assertThat(access.check()).isEqualTo(LibraryAccessState.NeedsPermission)
        assertThat(dir.exists()).isFalse()
    }

    @Test
    fun check_granted_createsRootWithTrashAndTemplates() {
        val dir = File(tmp.root, "Documents/Folio-Debug")
        val access = LibraryAccess({ true }, LibraryRoot(dir))
        assertThat(access.check()).isEqualTo(LibraryAccessState.Ready(dir.path))
        assertThat(File(dir, ".trash").isDirectory).isTrue()
        assertThat(File(dir, ".templates").isDirectory).isTrue()
        assertThat(access.existingSystemFolders()).containsExactly(".trash", ".templates")
        assertThat(access.check()).isEqualTo(LibraryAccessState.Ready(dir.path))
    }

    @Test
    fun check_rootIsAFile_failed() {
        val dir = tmp.newFile("Folio")
        assertThat(LibraryAccess({ true }, LibraryRoot(dir)).check()).isInstanceOf(LibraryAccessState.Failed::class.java)
    }
}
