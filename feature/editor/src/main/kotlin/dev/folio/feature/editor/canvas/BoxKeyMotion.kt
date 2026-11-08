package dev.folio.feature.editor.canvas

import dev.folio.core.model.GridUnit
import dev.folio.core.model.TextZones
import kotlin.math.floor
import kotlin.math.min

/** An arrow key that moves the selected text box in Preview (10-editor-ui.md#text-boxes, Move). */
enum class BoxKey {
    LEFT,
    RIGHT,
    UP,
    DOWN,
}

/** [key] went down (with Shift: [smooth]) or up; the canvas host moves the box in Preview while it is held (P07-T14). */
data class BoxKeyEvent(
    val key: BoxKey,
    val down: Boolean,
    val smooth: Boolean = false,
)

/**
 * The speed curve of arrow key box moves (10-editor-ui.md#text-boxes, Move), the tunable constants in one place:
 * a press moves [PRESS_PT] at once; while held the speed is [MAX_DP_PER_S] x min(1, (t / [RAMP_S])^2) on screen.
 * Up/Down step one line on the press and then follow the same curve up to [MAX_LINES_PER_S].
 */
internal object BoxKeyCurve {
    /** What a press moves at once, page pt. */
    const val PRESS_PT = 1f

    /** Top speed of a held key, dp per second on screen. */
    const val MAX_DP_PER_S = 900f

    /** Time from the press to the top speed, s. */
    const val RAMP_S = 0.8f

    /** Top line rate of a held Up/Down, lines per second. */
    const val MAX_LINES_PER_S = 15f

    /** Distance travelled [heldS] seconds after the press at top speed [max] per second (the press step not included). */
    fun distance(
        heldS: Float,
        max: Float,
    ): Float {
        val held = heldS.coerceAtLeast(0f)
        val ramp = min(held, RAMP_S)
        // Integral of max * (t / RAMP_S)^2 up to the ramp's end (max * ramp^3 / (3 RAMP_S^2)), then the top speed.
        return max * (ramp * ramp * ramp / (CUBIC * RAMP_S * RAMP_S) + (held - ramp))
    }

    // The 3 of the integral of t^2.
    private const val CUBIC = 3f

    /** Smooth distance [heldS] seconds after the press, dp on screen. */
    fun distanceDp(heldS: Float): Float = distance(heldS, MAX_DP_PER_S)

    /** Line steps taken [heldS] seconds after the press, the press's own step included. */
    fun lineSteps(heldS: Float): Int = 1 + min(floor(distance(heldS, MAX_LINES_PER_S) + EPS), MAX_STEPS).toInt()

    private const val EPS = 1e-4f

    // Far more than any hold takes (a day at the top rate); keeps the count clear of Int overflow.
    private const val MAX_STEPS = 1e6f
}

/**
 * Arrow key motion of one key hold, per axis (pure, P07-T14): the newest key of an axis drives it from its press time;
 * a release of that key stops the axis; the hold lasts while any axis runs. Up/Down step in lines, Shift+Up/Down
 * ([press] `smooth`) and Left/Right move smoothly. [advance] gives what happened since the last call in [dxPt],
 * [dyPt] (smooth, page pt) and [lineSteps] (signed: positive is down) without allocating.
 */
internal class BoxKeyMotion {
    private var xKey: BoxKey? = null
    private var xStartNs = 0L
    private var xDoneDp = 0f
    private var xPressPt = 0f

    private var yKey: BoxKey? = null
    private var yStartNs = 0L
    private var ySmooth = false
    private var yDoneDp = 0f
    private var yDoneSteps = 0
    private var yPressPt = 0f

    /** Smooth x since the last [advance], page pt. */
    var dxPt = 0f
        private set

    /** Smooth y since the last [advance], page pt. */
    var dyPt = 0f
        private set

    /** Line steps since the last [advance], positive down. */
    var lineSteps = 0
        private set

    /** True while a key of either axis is held. */
    val active: Boolean get() = xKey != null || yKey != null

    /**
     * [key] went down at [nowNs] ([smooth]: Shift held, which only changes Up/Down). False for a key already driving
     * its axis (a key repeat). The press step shows at the next [advance].
     */
    fun press(
        key: BoxKey,
        smooth: Boolean,
        nowNs: Long,
    ): Boolean {
        val horizontal = key == BoxKey.LEFT || key == BoxKey.RIGHT
        val repeat = if (horizontal) xKey == key else yKey == key && ySmooth == smooth
        if (repeat) return false
        if (horizontal) {
            xKey = key
            xStartNs = nowNs
            xDoneDp = 0f
            xPressPt += sign(key) * BoxKeyCurve.PRESS_PT
        } else {
            yKey = key
            yStartNs = nowNs
            ySmooth = smooth
            yDoneDp = 0f
            yDoneSteps = 0
            if (smooth) yPressPt += sign(key) * BoxKeyCurve.PRESS_PT
        }
        return true
    }

    /** [key] went up: its axis stops if it drives it (an older key of that axis was replaced and stopped already). */
    fun release(key: BoxKey) {
        if (xKey == key) xKey = null
        if (yKey == key) yKey = null
    }

    /** Stops both axes and drops what was not taken yet. */
    fun reset() {
        xKey = null
        yKey = null
        xPressPt = 0f
        yPressPt = 0f
        dxPt = 0f
        dyPt = 0f
        lineSteps = 0
    }

    /** Motion up to [nowNs] since the last call into [dxPt], [dyPt], [lineSteps]; [ptPerDp] maps screen dp to page pt. */
    fun advance(
        nowNs: Long,
        ptPerDp: Float,
    ) {
        // HOT PATH: once per display frame while a key is held; fields only.
        dxPt = xPressPt
        dyPt = yPressPt
        xPressPt = 0f
        yPressPt = 0f
        lineSteps = 0
        val x = xKey
        if (x != null) {
            val dp = BoxKeyCurve.distanceDp(seconds(nowNs - xStartNs))
            dxPt += sign(x) * (dp - xDoneDp) * ptPerDp
            xDoneDp = dp
        }
        val y = yKey ?: return
        val heldS = seconds(nowNs - yStartNs)
        if (ySmooth) {
            val dp = BoxKeyCurve.distanceDp(heldS)
            dyPt += sign(y) * (dp - yDoneDp) * ptPerDp
            yDoneDp = dp
        } else {
            // A page holds far fewer lines than this; the cap keeps a clock jump from stepping for long.
            val steps = BoxKeyCurve.lineSteps(heldS).coerceAtMost(yDoneSteps + MAX_STEPS_PER_ADVANCE)
            lineSteps = sign(y).toInt() * (steps - yDoneSteps)
            yDoneSteps = steps
        }
    }

    /**
     * A box top at [topPt] after the last [advance] on [grid]: the smooth y, then each line step to the next rule that
     * way, so the first step of a box between the lines snaps onto the nearest line (A-057).
     */
    fun topAfter(
        grid: GridUnit,
        topPt: Float,
    ): Float {
        var top = topPt + dyPt
        var steps = lineSteps
        while (steps != 0) {
            val down = steps > 0
            top = TextZones.lineStepTop(grid, top, down)
            steps += if (down) -1 else 1
        }
        return top
    }

    private fun sign(key: BoxKey): Float = if (key == BoxKey.LEFT || key == BoxKey.UP) -1f else 1f

    private fun seconds(ns: Long): Float = if (ns <= 0L) 0f else ns / NS_PER_S

    private companion object {
        const val NS_PER_S = 1e9f
        const val MAX_STEPS_PER_ADVANCE = 200
    }
}
