package dev.folio.core.testing

import com.google.common.truth.Truth.assertWithMessage
import dev.folio.core.model.InkStroke
import dev.folio.core.model.Page
import dev.folio.core.model.StrokeInputs
import java.io.File

/** Assertions for data that passed through the quantized stroke encoding. */
object ModelAssertions {
    /** Position tolerance of the stroke encoding (04-file-format.md#stroke-encoding). */
    const val POSITION_TOL_PT = 1f / 128f

    /** Pages equal except stroke samples, which may differ by the quantization steps. */
    fun assertPageEquivalent(
        expected: Page,
        actual: Page,
        msg: String = "",
    ) {
        assertWithMessage("$msg id").that(actual.id).isEqualTo(expected.id)
        assertWithMessage("$msg spec").that(actual.spec).isEqualTo(expected.spec)
        assertWithMessage("$msg background").that(actual.background).isEqualTo(expected.background)
        assertWithMessage("$msg object count").that(actual.objects.size).isEqualTo(expected.objects.size)
        expected.objects.zip(actual.objects).forEachIndexed { i, (e, a) ->
            if (e is InkStroke && a is InkStroke) {
                assertWithMessage("$msg [$i] brush").that(a.brush).isEqualTo(e.brush)
                assertWithMessage("$msg [$i] bounds").that(a.bounds).isEqualTo(e.bounds)
                assertInputsEquivalent(e.inputs, a.inputs, "$msg [$i]")
            } else {
                assertWithMessage("$msg [$i]").that(a).isEqualTo(e)
            }
        }
    }

    /** Stroke samples within the quantization tolerances. */
    fun assertInputsEquivalent(
        expected: StrokeInputs,
        actual: StrokeInputs,
        msg: String = "",
    ) {
        assertWithMessage("$msg tool").that(actual.tool).isEqualTo(expected.tool)
        assertWithMessage("$msg size").that(actual.size).isEqualTo(expected.size)
        assertClose(expected.x, actual.x, POSITION_TOL_PT, "$msg x")
        assertClose(expected.y, actual.y, POSITION_TOL_PT, "$msg y")
        assertClose(expected.tMs, actual.tMs, TIME_TOL_MS, "$msg t")
        assertClose(expected.pressure, actual.pressure, PRESSURE_TOL, "$msg pressure")
        assertClose(expected.tiltDeg, actual.tiltDeg, ANGLE_TOL_DEG, "$msg tilt")
        assertClose(expected.orientationDeg, actual.orientationDeg, ANGLE_TOL_DEG, "$msg orientation")
    }

    private fun assertClose(
        expected: FloatArray?,
        actual: FloatArray?,
        tol: Float,
        msg: String,
    ) {
        if (expected == null || actual == null) {
            assertWithMessage("$msg presence").that(actual == null).isEqualTo(expected == null)
            return
        }
        assertWithMessage(msg).that(actual.size).isEqualTo(expected.size)
        for (i in expected.indices) assertWithMessage("$msg[$i]").that(actual[i]).isWithin(tol).of(expected[i])
    }

    private const val TIME_TOL_MS = 0.051f
    private const val PRESSURE_TOL = 0.5f / 1023f + 1e-6f
    private const val ANGLE_TOL_DEG = 0.051f
}

/** Files under the repository `testdata/` folder (found by walking up from the working directory). */
object TestData {
    /** The `testdata` directory. */
    val root: File by lazy {
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "testdata") }
            .firstOrNull { it.isDirectory && File(it, "README.md").isFile }
            ?: error("testdata/ not found above ${System.getProperty("user.dir")}")
    }

    /** File at [relativePath] below `testdata/`. */
    fun file(relativePath: String): File = File(root, relativePath)
}
