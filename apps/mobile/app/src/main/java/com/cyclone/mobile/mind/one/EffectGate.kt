package com.cyclone.mobile.mind.one

import com.cyclone.mobile.mind.modes.InstantGrammar

/**
 * One rule for every app: what an action does to the owner's things decides whether it needs approval.
 *
 * - Typing and moving around are free.
 * - Sending, paying or publishing reaches another person or spends money: approval, unless the plan names that act.
 * - Deleting or removing: approval, unless the plan names that act, or it corrects the input being typed right now
 *   (backspace in a field that has focus). Removing text you are still typing loses nothing you cannot retype.
 *
 * Code decides here, never a model. The words are a signal, not the verdict: the plan and the focused field decide.
 * Shadow mode first: [StepEngine] records what this rule would say next to the current gate, and changes nothing else.
 */
enum class Effect { EDIT, NAVIGATE, OUTWARD, DESTRUCTIVE }

data class GateDecision(val effect: Effect, val allowed: Boolean, val reason: String)

object EffectGate {
    private val OUTWARD_WORDS = Regex("\\b(send|sent|pay|payment|buy|order|purchase|checkout|publish|post|share|transfer|subscribe|book|reserve|submit|confirm|donate|invite)\\b")
    private val DESTRUCTIVE_WORDS = Regex("\\b(delete|remove|erase|uninstall|reset|discard|block|unfollow|clear)\\b")
    private val EDIT_KEY_WORDS = setOf("delete", "backspace", "clear", "erase", "all clear", "clear entry")

    fun classify(control: StepControl): Effect {
        val label = InstantGrammar.normalize(control.label)
        return when {
            control.editable -> Effect.EDIT
            OUTWARD_WORDS.containsMatchIn(label) -> Effect.OUTWARD
            DESTRUCTIVE_WORDS.containsMatchIn(label) -> Effect.DESTRUCTIVE
            else -> Effect.NAVIGATE
        }
    }

    fun decide(step: Step, control: StepControl, screen: StepScreen): GateDecision {
        val effect = classify(control)
        return when (effect) {
            Effect.EDIT, Effect.NAVIGATE -> GateDecision(effect, true, "moves or types; nothing leaves the phone")
            Effect.OUTWARD ->
                if (plansIt(step, control, OUTWARD_WORDS)) GateDecision(effect, true, "the plan names this act")
                else GateDecision(effect, false, "reaches another person or spends money, and the plan does not name it")
            Effect.DESTRUCTIVE -> when {
                plansIt(step, control, DESTRUCTIVE_WORDS) -> GateDecision(effect, true, "the plan names this act")
                isEditKey(control) && focusedEditable(screen) ->
                    GateDecision(effect, true, "corrects the input being typed; it can be retyped")
                else -> GateDecision(effect, false, "removes something the plan does not name")
            }
        }
    }

    private fun isEditKey(control: StepControl): Boolean =
        InstantGrammar.normalize(control.label) in EDIT_KEY_WORDS

    private fun focusedEditable(screen: StepScreen): Boolean =
        screen.controls.any { it.editable && it.focused }

    /** The plan names this act when its target contains the control's own words or the act's word. */
    private fun plansIt(step: Step, control: StepControl, words: Regex): Boolean {
        val target = InstantGrammar.normalize(step.target.orEmpty())
        if (target.isBlank()) return false
        val label = InstantGrammar.normalize(control.label)
        return (label.isNotBlank() && target.contains(label)) || words.containsMatchIn(target)
    }
}
