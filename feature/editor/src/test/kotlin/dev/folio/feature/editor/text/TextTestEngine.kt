package dev.folio.feature.editor.text

import android.content.Context
import androidx.compose.ui.text.font.createFontFamilyResolver
import dev.folio.core.text.flow.FlowLayouter
import dev.folio.core.text.flow.FlowReflow
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.layout.BlockLayouter

/** A real text engine for Robolectric tests (the Hilt module of core:text is internal). */
internal object TextTestEngine {
    fun create(context: Context): TextEngine {
        val resolver = createFontFamilyResolver(context)
        val layouter = FlowLayouter(BlockLayouter(resolver, FontMetricsCache(resolver)))
        return TextEngine(layouter, FlowReflow(layouter))
    }
}
