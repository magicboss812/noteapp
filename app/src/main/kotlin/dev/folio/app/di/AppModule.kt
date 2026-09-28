package dev.folio.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.folio.app.BuildConfig
import dev.folio.core.common.Clock
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.SystemClock
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.storage.LibraryConfig
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object AppModule {
    @Provides
    fun libraryConfig(): LibraryConfig = LibraryConfig(rootRelativePath = BuildConfig.LIBRARY_ROOT)

    @Provides
    fun clock(): Clock = SystemClock

    /** Writer info stored in every manifest. */
    @Provides
    fun manifestApp(): ManifestApp = ManifestApp(name = "Folio", version = BuildConfig.VERSION_NAME)

    // Parallelism is a starting point; P01/P03 spikes tune render and pdf.
    @Provides
    @Singleton
    fun dispatchers(): FolioDispatchers =
        FolioDispatchers(
            main = Dispatchers.Main,
            io = Dispatchers.IO,
            render = Dispatchers.Default.limitedParallelism(RENDER_THREADS, "folio-render"),
            pdf = Dispatchers.IO.limitedParallelism(1, "folio-pdf"),
            text = Dispatchers.Default.limitedParallelism(1, "folio-text"),
        )

    private const val RENDER_THREADS = 2
}
