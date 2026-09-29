package dev.folio.core.render.template

import android.graphics.Bitmap
import dev.folio.core.model.AssetId

/**
 * Source of CUSTOM template images: a decoded PNG or a rasterized PDF page (core:pdf) per asset.
 * The renderer scales the image to the page frame; null draws nothing (asset missing or not decodable).
 */
fun interface TemplateAssets {
    /** Image of [asset], or null. Called while drawing, so implementations return cached images only. */
    fun image(asset: AssetId): Bitmap?

    /** No custom assets available. */
    object None : TemplateAssets {
        override fun image(asset: AssetId): Bitmap? = null
    }
}
