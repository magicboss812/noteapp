package dev.folio.core.storage.work

import dev.folio.core.common.FolioFs
import dev.folio.core.common.Outcome
import dev.folio.core.format.container.DocumentCodec
import dev.folio.core.format.container.DocumentEntries
import dev.folio.core.format.container.EntryReader
import dev.folio.core.format.container.FolioContainerReader
import dev.folio.core.format.container.FolioContainerWriter
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.model.Document
import dev.folio.core.testing.ModelFixtures
import java.io.File
import kotlin.random.Random

internal object WorkFixture {
    val APP = ManifestApp("Folio", "test")

    fun document(seed: Int = 1): Document {
        val random = Random(seed)
        val doc =
            ModelFixtures.document(
                pages = List(3) { ModelFixtures.randomPage(random, 5) },
                flows = listOf(ModelFixtures.flow(ModelFixtures.randomId(random), "# Notes\n\nhello")),
                id = ModelFixtures.randomId(random),
            )
        return doc.copy(meta = doc.meta.copy(title = "Physics: Week 1"))
    }

    /** Writes [doc] as a `.folio` at [path] in [fs]. */
    fun writeFolio(
        fs: FolioFs,
        path: String,
        doc: Document,
    ) {
        val result = fs.writeAtomic(path) { out -> FolioContainerWriter.write(out, DocumentEntries.all(doc, APP, emptyMap())) }
        check(result is Outcome.Success) { result }
    }

    /** Reads a `.folio` back (all pages) from the real file behind [file]. */
    fun readFolio(file: File): Document =
        FolioContainerReader.open(file).orThrow().use { DocumentCodec.readDocument(it as EntryReader, loadPages = true).orThrow() }

    fun <T> Outcome<T>.orThrow(): T =
        when (this) {
            is Outcome.Success -> value
            is Outcome.Failure -> throw AssertionError(message, cause)
        }
}
