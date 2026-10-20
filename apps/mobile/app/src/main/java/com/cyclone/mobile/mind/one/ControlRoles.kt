package com.cyclone.mobile.mind.one

import com.cyclone.mobile.mind.modes.InstantGrammar

/**
 * Plan 75 alpha 148: what a control is for, in the owner's languages (English and Dutch). "Mijn profiel" and "Profile" name
 * the same role, so a step's target finds the right control whatever the screen's language. A role only ORDERS the controls
 * the decision models choose from; it never picks one (the models still decide, code still checks the move). Pure.
 */
object ControlRoles {
    /** Role → the words that name it, normalised as [InstantGrammar.normalize] does. */
    private val WORDS: Map<String, List<String>> = linkedMapOf(
        "profile" to listOf("profile", "profiel", "mijn profiel", "my profile", "account"),
        "search" to listOf("search", "zoek", "zoeken", "find"),
        "settings" to listOf("settings", "instellingen", "setting"),
        "home" to listOf("home", "start", "homepage"),
        "back" to listOf("back", "terug"),
        "close" to listOf("close", "sluiten", "dismiss", "not now", "niet nu", "no thanks", "nee bedankt"),
        "send" to listOf("send", "verstuur", "verzend", "versturen"),
        "compose" to listOf("compose", "new message", "nieuw bericht", "write", "schrijven"),
    )

    /** The role [text] names (a target, a label), or null when none of its words is a known role word. */
    fun of(text: String): String? {
        val normal = " " + InstantGrammar.normalize(text) + " "
        return WORDS.entries.firstOrNull { (_, words) -> words.any { normal.contains(" $it ") } }?.key
    }

    /** Whether [control] plays the role [target] names. False when either names no role. */
    fun matches(target: String, control: StepControl): Boolean {
        val wanted = of(target) ?: return false
        return of("${control.label} ${control.role}") == wanted
    }
}
