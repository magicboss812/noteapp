package dev.folio.feature.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.ColorDot
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.feature.editor.state.OptionsPopover
import dev.folio.feature.editor.state.ToolOptions

private val SQUARE_HEIGHT = 168.dp
private val HUE_BAR_HEIGHT = 20.dp
private val THUMB_SIZE = 18.dp
private val THUMB_RING = 2.dp
private val PREVIEW_SIZE = 32.dp
private val HEX_FIELD_WIDTH = 96.dp

// Endpoints of the HSV color space and the picker thumb's contrast rings: color math, not theme colors.
private val SPACE_WHITE = Color.White
private val SPACE_BLACK = Color.Black
private val THUMB_EDGE = Color.Black.copy(alpha = 0.35f)
private const val FULL_TURN_DEG = 360f
private const val HUE_SECTORS = 6
private val HUE_STOPS = (0..HUE_SECTORS).map { Color(Hsv(it * FULL_TURN_DEG / HUE_SECTORS, 1f, 1f).toArgb()) }

/** Color picker: HSV square, hue bar, hex field and recent colors; edits a dot or adds one. */
@Composable
internal fun ColorPickerPopover(
    popover: OptionsPopover.ColorPicker,
    options: ToolOptions,
    onChange: OptionsChange,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val dots = options.swatches(popover.tool)
    val index = popover.index?.takeIf { it in dots.colors.indices }
    val initial = index?.let { dots.colors[it] } ?: dots.argb
    var hsv by remember(initial) { mutableStateOf(Hsv.of(initial)) }
    var hex by remember(initial) { mutableStateOf(hexOf(initial)) }
    val pick = { next: Hsv ->
        hsv = next
        hex = hexOf(next.toArgb())
    }
    PopoverCard(if (index == null) "Add color" else "Edit color", modifier) {
        SaturationValueSquare(hsv, pick)
        HueBar(hsv) { hueDeg -> pick(hsv.copy(hueDeg = hueDeg)) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.s12)) {
            val shape = FolioTheme.shapes.full
            Box(Modifier.size(PREVIEW_SIZE).background(Color(hsv.toArgb()), shape).border(space.borderWidth, colors.border, shape))
            HexField(hex) { text ->
                hex = hexInput(text)
                parseHex(hex)?.let { hsv = Hsv.of(it) }
            }
        }
        if (options.recentColors.isNotEmpty()) {
            Text(text = "Recent", style = FolioTheme.type.caption, color = colors.textSecondary)
            Row {
                options.recentColors.forEach { argb ->
                    ColorDot(
                        color = Color(argb),
                        contentDescription = "Recent #${hexOf(argb)}",
                        selected = argb == hsv.toArgb(),
                        onClick = { pick(Hsv.of(argb)) },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (index != null && dots.colors.size > 1) {
                PopoverButton("Remove", textColor = colors.danger) {
                    onChange { it.withSwatches(popover.tool, it.swatches(popover.tool).remove(index)) }
                    onClose()
                }
            }
            Spacer(Modifier.weight(1f))
            PopoverButton("Cancel", onClick = onClose)
            PopoverButton(if (index == null) "Add" else "Apply", filled = true) {
                val argb = hsv.toArgb()
                onChange { it.withPickedColor(popover.tool, index, argb) }
                onClose()
            }
        }
    }
}

@Composable
private fun SaturationValueSquare(
    hsv: Hsv,
    onPick: (Hsv) -> Unit,
) {
    val current by rememberUpdatedState(hsv)
    val pick by rememberUpdatedState(onPick)
    val hue = remember(hsv.hueDeg) { Color(Hsv(hsv.hueDeg, 1f, 1f).toArgb()) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(SQUARE_HEIGHT)
            .clip(FolioTheme.shapes.s)
            .semantics { contentDescription = "Saturation and brightness" }
            .pickPosition { x, y -> pick(current.copy(saturation = x, value = 1f - y)) },
    ) {
        drawRect(Brush.horizontalGradient(listOf(SPACE_WHITE, hue)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, SPACE_BLACK)))
        drawThumb(Offset(hsv.saturation * size.width, (1f - hsv.value) * size.height), Color(hsv.toArgb()))
    }
}

@Composable
private fun HueBar(
    hsv: Hsv,
    onHue: (Float) -> Unit,
) {
    val pick by rememberUpdatedState(onHue)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(HUE_BAR_HEIGHT)
            .semantics { contentDescription = "Hue" }
            .pickPosition { x, _ -> pick(x * FULL_TURN_DEG) },
    ) {
        val radius = size.height / 2
        drawRoundRect(Brush.horizontalGradient(HUE_STOPS), cornerRadius = CornerRadius(radius))
        val x = (hsv.hueDeg / FULL_TURN_DEG).coerceIn(0f, 1f) * size.width
        drawThumb(Offset(x.coerceIn(radius, size.width - radius), radius), Color(Hsv(hsv.hueDeg, 1f, 1f).toArgb()))
    }
}

private fun DrawScope.drawThumb(
    center: Offset,
    fill: Color,
) {
    val radius = THUMB_SIZE.toPx() / 2
    val ring = THUMB_RING.toPx()
    drawCircle(fill, radius, center)
    drawCircle(SPACE_WHITE, radius - ring / 2, center, style = Stroke(ring))
    drawCircle(THUMB_EDGE, radius + ring / 2, center, style = Stroke(ring / 2))
}

// Reports the pointer as fractions (0..1) of the element size on down and while it drags.
private fun Modifier.pickPosition(onPick: (Float, Float) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val report = { position: Offset ->
                onPick((position.x / size.width).coerceIn(0f, 1f), (position.y / size.height).coerceIn(0f, 1f))
            }
            report(down.position)
            down.consume()
            drag(down.id) { change ->
                report(change.position)
                change.consume()
            }
        }
    }

@Composable
private fun HexField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    Row(
        Modifier
            .heightIn(min = space.buttonVisual)
            .border(space.borderWidth, colors.border, FolioTheme.shapes.s)
            .padding(horizontal = space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "#", style = FolioTheme.type.body, color = colors.textSecondary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = FolioTheme.type.body.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
            modifier = Modifier.width(HEX_FIELD_WIDTH).semantics { contentDescription = "Hex color" },
        )
    }
}
