package dev.folio.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.designsystem.theme.FolderTint
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.designsystem.theme.folioShadow
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

private val CARD_MIN_HEIGHT = 240.dp
private const val TITLE_MAX_LINES = 2
private val STACK_HEIGHT = 132.dp
private val SHEET_HEIGHT = 112.dp
private val FADE_HEIGHT = 36.dp
private const val BACK_SHEET_ROTATION_DEG = -4f
private const val MIDDLE_SHEET_ROTATION_DEG = 3f
private const val PREVIEW_MAX_LINES = 4
private val OVERFLOW_OVERHANG = 10.dp

/**
 * Note card shell (11-design-system.md#cards): timestamp and overflow on top, serif title, a [preview] slot for the
 * cover thumbnail or mini-Markdown text, favorite star and tags at the bottom. [selected] draws the 2 dp accent outline.
 */
@Composable
fun NoteCard(
    title: String,
    timestamp: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    favorite: Boolean = false,
    tags: ImmutableList<String> = persistentListOf(),
    selected: Boolean = false,
    onOverflow: (() -> Unit)? = null,
    preview: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val shape = FolioTheme.shapes.l
    Column(
        modifier =
            modifier
                .heightIn(min = CARD_MIN_HEIGHT)
                .folioShadow(FolioTheme.elevation.card, shape, colors.shadow)
                .clip(shape)
                .background(colors.surface)
                .border(
                    width = if (selected) space.selectedBorderWidth else space.borderWidth,
                    color = if (selected) colors.accent else colors.border,
                    shape = shape,
                ).clickable(onClick = onClick)
                .padding(space.s20),
        verticalArrangement = Arrangement.spacedBy(space.s8),
    ) {
        CardTopLine(caption = timestamp, onOverflow = onOverflow)
        Text(
            text = title,
            style = FolioTheme.type.cardTitle,
            color = colors.textPrimary,
            maxLines = TITLE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        Column(Modifier.weight(1f)) { preview() }
        if (favorite || tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(space.s8), verticalAlignment = Alignment.CenterVertically) {
                if (favorite) {
                    Icon(FolioIcons.Star, contentDescription = "Favorite", modifier = Modifier.size(space.iconChip), tint = colors.warning)
                }
                tags.forEach { TagChip(it) }
            }
        }
    }
}

@Composable
private fun CardTopLine(
    caption: String,
    onOverflow: (() -> Unit)?,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = FolioTheme.space.s24), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = caption,
            style = FolioTheme.type.caption,
            color = FolioTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (onOverflow != null) {
            // The 44 dp target overhangs the card padding instead of pushing the title down.
            Box(Modifier.size(FolioTheme.space.s24), contentAlignment = Alignment.Center) {
                FolioIconButton(
                    FolioIcons.Ellipsis,
                    contentDescription = "More",
                    onClick = onOverflow,
                    modifier = Modifier.offset(x = OVERFLOW_OVERHANG),
                )
            }
        }
    }
}

@Composable
private fun TagChip(tag: String) {
    val space = FolioTheme.space
    Text(
        text = tag,
        style = FolioTheme.type.caption,
        color = FolioTheme.colors.textSecondary,
        modifier =
            Modifier
                .background(FolioTheme.colors.surfaceMuted, FolioTheme.shapes.s)
                .padding(horizontal = space.s8, vertical = space.s2),
    )
}

/**
 * Folder card shell: tinted background, two offset sheets behind a front sheet with the latest note's title and
 * first lines (faded at the bottom), the note count caption and the folder name.
 */
@Composable
fun FolderCard(
    name: String,
    noteCount: Int,
    tint: FolderTint,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    latestTitle: String? = null,
    latestPreview: String? = null,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val shape = FolioTheme.shapes.l
    Column(
        modifier =
            modifier
                .heightIn(min = CARD_MIN_HEIGHT)
                .clip(shape)
                .background(colors.folderTint(tint))
                .clickable(onClick = onClick)
                .padding(space.s20),
        verticalArrangement = Arrangement.spacedBy(space.s4),
    ) {
        Box(Modifier.fillMaxWidth().height(STACK_HEIGHT).padding(horizontal = space.s12), contentAlignment = Alignment.BottomCenter) {
            PaperSheet(Modifier.offset(y = -space.s20).rotate(BACK_SHEET_ROTATION_DEG).padding(horizontal = space.s16))
            PaperSheet(Modifier.offset(y = -space.s8).rotate(MIDDLE_SHEET_ROTATION_DEG).padding(horizontal = space.s8))
            PaperSheet {
                Column(Modifier.padding(space.s12), verticalArrangement = Arrangement.spacedBy(space.s4)) {
                    if (latestTitle != null) {
                        Text(
                            latestTitle,
                            style = FolioTheme.type.label,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (latestPreview != null) {
                        Text(latestPreview, style = FolioTheme.type.bodySmall, color = colors.textSecondary, maxLines = PREVIEW_MAX_LINES)
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(FADE_HEIGHT)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, colors.surface))),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = if (noteCount == 1) "1 note" else "$noteCount notes",
            style = FolioTheme.type.caption,
            color = colors.textSecondary,
        )
        Text(text = name, style = FolioTheme.type.cardTitle, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PaperSheet(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val colors = FolioTheme.colors
    val shape = FolioTheme.shapes.xs
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(SHEET_HEIGHT)
                .folioShadow(FolioTheme.elevation.card, shape, colors.shadow)
                .clip(shape)
                .background(colors.surface)
                .border(FolioTheme.space.borderWidth, colors.border, shape),
        content = content,
    )
}
