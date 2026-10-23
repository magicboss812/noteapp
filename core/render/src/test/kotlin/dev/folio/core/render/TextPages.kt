package dev.folio.core.render

import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FrameRole
import dev.folio.core.model.GridUnit
import dev.folio.core.model.ObjectId
import dev.folio.core.model.Page
import dev.folio.core.model.PageObject
import dev.folio.core.model.TemplateKind
import dev.folio.core.model.TextZones
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.render.template.TemplatePresets
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.text.flow.FlowLayouter
import dev.folio.core.text.flow.PageText
import dev.folio.core.text.flow.PageTexts
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.layout.BlockLayouter
import kotlinx.collections.immutable.toPersistentList

/**
 * A page of the default [kind] template whose frames show Markdown laid out by the real text engine (Robolectric).
 * One instance shares the block layout cache, so editing one block of the Markdown keeps the layouts of the others.
 */
internal class TextPages(
    kind: TemplateKind = TemplateKind.LINED,
) {
    private val resolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext())
    private val layouter = FlowLayouter(BlockLayouter(resolver, FontMetricsCache(resolver)))
    private val background = ModelFixtures.LINED_BACKGROUND.let { it.copy(template = TemplatePresets.default(kind)) }
    private val flowId = FlowId("body")

    /** The body zone of the template on A4. */
    val bodyRect: RectPt = checkNotNull(TextZones.bodyRect(ModelFixtures.A4, background.template))

    /** The template's grid unit. */
    val unitPt: Float = GridUnit.of(background.template).unitPt

    /** The one frame of [page] unless other frames are passed. */
    val body = FlowFrame(ObjectId("body-frame"), flowId, bodyRect, 0, FrameRole.BODY, autoGrow = false)

    /** A box frame of the flow, 200 pt wide, [lines] grid units tall, [topLines] units below the top of the body zone. */
    fun box(
        id: String,
        order: Int,
        topLines: Int,
        lines: Int,
    ): FlowFrame {
        val top = bodyRect.top + topLines * unitPt
        return FlowFrame(
            ObjectId(id),
            flowId,
            RectPt(BOX_LEFT_PT, top, BOX_LEFT_PT + BOX_WIDTH_PT, top + lines * unitPt),
            order,
            FrameRole.BOX,
            false,
        )
    }

    /** Page showing [frames] (the body frame by default), without text. */
    fun page(frames: List<PageObject> = listOf(body)): Page =
        ModelFixtures.page("p").copy(background = background, objects = frames.toPersistentList())

    /** The text [markdown] shows in [frames] of [page]. */
    fun text(
        markdown: String,
        frames: List<PageObject> = listOf(body),
    ): PageText {
        val doc =
            ModelFixtures.document(
                listOf(page(frames)),
                flows = listOf(ModelFixtures.flow(flowId.value, markdown)),
            )
        return PageTexts.of(doc, layouter).getValue(page(frames).id)
    }

    /** Render snapshot of [page] with [markdown] in its frames. */
    fun content(
        markdown: String,
        frames: List<PageObject> = listOf(body),
    ): PageContent = PageContent.of(page(frames), text(markdown, frames))

    private companion object {
        const val BOX_LEFT_PT = 100f
        const val BOX_WIDTH_PT = 200f
    }
}
