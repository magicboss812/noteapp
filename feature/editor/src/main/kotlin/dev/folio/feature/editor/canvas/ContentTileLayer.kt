package dev.folio.feature.editor.canvas

import android.content.Context
import android.view.View

/**
 * Committed objects in z-order (05-canvas-rendering.md#layers). Draws nothing until content tiles
 * arrive (P03-T04); it already holds its place in the layer stack.
 */
internal class ContentTileLayer(
    context: Context,
) : View(context) {
    init {
        setWillNotDraw(true)
    }
}
