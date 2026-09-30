// xorshift32 shift amounts (13, 17, 5) and bit masks are the algorithm's fixed constants.
@file:Suppress("MagicNumber")

package dev.folio.core.ink.brush

import android.graphics.Bitmap
import androidx.ink.brush.TextureBitmapStore

/**
 * Textures referenced by catalog brush families. Pass this store to every androidx.ink renderer
 * (`CanvasStrokeRenderer.create(BrushTextures)`, `InProgressStrokesView.textureBitmapStore`);
 * without it textured brushes draw untextured. Texture ids are versioned like the families.
 */
object BrushTextures : TextureBitmapStore {
    /** Paper grain of the v1 pencil: white with noisy alpha, modulated by the brush color. */
    const val PENCIL_GRAIN_V1 = "folio:pencil-grain:1"

    private const val GRAIN_SIZE_PX = 64
    private const val GRAIN_SEED = 0x2F6E2B1
    private const val GAP_PERCENT = 9
    private const val GAP_ALPHA = 70
    private const val MIN_ALPHA = 150

    private val pencilGrain: Bitmap by lazy { grain() }

    override fun get(clientTextureId: String): Bitmap? = if (clientTextureId == PENCIL_GRAIN_V1) pencilGrain else null

    /** Deterministic noise (xorshift32, fixed seed): the grain never changes for v1 strokes. */
    private fun grain(): Bitmap {
        val pixels = IntArray(GRAIN_SIZE_PX * GRAIN_SIZE_PX)
        var state = GRAIN_SEED
        for (i in pixels.indices) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            val r = (state ushr 8) and 0xFFFF
            val alpha = if (r % 100 < GAP_PERCENT) GAP_ALPHA else MIN_ALPHA + r % (256 - MIN_ALPHA)
            pixels[i] = (alpha shl 24) or 0xFFFFFF
        }
        return Bitmap.createBitmap(pixels, GRAIN_SIZE_PX, GRAIN_SIZE_PX, Bitmap.Config.ARGB_8888)
    }
}
