package com.cyclone.mobile.ui.overlay

/**
 * Frozen overlay copy from docs/agent-system/V4_BUILD_BIBLE.md.
 * Do not improvise strings. Tests assert these constants.
 */
object OverlayCopy {
    const val ANALYSIS_TITLE = "Analysis"
    const val WORKING_TITLE = "Task automation"
    const val WORKING_BODY = "I'm on it. I'll let you know when this is ready to complete. You can leave this screen."
    const val STATUS = "Working on this task"
    const val PRIMARY = "View progress"
    const val CONFIRM = "Do this"
    const val COMMERCE = "Order this from"
    const val LIVE_LEFT = "Stop task"
    const val LIVE_RIGHT = "Take control"
    const val COMPOSER = "Ask Cyclone"
    const val GATE = "Cyclone needs you to confirm before finishing this."
    const val DONE = "Saved as a draft skill. Review it in Automations before it can run alone."
    const val LEGAL = "Supervise closely. Interrupt when needed. Select apps only. Compatibility varies."

    const val NEVER_THINK = "Let me think about the best approach"
    const val NEVER_BUTTONS = "I noticed several possible buttons"
    const val NEVER_PLAN = "Here is my plan in eight steps"
    const val NEVER_ORDER = "I have placed the order"

    val NEVER_SAY: List<String> = listOf(
        NEVER_THINK,
        NEVER_BUTTONS,
        NEVER_PLAN,
        NEVER_ORDER,
    )

    fun visibleStrings(): List<String> = listOf(
        ANALYSIS_TITLE,
        WORKING_TITLE,
        WORKING_BODY,
        STATUS,
        PRIMARY,
        CONFIRM,
        COMMERCE,
        LIVE_LEFT,
        LIVE_RIGHT,
        COMPOSER,
        GATE,
        DONE,
        LEGAL,
    )

    fun primaryCta(cta: OverlayAnalysisCta): String = when (cta) {
        OverlayAnalysisCta.CONFIRM -> CONFIRM
        OverlayAnalysisCta.COMMERCE -> COMMERCE
    }
}
