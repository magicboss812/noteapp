package dev.folio.core.storage.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.format.container.FolioContainerReader
import dev.folio.core.format.container.FolioContainerWriter
import dev.folio.core.format.container.FolioEntries
import dev.folio.core.format.container.WriterEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryRepositoryTest : RepositoryTestBase() {
    @Test
    fun favoritesAndTags_updateFileAndLists() =
        runTest {
            val doc = documents.create(spec("Kinematics", "Physics")).orThrow()

            library.setFavorite(doc.path, true).orThrow()
            library.setTags(doc.path, setOf(" school ", "physics", "")).orThrow()

            assertThat(library.documents(DocumentFilter.Favorites).first().map { it.path }).containsExactly(doc.path)
            assertThat(library.tags().first()).containsExactly("physics", "school")
            assertThat(library.documents(DocumentFilter.Tagged("school")).first().map { it.path }).containsExactly(doc.path)
            library.setFavorite(doc.path, false).orThrow()
            assertThat(library.documents(DocumentFilter.Favorites).first()).isEmpty()
        }

    @Test
    fun setFavorite_keepsUnknownManifestKeysAndEntries() =
        runTest {
            val doc = documents.create(spec("Future")).orThrow()
            addUnknownKeyAndEntry(doc.path)

            library.setFavorite(doc.path, true).orThrow()

            FolioContainerReader.open(file(doc.path)).orThrow().use { reader ->
                val json = Json.parseToJsonElement(reader.read(FolioEntries.MANIFEST, 1_000_000).orThrow().decodeToString()).jsonObject
                assertThat(json["futureKey"]).isEqualTo(JsonPrimitive("kept"))
                assertThat(json["favorite"]).isEqualTo(JsonPrimitive(true))
                assertThat(reader.names()).contains("future/data.bin")
            }
        }

    @Test
    fun documents_sortOrders() =
        runTest {
            documents.create(spec("banana")).orThrow()
            clock.advanceMs(1000)
            documents.create(spec("Apple")).orThrow()
            clock.advanceMs(1000)
            documents.create(spec("cherry")).orThrow()

            fun titles(sort: DocumentSort) = library.documents(DocumentFilter.InFolder(""), sort)
            assertThat(titles(DocumentSort.NAME).first().map { it.title }).containsExactly("Apple", "banana", "cherry").inOrder()
            assertThat(titles(DocumentSort.RECENT).first().map { it.title }).containsExactly("cherry", "Apple", "banana").inOrder()
            assertThat(titles(DocumentSort.CREATED).first().map { it.title }).containsExactly("cherry", "Apple", "banana").inOrder()
        }

    @Test
    fun search_titleMatchesRankFirst() =
        runTest {
            documents.create(spec("Vectors")).orThrow() // body empty; title hit
            clock.advanceMs(1000)
            val other = documents.create(spec("Other")).orThrow()
            library.setTags(other.path, setOf("x")).orThrow()

            assertThat(library.search("vec").first().map { it.title }).containsExactly("Vectors")
            assertThat(library.search("  ").first()).isEmpty()
        }

    @Test
    fun folders_createRenameMoveTintDelete() =
        runTest {
            val physics = library.createFolder("", "Physics").orThrow()
            assertThat(library.createFolder("", "Physics").orThrow()).isEqualTo("Physics (2)")
            documents.create(spec("Waves", physics)).orThrow()
            val school = library.createFolder("", "School").orThrow()

            val renamed = library.renameFolder(physics, "Physik").orThrow()
            val moved = library.moveFolder(renamed, school).orThrow()
            library.setFolderTint(moved, "blue").orThrow()

            assertThat(moved).isEqualTo("School/Physik")
            assertThat(db.dao().document("School/Physik/Waves.folio")).isNotNull()
            assertThat(
                library
                    .folderTree()
                    .first()
                    .first { it.path == moved }
                    .tint,
            ).isEqualTo("blue")

            val entry = library.deleteFolder(moved).orThrow()
            assertThat(entry.isFolder).isTrue()
            assertThat(db.dao().document("School/Physik/Waves.folio")).isNull()
            assertThat(library.folderTree().first().map { it.path }).doesNotContain(moved)

            documents.restore(entry).orThrow()
            assertThat(db.dao().document("School/Physik/Waves.folio")).isNotNull()
        }

    private fun addUnknownKeyAndEntry(path: String) {
        val reader = FolioContainerReader.open(file(path)).orThrow()
        val entries =
            reader.use {
                reader.names().map { name ->
                    val bytes = reader.read(name, Long.MAX_VALUE).orThrow()
                    if (name == FolioEntries.MANIFEST) {
                        val json = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                        WriterEntry.of(name, JsonObject(json + ("futureKey" to JsonPrimitive("kept"))).toString().encodeToByteArray())
                    } else {
                        WriterEntry.of(name, bytes)
                    }
                } + WriterEntry.of("future/data.bin", byteArrayOf(1, 2, 3))
            }
        fs.writeAtomic(path) { FolioContainerWriter.write(it, entries) }.orThrow()
    }
}
