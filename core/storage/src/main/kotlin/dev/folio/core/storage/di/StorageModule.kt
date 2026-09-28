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
import dev.folio.core.common.FolioFs
import dev.folio.core.common.JavaFileFolioFs
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
import dev.folio.core.storage.session.DocumentSessions
import dev.folio.core.storage.work.Packer
import dev.folio.core.storage.work.Recovery
import dev.folio.core.storage.work.RecoveryEvents
import dev.folio.core.storage.work.WorkingCopyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** App-private files (`files/`: working copies, backups), as opposed to the library root. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppFiles

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
        @AppFiles
        fun appFiles(
            @ApplicationContext context: Context,
        ): FolioFs = JavaFileFolioFs(context.filesDir)

        @Provides
        @Singleton
        fun workingCopies(
            @AppFiles appFs: FolioFs,
            root: LibraryRoot,
        ): WorkingCopyStore = WorkingCopyStore(appFs, root.fs)

        @Provides
        @Singleton
        fun packer(
            @AppFiles appFs: FolioFs,
            root: LibraryRoot,
            clock: Clock,
        ): Packer = Packer(root.fs, appFs, clock)

        @Provides
        @Singleton
        fun recoveryEvents(): RecoveryEvents = RecoveryEvents()

        @Provides
        @Singleton
        fun recovery(
            store: WorkingCopyStore,
            packer: Packer,
            clock: Clock,
            events: RecoveryEvents,
        ): Recovery = Recovery(store, packer, clock, events)

        // Sessions outlive screens (timers, onStop packs): they run in a process-wide supervisor scope.
        @Provides
        @Singleton
        fun sessions(
            store: WorkingCopyStore,
            packer: Packer,
            scanner: LibraryScanner,
            clock: Clock,
            dispatchers: FolioDispatchers,
            app: ManifestApp,
        ): DocumentSessions =
            DocumentSessions(store, packer, scanner, clock, dispatchers, CoroutineScope(SupervisorJob() + dispatchers.io), app)

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
