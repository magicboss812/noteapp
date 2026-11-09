package dev.folio.app.debug

import androidx.annotation.MainThread
import dev.folio.core.common.FolioLog
import dev.folio.core.model.Attachment
import dev.folio.core.model.PageId
import dev.folio.feature.editor.state.EditorSession
import dev.folio.feature.editor.state.EditorTool
import dev.folio.feature.editor.state.ImportedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * Attachment tool automation (P07-T07, A-070): `attach-insert` attaches a generated one-page PDF fixture to the page on
 * screen like "Attach file" does (the system picker cannot be driven); `attach-open` presses Open on the newest chip,
 * which starts the "Open with" chooser. [json] lists the chips of the loaded pages.
 */
@MainThread
internal class AttachmentDebug(
    private val editor: () -> EditorSession?,
    private val scope: CoroutineScope,
) {
    fun insert(arg: String?): DebugReply {
        if (!arg.isNullOrBlank()) return DebugReply.error("attach-insert takes no argument")
        val session = editor() ?: return DebugReply.error(NO_CANVAS)
        session.selectTool(EditorTool.ATTACHMENT)
        scope.launch {
            val ok = session.insertAttachment(ImportedFile(FIXTURE_PDF.toByteArray(Charsets.US_ASCII), "application/pdf", FIXTURE_NAME))
            FolioLog.i(DebugReply.TAG, "attach-insert -> ${if (ok) "ok" else "failed"}")
        }
        return DebugReply.ok(buildJsonObject { put("inserting", FIXTURE_NAME) })
    }

    fun open(arg: String?): DebugReply {
        if (!arg.isNullOrBlank()) return DebugReply.error("attach-open takes no argument")
        val session = editor() ?: return DebugReply.error(NO_CANVAS)
        val (page, chip) = chips(session).lastOrNull() ?: return DebugReply.error("no attachment on the loaded pages")
        session.openAttachment(page, chip.id)
        return DebugReply.ok(buildJsonObject { put("opening", chip.displayName) })
    }

    /** The attachments of the loaded pages (name, mime, size, position in pt). */
    fun json(session: EditorSession): JsonObject =
        buildJsonObject {
            put(
                "list",
                JsonArray(
                    chips(session).map { (_, o) ->
                        JsonPrimitive(
                            "${o.displayName} ${o.mime} ${o.sizeBytes} B at ${o.position.x.roundToInt()},${o.position.y.roundToInt()}",
                        )
                    },
                ),
            )
        }

    private fun chips(session: EditorSession): List<Pair<PageId, Attachment>> {
        val doc = session.document.value
        return doc.pages.flatMap { ref ->
            doc.pageBodies[ref.id]
                ?.objects
                .orEmpty()
                .filterIsInstance<Attachment>()
                .map { ref.id to it }
        }
    }

    private companion object {
        const val NO_CANVAS = "no editor canvas shown; run open first"
        const val FIXTURE_NAME = "Folio fixture.pdf"

        // A minimal one-page PDF saying "Folio attachment"; no xref table (viewers rebuild it), stream length 47 bytes.
        val FIXTURE_PDF =
            "%PDF-1.4\n" +
                "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n" +
                "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n" +
                "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Contents 4 0 R" +
                "/Resources<</Font<</F1 5 0 R>>>>>>endobj\n" +
                "4 0 obj<</Length 47>>stream\nBT /F1 28 Tf 72 760 Td (Folio attachment) Tj ET\nendstream endobj\n" +
                "5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\n" +
                "trailer<</Root 1 0 R/Size 6>>\n%%EOF\n"
    }
}
