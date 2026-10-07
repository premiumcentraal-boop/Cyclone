package com.cyclone.mobile.gesture

/**
 * Plan 52 (Human Hands): how Cyclone's hands behave, chosen by the owner in Settings › Hands.
 *
 * - [PRECISE]: the V0.3 behaviour (one cubic stroke per swipe, set-text typing, no pauses).
 * - [NATURAL]: speed-curved strokes, varied starts, touch-focused key-by-key typing and short human pauses.
 * - [RELAXED]: as Natural, with slower typing and longer reading pauses.
 *
 * The style only changes how an already-authorized action is performed. It never changes what is allowed.
 */
enum class HandsStyle {
    PRECISE,
    NATURAL,
    RELAXED;

    val natural: Boolean get() = this != PRECISE

    companion object {
        fun parse(raw: String?): HandsStyle = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: NATURAL
    }
}

enum class Handedness {
    RIGHT,
    LEFT;

    companion object {
        fun parse(raw: String?): Handedness = if (raw.equals("left", ignoreCase = true)) LEFT else RIGHT
    }
}

/**
 * Process-wide hands settings. The Android settings store writes them; the gesture, typing and pacing code read them.
 * Pure Kotlin so the planners stay testable without Android.
 */
object Hands {
    /**
     * PRECISE until the owner's stored choice is loaded ([HandsSettings.apply] when the Accessibility service
     * connects; the stored default is NATURAL). Code that runs without the service (unit tests, tools) stays precise.
     */
    @Volatile var style: HandsStyle = HandsStyle.PRECISE
    @Volatile var handedness: Handedness = Handedness.RIGHT

    /** Opt-in: rare adjacent-key typos, corrected at once. Never in usernames, emails, codes, links or numbers. */
    @Volatile var typos: Boolean = false

    /**
     * Chained (speed-curved) strokes are switched off for the rest of the process when this phone drops one
     * mid-chain; single cubic strokes are used instead and the evidence says `segmented=false`.
     */
    @Volatile var segmentedStrokesSupported: Boolean = true

    /** Key-by-key typing is switched off for the process when the Accessibility input method is unavailable. */
    @Volatile var keystrokesSupported: Boolean = true
}
