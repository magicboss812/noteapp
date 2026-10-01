package dev.folio.feature.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.folio.core.designsystem.component.CloseChip
import dev.folio.core.designsystem.component.FolioIconButton
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.designsystem.theme.folioShadow
import dev.folio.core.model.PageId
import dev.folio.core.model.PageRef
import dev.folio.core.model.edit.PageOps
import dev.folio.feature.editor.state.ApplyTo
import dev.folio.feature.editor.state.PageSettings
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal val PAGE_PANEL_WIDTH = 280.dp
private val PANEL_THUMB_WIDTH = 168.dp
private val NUMBER_WIDTH = 28.dp
private val THUMB_CORNER = 4.dp
private val AUTOSCROLL_EDGE = 56.dp
private const val AUTOSCROLL_STEP_PX = 24f

/** What the page panel and overview do with pages; each acts on the editor session. */
class PageActions(
    val onGo: (PageId) -> Unit = {},
    val onDuplicate: (Collection<PageId>) -> Unit = {},
    val onDelete: (Collection<PageId>) -> Unit = {},
    val onInsert: (PageId, PageOps.Side) -> Unit = { _, _ -> },
    val onSettings: (PageId) -> Unit = {},
    val onMove: (PageId, Int) -> Unit = { _, _ -> },
    val onCloseSurface: () -> Unit = {},
    val onApplySettings: (PageSettings, ApplyTo) -> Unit = { _, _ -> },
    val onCloseSettings: () -> Unit = {},
)

/** Toolbar buttons of the page surfaces. */
class PageChrome(
    val onPanel: () -> Unit = {},
    val onOverview: () -> Unit = {},
    val onAdd: () -> Unit = {},
)

/**
 * Page panel (10-editor-ui.md#pages): a 280 dp left drawer with a thumbnail and number per page. A tap goes to the
 * page, a long press then drag reorders (one undo step on release), the "..." menu has the per-page actions.
 */
@Composable
internal fun PagePanel(
    pages: ImmutableList<PageRef>,
    current: PageId?,
    thumbnail: PageThumbnail,
    actions: PageActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val shape = FolioTheme.shapes.card
    Column(
        modifier
            .width(PAGE_PANEL_WIDTH)
            .fillMaxHeight()
            .folioShadow(FolioTheme.elevation.popover, shape, colors.shadow)
            .background(colors.surface, shape),
    ) {
        Box(Modifier.fillMaxWidth().padding(start = space.s16, end = space.s4), contentAlignment = Alignment.CenterStart) {
            Column(Modifier.padding(vertical = space.s8)) {
                Text("Pages", style = FolioTheme.type.titleSmall, color = colors.textPrimary)
                Text(
                    text = if (pages.size == 1) "1 page" else "${pages.size} pages",
                    style = FolioTheme.type.caption,
                    color = colors.textSecondary,
                )
            }
            CloseChip(onClose, Modifier.align(Alignment.CenterEnd))
        }
        PanelList(pages, current, thumbnail, actions, Modifier.weight(1f))
    }
}

// Rows in the live order of a running drag; the document order comes back when the pages change.
@Composable
private fun PanelList(
    pages: ImmutableList<PageRef>,
    current: PageId?,
    thumbnail: PageThumbnail,
    actions: PageActions,
    modifier: Modifier = Modifier,
) {
    val space = FolioTheme.space
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val edgePx = with(LocalDensity.current) { AUTOSCROLL_EDGE.toPx() }
    val drag = remember(pages) { PanelDrag(pages.map { it.id }, listState, scope, edgePx) }
    val byId = remember(pages) { pages.associateBy { it.id } }

    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(horizontal = space.s12, vertical = space.s8),
        verticalArrangement = Arrangement.spacedBy(space.s12),
    ) {
        itemsIndexed(drag.order, key = { _, id -> id.value }) { index, id ->
            val ref = byId[id] ?: return@itemsIndexed
            val isDragging = drag.dragging == id
            PanelRow(
                number = index + 1,
                page = ref,
                isCurrent = id == current,
                thumbnail = thumbnail,
                actions = actions,
                lastPage = pages.size == 1,
                // The gesture sits outside the graphics layer: the finger's position must not move with the row.
                modifier =
                    Modifier
                        .then(if (isDragging) Modifier else Modifier.animateItem())
                        .zIndex(if (isDragging) 1f else 0f)
                        .pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { drag.start(id) },
                                onDrag = { change, amount ->
                                    change.consume()
                                    drag.move(id, amount.y)
                                },
                                onDragEnd = { drag.end(id)?.let { actions.onMove(id, it) } },
                                onDragCancel = drag::cancel,
                            )
                        }.graphicsLayer {
                            translationY = if (isDragging) drag.dyPx else 0f
                            alpha = if (isDragging) DRAG_ALPHA else 1f
                        },
            )
        }
    }
}

private const val DRAG_ALPHA = 0.9f

/** Live order of the panel rows during a long-press drag; the dragged row follows the finger and swaps as it crosses a row. */
@Stable
private class PanelDrag(
    private val documentOrder: List<PageId>,
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val edgePx: Float,
) {
    var order by mutableStateOf(documentOrder)
        private set
    var dragging by mutableStateOf<PageId?>(null)
        private set
    var dyPx by mutableFloatStateOf(0f)
        private set
    private var startIndex = 0

    fun start(id: PageId) {
        dragging = id
        dyPx = 0f
        startIndex = order.indexOf(id)
    }

    fun move(
        id: PageId,
        deltaPx: Float,
    ) {
        dyPx += deltaPx
        val visible = listState.layoutInfo.visibleItemsInfo
        val info = visible.firstOrNull { it.key == id.value } ?: return
        val from = order.indexOf(id)
        if (info.index != from) return // the list has not laid out the last swap yet
        val center = info.offset + info.size / 2f + dyPx
        val target = visible.firstOrNull { it.key != id.value && center >= it.offset && center < it.offset + it.size }
        if (target != null) {
            // The row keeps its place under the finger: its layout position jumps by the target's size.
            dyPx -= if (target.index > from) target.size else -target.size
            order = order.toMutableList().apply { add(target.index, removeAt(from)) }
        }
        autoScroll(center, listState.layoutInfo.viewportSize.height)
    }

    private fun autoScroll(
        centerPx: Float,
        viewportPx: Int,
    ) {
        when {
            centerPx < edgePx -> scope.launch { listState.scrollBy(-AUTOSCROLL_STEP_PX) }
            centerPx > viewportPx - edgePx -> scope.launch { listState.scrollBy(AUTOSCROLL_STEP_PX) }
        }
    }

    /** Ends the drag; the new index of [id] if it moved. */
    fun end(id: PageId): Int? {
        val to = order.indexOf(id)
        dragging = null
        dyPx = 0f
        return to.takeIf { it != startIndex }
    }

    fun cancel() {
        dragging = null
        dyPx = 0f
        order = documentOrder
    }
}

@Composable
private fun PanelRow(
    number: Int,
    page: PageRef,
    isCurrent: Boolean,
    thumbnail: PageThumbnail,
    actions: PageActions,
    lastPage: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    var menu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = number.toString(),
            style = FolioTheme.type.labelSmall,
            color = if (isCurrent) colors.accent else colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(NUMBER_WIDTH).padding(top = space.s8),
        )
        Box(
            Modifier
                .width(PANEL_THUMB_WIDTH)
                .clip(
                    androidx.compose.foundation.shape
                        .RoundedCornerShape(THUMB_CORNER),
                ).border(
                    if (isCurrent) space.selectedBorderWidth else space.borderWidth,
                    if (isCurrent) colors.accent else colors.border,
                    androidx.compose.foundation.shape
                        .RoundedCornerShape(THUMB_CORNER),
                ).clickable { actions.onGo(page.id) }
                .semantics {
                    contentDescription = "Page $number"
                    selected = isCurrent
                },
        ) { thumbnail(page, Modifier.fillMaxWidth()) }
        Box {
            FolioIconButton(FolioIcons.Ellipsis, "Page $number menu", onClick = { menu = true })
            PageMenu(menu, { menu = false }, page.id, lastPage, actions)
        }
    }
}

/** The per-page menu of the panel and the overview: duplicate, delete, insert blank before or after, page settings. */
@Composable
internal fun PageMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    page: PageId,
    onlyPage: Boolean,
    actions: PageActions,
) {
    val colors = FolioTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = FolioTheme.shapes.menu,
    ) {
        MenuItem("Duplicate", enabled = true) {
            onDismiss()
            actions.onDuplicate(listOf(page))
        }
        MenuItem("Insert blank before", enabled = true) {
            onDismiss()
            actions.onInsert(page, PageOps.Side.BEFORE)
        }
        MenuItem("Insert blank after", enabled = true) {
            onDismiss()
            actions.onInsert(page, PageOps.Side.AFTER)
        }
        MenuItem("Page settings", enabled = true) {
            onDismiss()
            actions.onSettings(page)
        }
        MenuItem("Delete", enabled = !onlyPage, destructive = true) {
            onDismiss()
            actions.onDelete(listOf(page))
        }
    }
}

@Composable
private fun MenuItem(
    label: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = FolioTheme.colors
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                style = FolioTheme.type.body,
                color =
                    when {
                        !enabled -> colors.textDisabled
                        destructive -> colors.danger
                        else -> colors.textPrimary
                    },
            )
        },
        onClick = onClick,
        enabled = enabled,
    )
}
