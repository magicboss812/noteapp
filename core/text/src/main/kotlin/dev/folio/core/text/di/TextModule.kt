package dev.folio.core.text.di

import android.content.Context
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.folio.core.text.flow.FlowLayouter
import dev.folio.core.text.flow.FlowReflow
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.fonts.FontRegistry
import dev.folio.core.text.layout.BlockLayouter
import javax.inject.Singleton

/** Hilt bindings of core:text: one layouter stack (and so one layout cache) for the whole app. */
@Module
@InstallIn(SingletonComponent::class)
internal object TextModule {
    @Provides
    @Singleton
    fun fontResolver(
        @ApplicationContext context: Context,
    ): FontFamily.Resolver = createFontFamilyResolver(context)

    @Provides
    @Singleton
    fun fontMetrics(resolver: FontFamily.Resolver): FontMetricsCache = FontMetricsCache(resolver)

    // Imported fonts join the registry with the font picker (P06-T10).
    @Provides
    @Singleton
    fun blockLayouter(
        resolver: FontFamily.Resolver,
        metrics: FontMetricsCache,
    ): BlockLayouter = BlockLayouter(resolver, metrics, FontRegistry.Bundled)

    @Provides
    @Singleton
    fun flowLayouter(blocks: BlockLayouter): FlowLayouter = FlowLayouter(blocks)

    @Provides
    @Singleton
    fun flowReflow(layouter: FlowLayouter): FlowReflow = FlowReflow(layouter)
}
