package com.cyclone.mobile.ui.overlay

/**
 * Product guards for overlay chrome. Overlay is a TYPE_ACCESSIBILITY_OVERLAY window on the
 * existing Accessibility service — never a new launcher activity or a seventh tab.
 */
object OverlayChromeContract {
    const val JSON_TYPE = "cyclone.overlay"
    const val WINDOW_TYPE = "TYPE_ACCESSIBILITY_OVERLAY"
    const val HOST_LAUNCHER_ACTIVITY = "com.cyclone.mobile.MainActivity"

    const val IDLE_VISUAL_WIDTH_DP = 144
    const val IDLE_VISUAL_HEIGHT_DP = 72
    const val IDLE_TOUCH_SIZE_DP = 48
    const val IDLE_TOUCH_BOTTOM_MARGIN_DP = 28
    const val IDLE_VISUAL_BOTTOM_MARGIN_DP = 16

    const val COMPOSER_HEIGHT_DP = 64
    const val COMPOSER_TOUCH_TARGET_DP = 48
    const val COMPOSER_BOTTOM_GAP_DP = 30
    const val TASK_AREA_MAX_HEIGHT_DP = 430
    const val TASK_AREA_KEYBOARD_MAX_HEIGHT_DP = 210
    const val EXPANDED_GLASS_ALPHA = 0.92f

    const val IDLE_TAP_MAX_GAP_MS = 700L
    const val IDLE_TAP_MAX_SEQUENCE_MS = 1_400L
    const val IDLE_ACTIVATION_DELAY_MS = 300L

    const val EXPANDED_AURORA_BASE_ALPHA = 0.66f

    val overlayLauncherActivity: String? = null

    val homeDestinationNames: List<String> = listOf("HOME", "PROFILES", "AI", "ROUTINES", "BRAIN")
    val homeDestinationLabels: List<String> = listOf("Home", "Profiles", "AI", "Routines", "Brain")

    val overlayStates: List<String> = listOf("IDLE", "ANALYSIS", "WORKING", "LIVE", "GATE", "DONE")

    val eventKinds: List<String> = listOf(
        "ASK_CYCLONE","CONFIRM","COMMERCE","VIEW_PROGRESS","STOP_TASK","TAKE_CONTROL","GATE","GATE_CONFIRM","DONE",
    )

    val jsonKeys: List<String> = listOf(
        "type","kind","state","sessionId","clicksHost","dispatchAccessibilityAction","pcAutoApproveIgnored","requestText","userPaused","gateClass",
    )
}
