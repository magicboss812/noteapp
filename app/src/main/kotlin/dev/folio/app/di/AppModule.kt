package dev.folio.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.folio.app.BuildConfig
import dev.folio.core.storage.LibraryConfig

@Module
@InstallIn(SingletonComponent::class)
internal object AppModule {
    @Provides
    fun libraryConfig(): LibraryConfig = LibraryConfig(rootRelativePath = BuildConfig.LIBRARY_ROOT)
}
