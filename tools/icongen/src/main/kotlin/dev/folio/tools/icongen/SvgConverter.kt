package dev.folio.tools.icongen

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.RoundingMode
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.min

/** One drawable element of an icon as SVG path data; [filled] elements are filled as well as stroked. */
internal data class IconPath(
    val pathData: String,
    val filled: Boolean,
)

/**
 * Converts a Lucide SVG (24 px viewBox, stroke only) into path data strings.
 * Supported elements: path, line, polyline, polygon, rect, circle, ellipse. Anything else (groups, transforms)
 * fails loudly so a new Lucide construct is noticed instead of silently dropped.
 */
internal object SvgConverter {
    fun convert(svgXml: String): List<IconPath> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(svgXml.toByteArray())).documentElement
        require(root.tagName == "svg") { "root element is <${root.tagName}>, expected <svg>" }
        val children = root.childNodes
        return (0 until children.length)
            .map { children.item(it) }
            .filterIsInstance<Element>()
            .map { element ->
                require(!element.hasAttribute("transform")) { "<${element.tagName}> uses a transform (unsupported)" }
                val fill = element.getAttribute("fill")
                IconPath(pathData = pathDataOf(element), filled = fill.isNotEmpty() && fill != "none")
            }
    }

    private fun pathDataOf(e: Element): String =
        when (e.tagName) {
            "path" -> e.getAttribute("d").trim().replace(WHITESPACE, " ")
            "line" -> line(e.num("x1"), e.num("y1"), e.num("x2"), e.num("y2"))
            "polyline" -> poly(e.getAttribute("points"), close = false)
            "polygon" -> poly(e.getAttribute("points"), close = true)
            "rect" -> rect(e.num("x"), e.num("y"), e.num("width"), e.num("height"), e.numOrNull("rx"), e.numOrNull("ry"))
            "circle" -> ellipse(e.num("cx"), e.num("cy"), e.num("r"), e.num("r"))
            "ellipse" -> ellipse(e.num("cx"), e.num("cy"), e.num("rx"), e.num("ry"))
            else -> throw IllegalArgumentException("unsupported SVG element <${e.tagName}>")
        }

    fun line(
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
    ): String = "M${f(x1)} ${f(y1)}L${f(x2)} ${f(y2)}"

    fun poly(
        points: String,
        close: Boolean,
    ): String {
        val values = points.trim().split(SEPARATORS).map { it.toDouble() }
        require(values.size >= MIN_POLY_VALUES && values.size % 2 == 0) { "points needs at least two x,y pairs: '$points'" }
        val pairs = values.chunked(2)
        val body = pairs.drop(1).joinToString("") { (x, y) -> "L${f(x)} ${f(y)}" }
        return "M${f(pairs[0][0])} ${f(pairs[0][1])}$body${if (close) "Z" else ""}"
    }

    /** SVG rect semantics: a missing rx/ry takes the other value; both are clamped to half the side. */
    fun rect(
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        rxAttr: Double?,
        ryAttr: Double?,
    ): String {
        val rx = min(rxAttr ?: ryAttr ?: 0.0, w / 2)
        val ry = min(ryAttr ?: rxAttr ?: 0.0, h / 2)
        if (rx <= 0.0 || ry <= 0.0) return "M${f(x)} ${f(y)}H${f(x + w)}V${f(y + h)}H${f(x)}Z"
        val arc = "A${f(rx)} ${f(ry)} 0 0 1"
        return "M${f(x + rx)} ${f(y)}H${f(x + w - rx)}$arc ${f(x + w)} ${f(y + ry)}" +
            "V${f(y + h - ry)}$arc ${f(x + w - rx)} ${f(y + h)}" +
            "H${f(x + rx)}$arc ${f(x)} ${f(y + h - ry)}" +
            "V${f(y + ry)}$arc ${f(x + rx)} ${f(y)}Z"
    }

    /** Two half arcs from the leftmost point; also used for circles (rx == ry). */
    fun ellipse(
        cx: Double,
        cy: Double,
        rx: Double,
        ry: Double,
    ): String {
        val arc = "A${f(rx)} ${f(ry)} 0 1 0"
        return "M${f(cx - rx)} ${f(cy)}$arc ${f(cx + rx)} ${f(cy)}$arc ${f(cx - rx)} ${f(cy)}Z"
    }

    /** Shortest decimal with at most 3 fraction digits: 3.0 -> "3", 0.5 -> "0.5", -0.0 -> "0". */
    fun f(value: Double): String {
        val text = BigDecimal(value).setScale(FRACTION_DIGITS, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return if (text == "-0") "0" else text
    }

    private fun Element.num(name: String): Double = numOrNull(name) ?: 0.0

    private fun Element.numOrNull(name: String): Double? = getAttribute(name).takeIf { it.isNotEmpty() }?.toDouble()

    private const val FRACTION_DIGITS = 3
    private const val MIN_POLY_VALUES = 4
    private val WHITESPACE = Regex("\\s+")
    private val SEPARATORS = Regex("[\\s,]+")
}
