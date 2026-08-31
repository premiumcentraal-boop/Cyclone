package com.cyclone.mobile.ui.overlay

/**
 * Product guards for overlay chrome. Overlay is a TYPE_ACCESSIBILITY_OVERLAY window on the
 * existing Accessibility service — never a new launcher activity or a seventh tab.
 */
object OverlayChromeContract {
    const val JSON_TYPE = "cyclone.overlay"
    const val WINDOW_TYPE = "TYPE_ACCESSIBILITY_OVERLAY"
    const val HOST_LAUNCHER_ACTIVITY = "com.cyclone.mobile.MainActivity"

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
        "gateClass",
    )
}
