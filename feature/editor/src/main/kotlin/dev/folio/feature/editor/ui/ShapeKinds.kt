package dev.folio.feature.editor.ui

import androidx.compose.runtime.Composable
import dev.folio.core.designsystem.component.ToolButton
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.model.ShapeKind
import dev.folio.feature.editor.state.SHAPE_KINDS

/** One tool button per shape kind ([SHAPE_KINDS] order), for the Shape options row and panel. */
@Composable
internal fun ShapeKindButtons(
    selected: ShapeKind,
    onSelect: (ShapeKind) -> Unit,
) {
    SHAPE_KINDS.forEach { kind ->
        ToolButton(icon = kind.icon, contentDescription = kind.label, selected = kind == selected, onClick = { onSelect(kind) })
    }
}

internal val ShapeKind.icon
    get() =
        when (this) {
            ShapeKind.LINE -> FolioIcons.Minus
            ShapeKind.ARROW -> FolioIcons.MoveUpRight
            ShapeKind.RECTANGLE -> FolioIcons.Square
            ShapeKind.ELLIPSE -> FolioIcons.Circle
            ShapeKind.TRIANGLE -> FolioIcons.Triangle
            ShapeKind.POLYGON -> FolioIcons.Hexagon
        }

internal val ShapeKind.label: String
    get() =
        when (this) {
            ShapeKind.LINE -> "Line"
            ShapeKind.ARROW -> "Arrow"
            ShapeKind.RECTANGLE -> "Rectangle"
            ShapeKind.ELLIPSE -> "Ellipse"
            ShapeKind.TRIANGLE -> "Triangle"
            ShapeKind.POLYGON -> "Polygon"
        }
