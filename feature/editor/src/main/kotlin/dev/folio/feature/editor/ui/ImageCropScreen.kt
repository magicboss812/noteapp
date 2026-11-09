package dev.folio.feature.editor.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.FolioButton
import dev.folio.core.designsystem.component.FolioButtonStyle
import dev.folio.core.designsystem.component.FolioIconButton
import dev.folio.core.designsystem.component.PillGroup
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.model.AssetId
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.RectF01
import dev.folio.core.model.image.ImageCrops
import dev.folio.feature.editor.state.CropDrag
import dev.folio.feature.editor.state.CropHandle
import dev.folio.feature.editor.state.ImageCropResult
import dev.folio.feature.editor.state.ImageEditUi
import dev.folio.feature.editor.state.ImageTarget
import kotlin.math.min
import kotlin.math.roundToInt

private val FRAME_PAD = 32.dp
private val HANDLE_GRAB = 28.dp
private val HANDLE_ARM = 18.dp
private val HANDLE_WIDTH = 4.dp
private val CROP_BORDER = 1.5.dp
private const val SCRIM_ALPHA = 0.55f

/**
 * The crop and turn screen of an image (P07-T04): the whole picture as it lies on the page (nearest quarter turn), the
 * crop as a frame with corner and edge handles and a darkened outside; Turn left/right and Reset below. [onDone] gets
 * the crop in source space and the quarter turns added; Cancel and Back call [onCancel]. [bitmap] null shows the frame
 * on an empty picture while the image decodes.
 */
@Composable
internal fun ImageCropScreen(
    edit: ImageEditUi,
    bitmap: Bitmap?,
    onDone: (ImageCropResult) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FolioTheme.colors
    val base = ImageCrops.quarterTurns(edit.rotationDeg)
    var turns by remember(edit) { mutableIntStateOf(0) }
    var shown by remember(edit) { mutableStateOf(ImageCrops.toShown(edit.crop, base)) }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val quarters = base + turns
    val turn = { by: Int ->
        shown = ImageCrops.toShown(ImageCrops.toSource(shown, quarters), quarters + by)
        turns += by
    }
    BackHandler(onBack = onCancel)
    Column(
        modifier
            .fillMaxSize()
            .background(colors.canvas)
            .pointerInput(Unit) { detectTapGestures { } }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(FolioTheme.space.s16)
            .testTag("imageCrop"),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FolioButton(label = "Cancel", onClick = onCancel, style = FolioButtonStyle.Text)
            Text(
                "Crop and turn",
                style = FolioTheme.type.title,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f).padding(horizontal = FolioTheme.space.s16),
            )
            FolioButton(label = "Done", onClick = { onDone(ImageCropResult(ImageCrops.toSource(shown, quarters), turns)) })
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(FRAME_PAD), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val srcW = bitmap?.width ?: 1
            val srcH = bitmap?.height ?: 1
            val sideways = Math.floorMod(quarters, 2) == 1
            val shownW = if (sideways) srcH else srcW
            val shownH = if (sideways) srcW else srcH
            val maxW = with(density) { maxWidth.toPx() }
            val maxH = with(density) { maxHeight.toPx() }
            val fit = min(maxW / shownW, maxH / shownH)
            val frame = Size(shownW * fit, shownH * fit)
            CropFrame(image, frame, quarters, shown, onCrop = { shown = it })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            PillGroup {
                FolioIconButton(FolioIcons.RotateCcw, "Turn left", onClick = { turn(-1) })
                FolioIconButton(FolioIcons.RotateCcw, "Turn right", onClick = { turn(1) }, modifier = Modifier.scale(-1f, 1f))
                FolioButton(
                    label = "Reset",
                    onClick = {
                        shown = RectF01.FULL
                        turns = 0
                    },
                    style = FolioButtonStyle.Text,
                )
            }
        }
    }
}

// The picture turned by [quarters] in a [frame]-sized area with the [crop] frame over it; drags change the crop.
@Composable
private fun CropFrame(
    image: ImageBitmap?,
    frame: Size,
    quarters: Int,
    crop: RectF01,
    onCrop: (RectF01) -> Unit,
) {
    val colors = FolioTheme.colors
    val density = LocalDensity.current
    val grabPx = with(density) { HANDLE_GRAB.toPx() }
    val armPx = with(density) { HANDLE_ARM.toPx() }
    val handlePx = with(density) { HANDLE_WIDTH.toPx() }
    val borderPx = with(density) { CROP_BORDER.toPx() }
    val current by rememberUpdatedState(crop)
    val scrim = colors.canvas.copy(alpha = SCRIM_ALPHA)
    val accent = colors.accent
    Box(Modifier.size(with(density) { frame.width.toDp() }, with(density) { frame.height.toDp() })) {
        Canvas(
            Modifier
                .fillMaxSize()
                .testTag("cropFrame")
                .cropDrag(frame, grabPx, { current }, onCrop),
        ) {
            if (image != null) drawTurned(image, quarters)
            val l = crop.left * size.width
            val t = crop.top * size.height
            val r = crop.right * size.width
            val b = crop.bottom * size.height
            val outside =
                Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, size.width, size.height))
                    addRect(Rect(l, t, r, b))
                }
            drawPath(outside, scrim)
            drawRect(accent, Offset(l, t), Size(r - l, b - t), style = Stroke(borderPx))
            // Corner brackets.
            for (x in listOf(l, r)) {
                for (y in listOf(t, b)) {
                    val sx = if (x == l) armPx else -armPx
                    val sy = if (y == t) armPx else -armPx
                    drawLine(accent, Offset(x, y), Offset(x + sx, y), handlePx)
                    drawLine(accent, Offset(x, y), Offset(x, y + sy), handlePx)
                }
            }
        }
    }
}

// Drags on a [frame]-sized area move the crop handle under the drag start (or the whole crop) by the drag.
private fun Modifier.cropDrag(
    frame: Size,
    grabPx: Float,
    current: () -> RectF01,
    onCrop: (RectF01) -> Unit,
): Modifier =
    pointerInput(frame) {
        var handle: CropHandle? = null
        var start = current()
        var dx = 0f
        var dy = 0f
        detectDragGestures(
            onDragStart = { at ->
                start = current()
                dx = 0f
                dy = 0f
                handle = CropDrag.handleAt(start, at.x / frame.width, at.y / frame.height, grabPx / frame.width, grabPx / frame.height)
            },
            onDrag = { change, amount ->
                val h = handle ?: return@detectDragGestures
                change.consume()
                dx += amount.x / frame.width
                dy += amount.y / frame.height
                onCrop(CropDrag.dragged(start, h, dx, dy))
            },
        )
    }

// [image] turned clockwise by [quarters], filling the draw area.
private fun DrawScope.drawTurned(
    image: ImageBitmap,
    quarters: Int,
) {
    val sideways = Math.floorMod(quarters, 2) == 1
    val w = if (sideways) size.height else size.width
    val h = if (sideways) size.width else size.height
    rotate(Math.floorMod(quarters, TURNS) * QUARTER_DEG) {
        drawImage(
            image,
            dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
        )
    }
}

private const val QUARTER_DEG = 90f
private const val TURNS = 4
private const val PREVIEW_CELL_W = 150f
private const val PREVIEW_CELL_H = 100f

// A 3:2 four-color picture for the previews: red/green over blue/yellow.
private fun previewBitmap(): Bitmap {
    val colors = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow)
    return Bitmap.createBitmap((PREVIEW_CELL_W * 2).toInt(), (PREVIEW_CELL_H * 2).toInt(), Bitmap.Config.ARGB_8888).apply {
        val c = android.graphics.Canvas(this)
        val p = android.graphics.Paint()
        colors.forEachIndexed { i, color ->
            p.color = color.toArgb()
            val x = (i % 2) * PREVIEW_CELL_W
            val y = (i / 2) * PREVIEW_CELL_H
            c.drawRect(x, y, x + PREVIEW_CELL_W, y + PREVIEW_CELL_H, p)
        }
    }
}

private val PREVIEW_EDIT =
    ImageEditUi(ImageTarget(PageId("p"), ObjectId("i")), AssetId("a".repeat(64)), RectF01(0.1f, 0.15f, 0.7f, 0.9f), 0f)

@Preview(name = "crop light", widthDp = 1164, heightDp = 777)
@Composable
private fun ImageCropLightPreview() {
    FolioTheme(darkTheme = false) { ImageCropScreen(PREVIEW_EDIT, previewBitmap(), {}, {}) }
}

@Preview(name = "crop dark", widthDp = 1164, heightDp = 777, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ImageCropDarkPreview() {
    FolioTheme(darkTheme = true) { ImageCropScreen(PREVIEW_EDIT, previewBitmap(), {}, {}) }
}
