package dev.folio.app.spikes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

// SpikeInkView itself needs androidx.ink native code, so it is measured on the device (P01-S1).
class SpikeInkCommandTest {
    @Test
    fun spikeInkCommand_routeHidden_notOk() {
        val reply = spikeInkCommand(view = null, arg = "stats")

        assertThat(reply.ok).isFalse()
        assertThat(reply.json.toString()).contains("spike-ink is not shown")
    }

    @Test
    fun handoffParse_anyCase_matchesModes() {
        assertThat(Handoff.parse("immediate")).isEqualTo(Handoff.IMMEDIATE)
        assertThat(Handoff.parse("FRAME")).isEqualTo(Handoff.FRAME)
        assertThat(Handoff.parse("commit")).isEqualTo(Handoff.COMMIT)
        assertThat(Handoff.parse("later")).isNull()
    }
}
