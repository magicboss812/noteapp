package dev.folio.core.ink.input

import android.view.MotionEvent

/** What holding a stylus button does (10-editor-ui.md Settings, "pen button action"). */
enum class PenButtonAction {
    /** Nothing: the stroke uses the active tool. */
    NONE,

    /** The gesture erases while the button is held at touch down (temporary eraser). */
    ERASER,
}

/**
 * The user's stylus choices (Settings > Pen and input, P11-T01). Each applies only when the stylus has
 * the capability ([StylusFeatures]). Tilt shading has no switch yet: committed strokes apply tilt
 * behaviors whenever their stored inputs carry tilt (STATUS D-014).
 */
data class StylusPreferences(
    /** Show the size ring under a hovering pen. */
    val hoverCursor: Boolean = true,
    /** Action of a held stylus button. */
    val penButton: PenButtonAction = PenButtonAction.ERASER,
) {
    /** Defaults. */
    companion object {
        /** Hover ring on, button erases. */
        val DEFAULT: StylusPreferences = StylusPreferences()
    }
}

/** Stylus settings rows; a row is shown only when the stylus supports it ([StylusFeatures.visibleSettings]). */
enum class StylusSetting {
    /** Pencil tilt shading (needs tilt). */
    TILT_SHADING,

    /** Hover size ring (needs hover). */
    HOVER_CURSOR,

    /** Pen button action (needs button state reaching the app). */
    PEN_BUTTON,
}

/**
 * Capability gating (06-ink-input.md#stylus-capabilities): a feature is on only when the stylus reports
 * what it needs, and the user did not turn it off. Unsupported features are hidden in settings, not
 * shown disabled. Allocation-free except [visibleSettings].
 */
object StylusFeatures {
    private const val STYLUS_BUTTONS = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY

    /** Pencil strokes get tilt behaviors (wet ink) when the stylus reports tilt. */
    fun tiltShading(capabilities: StylusCapabilities): Boolean = capabilities.tilt

    /** A size ring follows the hovering pen. */
    fun hoverCursor(
        capabilities: StylusCapabilities,
        preferences: StylusPreferences,
    ): Boolean = capabilities.hover && preferences.hoverCursor

    /** A held stylus button turns the gesture into an eraser gesture. */
    fun buttonEraser(
        capabilities: StylusCapabilities,
        preferences: StylusPreferences,
    ): Boolean = capabilities.primaryButton && preferences.penButton == PenButtonAction.ERASER

    /** True when [event] (a stylus down or hover) holds a stylus button and [buttonEraser] is on. */
    fun buttonErases(
        event: MotionEvent,
        capabilities: StylusCapabilities,
        preferences: StylusPreferences,
    ): Boolean = event.buttonState and STYLUS_BUTTONS != 0 && buttonEraser(capabilities, preferences)

    /** Settings rows to show for [capabilities], in display order. */
    fun visibleSettings(capabilities: StylusCapabilities): List<StylusSetting> =
        buildList {
            if (capabilities.tilt) add(StylusSetting.TILT_SHADING)
            if (capabilities.hover) add(StylusSetting.HOVER_CURSOR)
            if (capabilities.primaryButton) add(StylusSetting.PEN_BUTTON)
        }
}
