package dev.folio.app.spikes

import android.annotation.SuppressLint
import android.content.Context
import android.os.Environment
import android.os.FileObserver
import android.os.SystemClock
import android.util.TypedValue
import android.widget.TextView
import androidx.annotation.MainThread
import dev.folio.app.BuildConfig
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.FolioLog
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
 * Spike P01-S6: All-files access + java.io on the HyperOS shared-storage FUSE mount. Packs a
 * synthetic 50 MB working copy to `Folio-Debug/spike/spike.folio`, verifies it, removes stale
 * `.folio.tmp` files on start, and records FileObserver events on `Folio-Debug/fixtures` and
 * `Folio-Debug/spike` with their delay after `spike-io mark`. Deleted or promoted by P01-T08.
 */
@SuppressLint("ViewConstructor", "SetTextI18n") // created in code by AppDebugHooks; spike readout is not UI copy
@MainThread
internal class SpikeIoView(
    context: Context,
    private val dispatchers: FolioDispatchers,
) : TextView(context) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
    private val libraryRoot = File(Environment.getExternalStorageDirectory(), BuildConfig.LIBRARY_ROOT)
    private val spikeDir = File(libraryRoot, "spike")
    private val fixturesDir = File(libraryRoot, "fixtures")
    private val workDir = File(context.filesDir, "work/spike")
    private val target = File(spikeDir, "spike.folio")
    private val events = ArrayDeque<ObservedEvent>()
    private var observer: FileObserver? = null
    private var job: Job? = null
    private var markElapsedMs = 0L

    /** Last pack, or null. */
    var lastPack: PackResult? = null
        private set

    /** Last verify, or null. */
    var lastVerify: VerifyResult? = null
        private set

    /** What the current or last background action is doing. */
    var state = "idle"
        private set

    /** Stale temp files removed when the screen opened. */
    var removedOnStart: List<String> = emptyList()
        private set

    val hasAllFilesAccess: Boolean get() = Environment.isExternalStorageManager()

    init {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
        setBackgroundColor(BACKGROUND_ARGB)
        setPadding(PADDING_PX, PADDING_PX, PADDING_PX, PADDING_PX)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        spikeDir.mkdirs()
        fixturesDir.mkdirs()
        runAction("cleanup") {
            val removed = SpikePacker.removeStaleTmp(spikeDir)
            withContext(dispatchers.main) { removedOnStart = removed }
        }
        observer =
            object : FileObserver(listOf(fixturesDir, spikeDir), OBSERVED) {
                override fun onEvent(
                    event: Int,
                    path: String?,
                ) {
                    val nowMs = SystemClock.elapsedRealtime()
                    post { record(ObservedEvent(event and ALL_EVENTS, path.orEmpty(), nowMs)) }
                }
            }.also { it.startWatching() }
        refresh()
    }

    override fun onDetachedFromWindow() {
        observer?.stopWatching()
        scope.cancel()
        super.onDetachedFromWindow()
    }

    /** Writes the synthetic working copy (4 x 10 MB STORED assets, 10 x 1 MB pages) to app storage. */
    fun prepare() =
        runAction("prepare") {
            SpikePacker.prepareWorkingCopy(workDir, ASSETS, ASSET_BYTES, PAGES, PAGE_BYTES)
        }

    /** Packs to the library; [slow] waits [SLOW_ENTRY_MS] before each entry so `stop.sh` can land mid-pack. */
    fun pack(slow: Boolean) =
        runAction(if (slow) "pack-slow" else "pack") {
            if (!File(workDir, "manifest.json").exists()) SpikePacker.prepareWorkingCopy(workDir, ASSETS, ASSET_BYTES, PAGES, PAGE_BYTES)
            val result = SpikePacker.pack(workDir, target) { if (slow) Thread.sleep(SLOW_ENTRY_MS) }
            withContext(dispatchers.main) { lastPack = result }
        }

    /** Reads the packed file back completely. */
    fun verify() =
        runAction("verify") {
            val result = SpikePacker.verify(target)
            withContext(dispatchers.main) { lastVerify = result }
        }

    /** Starts a FileObserver latency measurement: clears events and remembers now. */
    fun mark() {
        events.clear()
        markElapsedMs = SystemClock.elapsedRealtime()
        refresh()
    }

    /** Counters, results and events as JSON for `debugcmd.sh spike-io`. */
    fun stats(): JsonObject =
        buildJsonObject {
            put("state", state)
            put("allFilesAccess", hasAllFilesAccess)
            put("target", target.path)
            put("targetBytes", target.length())
            put("tmpPresent", File(spikeDir, target.name + SpikePacker.TMP_SUFFIX).exists())
            put("removedOnStart", buildJsonArray { removedOnStart.forEach { add(JsonPrimitive(it)) } })
            lastPack?.let { p ->
                put(
                    "pack",
                    buildJsonObject {
                        put("totalMs", p.totalMs)
                        put("crcMs", p.crcMs)
                        put("writeMs", p.writeMs)
                        put("fsyncMs", p.fsyncMs)
                        put("renameMs", p.renameMs)
                        put("bytes", p.bytes)
                        put("dirFsync", p.dirFsync)
                    },
                )
            }
            lastVerify?.let { v ->
                put(
                    "verify",
                    buildJsonObject {
                        put("ok", v.ok)
                        put("entries", v.entries)
                        put("mimetypeFirst", v.mimetypeFirst)
                        put("error", v.error)
                    },
                )
            }
            put(
                "events",
                buildJsonArray {
                    events.forEach { e ->
                        add(
                            buildJsonObject {
                                put("event", eventName(e.event))
                                put("path", e.path)
                                put("afterMarkMs", if (markElapsedMs == 0L) null else e.elapsedMs - markElapsedMs)
                            },
                        )
                    }
                },
            )
        }

    private fun runAction(
        name: String,
        block: suspend () -> Unit,
    ) {
        if (job?.isActive == true) return
        state = "$name running"
        refresh()
        job =
            scope.launch {
                state =
                    try {
                        withContext(dispatchers.io) { block() }
                        "$name done"
                    } catch (e: IOException) {
                        FolioLog.w(TAG, "$name failed", e)
                        "$name failed: ${e.message}"
                    }
                refresh()
            }
    }

    private fun record(event: ObservedEvent) {
        if (events.size == MAX_EVENTS) events.removeFirst()
        events.addLast(event)
        refresh()
    }

    private fun refresh() {
        text = "spike-io\n" + stats().toString().replace(",\"", ",\n\"")
    }

    private class ObservedEvent(
        val event: Int,
        val path: String,
        val elapsedMs: Long,
    )

    companion object {
        const val ROUTE = "spike-io"
        private const val TAG = "SpikeIo"
        private const val ASSETS = 4
        private const val ASSET_BYTES = 10 * 1024 * 1024
        private const val PAGES = 10
        private const val PAGE_BYTES = 1024 * 1024
        private const val SLOW_ENTRY_MS = 400L
        private const val MAX_EVENTS = 40
        private const val TEXT_SP = 14f
        private const val PADDING_PX = 48
        private const val BACKGROUND_ARGB = 0xFFFFFFFF.toInt()
        private const val ALL_EVENTS = FileObserver.ALL_EVENTS
        private const val OBSERVED =
            FileObserver.CREATE or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.MOVED_FROM or FileObserver.DELETE

        fun eventName(event: Int): String =
            when (event) {
                FileObserver.CREATE -> "CREATE"
                FileObserver.CLOSE_WRITE -> "CLOSE_WRITE"
                FileObserver.MOVED_TO -> "MOVED_TO"
                FileObserver.MOVED_FROM -> "MOVED_FROM"
                FileObserver.DELETE -> "DELETE"
                else -> "0x" + event.toString(HEX)
            }

        private const val HEX = 16
    }
}
