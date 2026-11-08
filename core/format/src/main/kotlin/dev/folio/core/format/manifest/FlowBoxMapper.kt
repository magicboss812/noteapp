package dev.folio.core.format.manifest

import dev.folio.core.format.codec.CorruptDataException
import dev.folio.core.format.codec.DecodeLimits
import dev.folio.core.format.codec.corruptIf
import dev.folio.core.model.Borders
import dev.folio.core.model.FlowBox

/** [FlowBox] <-> [FlowBoxJson] (04-file-format.md, A-059). Decoding throws [CorruptDataException]. */
internal object FlowBoxMapper {
    fun toJson(box: FlowBox): FlowBoxJson =
        FlowBoxJson(
            anchorXPt = box.anchorXPt,
            autoWidth = box.autoWidth,
            fill = box.fill,
            custom = box.custom?.let(::bordersToJson),
            fillBorders = box.fillBorders?.let(::bordersToJson),
            centerSaved = box.centerSaved?.let(::bordersToJson),
            offsetPt = box.offsetPt,
        )

    /** [json] of a flow; null (a file written before boxes): an [autoContinue] flow is the body flow and fills. */
    fun fromJson(
        json: FlowBoxJson?,
        autoContinue: Boolean,
    ): FlowBox {
        val box = json ?: return if (autoContinue) FlowBox.FILL else FlowBox.FIXED
        return FlowBox(
            anchorXPt = box.anchorXPt?.let { DecodeLimits.coord(it, "box anchor") },
            autoWidth = box.autoWidth,
            fill = box.fill,
            custom = box.custom?.let { bordersFromJson(it, "custom borders") },
            fillBorders = box.fillBorders?.let { bordersFromJson(it, "fill borders") },
            centerSaved = box.centerSaved?.let { bordersFromJson(it, "saved borders") },
            offsetPt = DecodeLimits.coord(box.offsetPt, "box offset"),
        )
    }

    private fun bordersToJson(borders: Borders): List<Float> = listOf(borders.leftPt, borders.rightPt)

    private fun bordersFromJson(
        edges: List<Float>,
        what: String,
    ): Borders {
        corruptIf(edges.size != 2) { "$what need two edges" }
        val left = DecodeLimits.coord(edges[0], what)
        val right = DecodeLimits.coord(edges[1], what)
        corruptIf(left > right) { "$what inverted" }
        return Borders(left, right)
    }
}
