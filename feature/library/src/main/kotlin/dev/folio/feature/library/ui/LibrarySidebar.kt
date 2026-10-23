package dev.folio.feature.library.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.FolioIconButton
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.feature.library.state.LibraryFolderItem
import dev.folio.feature.library.state.LibraryTab
import kotlinx.collections.immutable.ImmutableList

internal val SIDEBAR_WIDTH = 280.dp
internal val RAIL_WIDTH = 80.dp
private val PILL_HEIGHT = 42.dp
private val RAIL_PILL_WIDTH = 50.dp
private val NAV_INSET = 15.dp
private val FOLDER_INDENT = 16.dp
private val SECTION_TOP = 16.dp

/**
 * Sidebar (11-design-system.md#library-screen): 280 dp, or an 80 dp icon rail when [collapsed]. Home, Favorites and
 * Bin are the same views as the tabs; Home and the folder pills open a folder ([currentFolder] is marked); the gear
 * sits at the bottom. [onOpenSettings] is null while that screen does not exist, which draws the button disabled.
 */
@Composable
internal fun LibrarySidebar(
    collapsed: Boolean,
    tab: LibraryTab,
    folders: ImmutableList<LibraryFolderItem>,
    onToggleCollapse: () -> Unit,
    onSelectTab: (LibraryTab) -> Unit,
    onOpenFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
    currentFolder: String = "",
    onOpenSettings: (() -> Unit)? = null,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val motion = FolioTheme.motion
    val width by animateDpAsState(if (collapsed) RAIL_WIDTH else SIDEBAR_WIDTH, motion.standard(), label = "sidebarWidth")
    Column(
        modifier =
            modifier
                .width(width)
                .clipToBounds()
                .fillMaxHeight()
                .background(colors.sidebar)
                .padding(vertical = space.s8),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = if (collapsed) Alignment.Center else Alignment.CenterEnd) {
            FolioIconButton(
                icon = FolioIcons.PanelLeft,
                contentDescription = if (collapsed) "Expand sidebar" else "Collapse sidebar",
                onClick = onToggleCollapse,
                modifier = Modifier.padding(horizontal = space.s8),
            )
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Explicit gaps instead of spacedBy: the folders block collapses to zero height without leaving a double gap.
            NavPill(
                FolioIcons.House,
                "Home",
                tab == LibraryTab.ALL && currentFolder.isEmpty(),
                collapsed,
                onClick = { onOpenFolder("") },
            )
            Spacer(Modifier.height(space.s4))
            NavPill(FolioIcons.Star, "Favorites", tab == LibraryTab.FAVORITES, collapsed, onClick = { onSelectTab(LibraryTab.FAVORITES) })
            Spacer(Modifier.height(space.s4))
            AnimatedVisibility(
                visible = !collapsed,
                enter = fadeIn(motion.standard()) + expandVertically(motion.standard()),
                exit = fadeOut(motion.fast()) + shrinkVertically(motion.standard()),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(space.s4), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (folders.isNotEmpty()) SectionHeader("Folders")
                    folders.forEach { folder ->
                        NavPill(
                            icon = FolioIcons.Folder,
                            label = folder.name,
                            selected = tab == LibraryTab.ALL && folder.path == currentFolder,
                            collapsed = false,
                            onClick = { onOpenFolder(folder.path) },
                            indent = FOLDER_INDENT * folder.depth,
                        )
                    }
                    // Zero height, but spacedBy puts the gap to the Bin pill after the last folder.
                    if (folders.isNotEmpty()) Spacer(Modifier.height(0.dp))
                }
            }
            NavPill(FolioIcons.Trash, "Bin", tab == LibraryTab.BIN, collapsed, onClick = { onSelectTab(LibraryTab.BIN) })
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = space.s8), contentAlignment = Alignment.CenterStart) {
            FolioIconButton(
                icon = FolioIcons.Settings,
                contentDescription = "Settings",
                onClick = { onOpenSettings?.invoke() },
                enabled = onOpenSettings != null,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = FolioTheme.type.bodyMedium,
        color = FolioTheme.colors.textSecondary,
        modifier =
            Modifier.fillMaxWidth().padding(
                start = NAV_INSET + FolioTheme.space.s12,
                top = SECTION_TOP,
                bottom = FolioTheme.space.s4,
            ),
    )
}

@Composable
private fun NavPill(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    collapsed: Boolean,
    onClick: (() -> Unit)?,
    indent: Dp = 0.dp,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val tint = if (selected) colors.accent else colors.textPrimary
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Tab, onClick = onClick) else Modifier
    val shape = FolioTheme.shapes.menu
    if (collapsed) {
        Box(
            Modifier
                .size(RAIL_PILL_WIDTH, PILL_HEIGHT)
                .clip(shape)
                .background(if (selected) colors.navSelected else colors.sidebar)
                .then(clickable),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(space.iconMenu), tint = tint)
        }
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = NAV_INSET)
            .height(PILL_HEIGHT)
            .clip(shape)
            .background(if (selected) colors.navSelected else colors.sidebar)
            .then(clickable)
            .padding(start = space.s12 + indent, end = space.s12),
        horizontalArrangement = Arrangement.spacedBy(space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(space.iconMenu), tint = tint)
        Text(
            text = label,
            style = FolioTheme.type.bodyMedium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
