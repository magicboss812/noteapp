package dev.folio.core.model

import dev.folio.core.model.geometry.RectPt
import kotlinx.collections.immutable.PersistentList

/** ISO paper sizes in portrait orientation (PDF points). */
enum class PaperSize(
    val widthPt: Float,
    val heightPt: Float,
) {
    A3(841.8898f, 1190.5512f),
    A4(595.2756f, 841.8898f),
    A5(419.5276f, 595.2756f),
}

/** Page orientation; landscape swaps width and height. */
enum class Orientation { PORTRAIT, LANDSCAPE }

/** Page geometry. [widthPt]/[heightPt] are the page frame (for infinite pages: the origin frame). */
sealed interface PageSpec {
    /** Frame width in pt. */
    val widthPt: Float

    /** Frame height in pt. */
    val heightPt: Float

    /** Standard paper size. */
    data class Fixed(
        val size: PaperSize,
        val orientation: Orientation,
    ) : PageSpec {
        override val widthPt: Float
            get() = if (orientation == Orientation.PORTRAIT) size.widthPt else size.heightPt
        override val heightPt: Float
            get() = if (orientation == Orientation.PORTRAIT) size.heightPt else size.widthPt
    }

    /** Any other size (PDF pages that match no A-size within 1 pt). */
    data class Custom(
        override val widthPt: Float,
        override val heightPt: Float,
    ) : PageSpec

    /** Infinite canvas; [origin] (Fixed or Custom) keeps the template frame. */
    data class Infinite(
        val origin: PageSpec,
    ) : PageSpec {
        init {
            require(origin !is Infinite) { "Infinite origin must be Fixed or Custom" }
        }

        override val widthPt: Float get() = origin.widthPt
        override val heightPt: Float get() = origin.heightPt
    }
}

/** Page template kinds (05-canvas-rendering.md#templates). */
enum class TemplateKind {
    BLANK,
    LINED,
    GRID,
    DOTTED,
    CORNELL,
    GRAPH_AXES,
    MUSIC_STAFF,
    PLANNER_DAILY,
    PLANNER_WEEKLY,
    CUSTOM,
}

/** Template geometry; [spacingPt] is the grid unit U for most kinds. */
data class Template(
    val kind: TemplateKind,
    val spacingPt: Float,
    val lineArgb: Int,
    val marginLeftPt: Float,
    val marginTopPt: Float,
    val customAsset: AssetId?,
    val customGridPt: Float?,
)

/** PDF page shown under the page content. */
data class PdfBackground(
    val asset: AssetId,
    val pageIndex: Int,
)

/** Paper color, template and optional PDF page. */
data class Background(
    val paperArgb: Int,
    val template: Template,
    val pdf: PdfBackground?,
)

/** Lightweight page summary kept for every page so the page stack lays out without decoding objects. */
data class PageRef(
    val id: PageId,
    val spec: PageSpec,
    val background: Background,
    val contentBounds: RectPt?,
)

/** Decoded page body. [objects] index 0 = bottom of z-order. */
data class Page(
    val id: PageId,
    val spec: PageSpec,
    val background: Background,
    val objects: PersistentList<PageObject>,
) {
    /** Union of object bounds (null if there are no objects); cached in [PageRef] for infinite pages. */
    fun contentBounds(): RectPt? {
        val union = objects.fold(RectPt.EMPTY) { acc, o -> acc.union(o.bounds) }
        return if (union.isEmpty) null else union
    }

    /** The summary of this page. */
    fun toRef(): PageRef = PageRef(id, spec, background, if (spec is PageSpec.Infinite) contentBounds() else null)
}
