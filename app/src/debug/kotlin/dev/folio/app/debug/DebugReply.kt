package dev.folio.app.debug

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One reply line in logcat tag [TAG]: `nonce=<n> ok=<bool> <json>` (scripts/device/debugcmd.sh). */
internal data class DebugReply(
    val ok: Boolean,
    val json: JsonObject,
) {
    fun toLogLine(nonce: String): String = "nonce=$nonce ok=$ok $json"

    companion object {
        const val TAG = "FolioDebug"

        fun ok(json: JsonObject) = DebugReply(ok = true, json = json)

        fun error(message: String) = DebugReply(ok = false, json = buildJsonObject { put("error", message) })
    }
}
