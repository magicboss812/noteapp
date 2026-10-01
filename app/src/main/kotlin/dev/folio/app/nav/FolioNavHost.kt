package dev.folio.app.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.folio.feature.editor.ui.EditorCanvasListener
import dev.folio.feature.editor.ui.EditorRoute
import dev.folio.feature.library.ui.LibraryEntryRoute

/**
 * Shows [navigator]'s back stack. Every entry has its own ViewModelStore, so leaving an editor clears
 * its EditorViewModel, which releases (packs) the document. [canvasListener] observes editor canvases
 * (debug automation; null in release).
 */
@Composable
fun FolioNavHost(
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
    canvasListener: EditorCanvasListener? = null,
) {
    NavDisplay(
        backStack = navigator.backStack,
        modifier = modifier,
        onBack = { navigator.back() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        entryProvider = { route ->
            when (route) {
                AppRoute.Library -> {
                    NavEntry(route) { LibraryEntryRoute(onOpenDocument = navigator::openEditor) }
                }

                is AppRoute.Editor -> {
                    NavEntry(route) {
                        EditorRoute(route.docPath, onBack = { navigator.back() }, canvasListener = canvasListener)
                    }
                }
            }
        },
    )
}
