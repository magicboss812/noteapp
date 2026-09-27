package dev.folio.app

import com.google.common.truth.Truth.assertThat
import dev.folio.app.di.AppModule
import org.junit.Test

class AppModuleTest {
    @Test
    fun debugBuildUsesDebugLibraryRoot() {
        val expected = if (BuildConfig.DEBUG) "Documents/Folio-Debug" else "Documents/Folio"
        assertThat(AppModule.libraryConfig().rootRelativePath).isEqualTo(expected)
    }
}
