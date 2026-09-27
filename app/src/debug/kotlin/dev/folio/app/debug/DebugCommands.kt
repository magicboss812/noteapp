package dev.folio.app.debug

import dev.folio.core.common.Outcome
import dev.folio.core.common.PerfMonitor
import dev.folio.core.common.PerfStats
import dev.folio.core.common.outcomeOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Switches the frame-time overlay. */
internal fun interface OverlaySwitch {
    fun setVisible(visible: Boolean)
}

/**
 * Registry of debug commands sent by `scripts/device/debugcmd.sh <cmd> [arg]`.
 * Later phases add commands here (open, new, tool, seed-strokes, zoom-anim, pack, export-pdf, ...).
 */
internal class DebugCommands(
    private val state: DebugAppState,
    extra: Map<String, (String?) -> DebugReply> = emptyMap(),
    private val overlay: OverlaySwitch,
) {
    private val handlers: Map<String, (String?) -> DebugReply> =
        mapOf(
            "help" to { _ -> help() },
            "state" to { _ -> DebugReply.ok(state.toJson()) },
            "perf-reset" to { _ -> perfReset() },
            "perf-dump" to { _ -> DebugReply.ok(perfJson(PerfMonitor.snapshot())) },
            "route" to ::route,
            "overlay" to ::overlay,
        ) + extra

    val names: Set<String> get() = handlers.keys

    /** Runs [command]; [arg] is null when absent. Never throws. */
    fun execute(
        command: String,
        arg: String?,
    ): DebugReply {
        val handler = handlers[command] ?: return DebugReply.error("unknown command '$command'")
        return when (val result = outcomeOf("command $command failed") { handler(arg) }) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> DebugReply.error("${result.message}: ${result.cause}")
        }
    }

    private fun help() =
        DebugReply.ok(
            buildJsonObject { put("commands", buildJsonArray { names.sorted().forEach { add(JsonPrimitive(it)) } }) },
        )

    private fun perfReset(): DebugReply {
        PerfMonitor.reset()
        return DebugReply.ok(buildJsonObject { put("reset", true) })
    }

    private fun route(arg: String?): DebugReply {
        val target = arg ?: return DebugReply.error("route needs a name")
        if (!state.navigate(target)) return DebugReply.error("unknown route '$target', known: ${state.knownRoutes.sorted()}")
        return DebugReply.ok(state.toJson())
    }

    private fun overlay(arg: String?): DebugReply {
        val visible =
            when (arg) {
                "on" -> true
                "off" -> false
                else -> return DebugReply.error("overlay needs on|off")
            }
        overlay.setVisible(visible)
        return DebugReply.ok(buildJsonObject { put("overlay", visible) })
    }
}

internal fun perfJson(stats: Map<String, PerfStats>): JsonObject =
    buildJsonObject {
        putJsonObject("sections") {
            stats.forEach { (name, s) ->
                putJsonObject(name) {
                    put("count", s.count)
                    put("p50Ms", s.p50Ms)
                    put("p95Ms", s.p95Ms)
                    put("maxMs", s.maxMs)
                }
            }
        }
    }
