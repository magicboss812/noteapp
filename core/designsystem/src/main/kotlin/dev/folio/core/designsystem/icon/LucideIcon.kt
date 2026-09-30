package dev.folio.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Helpers for the generated FolioIcons.kt (tools:icongen). Icons are drawn black and tinted by Icon().
private const val VIEWBOX = 24f
private const val STROKE_WIDTH = 1.75f

internal class LucidePath(
    val pathData: String,
    val filled: Boolean,
)

/** A stroked path; [parts] are one path split for line length and are joined with spaces. */
internal fun stroke(vararg parts: String) = LucidePath(parts.joinToString(" "), filled = false)

/** A path that is filled as well as stroked (SVG `fill="currentColor"`). */
internal fun filled(vararg parts: String) = LucidePath(parts.joinToString(" "), filled = true)

internal fun lucideIcon(
    name: String,
    vararg paths: LucidePath,
): ImageVector {
    val builder = ImageVector.Builder(name, VIEWBOX.dp, VIEWBOX.dp, VIEWBOX, VIEWBOX)
    paths.forEach { path ->
        builder.addPath(
            pathData = addPathNodes(path.pathData),
            fill = if (path.filled) SolidColor(Color.Black) else null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE_WIDTH,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}
