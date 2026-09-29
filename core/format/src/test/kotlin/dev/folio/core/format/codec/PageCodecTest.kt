package dev.folio.core.format.codec

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.folio.core.common.Outcome
import dev.folio.core.format.FormatError
import dev.folio.core.model.BrushKind
import dev.folio.core.model.FrameRole
import dev.folio.core.model.Orientation
import dev.folio.core.model.Page
import dev.folio.core.model.PageSpec
import dev.folio.core.model.PaperSize
import dev.folio.core.model.ShapeKind
import dev.folio.core.model.TemplateKind
import dev.folio.core.testing.ModelAssertions.assertPageEquivalent
import dev.folio.core.testing.ModelFixtures
import org.junit.Test
import kotlin.random.Random
import dev.folio.core.format.proto.v1.Page as PbPage
import dev.folio.core.format.proto.v1.PageObject as PbPageObject
import dev.folio.core.format.proto.v1.StrokeInputs as PbStrokeInputs

class PageCodecTest {
    @Test
    fun roundTrip_randomPagesAllObjectTypes_within1over128Pt() {
        val random = Random(SEED)
        repeat(50) { i ->
            val spec =
                when (i % 4) {
                    0 -> ModelFixtures.A4
                    1 -> PageSpec.Fixed(PaperSize.A3, Orientation.LANDSCAPE)
                    2 -> PageSpec.Custom(612f, 792f)
                    else -> PageSpec.Infinite(ModelFixtures.A4)
                }
            val page = ModelFixtures.randomPage(random, objectCount = 30, spec = spec)
            val decoded = decode(PageCodec.encode(page))
            assertPageEquivalent(page, decoded, "seed=$SEED page=$i")
        }
    }

    @Test
    fun roundTrip_minimalStrokeWithoutOptionalChannels() {
        val stroke = ModelFixtures.randomStroke(Random(1), minimal = true)
        val page = ModelFixtures.page("p", listOf(stroke))
        val decoded = decode(PageCodec.encode(page))
        assertPageEquivalent(page, decoded)
        val inputs = (decoded.objects[0] as dev.folio.core.model.InkStroke).inputs
        assertThat(inputs.pressure).isNull()
        assertThat(inputs.tiltDeg).isNull()
    }

    @Test
    fun enums_everyModelValue_roundTrips() {
        for (v in TemplateKind.entries) assertThat(EnumCodec.templateKind(EnumCodec.templateKind(v))).isEqualTo(v)
        for (v in BrushKind.entries) assertThat(EnumCodec.brushKind(EnumCodec.brushKind(v))).isEqualTo(v)
        for (v in ShapeKind.entries) assertThat(EnumCodec.shapeKind(EnumCodec.shapeKind(v))).isEqualTo(v)
        for (v in FrameRole.entries) assertThat(EnumCodec.frameRole(EnumCodec.frameRole(v))).isEqualTo(v)
        for (v in PaperSize.entries) assertThat(EnumCodec.paperSize(EnumCodec.paperSize(v))).isEqualTo(v)
        for (v in Orientation.entries) assertThat(EnumCodec.orientation(EnumCodec.orientation(v))).isEqualTo(v)
    }

    @Test
    fun size_1000StrokesOf120Inputs_under1200KbBeforeDeflate() {
        val random = Random(SEED)
        val strokes = List(1000) { handwrittenStroke(random, it) }
        val bytes = PageCodec.encode(ModelFixtures.page("p", strokes))
        println("1000 x 120 inputs (pressure): ${bytes.size} bytes")
        assertThat(bytes.size).isLessThan(1_200_000)
    }

    @Test
    fun decode_garbage_isCorrupt() {
        val result = PageCodec.decode(byteArrayOf(0x0a, 0x7f, 0x01), "pages/x.pb")
        assertCorrupt(result)
    }

    @Test
    fun decode_strokeChannelSizeMismatch_isCorrupt() {
        val page = ModelFixtures.page("p", listOf(ModelFixtures.randomStroke(Random(3), points = 5)))
        val pb = PageCodec.toProto(page)
        val stroke = pb.objects[0].stroke!!
        val broken = stroke.copy(inputs = stroke.inputs!!.copy(dy = listOf(0, 1)))
        val bytes = PbPage.ADAPTER.encode(pb.copy(objects = listOf(pb.objects[0].copy(stroke = broken))))
        assertCorrupt(PageCodec.decode(bytes, "pages/p.pb"))
    }

    @Test
    fun decode_missingSpec_isCorrupt() {
        val pb = PageCodec.toProto(ModelFixtures.page("p")).copy(spec = null)
        assertCorrupt(PageCodec.decode(PbPage.ADAPTER.encode(pb), "pages/p.pb"))
    }

    @Test
    fun decode_nonFiniteOrHugeGeometry_isCorrupt() {
        val page = ModelFixtures.page("p", listOf(ModelFixtures.randomStroke(Random(4), points = 5)))
        val pb = PageCodec.toProto(page)
        val obj = pb.objects[0]
        val stroke = obj.stroke!!
        val bounds = stroke.bounds!!
        val broken =
            listOf(
                stroke.copy(bounds = bounds.copy(left = Float.NaN)),
                stroke.copy(bounds = bounds.copy(right = 2e6f)),
                stroke.copy(brush = stroke.brush!!.copy(size_pt = Float.NaN)),
                stroke.copy(brush = stroke.brush!!.copy(size_pt = 0f)),
                stroke.copy(brush = stroke.brush!!.copy(size_pt = 501f)),
                stroke.copy(brush = stroke.brush!!.copy(pressure_gamma = Float.POSITIVE_INFINITY)),
                stroke.copy(inputs = stroke.inputs!!.copy(origin_x = Long.MAX_VALUE / 2)),
            )
        broken.forEachIndexed { i, s ->
            val bytes = PbPage.ADAPTER.encode(pb.copy(objects = listOf(obj.copy(stroke = s))))
            assertWithMessage("case $i").that(PageCodec.decode(bytes, "pages/p.pb")).isInstanceOf(Outcome.Failure::class.java)
        }
    }

    @Test
    fun decode_customPageSizeOutOfRange_isCorrupt() {
        listOf(PageSpec.Custom(0f, 100f), PageSpec.Custom(100f, 14_401f), PageSpec.Custom(Float.NaN, 100f)).forEach { spec ->
            val pb = PageCodec.toProto(ModelFixtures.page("p").copy(spec = spec))
            assertCorrupt(PageCodec.decode(PbPage.ADAPTER.encode(pb), "pages/p.pb"))
        }
        val ok = PageCodec.toProto(ModelFixtures.page("p").copy(spec = PageSpec.Custom(14_400f, 1f)))
        assertThat(decode(PbPage.ADAPTER.encode(ok)).spec).isEqualTo(PageSpec.Custom(14_400f, 1f))
    }

    @Test
    fun decode_invertedRect_isEmpty() {
        val rect =
            DecodeLimits.rect(
                Float.POSITIVE_INFINITY,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
                "r",
            )
        assertThat(rect.isEmpty).isTrue()
    }

    @Test
    fun decode_unknownObjectKind_isSkipped() {
        val pb = PageCodec.toProto(ModelFixtures.page("p"))
        val withUnknown = pb.copy(objects = listOf(PbPageObject(id = "future")))
        assertThat(decode(PbPage.ADAPTER.encode(withUnknown)).objects).isEmpty()
    }

    @Test
    fun decode_unsafePageId_isCorrupt() {
        listOf("", ".", "..", "a/b").forEach { id ->
            val pb = PageCodec.toProto(ModelFixtures.page("p")).copy(id = id)
            assertCorrupt(PageCodec.decode(PbPage.ADAPTER.encode(pb), "pages/p.pb"))
        }
    }

    @Test
    fun strokeCodec_largeCoordinates_noDrift() {
        val inputs =
            dev.folio.core.model.StrokeInputs(
                x = FloatArray(1000) { -50_000f + it * 0.37f },
                y = FloatArray(1000) { 80_000f - it * 0.21f },
                tMs = FloatArray(1000) { it * 2.19f },
                pressure = null,
                tiltDeg = null,
                orientationDeg = null,
                tool = dev.folio.core.model.InputTool.STYLUS,
            )
        val back = StrokeCodec.decode(PbStrokeInputs.ADAPTER.decode(PbStrokeInputs.ADAPTER.encode(StrokeCodec.encode(inputs))))
        for (i in 0 until 1000) {
            // Float resolution at 80k pt is 1/128 pt, so allow one extra ulp there.
            assertWithMessage("x[$i]").that(back.x[i]).isWithin(1f / 128f + Math.ulp(50_000f)).of(inputs.x[i])
            assertWithMessage("y[$i]").that(back.y[i]).isWithin(1f / 128f + Math.ulp(80_000f)).of(inputs.y[i])
        }
    }

    private fun decode(bytes: ByteArray): Page =
        when (val r = PageCodec.decode(bytes, "pages/test.pb")) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> throw AssertionError(r.message, r.cause)
        }

    private fun assertCorrupt(result: Outcome<Page>) {
        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat((result as Outcome.Failure).cause).isInstanceOf(FormatError.Corrupt::class.java)
    }

    /** Smooth pen motion sampled at ~457 Hz with pressure (device.md#stylus), 120 samples. */
    private fun handwrittenStroke(
        random: Random,
        index: Int,
    ): dev.folio.core.model.InkStroke {
        val n = 120
        var x = 40f + (index % 20) * 25f
        var y = 60f + (index / 20) * 15f
        var angle = random.nextFloat() * 6.28f
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val ts = FloatArray(n)
        val ps = FloatArray(n)
        for (i in 0 until n) {
            angle += random.nextFloat() * 0.3f - 0.15f
            x += kotlin.math.cos(angle) * 0.6f
            y += kotlin.math.sin(angle) * 0.6f
            xs[i] = x
            ys[i] = y
            ts[i] = i * 2.19f
            ps[i] = 0.3f + 0.4f * kotlin.math.sin(i / 20f).let { it * it }
        }
        val inputs =
            dev.folio.core.model
                .StrokeInputs(xs, ys, ts, ps, null, null, dev.folio.core.model.InputTool.STYLUS)
        val brush =
            dev.folio.core.model
                .BrushSpec(BrushKind.FOUNTAIN, 0xFF1A1A1A.toInt(), 1.2f, 1)
        return dev.folio.core.model.InkStroke
            .of(
                dev.folio.core.model
                    .ObjectId(ModelFixtures.randomId(random)),
                brush,
                inputs,
            )
    }

    private companion object {
        const val SEED = 404
    }
}
