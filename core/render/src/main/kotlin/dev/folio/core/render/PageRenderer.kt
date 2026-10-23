package dev.folio.core.render

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.annotation.WorkerThread
import dev.folio.core.model.Attachment
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.ImageObject
import dev.folio.core.model.InkStroke
import dev.folio.core.model.PageRef
import dev.folio.core.model.PageSpec
import dev.folio.core.model.Shape
import dev.folio.core.model.StickyNote
import dev.folio.core.model.TemplateKind
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.render.template.TemplateAssets
import dev.folio.core.render.template.TemplateRenderer
import dev.folio.core.render.template.darken

/** What [PageRenderer.draw] paints (05-canvas-rendering.md#page-renderer). */
enum class RenderTarget(
    internal val background: Boolean,
    internal val content: Boolean,
) {
    /** Screen background tiles: paper, template, PDF page. */
    SCREEN_BACKGROUND(background = true, content = false),

    /** Screen content tiles: committed objects on a transparent tile. */
    SCREEN_CONTENT(background = false, content = true),

    /** PNG export and thumbnails: everything. */
    EXPORT_RASTER(background = true, content = true),
}

/**
 * The one page renderer for screen tiles and raster export (05-canvas-rendering.md#page-renderer).
 * Deterministic for the same inputs. Not thread-safe: one instance per drawing thread.
 */
class PageRenderer(
    assets: TemplateAssets = TemplateAssets.None,
    private val ink: InkPainter = AndroidInkPainter(),
) {
    private val templates = TemplateRenderer(assets)
    private val text = TextRunPainter()
    private val toDevice = Matrix()
    private val regionF = RectF()
    private val paper = Paint()

    /**
     * Draws [regionPt] (page pt) of [page] into [canvas] at [scale] px per pt with the region's top-left
     * at the canvas origin. [content] holds the objects (null or unloaded: no objects drawn). Fixed pages
     * clip to their frame; outside it the canvas stays untouched.
     */
    @WorkerThread
    fun draw(
        canvas: Canvas,
        page: PageRef,
        content: PageContent?,
        regionPt: RectPt,
        scale: Float,
        target: RenderTarget,
    ) {
        if (regionPt.isEmpty || !(scale > 0f)) return
        regionF.set(regionPt.left, regionPt.top, regionPt.right, regionPt.bottom)
        if (page.spec !is PageSpec.Infinite && !regionF.intersect(0f, 0f, page.spec.widthPt, page.spec.heightPt)) return
        toDevice.setTranslate(-regionPt.left, -regionPt.top)
        toDevice.postScale(scale, scale)
        canvas.save()
        canvas.concat(toDevice)
        canvas.clipRect(regionF)
        if (target.background) {
            paper.color = page.background.paperArgb
            canvas.drawRect(regionF, paper)
            templates.draw(canvas, page.background.template, page.spec, regionF, scale)
            // PDF page rasters join the background with core:pdf (P08).
        }
        if (target.content && content != null) {
            try {
                val borderArgb = darken(page.background.template.lineArgb, TABLE_BORDER_DARKEN)
                drawObjects(canvas, content, regionPt, scale, textArgb(page.background.paperArgb), borderArgb)
            } finally {
                text.release()
            }
        }
        canvas.restore()
    }

    /** Fills the painter cache of object [index] of [content] ahead of drawing (ink meshes). */
    @WorkerThread
    fun prepare(
        content: PageContent,
        index: Int,
    ) {
        val o = content[index]
        if (o is InkStroke) ink.prepare(o, content, index)
    }

    private fun drawObjects(
        canvas: Canvas,
        content: PageContent,
        regionPt: RectPt,
        scale: Float,
        textArgb: Int,
        borderArgb: Int,
    ) {
        for (i in content.objectsIn(regionPt)) {
            when (val o = content[i]) {
                is InkStroke -> ink.draw(canvas, o, content, i, toDevice)

                // Unfocused text is painted at its frame's place in the z-order from the cached block layouts (P06-T05).
                is FlowFrame -> text.draw(canvas, content.text.runsOf(o.id), regionPt, scale, textArgb, borderArgb)

                // Painters for these arrive with their features (P07); a sticky note paints its own flow there.
                is Shape, is ImageObject, is StickyNote, is Attachment -> Unit
            }
        }
    }

    /** Helpers for tile producers. */
    companion object {
        /** Text color on paper [paperArgb]: near-black on light paper, near-white on dark paper. */
        fun textArgb(paperArgb: Int): Int {
            val luma = 0.299f * (paperArgb shr 16 and 0xFF) + 0.587f * (paperArgb shr 8 and 0xFF) + 0.114f * (paperArgb and 0xFF)
            return if (luma < DARK_PAPER_LUMA) LIGHT_TEXT_ARGB else DARK_TEXT_ARGB
        }

        private const val DARK_PAPER_LUMA = 110f
        private const val TABLE_BORDER_DARKEN = 0.2f // 07-text-engine.md#tables
        private const val DARK_TEXT_ARGB = 0xFF1B1D22.toInt()
        private const val LIGHT_TEXT_ARGB = 0xFFE6E6EA.toInt()

        /** True when [target] paints nothing but paper for [page] (the layer draws paper itself). */
        fun isPlainPaper(
            page: PageRef,
            target: RenderTarget,
        ): Boolean =
            target == RenderTarget.SCREEN_BACKGROUND &&
                page.background.template.kind == TemplateKind.BLANK &&
                page.background.pdf == null
    }
}
