package com.cyclone.mobile.ui.overlay

/**
 * Product guards for overlay chrome. Overlay is a TYPE_ACCESSIBILITY_OVERLAY window on the
 * existing Accessibility service — never a new launcher activity or a seventh tab.
 */
object OverlayChromeContract {
    const val JSON_TYPE = "cyclone.overlay"
    const val WINDOW_TYPE = "TYPE_ACCESSIBILITY_OVERLAY"
    const val HOST_LAUNCHER_ACTIVITY = "com.cyclone.mobile.MainActivity"

    // 3.9 compact Aurora geometry. The visual halo is a separate non-touchable window so its
    // larger footprint never becomes a larger input-blocking rectangle.
    const val IDLE_VISUAL_WIDTH_DP = 144
    const val IDLE_VISUAL_HEIGHT_DP = 72
    const val IDLE_TOUCH_SIZE_DP = 48
    const val IDLE_TOUCH_BOTTOM_MARGIN_DP = 28
    const val IDLE_VISUAL_BOTTOM_MARGIN_DP = 16

    // Three deliberate taps. Provider/runtime state is not involved in activation.
    const val IDLE_TAP_MAX_GAP_MS = 700L
    const val IDLE_TAP_MAX_SEQUENCE_MS = 1_400L
    const val IDLE_ACTIVATION_DELAY_MS = 300L

    // 3.8.9's moving layer relied on a 0.34 mid-panel dark stop. 3.9 adds this darker base below
    // the aurora so the AI layer stays readable over bright apps without becoming opaque.
    const val EXPANDED_AURORA_BASE_ALPHA = 0.66f

    /** Overlay chrome is not an Activity. Null means no launcher component is added. */
    val overlayLauncherActivity: String? = null

    val homeDestinationNames: List<String> = listOf("HOME", "TEACH", "AI", "ROUTINES", "BRAIN")
    val homeDestinationLabels: List<String> = listOf("Home", "Teach", "AI", "Routines", "Brain")

    val overlayStates: List<String> = listOf("IDLE", "ANALYSIS", "WORKING", "LIVE", "GATE", "DONE")

    val eventKinds: List<String> = listOf(
        "ASK_CYCLONE",
        "CONFIRM",
        "COMMERCE",
        "VIEW_PROGRESS",
        "STOP_TASK",
        "TAKE_CONTROL",
        "GATE",
        "GATE_CONFIRM",
        "DONE",
    )

    val jsonKeys: List<String> = listOf(
        "type",
        "kind",
        "state",
        "sessionId",
        "clicksHost",
        "dispatchAccessibilityAction",
        "pcAutoApproveIgnored",
        "requestText",
        "userPaused",
        "gateClass",
    )
}
