package dev.folio.core.format.container

import com.google.common.truth.Truth.assertThat
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.model.AssetId
import dev.folio.core.model.AssetInfo
import dev.folio.core.model.Document
import dev.folio.core.model.FlowId
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageSpec
import dev.folio.core.testing.ModelAssertions.assertPageEquivalent
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.testing.TestData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.random.Random

/**
 * Golden `testdata/format/v1/sample.folio`. Its manifest carries keys this version does not know
 * (top level and inside a page) to prove they are ignored. A missing golden is written once and the
 * test fails so the file gets reviewed and committed.
 */
class GoldenContainerTest {
    @Test
    fun golden_sampleFolio_opensWithUnknownKeysIgnored() {
        val file = TestData.file("format/v1/sample.folio")
        val expected = expectedDocument()
        if (!file.exists()) {
            file.parentFile.mkdirs()
            file.outputStream().use { FolioContainerWriter.write(it, goldenEntries(expected)) }
            throw AssertionError("golden $file was missing and has been written; review and commit it, then rerun")
        }
        val read = FolioContainerReader.open(file).orFail().use { DocumentCodec.readDocument(it, loadPages = true).orFail() }
        assertThat(read.meta).isEqualTo(expected.meta)
        assertThat(read.pages).isEqualTo(expected.pages)
        assertThat(read.flows).isEqualTo(expected.flows)
        assertThat(read.assets).isEqualTo(expected.assets)
        expected.pages.forEach { assertPageEquivalent(expected.pageBodies.getValue(it.id), read.pageBodies.getValue(it.id)) }
    }

    private fun goldenEntries(doc: Document): List<WriterEntry> {
        val entries = DocumentEntries.all(doc, ManifestApp("Folio", "0.1.0"), mapOf(ASSET to { PNG_1X1.inputStream() }))
        return entries.map { e ->
            if (e.name != FolioEntries.MANIFEST) return@map e
            val json = Json.parseToJsonElement(e.open().readBytes().decodeToString()).jsonObject
            val pages = json.getValue("pages").jsonArray
            val page0 = JsonObject(pages[0].jsonObject + ("futurePageFlag" to JsonPrimitive(true)))
            val withUnknown =
                JsonObject(
                    json + ("futureTopLevel" to JsonObject(mapOf("layers" to JsonArray(listOf(JsonPrimitive(1)))))) +
                        ("pages" to JsonArray(listOf(page0) + pages.drop(1))),
                )
            WriterEntry.of(FolioEntries.MANIFEST, withUnknown.toString().encodeToByteArray())
        }
    }

    private fun expectedDocument(): Document {
        val random = Random(2026)
        val page1 = ModelFixtures.page("33333333-3333-4333-8333-333333333333", List(4) { ModelFixtures.randomObject(random) })
        val page2 =
            ModelFixtures.page(
                "44444444-4444-4444-8444-444444444444",
                listOf(ModelFixtures.randomStroke(random, ObjectId("55555555-5555-4555-8555-555555555555"))),
                PageSpec.Infinite(ModelFixtures.A4),
            )
        val flow = ModelFixtures.flow("66666666-6666-4666-8666-666666666666", "# Kinematics\n\n- \$v = s/t\$\n- [ ] homework")
        val doc =
            ModelFixtures.document(
                listOf(page1, page2),
                listOf(flow),
                listOf(AssetInfo(ASSET, "image/png", PNG_1X1.size.toLong(), "dot.png")),
            )
        return doc
            .copy(meta = doc.meta.copy(title = "Physics 11 - Kinematics"), flows = doc.flows)
            .also { check(it.flows.containsKey(FlowId(flow.id.value))) }
    }

    private companion object {
        val ASSET = AssetId("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")

        // Smallest valid PNG (1x1 transparent).
        val PNG_1X1: ByteArray =
            java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
            )
    }
}
