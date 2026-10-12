package com.cyclone.mobile.gesture

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** What the next action is, for its pause: a person types right after tapping a field, but reads before a confirm. */
enum class PaceKind {
    TAP,
    TYPE,
    SCROLL,
    SWIPE,
    GESTURE,
    NAVIGATE,
    /** A step the owner already approved that cannot be undone: a person looks once more before pressing it. */
    CONFIRM,
}

/** The facts a pause is made from. Nothing here holds text, only counts and geometry. */
data class PaceStep(
    /** How much text the current page shows (labels of its controls), for reading time. */
    val pageTextChars: Int,
    /** Time since the page was observed: the model thinking and the screen settling count toward the pause. */
    val alreadyWaitedMs: Long,
    /** True for the first action on a page (it has to be read); false for another action on the same page. */
    val newPage: Boolean = true,
    /** How far the thumb travels from where it last touched to the target, when known. */
    val reachPx: Float? = null,
    /** The target's smaller side, when known (small targets take longer to hit). */
    val targetSizePx: Float? = null,
    val kind: PaceKind = PaceKind.TAP,
)

/**
 * Plan 52 run 4 (final): the pause before the next action, like a person who looks at the page, finds the control and
 * moves a thumb to it.
 *
 * - **Reaction:** a short beat before anything happens.
 * - **Reading:** scaled by the page's text on a new page; only a glance when acting again on the same page (a keypad,
 *   a form), so repeated actions flow instead of re-reading.
 * - **Reaching:** Fitts's law from the last touch to the target (far or small targets take longer).
 * - **Kind:** typing right after tapping a field is quick; a confirm gets one more look.
 *
 * Time already spent (the model thinking, the screen settling) counts toward the pause, so a slow model adds no extra
 * wait. Precise hands never pause. Owner hand-overs and approvals are never delayed by this.
 */
object Pacing {
    const val NATURAL_MAX_MS = 900L
    const val RELAXED_MAX_MS = 2_200L

    /** The pause before the next action; the original form (a first action on a new page, target unknown). */
    fun pauseMs(style: HandsStyle, pageTextChars: Int, alreadyWaitedMs: Long, rng: GestureRng): Long =
        pauseMs(style, PaceStep(pageTextChars, alreadyWaitedMs), rng)

    fun pauseMs(style: HandsStyle, step: PaceStep, rng: GestureRng): Long {
        if (!style.natural) return 0L
        val relaxed = style == HandsStyle.RELAXED
        val reactionRoll = rng.nextUnit()
        val searchRoll = rng.nextUnit()
        val tempoRoll = rng.nextUnit()
        var reaction = if (relaxed) 280.0 + reactionRoll * 320.0 else 120.0 + reactionRoll * 160.0
        val chars = step.pageTextChars.coerceAtLeast(0)
        val reading = if (step.newPage) {
            min(chars * (if (relaxed) 2.5 else 1.2), if (relaxed) 1_100.0 else 380.0)
        } else {
            // Same page: a glance to find the next control.
            min(chars * (if (relaxed) 0.4 else 0.15), if (relaxed) 260.0 else 90.0)
        }
        val reach = step.reachPx?.let { distance ->
            HandPlacement.reachMs(distance, step.targetSizePx ?: 48f) * (if (relaxed) 1.0 else 0.6)
        } ?: (searchRoll * (if (relaxed) 300.0 else 140.0))
        var extra = 0.0
        var readingShare = 1.0
        var reachShare = 1.0
        when (step.kind) {
            PaceKind.TYPE -> {
                // The finger is already on the field: no reach, a short beat.
                reaction *= 0.5
                readingShare = 0.3
                reachShare = 0.0
            }
            PaceKind.SCROLL, PaceKind.SWIPE -> {
                // A thumb already resting on the list: reading what came into view, no aiming.
                reachShare = 0.3
            }
            PaceKind.CONFIRM -> extra = if (relaxed) 400.0 + searchRoll * 500.0 else 200.0 + searchRoll * 300.0
            PaceKind.TAP, PaceKind.GESTURE, PaceKind.NAVIGATE -> Unit
        }
        // Tempo drifts a little from step to step, as a person's does.
        val tempo = 0.9 + tempoRoll * 0.2
        val wanted = ((reaction + reading * readingShare + reach * reachShare + extra) * tempo).roundToLong()
            .coerceAtMost(if (relaxed) RELAXED_MAX_MS else NATURAL_MAX_MS)
        return max(0L, wanted - step.alreadyWaitedMs.coerceAtLeast(0L))
    }
}

/**
 * Where the thumb last touched the screen, so the next pause knows how far it has to travel. Process-wide and
 * coordinates only: no app, no text, never stored.
 */
object HandMemory {
    @Volatile private var last: GesturePoint? = null
    @Volatile private var atMs: Long = 0L

    fun touched(point: GesturePoint, nowMs: Long = System.currentTimeMillis()) {
        last = point
        atMs = nowMs
    }

    /** Distance from the last touch to [target]; null when nothing touched in the last [freshMs]. */
    fun reachTo(target: GesturePoint, nowMs: Long = System.currentTimeMillis(), freshMs: Long = 60_000L): Float? {
        val from = last ?: return null
        if (nowMs - atMs > freshMs) return null
        return from.distanceTo(target)
    }

    fun forget() {
        last = null
        atMs = 0L
    }
}
