package dev.folio.app.nav

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.folio.core.designsystem.theme.FolioMotion
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.feature.editor.ui.EditorCanvasListener
import dev.folio.feature.editor.ui.EditorRoute
import dev.folio.feature.library.ui.LibraryEntryRoute

/** Scale the editor starts from when it opens and ends at when it closes (A-035). */
private const val EDITOR_SCALE = 0.94f

/** Scale the library sits at while an editor covers it or a back gesture is in progress. */
private const val LIBRARY_SCALE = 0.97f

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
    val transitions = FolioTheme.motion.let { remember(it) { NavTransitions(it) } }
    NavDisplay(
        backStack = navigator.backStack,
        modifier = modifier,
        onBack = { navigator.back() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        transitionSpec = { transitions.open() },
        popTransitionSpec = { transitions.close() },
        predictivePopTransitionSpec = { transitions.close() },
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

/**
 * Library <-> editor transitions (11-design-system.md#motion): the editor grows in from [EDITOR_SCALE] with a fade
 * (emphasized), the library behind it settles back slightly; closing reverses it, also under the predictive back
 * gesture. With reduce motion both are plain crossfades.
 */
private class NavTransitions(
    private val motion: FolioMotion,
) {
    // The page underneath never fades, so no frame shows the window background between the two screens.
    fun open(): ContentTransform =
        ContentTransform(
            targetContentEnter = grow(EDITOR_SCALE),
            initialContentExit = settle(LIBRARY_SCALE),
            targetContentZIndex = 1f,
        )

    fun close(): ContentTransform =
        ContentTransform(
            targetContentEnter = grow(LIBRARY_SCALE, fade = false),
            initialContentExit = shrink(EDITOR_SCALE),
            targetContentZIndex = -1f,
        )

    private fun grow(
        fromScale: Float,
        fade: Boolean = true,
    ): EnterTransition {
        val fadeIn = if (fade) fadeIn(motion.emphasized()) else EnterTransition.None
        return if (motion.reduceMotion) fadeIn else fadeIn + scaleIn(motion.emphasized(), initialScale = fromScale)
    }

    private fun shrink(toScale: Float): ExitTransition {
        val fadeOut = fadeOut(motion.emphasized())
        return if (motion.reduceMotion) fadeOut else fadeOut + scaleOut(motion.emphasized(), targetScale = toScale)
    }

    private fun settle(toScale: Float): ExitTransition =
        if (motion.reduceMotion) ExitTransition.None else scaleOut(motion.emphasized(), targetScale = toScale)
}
