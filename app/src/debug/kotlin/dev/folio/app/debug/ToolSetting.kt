package dev.folio.app.debug

import dev.folio.core.ink.erase.EraserMode
import dev.folio.core.ink.erase.EraserOptions
import dev.folio.feature.editor.canvas.CanvasTool

/**
 * Parsed `tool pen` or `tool eraser[,stroke|partial][,radiusPt][,hl]` (radius in 0.5..100 pt, `hl` =
 * highlighter only); unset eraser parts keep [EraserOptions.DEFAULT].
 */
internal data class ToolSetting(
    val tool: CanvasTool,
    val eraser: EraserOptions = EraserOptions.DEFAULT,
) {
    companion object {
        private const val MIN_RADIUS_PT = 0.5f
        private const val MAX_RADIUS_PT = 100f

        fun parse(arg: String?): ToolSetting? {
            val parts = arg?.split(',')?.map { it.trim().lowercase() } ?: return null
            return when (parts[0]) {
                "pen" -> if (parts.size == 1) ToolSetting(CanvasTool.PEN) else null
                "eraser" -> eraser(parts.drop(1))?.let { ToolSetting(CanvasTool.ERASER, it) }
                else -> null
            }
        }

        private fun eraser(parts: List<String>): EraserOptions? {
            var options = EraserOptions.DEFAULT
            for (part in parts) {
                val radius = part.toFloatOrNull()
                options =
                    when {
                        part == "stroke" -> options.copy(mode = EraserMode.STROKE)
                        part == "partial" -> options.copy(mode = EraserMode.PARTIAL)
                        part == "hl" -> options.copy(highlighterOnly = true)
                        radius != null && radius in MIN_RADIUS_PT..MAX_RADIUS_PT -> options.copy(radiusPt = radius)
                        else -> return null
                    }
            }
            return options
        }
    }
}
