package dev.folio.app.nav

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppNavigatorTest {
    private val navigator = AppNavigator()

    @Test
    fun fresh_showsLibraryAndBackDoesNothing() {
        assertThat(navigator.current).isEqualTo(AppRoute.Library)
        assertThat(navigator.back()).isFalse()
        assertThat(navigator.backStack).containsExactly(AppRoute.Library)
    }

    @Test
    fun openEditor_thenBack_returnsToLibrary() {
        navigator.openEditor("a.folio")

        assertThat(navigator.current).isEqualTo(AppRoute.Editor("a.folio"))
        assertThat(navigator.back()).isTrue()
        assertThat(navigator.current).isEqualTo(AppRoute.Library)
    }

    @Test
    fun openEditor_otherDocument_replacesEditorAndSameDocumentIsNoOp() {
        navigator.openEditor("a.folio")
        navigator.openEditor("b.folio")
        navigator.openEditor("b.folio")

        assertThat(navigator.backStack).containsExactly(AppRoute.Library, AppRoute.Editor("b.folio")).inOrder()
    }

    @Test
    fun toLibrary_popsEverythingAboveLibrary() {
        navigator.openEditor("a.folio")

        navigator.toLibrary()

        assertThat(navigator.backStack).containsExactly(AppRoute.Library)
    }
}
