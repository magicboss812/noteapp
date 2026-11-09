package dev.folio.app.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import dev.folio.core.common.FolioLog
import dev.folio.core.model.ImageObject
import dev.folio.core.model.PageId
import dev.folio.core.model.RectF01
import dev.folio.feature.editor.state.EditorSession
import dev.folio.feature.editor.state.EditorTool
import dev.folio.feature.editor.state.ImageCropResult
import dev.folio.feature.editor.state.ImageTarget
import dev.folio.feature.editor.state.ImportedImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Parsed `image-crop l,t,r,b[,turns]`: a source crop (0..1) and clockwise quarter turns to add. */
internal data class ImageCropArg(
    val crop: RectF01,
    val turns: Int,
) {
    companion object {
        private const val CROP_PARTS = 4

        fun parse(arg: String?): ImageCropArg? {
            val parts = arg?.split(',')?.takeIf { it.size == CROP_PARTS || it.size == CROP_PARTS + 1 } ?: return null
            val v = parts.take(CROP_PARTS).map { it.toFloatOrNull()?.takeIf { f -> f in 0f..1f } ?: return null }
            if (v[0] >= v[2] || v[1] >= v[3]) return null
            val turns = if (parts.size > CROP_PARTS) parts[CROP_PARTS].toIntOrNull() ?: return null else 0
            return ImageCropArg(RectF01(v[0], v[1], v[2], v[3]), turns)
        }
    }
}

/**
 * Image tool automation (P07-T04): `image-insert [w,h]` inserts a generated four-color JPEG fixture into the page on
 * screen like Gallery does (the system picker cannot be driven); `image-crop l,t,r,b[,turns]` applies a crop screen
 * result to the newest image; `image-edit` opens the crop screen on it. [json] lists the images of the loaded pages.
 */
@MainThread
internal class ImageDebug(
    private val editor: () -> EditorSession?,
    private val scope: CoroutineScope,
) {
    fun insert(arg: String?): DebugReply {
        val parts = arg?.split(',')
        val w = parts?.getOrNull(0)?.toIntOrNull() ?: DEFAULT_W
        val h = parts?.getOrNull(1)?.toIntOrNull() ?: DEFAULT_H
        if (w !in 1..MAX_SIDE || h !in 1..MAX_SIDE) return DebugReply.error("image-insert needs [w,h] in 1..$MAX_SIDE")
        val session = editor() ?: return DebugReply.error(NO_CANVAS)
        session.selectTool(EditorTool.IMAGE)
        scope.launch {
            val bytes = withContext(Dispatchers.Default) { fixtureJpeg(w, h) }
            val ok = session.insertImage(ImportedImage(bytes, "image/jpeg", "fixture.jpg", w, h, 0f))
            FolioLog.i(DebugReply.TAG, "image-insert ${w}x$h -> ${if (ok) "ok" else "failed"}")
        }
        return DebugReply.ok(buildJsonObject { put("inserting", "${w}x$h") })
    }

    fun crop(arg: String?): DebugReply {
        val parsed = ImageCropArg.parse(arg) ?: return DebugReply.error("image-crop needs l,t,r,b[,turns] with 0 <= l < r <= 1")
        val session = editor() ?: return DebugReply.error(NO_CANVAS)
        val target = newest(session) ?: return DebugReply.error("no image on the loaded pages")
        scope.launch {
            val ok = session.applyImageEdit(target, ImageCropResult(parsed.crop, parsed.turns))
            FolioLog.i(DebugReply.TAG, "image-crop $arg -> ${if (ok) "ok" else "failed"}")
        }
        return DebugReply.ok(buildJsonObject { put("cropping", arg) })
    }

    fun edit(arg: String?): DebugReply {
        if (!arg.isNullOrBlank()) return DebugReply.error("image-edit takes no argument")
        val session = editor() ?: return DebugReply.error(NO_CANVAS)
        val target = newest(session) ?: return DebugReply.error("no image on the loaded pages")
        session.editImage(target.pageId, target.id)
        return DebugReply.ok(buildJsonObject { put("editing", target.id.value) })
    }

    /** The images of the loaded pages (rect in pt, crop, turn) and whether the crop screen is open. */
    fun json(session: EditorSession): JsonObject =
        buildJsonObject {
            put("cropOpen", session.imageEdit.value != null)
            put(
                "list",
                JsonArray(
                    images(session).map { (_, o) ->
                        val r = o.rect
                        val c = o.crop
                        JsonPrimitive(
                            "${o.asset.value.take(ASSET_CHARS)} rect=${r.left.roundToInt()},${r.top.roundToInt()}," +
                                "${r.right.roundToInt()},${r.bottom.roundToInt()} crop=${c.left},${c.top},${c.right},${c.bottom} " +
                                "rot=${o.rotationDeg}",
                        )
                    },
                ),
            )
            put("assets", session.document.value.assets.size)
        }

    private fun images(session: EditorSession): List<Pair<PageId, ImageObject>> {
        val doc = session.document.value
        return doc.pages.flatMap { ref ->
            doc.pageBodies[ref.id]
                ?.objects
                .orEmpty()
                .filterIsInstance<ImageObject>()
                .map { ref.id to it }
        }
    }

    private fun newest(session: EditorSession): ImageTarget? = images(session).lastOrNull()?.let { (page, o) -> ImageTarget(page, o.id) }

    // Red, green / blue, yellow quadrants with a black diagonal from the top-left, so crops and turns are easy to read.
    @WorkerThread
    private fun fixtureJpeg(
        w: Int,
        h: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        QUADRANTS.forEachIndexed { i, argb ->
            paint.color = argb
            val left = (i % 2) * w / 2f
            val top = (i / 2) * h / 2f
            canvas.drawRect(left, top, left + w / 2f, top + h / 2f, paint)
        }
        paint.color = BLACK
        paint.strokeWidth = maxOf(w, h) / LINE_DIVISOR
        canvas.drawLine(0f, 0f, w / 2f, h / 2f, paint)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private companion object {
        const val DEFAULT_W = 1600
        const val DEFAULT_H = 1000
        const val MAX_SIDE = 8000
        const val JPEG_QUALITY = 90
        const val LINE_DIVISOR = 60f
        const val ASSET_CHARS = 8
        const val BLACK = 0xFF000000.toInt()
        const val NO_CANVAS = "no editor canvas shown; run open first"
        val QUADRANTS = intArrayOf(0xFFDC2626.toInt(), 0xFF16A34A.toInt(), 0xFF2563EB.toInt(), 0xFFEAB308.toInt())
    }
}
