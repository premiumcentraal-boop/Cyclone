package com.cyclone.mobile.runtime.plane

/** Settings → Where Cyclone works. */
enum class PlaneMode(val wire: String, val label: String) {
    AUTOMATIC("automatic", "Automatic"),
    SCREEN("screen", "On screen"),
    BACKGROUND("background", "In the background"),
    ;

    companion object {
        fun fromWire(raw: String?): PlaneMode = entries.firstOrNull { it.wire == raw } ?: AUTOMATIC
    }
}

/** What is known about the phone and the task when a plane is chosen. */
data class PlaneFacts(
    val mode: PlaneMode,
    /** Android 15+, Shizuku running and authorised, the watchdog not tripped. */
    val backgroundReady: Boolean,
    /** Why background is not ready, in the owner's words (null when ready). */
    val backgroundBlocker: String? = null,
    /** The owner is using another app right now (screen on, recent touch, foreground ≠ Cyclone / launcher). */
    val ownerBusyElsewhere: Boolean = false,
    /** Driver mode (plan 24): the screen belongs to navigation. */
    val driverMode: Boolean = false,
    /** Several apps, or a long walk (the map or a skill says so). */
    val longTask: Boolean = false,
    /** What Cyclone knows about running the target app in the background. */
    val targetCompat: BackgroundCompat = BackgroundCompat.UNKNOWN,
    /** The goal needs the owner's hands (sign-in with a password, camera, a CAPTCHA). */
    val needsHands: Boolean = false,
)

data class PlaneDecision(val plane: PlaneKind, val reason: String)

/** Something observed during a task that may call for the screen. */
enum class PlaneSignal {
    /** Background frames are black while the app's accessibility tree is present: a protected (FLAG_SECURE) screen. */
    SECURE_CONTENT,
    /** The app left or crashed off the background display. */
    LEFT_DISPLAY,
    /** Verified actions stopped changing the background screen. */
    NO_EFFECT,
    /** An Owner Moment that needs the owner's hands (secret, hand-over). */
    OWNER_HANDS,
    /** An approval for pay or delete: the owner should see what they approve. */
    SEE_TO_APPROVE,
    /** The owner opened the same app on the main screen. */
    OWNER_OPENED_APP,
}

/**
 * Plan 25 §4.3. Pure and conservative: background is an upgrade when it clearly helps and is known to work; the screen
 * is the fallback that always works.
 */
object PlanePolicy {
    const val NO_EFFECT_LIMIT = 3
    const val LEFT_DISPLAY_LIMIT = 2

    fun start(facts: PlaneFacts): PlaneDecision {
        fun screen(reason: String) = PlaneDecision(PlaneKind.SCREEN, reason)
        fun background(reason: String) = PlaneDecision(PlaneKind.BACKGROUND, reason)
        if (facts.mode == PlaneMode.SCREEN) return screen("You chose to have Cyclone work on screen.")
        if (!facts.backgroundReady) return screen(facts.backgroundBlocker ?: "Background work is not available on this phone.")
        if (facts.targetCompat.needsScreen) return screen("This app ${facts.targetCompat.why}.")
        if (facts.needsHands) return screen("This task needs your hands on the phone.")
        if (facts.mode == PlaneMode.BACKGROUND) return background("You chose to have Cyclone work in the background.")
        return when {
            facts.driverMode -> background("Driving: your screen stays on navigation.")
            facts.ownerBusyElsewhere -> background("You are using your phone, so Cyclone works behind it.")
            facts.longTask -> background("A longer task: Cyclone works behind your screen.")
            else -> screen("You are not using your phone, so Cyclone works where you can watch.")
        }
    }

    /**
     * Whether a signal during a background task should bring it to the screen. [count] is how many times the signal
     * was seen in this task. Driver mode asks first (the caller speaks the question); the decision is the same.
     */
    fun escalate(signal: PlaneSignal, count: Int = 1): PlaneDecision? = when (signal) {
        PlaneSignal.SECURE_CONTENT -> PlaneDecision(PlaneKind.SCREEN, "This screen is protected and can't be seen in the background.")
        PlaneSignal.OWNER_HANDS -> PlaneDecision(PlaneKind.SCREEN, "This step needs you on the phone.")
        PlaneSignal.SEE_TO_APPROVE -> PlaneDecision(PlaneKind.SCREEN, "Take a look before you approve.")
        PlaneSignal.OWNER_OPENED_APP -> PlaneDecision(PlaneKind.SCREEN, "You opened this app, so it is yours now.")
        PlaneSignal.LEFT_DISPLAY -> if (count >= LEFT_DISPLAY_LIMIT) PlaneDecision(PlaneKind.SCREEN, "This app keeps leaving the background screen.") else null
        PlaneSignal.NO_EFFECT -> if (count >= NO_EFFECT_LIMIT) PlaneDecision(PlaneKind.SCREEN, "Actions stopped working in the background.") else null
    }

    /**
     * After an escalation, a task that started in the background goes back once the owner is done: automatic mode,
     * nothing open for the owner, and they have been idle for a moment.
     */
    fun returnToBackground(startedIn: PlaneKind, mode: PlaneMode, ownerMomentOpen: Boolean, ownerIdleMs: Long, backgroundReady: Boolean): Boolean =
        startedIn == PlaneKind.BACKGROUND && mode == PlaneMode.AUTOMATIC && !ownerMomentOpen && ownerIdleMs >= RETURN_IDLE_MS && backgroundReady

    const val RETURN_IDLE_MS = 5_000L
}
