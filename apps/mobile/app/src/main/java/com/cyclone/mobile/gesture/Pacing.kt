package com.cyclone.mobile.gesture

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Plan 52 run 4: the pause before the Mind's next action, like a person who looks at the page, finds the control and
 * moves a thumb to it. Time already spent (the model thinking, the screen settling) counts toward the pause, so a slow
 * model adds no extra wait. Precise hands never pause. Owner hand-overs and approvals are never delayed by this.
 */
object Pacing {
    const val NATURAL_MAX_MS = 900L
    const val RELAXED_MAX_MS = 2_200L

    /**
     * @param pageTextChars how much text the current page shows (labels of its controls), for reading time.
     * @param alreadyWaitedMs time since the page was observed.
     */
    fun pauseMs(style: HandsStyle, pageTextChars: Int, alreadyWaitedMs: Long, rng: GestureRng): Long {
        if (!style.natural) return 0L
        val relaxed = style == HandsStyle.RELAXED
        val reaction = if (relaxed) 280.0 + rng.nextUnit() * 320.0 else 120.0 + rng.nextUnit() * 160.0
        val reading = min(pageTextChars.coerceAtLeast(0) * (if (relaxed) 2.5 else 1.2), if (relaxed) 1_100.0 else 380.0)
        // A target is not found at once: a little more on some steps than others.
        val search = rng.nextUnit() * (if (relaxed) 300.0 else 140.0)
        val wanted = (reaction + reading + search).roundToLong().coerceAtMost(if (relaxed) RELAXED_MAX_MS else NATURAL_MAX_MS)
        return max(0L, wanted - alreadyWaitedMs.coerceAtLeast(0L))
    }
}
