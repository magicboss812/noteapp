package dev.folio.core.ink.brush

import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushTip
import androidx.ink.brush.StockBrushes
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.ink.StrokeBuilder
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.InkStroke
import dev.folio.core.model.InputTool
import dev.folio.core.model.ObjectId
import dev.folio.core.model.StrokeInputs
import dev.folio.core.model.geometry.RectPt
import org.junit.Test
import org.junit.runner.RunWith

// Robolectric only for android.* classes; the host libink.so comes from ink-nativeloader-jvm.
@RunWith(AndroidJUnit4::class)
class BrushCatalogTest {
    private val catalog = BrushCatalog.DEFAULT

    @Test
    fun specOf_everyKindPresetColorAndCurve_roundTripsTheSpec() {
        var checked = 0
        for (kind in BrushKind.entries) {
            for (argb in BrushPresets.palette(kind)) {
                for (width in BrushPresets.widthsPt(kind) + listOf(0.3f, 30f, 7.25f)) {
                    for (gamma in listOf(0.5f, 1f, 1.35f, 2f)) {
                        val spec = BrushSpec(kind, argb, width, 1, if (kind.hasPressure()) gamma else 1f)
                        assertThat(catalog.specOf(catalog.brush(spec))).isEqualTo(spec)
                        checked++
                    }
                }
            }
        }
        assertThat(checked).isEqualTo(5 * 5 * 6 * 4)
    }

    @Test
    fun specOf_translucentColorAndTilt_roundTrips() {
        val spec = BrushSpec(BrushKind.PENCIL, 0x80336699.toInt(), 1.2f, 1, 0.75f)
        assertThat(catalog.specOf(catalog.brush(spec, tilt = true))).isEqualTo(spec)
    }

    @Test
    fun specOf_brushFromAnotherFamily_isNull() {
        val foreign =
            androidx.ink.brush.Brush
                .createWithColorIntArgb(StockBrushes.marker(), -1, 2f, 0.05f)
        assertThat(catalog.specOf(foreign)).isNull()
    }

    @Test
    fun family_sameKindVersionAndCurve_builtOnceAndShared() {
        val a = catalog.family(BrushSpec(BrushKind.FOUNTAIN, BLACK, 0.8f, 1, 1.2f))
        val b = catalog.family(BrushSpec(BrushKind.FOUNTAIN, BLUE, 1.8f, 1, 1.21f)) // snaps to 1.2
        assertThat(b).isSameInstanceAs(a)
        assertThat(catalog.family(BrushSpec(BrushKind.FOUNTAIN, BLACK, 0.8f, 1, 1.5f))).isNotSameInstanceAs(a)
    }

    @Test
    fun family_kindsWithoutPressureOrTilt_ignoreCurveAndTilt() {
        val marker = catalog.family(BrushSpec(BrushKind.MARKER, BLACK, 2.5f, 1, 1f))
        assertThat(catalog.family(BrushSpec(BrushKind.MARKER, BLACK, 2.5f, 1, 2f), tilt = true)).isSameInstanceAs(marker)
        val ballpoint = catalog.family(BrushSpec(BrushKind.BALLPOINT, BLACK, 0.9f, 1))
        assertThat(catalog.family(BrushSpec(BrushKind.BALLPOINT, BLACK, 0.9f, 1), tilt = true)).isSameInstanceAs(ballpoint)
        val pencil = catalog.family(BrushSpec(BrushKind.PENCIL, BLACK, 1.2f, 1))
        assertThat(catalog.family(BrushSpec(BrushKind.PENCIL, BLACK, 1.2f, 1), tilt = true)).isNotEqualTo(pencil)
    }

    @Test
    fun family_v1Spec_rendersWithV1Definitions() {
        for (kind in BrushKind.entries) {
            val spec = BrushSpec(kind, BLACK, 1f, 1, 1.5f)
            val gamma = if (kind.hasPressure()) 1.5f else 1f
            assertThat(catalog.family(spec)).isEqualTo(BrushDefinitionsV1.family(kind, gamma, tilt = false))
        }
    }

    @Test
    fun family_twoVersions_eachSpecKeepsItsVersionAndUnknownVersionsClamp() {
        val two = BrushCatalog(listOf(BrushDefinitionsV1, FakeV2))
        val v1 = BrushSpec(BrushKind.BALLPOINT, BLACK, 0.9f, 1)

        assertThat(two.latestVersion).isEqualTo(2)
        assertThat(two.family(v1)).isEqualTo(BrushDefinitionsV1.family(BrushKind.BALLPOINT, 1f, tilt = false))
        assertThat(two.family(v1.copy(version = 2)).developerComment).isEqualTo(FakeV2.COMMENT)
        assertThat(two.family(v1.copy(version = 7)).developerComment).isEqualTo(FakeV2.COMMENT) // newer file: latest
        assertThat(two.family(v1.copy(version = 0))).isSameInstanceAs(two.family(v1)) // damaged: oldest
        assertThat(two.specOf(two.brush(v1.copy(version = 2)))).isEqualTo(v1.copy(version = 2))
        assertThat(two.specOf(two.brush(v1))).isEqualTo(v1)
    }

    @Test
    fun strokeBuilder_storedInputsWithTilt_useCatalogFamilyWithTilt() {
        val spec = BrushSpec(BrushKind.PENCIL, BLACK, 1.2f, 1)
        val n = 4
        val inputs =
            StrokeInputs(
                FloatArray(n) { it * 10f },
                FloatArray(n) { 5f },
                FloatArray(n) { it * 8f },
                FloatArray(n) { 0.5f },
                FloatArray(n) { 50f },
                FloatArray(n) { 370f },
                InputTool.STYLUS,
            )
        val stroke = StrokeBuilder.build(InkStroke(ObjectId("s"), spec, inputs, RectPt(0f, 0f, 30f, 10f)))

        assertThat(stroke.brush.family).isSameInstanceAs(catalog.family(spec, tilt = true))
        assertThat(stroke.inputs.size).isEqualTo(n)
        assertThat(stroke.inputs[1].tiltRadians.toDouble()).isWithin(1e-4).of(Math.toRadians(50.0))
        assertThat(stroke.inputs[1].orientationRadians.toDouble()).isWithin(1e-4).of(Math.toRadians(10.0))
    }

    private fun BrushKind.hasPressure(): Boolean = BrushDefinitionsV1.usesPressure(this)

    /** A later version that changes the ballpoint look: stands in for a real v2. */
    private object FakeV2 : BrushDefinitions by BrushDefinitionsV1 {
        const val COMMENT = "fake v2"
        override val version: Int = 2

        override fun family(
            kind: BrushKind,
            gamma: Float,
            tilt: Boolean,
        ): BrushFamily = BrushFamily(tip = BrushTip(scaleX = 0.5f), developerComment = COMMENT)
    }

    private companion object {
        const val BLACK = 0xFF1A1A1A.toInt()
        const val BLUE = 0xFF2563EB.toInt()
    }
}
