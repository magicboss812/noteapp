package dev.folio.core.format.container

import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.format.FormatError
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.model.AssetId
import dev.folio.core.model.AssetInfo
import dev.folio.core.model.Document
import dev.folio.core.model.PageSpec
import dev.folio.core.testing.ModelAssertions.assertPageEquivalent
import dev.folio.core.testing.ModelFixtures
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.random.Random

class ContainerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val app = ManifestApp("Folio", "0.1.0-test")
    private val assetBytes = ByteArray(3000) { (it * 31).toByte() }
    private val assetId = AssetId("a".repeat(64))

    @Test
    fun writer_producedZip_listsMimetypeFirstAndStored() {
        val file = writeSample(sampleDocument())
        ZipInputStream(file.inputStream()).use { zip ->
            val first = zip.nextEntry
            assertThat(first.name).isEqualTo("mimetype")
            assertThat(first.method).isEqualTo(ZipEntry.STORED)
            assertThat(zip.readBytes().decodeToString()).isEqualTo("application/vnd.folio+zip")
            val rest = generateSequence { zip.nextEntry }.toList()
            assertThat(rest.first().name).isEqualTo("manifest.json")
            assertThat(rest.first { it.name.startsWith("assets/") }.method).isEqualTo(ZipEntry.STORED)
            assertThat(rest.first { it.name.startsWith("pages/") }.method).isEqualTo(ZipEntry.DEFLATED)
            assertThat(rest.last().name).isEqualTo("search/text.txt")
        }
    }

    @Test
    fun roundTrip_documentThroughZip_equivalent() {
        val doc = sampleDocument()
        val file = writeSample(doc)
        val read = FolioContainerReader.open(file).orFail().use { DocumentCodec.readDocument(it, loadPages = true).orFail() }
        assertThat(read.meta).isEqualTo(doc.meta)
        assertThat(read.pages).isEqualTo(doc.pages)
        assertThat(read.flows).isEqualTo(doc.flows)
        assertThat(read.assets).isEqualTo(doc.assets)
        doc.pages.forEach { assertPageEquivalent(doc.pageBodies.getValue(it.id), read.pageBodies.getValue(it.id)) }
        FolioContainerReader.open(file).orFail().use { reader ->
            val entry = DocumentEntries.assetEntry(doc, assetId)!!
            assertThat(reader.read(entry, Long.MAX_VALUE).orFail()).isEqualTo(assetBytes)
        }
    }

    @Test
    fun manifest_unknownKeys_ignored() {
        val text =
            """{"format":"folio","formatVersion":1,"id":"d1","title":"T","createdMs":1,"modifiedMs":2,
               "futureTopLevel":{"x":[1,2]},"pages":[{"id":"p1","kind":"fixed","size":"A5","orientation":"landscape",
               "widthPt":595.2756,"heightPt":419.5276,"template":"GRID","futurePageKey":true}]}"""
        val manifest = DocumentCodec.parseManifest(text).orFail()
        assertThat(manifest.pages.single().size).isEqualTo("A5")
    }

    @Test
    fun manifest_newerMajorVersion_rejectedWithFormatTooNew() {
        val text = """{"format":"folio","formatVersion":2,"id":"d1","title":"T","createdMs":1,"modifiedMs":2}"""
        val result = DocumentCodec.parseManifest(text)
        assertThat((result as Outcome.Failure).cause).isInstanceOf(FormatError.FormatTooNew::class.java)
        assertThat((result.cause as FormatError.FormatTooNew).found).isEqualTo(2)
    }

    @Test
    fun migrations_chainApplied_gapIsCorrupt() {
        val renameTitle =
            object : Migration {
                override val from = 1
                override val to = 2

                override fun migrateManifest(manifest: kotlinx.serialization.json.JsonObject) =
                    kotlinx.serialization.json.JsonObject(
                        manifest + ("title" to kotlinx.serialization.json.JsonPrimitive("migrated")) +
                            ("formatVersion" to kotlinx.serialization.json.JsonPrimitive(2)),
                    )
            }
        val registry = MigrationRegistry(listOf(renameTitle), current = 2)
        val text = """{"format":"folio","formatVersion":1,"id":"d1","title":"T","createdMs":1,"modifiedMs":2}"""
        assertThat(DocumentCodec.parseManifest(text, registry).orFail().title).isEqualTo("migrated")
        val gap = MigrationRegistry(emptyList(), current = 2).plan(1)
        assertThat((gap as Outcome.Failure).cause).isInstanceOf(FormatError.Corrupt::class.java)
    }

    @Test
    fun reader_notAZip_isCorrupt_missingManifest_isMissingEntry() {
        val junk = tmp.newFile("junk.folio").apply { writeText("hello") }
        assertThat((FolioContainerReader.open(junk) as Outcome.Failure).cause).isInstanceOf(FormatError.Corrupt::class.java)
        val empty = tmp.newFile("empty.folio")
        empty.outputStream().use { FolioContainerWriter.write(it, emptyList()) }
        val result = FolioContainerReader.open(empty).orFail().use { DocumentCodec.readDocument(it) }
        assertThat((result as Outcome.Failure).cause).isInstanceOf(FormatError.MissingEntry::class.java)
    }

    @Test
    fun entryNames_unsafeRejected() {
        listOf("../x", "/abs", "a\\b", "a/../b", "a//b", "dir/", "ü.txt", "").forEach {
            assertThat(FolioEntries.isValidName(it)).isFalse()
        }
        assertThat(FolioEntries.isValidName("pages/abc-1.pb")).isTrue()
    }

    private fun sampleDocument(): Document {
        val random = Random(99)
        val pages =
            listOf(
                ModelFixtures.randomPage(random, 12),
                ModelFixtures.randomPage(random, 3, PageSpec.Infinite(PageSpec.Custom(500f, 700f))),
            )
        val flows = listOf(ModelFixtures.flow(ModelFixtures.randomId(random), "# Heading\n\nText with $\\sqrt{x}$."))
        return ModelFixtures.document(pages, flows, listOf(AssetInfo(assetId, "image/png", assetBytes.size.toLong(), "photo.png")))
    }

    private fun writeSample(doc: Document): File {
        val file = tmp.newFile("sample.folio")
        file.outputStream().use {
            FolioContainerWriter.write(
                it,
                DocumentEntries.all(
                    doc,
                    app,
                    mapOf(assetId to { assetBytes.inputStream() }),
                ),
            )
        }
        return file
    }
}

internal fun <T> Outcome<T>.orFail(): T =
    when (this) {
        is Outcome.Success -> value
        is Outcome.Failure -> throw AssertionError(message, cause)
    }
