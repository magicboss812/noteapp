package dev.folio.core.testing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

// Placeholder so every module has a test task (P00-T04). Replaced by real tests as the module grows.
class TestingModuleTest {
    @Test
    fun packageMatchesModulePath() {
        assertThat(javaClass.packageName).isEqualTo("dev.folio.core.testing")
    }
}
