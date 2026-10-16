package com.cyclone.mobile.mind.one

/**
 * Plan 69 §7: what stops a run from going round in circles, now that nothing stops it for money.
 *
 * - **Actions:** the same action on the same screen twice without progress climbs a rung; a mind proposing the same
 *   failing action twice, or 10 actions with no plan step done, stops for the owner.
 * - **Wakes:** 4 wakes in one milestone make the mind change approach; 8, or the same trigger three times with no
 *   milestone progress, ask the owner a quick check-in.
 * - **Done doubt:** at most 2 re-checks, then "Couldn't confirm: please check".
 * - **Handovers:** at most one Flash → Mind per run.
 *
 * Pure: fed what happens, it answers what to do next.
 */
class Breaker {
    sealed interface Call {
        data object Go : Call
        /** Climb a rung for this step. */
        data object Climb : Call
        /** The mind must rewrite its standing orders or take the wheel. */
        data object ChangeApproach : Call
        /** Ask the owner: keep going, change course, or stop. */
        data class CheckIn(val reason: String) : Call
        /** Stop the run and explain. */
        data class Stop(val reason: String) : Call
    }

    private var lastAction: String? = null
    private var sameAction = 0
    private var actionsSinceProgress = 0
    private var lastMindProposal: String? = null
    private var mindRepeats = 0
    private var wakesThisMilestone = 0
    private val triggerCounts = HashMap<String, Int>()
    private var doneRechecks = 0
    private var handovers = 0

    /** An action the crew (or a mind) took: [key] is the action and its target, [screen] the page key before it. */
    fun action(key: String, screen: String, progressed: Boolean): Call {
        val k = "$key@$screen"
        if (progressed) { actionsSinceProgress = 0; sameAction = 0; lastAction = null; return Call.Go }
        actionsSinceProgress++
        sameAction = if (k == lastAction) sameAction + 1 else 1
        lastAction = k
        return when {
            actionsSinceProgress >= MAX_ACTIONS_WITHOUT_PROGRESS -> Call.Stop("$MAX_ACTIONS_WITHOUT_PROGRESS actions without finishing a step")
            sameAction >= 2 -> Call.Climb
            else -> Call.Go
        }
    }

    /** A mind proposed an action that then failed. */
    fun mindFailed(key: String): Call {
        mindRepeats = if (key == lastMindProposal) mindRepeats + 1 else 1
        lastMindProposal = key
        return if (mindRepeats >= 2) Call.CheckIn("the same step keeps failing: $key") else Call.Go
    }

    /** A step of the plan was completed: progress for every counter. */
    fun stepDone() { actionsSinceProgress = 0; sameAction = 0; lastAction = null; mindRepeats = 0; lastMindProposal = null }

    /** A milestone was completed: the wake counters start again. */
    fun milestoneDone() { wakesThisMilestone = 0; triggerCounts.clear(); stepDone() }

    /** A mind is about to be woken by [trigger] (a stable word: "surprise", "blocked", "divergence"…). */
    fun wake(trigger: String): Call {
        wakesThisMilestone++
        val same = (triggerCounts[trigger] ?: 0) + 1
        triggerCounts[trigger] = same
        return when {
            wakesThisMilestone >= SPIRAL_WAKES -> Call.CheckIn("$wakesThisMilestone wakes in this milestone")
            same >= SAME_TRIGGER -> Call.CheckIn("\"$trigger\" happened $same times without progress")
            wakesThisMilestone == CHANGE_APPROACH_WAKES -> Call.ChangeApproach
            else -> Call.Go
        }
    }

    /** The done check doubted a finish. True while another re-check is allowed. */
    fun doneDoubt(): Boolean = ++doneRechecks <= MAX_DONE_RECHECKS

    /** A Flash run wants to hand over to OneMind. True for the first time only. */
    fun handover(): Boolean = ++handovers <= 1

    companion object {
        const val MAX_ACTIONS_WITHOUT_PROGRESS = 10
        const val CHANGE_APPROACH_WAKES = 4
        const val SPIRAL_WAKES = 8
        const val SAME_TRIGGER = 3
        const val MAX_DONE_RECHECKS = 2
    }
}
