package dev.folio.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.designsystem.theme.FolderColor
import dev.folio.core.designsystem.theme.FolioTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Side of the square note and folder cards. */
val CardSize = 195.dp
private val HEADER_HEIGHT = 66.dp
private const val TITLE_MAX_LINES = 2
private val OVERFLOW_OVERHANG = 12.dp
private val FRONT_PAGE_WIDTH = 150.dp
private val FRONT_PAGE_HEIGHT = 100.dp
private val BACK_PAGE_WIDTH = 134.dp

// Sheet tops below the header (the back sheet used to rise into the folder name), and how much of the card the pocket covers.
private val BACK_PAGE_TOP = 10.dp
private val FRONT_PAGE_TOP = 22.dp
private const val POCKET_FRACTION = 0.5f
private const val POCKET_ALPHA = 0.55f
private const val COUNT_ALPHA = 0.45f
private const val OVERFLOW_ALPHA = 0.7f
private const val BACK_PAGE_ALPHA = 0.35f

/**
 * Note card (11-design-system.md#cards): 66 dp `surfaceHeader` header with timestamp, serif title and overflow, a 1 dp
 * divider, then the [preview] slot edge to edge. [selected] draws the 2 dp accent outline.
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
    selectMode: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onOverflow: (() -> Unit)? = null,
    preview: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val shape = FolioTheme.shapes.card
    Column(
        modifier =
            modifier
                .size(CardSize)
                .clip(shape)
                .background(colors.surface)
                .border(
                    width = if (selected) space.selectedBorderWidth else space.borderWidth,
                    color = if (selected) colors.accent else colors.border,
                    shape = shape,
                ).combinedClickable(onLongClick = onLongClick, onClick = onClick),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(HEADER_HEIGHT)
                .background(colors.surfaceHeader)
                .padding(horizontal = space.s12, vertical = space.s8),
            verticalArrangement = Arrangement.spacedBy(space.s2),
        ) {
            CardMetaLine(
                timestamp = timestamp,
                favorite = favorite,
                tags = tags,
                selectMark = if (selectMode) selected else null,
                onOverflow = onOverflow,
            )
            Text(
                text = title,
                style = FolioTheme.type.cardTitle,
                color = colors.textPrimary,
                maxLines = TITLE_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.fillMaxWidth().height(space.borderWidth).background(colors.divider))
        Column(Modifier.fillMaxWidth().weight(1f)) { preview() }
    }
}

@Composable
private fun CardMetaLine(
    timestamp: String,
    favorite: Boolean,
    tags: ImmutableList<String>,
    selectMark: Boolean?,
    onOverflow: (() -> Unit)?,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    Row(
        Modifier.fillMaxWidth().height(space.s16),
        horizontalArrangement = Arrangement.spacedBy(space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = timestamp, style = FolioTheme.type.caption, color = colors.textTertiary, maxLines = 1)
        if (favorite) {
            Icon(FolioIcons.Star, contentDescription = "Favorite", modifier = Modifier.size(space.s12), tint = colors.warning)
        }
        tags.forEach { tag ->
            Text(text = "#$tag", style = FolioTheme.type.caption, color = colors.textTertiary, maxLines = 1)
        }
        Box(Modifier.weight(1f))
        if (selectMark != null) {
            // Select mode replaces the overflow button with a tick circle.
            Icon(
                if (selectMark) FolioIcons.CircleCheck else FolioIcons.Circle,
                contentDescription = if (selectMark) "Selected" else "Not selected",
                modifier = Modifier.size(space.s16),
                tint = if (selectMark) colors.accent else colors.textTertiary,
            )
        } else if (onOverflow != null) {
            // The 44 dp target overhangs the header padding instead of pushing the title down.
            Box(Modifier.size(space.s16), contentAlignment = Alignment.Center) {
                FolioIconButton(
                    FolioIcons.Ellipsis,
                    contentDescription = "More",
                    onClick = onOverflow,
                    modifier = Modifier.offset(x = OVERFLOW_OVERHANG),
                    tint = colors.textTertiary,
                )
            }
        }
    }
}

/**
 * Folder card: [color] fill without border; header with the note count (title color at 45%) and serif name, then two
 * stacked page previews (back dark, front white with the latest note's title and first lines) under a translucent pocket.
 */
@Composable
fun FolderCard(
    name: String,
    noteCount: Int,
    color: FolderColor,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    latestTitle: String? = null,
    latestPreview: String? = null,
    onLongClick: (() -> Unit)? = null,
    onOverflow: (() -> Unit)? = null,
) {
    val space = FolioTheme.space
    val shape = FolioTheme.shapes.card
    val onColor = color.onColor
    Column(
        modifier =
            modifier
                .size(CardSize)
                .clip(shape)
                .background(color.color)
                .combinedClickable(onLongClick = onLongClick, onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth().height(HEADER_HEIGHT).padding(horizontal = space.s12, vertical = space.s8)) {
            Row(Modifier.fillMaxWidth().height(space.s16), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (noteCount == 1) "1 note" else "$noteCount notes",
                    style = FolioTheme.type.caption,
                    color = onColor.copy(alpha = COUNT_ALPHA),
                )
                Box(Modifier.weight(1f))
                if (onOverflow != null) {
                    // Same overhang as the note card: the 44 dp target must not push the title down.
                    Box(Modifier.size(space.s16), contentAlignment = Alignment.Center) {
                        FolioIconButton(
                            FolioIcons.Ellipsis,
                            contentDescription = "More",
                            onClick = onOverflow,
                            modifier = Modifier.offset(x = OVERFLOW_OVERHANG),
                            tint = onColor.copy(alpha = OVERFLOW_ALPHA),
                        )
                    }
                }
            }
            Text(text = name, style = FolioTheme.type.cardTitle, color = onColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            PreviewPage(Color.Black.copy(alpha = BACK_PAGE_ALPHA), Modifier.offset(y = BACK_PAGE_TOP), width = BACK_PAGE_WIDTH)
            PreviewPage(Color.White, Modifier.offset(y = FRONT_PAGE_TOP)) {
                Column(Modifier.padding(space.s8), verticalArrangement = Arrangement.spacedBy(space.s2)) {
                    if (latestTitle != null) {
                        Text(
                            latestTitle,
                            style = FolioTheme.type.caption,
                            color = Color(TEXT_ON_PAPER),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (latestPreview != null) {
                        Text(latestPreview, style = FolioTheme.type.caption, color = Color(TEXT_ON_PAPER).copy(alpha = COUNT_ALPHA))
                    }
                }
            }
            // Translucent pocket over the lower part of the previews.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(POCKET_FRACTION)
                    .background(Color.White.copy(alpha = POCKET_ALPHA - COUNT_ALPHA)),
            )
        }
    }
}

// Paper preview text is always dark: pages are white in both themes (R-PAGE-03).
private const val TEXT_ON_PAPER = 0xFF2E2D2B

@Composable
private fun PreviewPage(
    fill: Color,
    modifier: Modifier = Modifier,
    width: Dp = FRONT_PAGE_WIDTH,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier = modifier.size(width, FRONT_PAGE_HEIGHT).clip(FolioTheme.shapes.settings).background(fill),
        content = content,
    )
}

/** The stored cover thumbnail edge to edge, top-aligned and cropped; paper white while [cover] is still loading. */
@Composable
fun ColumnScope.NoteCardCover(
    cover: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Color.White)) {
        if (cover != null) {
            Image(
                bitmap = cover,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
            )
        }
    }
}

/** Mini-Markdown excerpt of a note in caption type on paper white (the [NoteCard] preview of a note without cover). */
@Composable
fun ColumnScope.NoteCardTextPreview(
    text: String,
    modifier: Modifier = Modifier,
) {
    NoteCardPaper(modifier) {
        Text(text = text, style = FolioTheme.type.caption, color = Color(TEXT_ON_PAPER), overflow = TextOverflow.Clip)
    }
}

/** Fills a [NoteCard] preview with paper white (cover thumbnails draw on top). */
@Composable
fun ColumnScope.NoteCardPaper(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier.fillMaxSize().background(Color.White).padding(FolioTheme.space.s12), content = content)
}
