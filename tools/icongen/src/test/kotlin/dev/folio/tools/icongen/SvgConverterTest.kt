package dev.folio.tools.icongen

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SvgConverterTest {
    private fun svg(body: String) =
        """
        <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none"
          stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          $body
        </svg>
        """.trimIndent()

    @Test
    fun convert_rectWithoutRadius_isClosedBox() {
        val paths = SvgConverter.convert(svg("""<rect width="18" height="12" x="3" y="6" />"""))

        assertThat(paths).containsExactly(IconPath("M3 6H21V18H3Z", filled = false))
    }

    @Test
    fun convert_rectWithRx_hasFourCornerArcs() {
        // layout-grid's cell.
        val paths = SvgConverter.convert(svg("""<rect width="7" height="7" x="3" y="3" rx="1" />"""))

        assertThat(paths.single().pathData)
            .isEqualTo("M4 3H9A1 1 0 0 1 10 4V9A1 1 0 0 1 9 10H4A1 1 0 0 1 3 9V4A1 1 0 0 1 4 3Z")
    }

    @Test
    fun convert_rectRadiusLargerThanHalfSide_isClamped() {
        val paths = SvgConverter.convert(svg("""<rect width="4" height="10" x="0" y="0" rx="3" ry="1.5" />"""))

        assertThat(paths.single().pathData)
            .isEqualTo("M2 0H2A2 1.5 0 0 1 4 1.5V8.5A2 1.5 0 0 1 2 10H2A2 1.5 0 0 1 0 8.5V1.5A2 1.5 0 0 1 2 0Z")
    }

    @Test
    fun convert_circle_isTwoHalfArcs() {
        // settings' hub.
        val paths = SvgConverter.convert(svg("""<circle cx="12" cy="12" r="3" />"""))

        assertThat(paths.single().pathData).isEqualTo("M9 12A3 3 0 1 0 15 12A3 3 0 1 0 9 12Z")
    }

    @Test
    fun convert_circleWithFractionalRadiusAndFill_isFilled() {
        // tag's hole.
        val paths = SvgConverter.convert(svg("""<circle cx="7.5" cy="7.5" r=".5" fill="currentColor" />"""))

        assertThat(paths).containsExactly(IconPath("M7 7.5A0.5 0.5 0 1 0 8 7.5A0.5 0.5 0 1 0 7 7.5Z", filled = true))
    }

    @Test
    fun convert_ellipse_usesBothRadii() {
        val paths = SvgConverter.convert(svg("""<ellipse cx="12" cy="5" rx="9" ry="3" />"""))

        assertThat(paths.single().pathData).isEqualTo("M3 5A9 3 0 1 0 21 5A9 3 0 1 0 3 5Z")
    }

    @Test
    fun convert_polyline_isOpenPath() {
        val paths = SvgConverter.convert(svg("""<polyline points="22 12 18 12 15 21 9 3 6 12 2 12" />"""))

        assertThat(paths.single().pathData).isEqualTo("M22 12L18 12L15 21L9 3L6 12L2 12")
    }

    @Test
    fun convert_polylineWithCommaPairs_parsesSamePoints() {
        val paths = SvgConverter.convert(svg("""<polyline points="4,17 10,11 4,5" />"""))

        assertThat(paths.single().pathData).isEqualTo("M4 17L10 11L4 5")
    }

    @Test
    fun convert_polygon_isClosed() {
        val paths = SvgConverter.convert(svg("""<polygon points="12 2 22 20 2 20" />"""))

        assertThat(paths.single().pathData).isEqualTo("M12 2L22 20L2 20Z")
    }

    @Test
    fun convert_line_isMoveLine() {
        val paths = SvgConverter.convert(svg("""<line x1="8.59" x2="15.42" y1="13.51" y2="17.49" />"""))

        assertThat(paths.single().pathData).isEqualTo("M8.59 13.51L15.42 17.49")
    }

    @Test
    fun convert_pathWithNewlines_collapsesWhitespaceAndKeepsOrder() {
        val paths = SvgConverter.convert(svg("<path d=\"M3 6h18\" />\n<path d=\"M19 6v14a2 2 0 0 1-2 2H7\n  a2 2 0 0 1-2-2V6\" />"))

        assertThat(paths.map { it.pathData }).containsExactly("M3 6h18", "M19 6v14a2 2 0 0 1-2 2H7 a2 2 0 0 1-2-2V6").inOrder()
    }

    @Test
    fun convert_groupElement_fails() {
        assertThrows(IllegalArgumentException::class.java) {
            SvgConverter.convert(svg("""<g><path d="M0 0h1" /></g>"""))
        }
    }

    @Test
    fun f_numbers_areShortestDecimals() {
        assertThat(listOf(3.0, 0.5, -0.0, 1.23456, 2.0 / 3).map(SvgConverter::f))
            .containsExactly("3", "0.5", "0", "1.235", "0.667")
            .inOrder()
    }
}
