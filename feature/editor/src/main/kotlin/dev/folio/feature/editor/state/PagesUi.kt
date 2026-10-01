package dev.folio.feature.editor.state

import androidx.compose.runtime.Immutable
import dev.folio.core.model.PageId
import dev.folio.core.model.PageRef
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Which page surface is open over the canvas (10-editor-ui.md#pages). */
enum class PageSurface {
    NONE,

    /** The 280 dp page panel drawer. */
    PANEL,

    /** The full-screen page grid overview. */
    OVERVIEW,
}

/** Page chrome state: the page list and the surfaces that show it. */
@Immutable
data class PagesUi(
    val pages: ImmutableList<PageRef> = persistentListOf(),
    val surface: PageSurface = PageSurface.NONE,
    /** The page the pane was on when a surface opened or the last jump or insert went to (highlighted in the panel). */
    val current: PageId? = null,
    /** Page whose settings sheet is open; null when closed. */
    val settingsPage: PageId? = null,
)
