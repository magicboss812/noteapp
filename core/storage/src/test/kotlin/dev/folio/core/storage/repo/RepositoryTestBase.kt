package dev.folio.core.storage.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.Outcome
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.storage.index.IndexDb
import dev.folio.core.storage.index.LibraryScanner
import dev.folio.core.testing.FakeClock
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.testing.TempDirFolioFs
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.io.File

/** Library in a temp dir, in-memory index, fake clock; everything runs on the test thread. */
abstract class RepositoryTestBase {
    @get:Rule val fs = TempDirFolioFs()

    protected val clock = FakeClock()
    protected lateinit var db: IndexDb
    protected lateinit var scanner: LibraryScanner
    protected lateinit var documents: DocumentRepository
    protected lateinit var library: LibraryRepository

    private val direct = Dispatchers.Unconfined
    protected val dispatchers = FolioDispatchers(direct, direct, direct, direct, direct)

    @Before
    fun setUpRepositories() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), IndexDb::class.java).build()
        scanner = LibraryScanner(fs, db, clock)
        documents = DocumentRepository(fs, scanner, db.dao(), clock, dispatchers, ManifestApp("Folio", "test"))
        library = LibraryRepository(fs, scanner, db.dao(), dispatchers, documents)
    }

    @After
    fun closeDb() = db.close()

    protected fun spec(
        title: String,
        folder: String = "",
        pages: Int = 1,
    ) = NewDocumentSpec(folder, title, ModelFixtures.A4, ModelFixtures.LINED_BACKGROUND, pages)

    protected fun file(path: String) = File(fs.root, path)

    protected fun <T> Outcome<T>.orThrow(): T =
        when (this) {
            is Outcome.Success -> value
            is Outcome.Failure -> throw AssertionError(message, cause)
        }
}
