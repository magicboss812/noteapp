package dev.folio.core.format.codec

import com.google.common.truth.Truth.assertWithMessage
import dev.folio.core.common.Outcome
import dev.folio.core.model.AssetId
import dev.folio.core.model.Attachment
import dev.folio.core.model.Background
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FrameRole
import dev.folio.core.model.ImageObject
import dev.folio.core.model.InkStroke
import dev.folio.core.model.InputTool
import dev.folio.core.model.ObjectId
import dev.folio.core.model.Page
import dev.folio.core.model.PageId
import dev.folio.core.model.PageSpec
import dev.folio.core.model.PdfBackground
import dev.folio.core.model.RectF01
import dev.folio.core.model.Shape
import dev.folio.core.model.ShapeKind
import dev.folio.core.model.StickyNote
import dev.folio.core.model.StrokeInputs
import dev.folio.core.model.StrokeStyle
import dev.folio.core.model.Template
import dev.folio.core.model.TemplateKind
import dev.folio.core.model.edit.shapeBounds
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.testing.ModelAssertions.assertPageEquivalent
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.testing.TestData
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

/**
 * Golden `.pb` files of format v1 (testdata/format/v1). A missing golden is written from the expected
 * model and the test fails once so the new file gets reviewed and committed; existing goldens are never
 * rewritten by tests.
 */
class GoldenPageTest {
    @Test
    fun golden_allObjectTypes_decodesToExpectedModel() = checkGolden("page-all-objects.pb", allObjectsPage())

    @Test
    fun golden_infinitePdfCustomTemplate_decodesToExpectedModel() = checkGolden("page-infinite-pdf.pb", infinitePdfPage())

    private fun checkGolden(
        name: String,
        expected: Page,
    ) {
        val file = TestData.file("format/v1/$name")
        if (!file.exists()) {
            file.parentFile.mkdirs()
            file.writeBytes(PageCodec.encode(expected))
            throw AssertionError("golden $file was missing and has been written; review and commit it, then rerun")
        }
        val decoded = PageCodec.decode(file.readBytes(), "pages/$name")
        assertWithMessage(name).that(decoded).isInstanceOf(Outcome.Success::class.java)
        assertPageEquivalent(expected, (decoded as Outcome.Success).value, name)
    }

    private fun allObjectsPage(): Page {
        val inputs =
            StrokeInputs(
                x = floatArrayOf(10f, 10.5f, 11.25f, 12f),
                y = floatArrayOf(20f, 20.25f, 21f, 22.5f),
                tMs = floatArrayOf(0f, 2.2f, 4.4f, 6.6f),
                pressure = floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f),
                tiltDeg = floatArrayOf(10f, 12.5f, 15f, 20f),
                orientationDeg = floatArrayOf(-90f, -45.5f, 0f, 179.9f),
                tool = InputTool.STYLUS,
            )
        val points = listOf(PointPt(100f, 100f), PointPt(200f, 150f))
        val style = StrokeStyle(0xFF2255AA.toInt(), 1.5f, dashed = true)
        return ModelFixtures.page(
            "11111111-1111-4111-8111-111111111111",
            listOf(
                FlowFrame(ObjectId("o-frame"), FlowId("f-body"), RectPt(56.7f, 70.866f, 538.6f, 800f), 0, FrameRole.BODY, true),
                InkStroke.of(ObjectId("o-stroke"), BrushSpec(BrushKind.PENCIL, 0xFF333333.toInt(), 1.2f, 1, 1.4f), inputs),
                Shape(ObjectId("o-shape"), ShapeKind.ELLIPSE, points, 15f, style, 0x4000FF00, shapeBounds(points, 15f, 1.5f)),
                ImageObject(ObjectId("o-image"), AssetId(HASH_A), RectPt(300f, 400f, 450f, 500f), -10f, RectF01(0.1f, 0f, 1f, 0.9f)),
                StickyNote(ObjectId("o-sticky"), FlowId("f-sticky"), RectPt(400f, 50f, 520f, 170f), 3f, 0xFFFFE680.toInt()),
                Attachment(ObjectId("o-attach"), AssetId(HASH_B), "Syllabus.pdf", "application/pdf", PointPt(50f, 780f)),
            ),
        )
    }

    private fun infinitePdfPage(): Page =
        Page(
            id = PageId("22222222-2222-4222-8222-222222222222"),
            spec = PageSpec.Infinite(PageSpec.Custom(612f, 792f)),
            background =
                Background(
                    paperArgb = 0xFFFAF7F0.toInt(),
                    template = Template(TemplateKind.CUSTOM, 20.126f, 0xFFC9D3E0.toInt(), 0f, 0f, AssetId(HASH_B), 14.17f),
                    pdf = PdfBackground(AssetId(HASH_A), 3),
                ),
            objects = persistentListOf(),
        )

    private companion object {
        const val HASH_A = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        const val HASH_B = "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752"
    }
}
