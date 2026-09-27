package dev.folio.app.debug

import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.PerfMonitor
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test

class DebugCommandsTest {
    private val state = DebugAppState()
    private val overlayCalls = mutableListOf<Boolean>()
    private val commands = DebugCommands(state) { overlayCalls += it }

    @Before
    fun setUp() {
        PerfMonitor.reset()
        PerfMonitor.enabled = true
    }

    @After
    fun tearDown() {
        PerfMonitor.reset()
        PerfMonitor.enabled = false
    }

    @Test
    fun state_fresh_reportsPlaceholderScreenAndNullFields() {
        val reply = commands.execute("state", null)

        assertThat(reply.ok).isTrue()
        assertThat(reply.json.keys).containsExactly("screen", "route", "doc", "requestedDoc", "tool", "zoom")
        assertThat(
            reply.json
                .getValue("screen")
                .jsonPrimitive.content,
        ).isEqualTo("placeholder")
        assertThat(reply.json.getValue("doc")).isEqualTo(JsonNull)
    }

    @Test
    fun toLogLine_okReply_matchesDebugcmdFormat() {
        val line = commands.execute("state", null).toLogLine("42")

        assertThat(line).startsWith("nonce=42 ok=true {\"screen\":\"placeholder\"")
    }

    @Test
    fun execute_unknownCommand_replyNotOk() {
        val reply = commands.execute("warp", null)

        assertThat(reply.ok).isFalse()
        assertThat(
            reply.json
                .getValue("error")
                .jsonPrimitive.content,
        ).contains("warp")
    }

    @Test
    fun perfDump_afterSamples_returnsPercentilesPerSection() {
        PerfMonitor.record("editor:open", 2_000_000L)

        val section =
            commands
                .execute("perf-dump", null)
                .json
                .getValue("sections")
                .jsonObject
                .getValue("editor:open")
                .jsonObject

        assertThat(section.getValue("count").jsonPrimitive.content).isEqualTo("1")
        assertThat(section.getValue("p95Ms").jsonPrimitive.content).isEqualTo("2.0")
    }

    @Test
    fun perfReset_afterSamples_clearsMonitor() {
        PerfMonitor.record("a", 1L)

        assertThat(commands.execute("perf-reset", null).ok).isTrue()
        assertThat(PerfMonitor.snapshot()).isEmpty()
    }

    @Test
    fun route_unknownName_notOkAndStateUnchanged() {
        assertThat(commands.execute("route", "nowhere").ok).isFalse()
        assertThat(commands.execute("route", null).ok).isFalse()
        assertThat(state.route).isEqualTo("placeholder")
    }

    @Test
    fun route_knownName_okAndReportsState() {
        val reply = commands.execute("route", "placeholder")

        assertThat(reply.ok).isTrue()
        assertThat(
            reply.json
                .getValue("route")
                .jsonPrimitive.content,
        ).isEqualTo("placeholder")
    }

    @Test
    fun overlay_onOffAndBadArg_switchesOnlyForValidArgs() {
        assertThat(commands.execute("overlay", "on").ok).isTrue()
        assertThat(commands.execute("overlay", "off").ok).isTrue()
        assertThat(commands.execute("overlay", "maybe").ok).isFalse()
        assertThat(overlayCalls).containsExactly(true, false).inOrder()
    }

    @Test
    fun help_listsInitialCommandSet() {
        assertThat(commands.names).containsAtLeast("state", "perf-reset", "perf-dump", "route", "overlay")
    }

    @Test
    fun route_spikeInk_navigatesAndNotifiesListener() {
        val shown = mutableListOf<String>()
        state.onNavigate = { shown += it }

        assertThat(commands.execute("route", "spike-ink").ok).isTrue()

        assertThat(state.screen).isEqualTo("spike-ink")
        assertThat(shown).containsExactly("spike-ink")
    }

    @Test
    fun execute_extraHandler_runsWithArgAndIsListed() {
        val withExtra =
            DebugCommands(state, extra = mapOf("echo" to { arg -> DebugReply.error(arg ?: "none") })) { }

        assertThat(withExtra.names).contains("echo")
        assertThat(withExtra.execute("echo", "hi").json.toString()).isEqualTo("{\"error\":\"hi\"}")
    }
}
