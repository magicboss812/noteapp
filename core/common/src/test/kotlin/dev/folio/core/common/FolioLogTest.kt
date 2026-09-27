package dev.folio.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class FolioLogTest {
    @After
    fun tearDown() = FolioLog.install { _, _, _, _ -> }

    @Test
    fun log_installedSink_receivesLevelTagMessageAndThrowable() {
        val lines = mutableListOf<String>()
        FolioLog.install { level, tag, message, t -> lines += "$level $tag $message ${t?.message}" }
        val boom = IllegalStateException("boom")

        FolioLog.d("Ink", "down")
        FolioLog.e("Io", "failed", boom)

        assertThat(lines).containsExactly("DEBUG Ink down null", "ERROR Io failed boom").inOrder()
    }
}
