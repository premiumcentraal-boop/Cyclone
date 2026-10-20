package com.cyclone.mobile.mind.one

/**
 * Plan 75 alpha 147: the first officer. A Flash run's fast model (the latest DeepSeek Flash by default) writes its plan, answers
 * its consults and glances, and checks the move at a barrier. The captain (the owner's model) re-plans and decides the rest.
 *
 * Code keeps the officer to its job: a re-plan from it may change only the current milestone, and the run's done check
 * (the contract) never changes. Pure.
 */
object FirstOfficer {
    /** The wakes the officer answers. A re-plan of a whole run, a hand-over and the wheel stay with the captain. */
    fun answers(kind: WakeKind): Boolean = kind == WakeKind.PLAN || kind == WakeKind.CONSULT || kind == WakeKind.GLANCE

    /**
     * Whether [new] keeps to the current milestone: the same milestones in the same order, and only [current]'s steps may
     * differ. A re-plan that touches another milestone is the captain's to decide.
     */
    fun keepsMilestones(old: Plan, new: Plan, current: String?): Boolean {
        if (old.milestones.map { it.id } != new.milestones.map { it.id }) return false
        return old.milestones.zip(new.milestones).all { (o, n) ->
            o.id == current || (o.title == n.title && o.review == n.review &&
                o.steps.map { Triple(it.id, it.action, it.target) } == n.steps.map { Triple(it.id, it.action, it.target) })
        }
    }
}
