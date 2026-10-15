package com.cyclone.mobile.ui.overlay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Alpha.134 (plan 60): the overlay follows the owner's Visual quality (Settings: Auto / Full / Lite).
 *
 * **Lite** keeps the overlay's layout, words, windows and rules, and drops the work that runs every frame or every
 * resize:
 * - no cross-window blur (it forces the whole screen onto GPU composition);
 * - no halftone dots, tilt light or gravity sensor;
 * - no full-screen Trace Field;
 * - spinners and rings drawn still;
 * - sizes that snap instead of animating, so the overlay's window resizes once, not on every frame.
 *
 * What stays the same in Lite: the windows (their types, flags, sizes and the Secrets card's capture protection), so
 * the agent's view, the Glass mirror and every approval boundary are unchanged.
 *
 * [active] is Compose state: every overlay window's composition and drawing code that reads it follows a change.
 */
object OverlayLite {
    var active by mutableStateOf(false)
        private set

    /** Called from the overlay's root composition when Visual quality resolves (main thread). */
    fun set(lite: Boolean) {
        if (active == lite) return
        active = lite
        com.cyclone.mobile.ui.overlay.glass.GlassBlur.lite(lite)
        com.cyclone.mobile.ui.overlay.tracefield.TraceFieldRuntime.refresh()
    }
}
