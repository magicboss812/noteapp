package dev.folio.app.nav

import androidx.annotation.MainThread
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel

/** App destinations (ADR-013: Navigation 3; split view is editor state, not navigation). */
sealed interface AppRoute {
    /** Library entry: onboarding or the library. */
    data object Library : AppRoute

    /** Editor for the document at library path [docPath]. */
    data class Editor(
        val docPath: String,
    ) : AppRoute
}

/** The app back stack: the library at the bottom, at most one editor above it. */
@MainThread
class AppNavigator {
    /** Observed by the NavDisplay; never empty. */
    val backStack: SnapshotStateList<AppRoute> = mutableStateListOf(AppRoute.Library)

    /** Top destination. */
    val current: AppRoute get() = backStack.last()

    /** Shows the editor for [docPath], replacing any other open editor. */
    fun openEditor(docPath: String) {
        if ((current as? AppRoute.Editor)?.docPath == docPath) return
        backStack.removeAll { it is AppRoute.Editor }
        backStack.add(AppRoute.Editor(docPath))
    }

    /** Pops the top destination; false when only the library is left. */
    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    /** Pops everything above the library. */
    fun toLibrary() {
        while (back()) Unit
    }
}

/** Keeps the [AppNavigator] across configuration changes (activity-scoped). */
class NavigationViewModel : ViewModel() {
    /** The activity's navigator. */
    val navigator = AppNavigator()
}
