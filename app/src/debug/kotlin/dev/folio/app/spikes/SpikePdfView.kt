package dev.folio.app.spikes

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.widget.TextView
import androidx.annotation.MainThread
import dev.folio.app.debug.DebugReply
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.FolioLog
import dev.folio.core.pdf.spike.PdfProbeRun
import dev.folio.core.pdf.spike.PdfSpikeProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/**
 * Spike P01-S5 inside the debug app (the tablet may refuse the separate test APK): runs
 * [PdfSpikeProbe] 3 times on the pdf dispatcher and shows one line per run. Deleted by P01-T08.
 */
@SuppressLint("ViewConstructor", "SetTextI18n") // created in code by AppDebugHooks; spike readout is not UI copy
@MainThread
internal class SpikePdfView(
    context: Context,
    private val dispatchers: FolioDispatchers,
) : TextView(context) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
    private val dir = File(context.cacheDir, "p01-s5")
    private var job: Job? = null
    private var runs: List<PdfProbeRun> = emptyList()

    /** idle, running, done, or failed: message. */
    var state = "idle"
        private set

    init {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
        setBackgroundColor(BACKGROUND_ARGB)
        setPadding(PADDING_PX, PADDING_PX, PADDING_PX, PADDING_PX)
        text = "spike-pdf: run `debugcmd.sh spike-pdf run`"
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }

    /** Starts the 3-run probe unless one is running. */
    fun start() {
        if (job?.isActive == true) return
        state = "running"
        text = "spike-pdf: running"
        job =
            scope.launch {
                state =
                    try {
                        runs = withContext(dispatchers.pdf) { PdfSpikeProbe.run(context, dir, RUNS) }
                        runs.forEach { FolioLog.i(TAG, it.line()) }
                        "done"
                    } catch (e: IOException) {
                        FolioLog.w(TAG, "pdf probe failed", e)
                        "failed: ${e.message}"
                    }
                text = "spike-pdf: $state\n" + runs.joinToString("\n") { it.line() }
            }
    }

    fun stats(): JsonObject =
        buildJsonObject {
            put("state", state)
            put("runs", buildJsonArray { runs.forEach { add(JsonPrimitive(it.line())) } })
        }

    companion object {
        const val ROUTE = "spike-pdf"
        private const val TAG = "FolioProbe"
        private const val RUNS = 3
        private const val TEXT_SP = 14f
        private const val PADDING_PX = 48
        private const val BACKGROUND_ARGB = 0xFFFFFFFF.toInt()
    }
}

/** `debugcmd.sh spike-pdf [stats|run]` for P01-S5; poll `stats` until state is done. */
internal fun spikePdfCommand(
    view: SpikePdfView?,
    arg: String?,
): DebugReply {
    if (view == null) return DebugReply.error("route ${SpikePdfView.ROUTE} is not shown")
    when (arg) {
        null, "stats" -> Unit
        "run" -> view.start()
        else -> return DebugReply.error("spike-pdf needs stats|run")
    }
    return DebugReply.ok(view.stats())
}
