package dev.folio.core.storage.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.folio.core.common.Clock
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.storage.LibraryConfig
import dev.folio.core.storage.index.IndexDb
import dev.folio.core.storage.index.LibraryScanner
import dev.folio.core.storage.library.AndroidStoragePermission
import dev.folio.core.storage.library.LibraryLayout
import dev.folio.core.storage.library.LibraryRoot
import dev.folio.core.storage.library.StoragePermission
import dev.folio.core.storage.repo.DocumentRepository
import dev.folio.core.storage.repo.LibraryRepository
import javax.inject.Singleton

/** Hilt bindings of core:storage. [LibraryConfig], [Clock], [ManifestApp] and dispatchers come from :app. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class StorageModule {
    @Binds
    abstract fun storagePermission(impl: AndroidStoragePermission): StoragePermission

    internal companion object {
        @Provides
        @Singleton
        fun libraryRoot(config: LibraryConfig): LibraryRoot = LibraryRoot(LibraryLayout.rootFor(config))

        // Disposable cache: schema changes drop and rebuild it by a full scan.
        @Provides
        @Singleton
        fun indexDb(
            @ApplicationContext context: Context,
        ): IndexDb =
            Room
                .databaseBuilder(context, IndexDb::class.java, IndexDb.NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        @Provides
        @Singleton
        fun scanner(
            root: LibraryRoot,
            db: IndexDb,
            clock: Clock,
        ): LibraryScanner = LibraryScanner(root.fs, db, clock)

        @Provides
        @Singleton
        fun documentRepository(
            root: LibraryRoot,
            scanner: LibraryScanner,
            db: IndexDb,
            clock: Clock,
            dispatchers: FolioDispatchers,
            app: ManifestApp,
        ): DocumentRepository = DocumentRepository(root.fs, scanner, db.dao(), clock, dispatchers, app)

        @Provides
        @Singleton
        fun libraryRepository(
            root: LibraryRoot,
            scanner: LibraryScanner,
            db: IndexDb,
            dispatchers: FolioDispatchers,
            documents: DocumentRepository,
        ): LibraryRepository = LibraryRepository(root.fs, scanner, db.dao(), dispatchers, documents)
    }
}
