package dev.folio.core.ink.input

import android.view.MotionEvent
import androidx.annotation.MainThread

/** Receives the stylus pointer of a gesture (06-ink-input.md#input-routing): the active tool draws with it. */
interface StylusTarget {
    /** Pointer [pointerId] of [event] touched down; [eraser] for TOOL_TYPE_ERASER (erases whatever the tool). */
    fun onStylusDown(
        event: MotionEvent,
        pointerId: Int,
        eraser: Boolean,
    )

    /** [event] moves the stylus pointer (historical samples included). */
    fun onStylusMove(
        event: MotionEvent,
        pointerId: Int,
    )

    /** The stylus pointer lifted normally. */
    fun onStylusUp(
        event: MotionEvent,
        pointerId: Int,
    )

    /** The stylus gesture was canceled (ACTION_CANCEL or FLAG_CANCELED): discard it. */
    fun onStylusCancel(
        event: MotionEvent,
        pointerId: Int,
    )
}

/** Receives finger and mouse gestures: pan with fling, pinch zoom, scroll wheel. */
interface NavigationTarget {
    /** Every event of an accepted finger or mouse gesture, from its ACTION_DOWN to its ACTION_UP. */
    fun onNavigationEvent(event: MotionEvent)

    /** Ends the current gesture without effect (no fling): a stylus or a palm arrived. */
    fun cancelNavigation()

    /** Mouse wheel or trackpad scroll (ACTION_SCROLL). */
    fun onScroll(event: MotionEvent)
}

/** Receives the hovering stylus (hover cursor ring, 06-ink-input.md#stylus-capabilities). */
interface HoverTarget {
    /** The stylus hovers at pointer 0 of [event] (ACTION_HOVER_ENTER or ACTION_HOVER_MOVE). */
    fun onHover(event: MotionEvent)

    /** Hovering ended: ACTION_HOVER_EXIT, or the stylus touched down. */
    fun onHoverEnd()

    /** Ignores hover. */
    object None : HoverTarget {
        override fun onHover(event: MotionEvent) = Unit

        override fun onHoverEnd() = Unit
    }
}

/**
 * Routes every canvas MotionEvent (06-ink-input.md#input-routing, #palm-rejection). Decisions are made
 * when a pointer goes down and hold for its gesture:
 * - TOOL_TYPE_STYLUS / TOOL_TYPE_ERASER -> [stylus] (one pen at a time); a finger gesture in progress is
 *   canceled, since the palm usually lands before the pen.
 * - TOOL_TYPE_FINGER or TOOL_TYPE_MOUSE as the first pointer -> [navigation], with every later event of the
 *   gesture. A finger is refused while the stylus is down or was seen (touch or hover) within
 *   [PALM_QUIET_MS], when its touch major exceeds [largeTouchPx], or when it is FLAG_CANCELED; a refused
 *   gesture stays refused until all pointers are up. A large finger joining a gesture cancels it.
 * - Stylus hover goes to [hover] (ended when the stylus touches down) and cancels a finger gesture;
 *   ACTION_SCROLL goes to [navigation].
 * Fingers never reach [stylus]. Routing allocates nothing per event.
 */
@MainThread
class InputRouter(
    private val stylus: StylusTarget,
    private val navigation: NavigationTarget,
    /** Touch major above which a finger contact is a palm (40 mm on screen). */
    private val largeTouchPx: Float,
    private val hover: HoverTarget = HoverTarget.None,
) {
    private var stylusId = NO_POINTER
    private var navigating = false
    private var lastStylusMs = NEVER_MS
    private var capabilitiesDeviceId = NO_DEVICE

    /** Capabilities of the stylus that touched or hovered last ([StylusCapabilities.NONE] before). */
    var capabilities: StylusCapabilities = StylusCapabilities.NONE
        private set

    /** True while a stylus pointer is down. */
    val isStylusDown: Boolean get() = stylusId != NO_POINTER

    /** True while a finger or mouse gesture goes to navigation. */
    val isNavigating: Boolean get() = navigating

    /** Routes one touch event. */
    fun onTouchEvent(event: MotionEvent) {
        // HOT PATH: per MotionEvent.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stylusId = NO_POINTER
                navigating = false
                onPointerDown(event, 0)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                onPointerDown(event, event.actionIndex)
            }

            MotionEvent.ACTION_MOVE -> {
                if (stylusId != NO_POINTER) {
                    lastStylusMs = event.eventTime
                    stylus.onStylusMove(event, stylusId)
                }
                if (navigating) navigation.onNavigationEvent(event)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                onPointerUp(event, event.actionIndex)
            }

            MotionEvent.ACTION_UP -> {
                onPointerUp(event, 0)
                navigating = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (stylusId != NO_POINTER) {
                    lastStylusMs = event.eventTime
                    stylus.onStylusCancel(event, stylusId)
                    stylusId = NO_POINTER
                }
                cancelNavigation()
            }
        }
    }

    /** Routes a generic motion event (hover, scroll); returns true when consumed. */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // HOT PATH: per hover event.
        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT -> {
                if (!isStylus(event.getToolType(0))) return false
                lastStylusMs = event.eventTime
                refreshCapabilities(event, 0)
                cancelNavigation()
                if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) hover.onHoverEnd() else hover.onHover(event)
                true
            }

            MotionEvent.ACTION_SCROLL -> {
                navigation.onScroll(event)
                true
            }

            else -> {
                false
            }
        }
    }

    private fun onPointerDown(
        event: MotionEvent,
        index: Int,
    ) {
        when (val tool = event.getToolType(index)) {
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> {
                lastStylusMs = event.eventTime
                if (stylusId != NO_POINTER) return
                cancelNavigation()
                hover.onHoverEnd()
                refreshCapabilities(event, index)
                stylusId = event.getPointerId(index)
                stylus.onStylusDown(event, stylusId, eraser = tool == MotionEvent.TOOL_TYPE_ERASER)
            }

            MotionEvent.TOOL_TYPE_FINGER -> {
                if (index == 0) {
                    navigating = acceptsFinger(event, index)
                    if (navigating) navigation.onNavigationEvent(event)
                } else if (navigating) {
                    if (acceptsFinger(event, index)) navigation.onNavigationEvent(event) else cancelNavigation()
                }
            }

            MotionEvent.TOOL_TYPE_MOUSE -> {
                if (index == 0) {
                    navigating = true
                    navigation.onNavigationEvent(event)
                }
            }

            else -> {
                Unit
            }
        }
    }

    private fun onPointerUp(
        event: MotionEvent,
        index: Int,
    ) {
        val canceled = event.flags and MotionEvent.FLAG_CANCELED != 0
        if (event.getPointerId(index) == stylusId) {
            lastStylusMs = event.eventTime
            if (canceled) stylus.onStylusCancel(event, stylusId) else stylus.onStylusUp(event, stylusId)
            stylusId = NO_POINTER
        } else if (navigating) {
            if (canceled) cancelNavigation() else navigation.onNavigationEvent(event)
        }
    }

    private fun acceptsFinger(
        event: MotionEvent,
        index: Int,
    ): Boolean =
        stylusId == NO_POINTER &&
            event.eventTime - lastStylusMs >= PALM_QUIET_MS &&
            event.getTouchMajor(index) <= largeTouchPx &&
            event.flags and MotionEvent.FLAG_CANCELED == 0

    private fun cancelNavigation() {
        if (!navigating) return
        navigating = false
        navigation.cancelNavigation()
    }

    private fun refreshCapabilities(
        event: MotionEvent,
        index: Int,
    ) {
        if (event.deviceId != capabilitiesDeviceId) {
            // Once per stylus device: the InputDevice lookup is cached by the framework.
            capabilitiesDeviceId = event.deviceId
            capabilities = StylusCapabilities.detect(event.device)
        }
        capabilities = capabilities.observe(event, index)
    }

    /** Timing constants. */
    companion object {
        /** Fingers are ignored until this long after the stylus was last down or hovering. */
        const val PALM_QUIET_MS = 300L

        /** Contacts larger than this are palms. */
        const val LARGE_TOUCH_MM = 40f

        private const val NO_POINTER = -1
        private const val NO_DEVICE = Int.MIN_VALUE
        private const val NEVER_MS = Long.MIN_VALUE / 2

        private fun isStylus(toolType: Int): Boolean = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
    }
}
