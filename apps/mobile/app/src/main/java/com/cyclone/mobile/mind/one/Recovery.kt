package com.cyclone.mobile.mind.one

import com.cyclone.mobile.mind.MindToolCall
import com.cyclone.mobile.mind.MindToolResult
import com.cyclone.mobile.mind.modes.InstantGrammar
import com.cyclone.mobile.mind.pilot.Pilot
import com.cyclone.mobile.mind.workspace.Surfaces
import org.json.JSONObject

/**
 * Alpha 144: what a stuck run tries before it asks the owner. Field test 143 showed "This seems stuck… Keep going, change
 * course, or stop?" twice in ten minutes. Both times a person would have known what to do: Google Maps had put a "dark
 * theme" dialog over the map, and an Instagram tap kept landing on a screen that a Back would have cleared. A
 * question to the owner is for choices that matter, not for "something is in the way".
 *
 * The ladder, at most once per milestone, each rung proven on the screen before it counts:
 * 1. **Close what is in the way:** a dialog's own way out ("Not now", "No thanks", "Close", "Cancel", the ✕), only when
 *    the screen is small enough to be a dialog (or the label can only mean "not now"), never a half-typed field or
 *    anything that confirms, allows, sends, pays or deletes.
 * 2. **Back:** one Back, kept when the same app is still in front and the screen changed; never with text in a field.
 * 3. **Reopen the app:** the run's app brought back to the front.
 *
 * Nothing on the ladder runs on a system surface (a permission, sign-in, install or share sheet) or a sensitive screen:
 * those are the owner's. Only then does the owner get the check-in. Pure apart from what [look] and [hands] do.
 */
class Recovery(
    private val look: () -> StepScreen?,
    private val hands: (name: String, args: JSONObject) -> MindToolResult,
) {
    /** What it did ("closed \"Not now\""), or null when nothing on the ladder changed the screen. */
    fun attempt(): String? {
        val start = look() ?: return null
        // A system sheet (a permission, sign-in, install or share sheet) is the owner's to answer: even Back there would
        // answer it ("don't allow"), so the ladder doesn't touch it.
        if (start.sensitive || Surfaces.transient(start.packageName)) return null
        dismissive(start)?.let { control ->
            if (call("tap", JSONObject().put("ref", control.ref)).ok && moved(start, look())) return "closed \"${control.label}\""
        }
        val app = start.packageName.takeUnless { Surfaces.launcher(it) }
        // Back can throw away what someone typed: with text in a field, only reopening is tried.
        val typed = start.controls.any { it.editable && !it.value.isNullOrBlank() }
        if (!typed && call("back", JSONObject()).ok) {
            val after = look()
            if (after != null && moved(start, after) && (app == null || after.packageName == app)) return "pressed Back"
        }
        if (app != null && call("open_app", JSONObject().put("app", app)).ok) {
            val after = look()
            if (after != null && after.packageName == app && moved(start, after)) return "reopened ${start.appLabel}"
        }
        return null
    }

    private fun call(name: String, args: JSONObject): MindToolResult =
        runCatching { hands(name, args) }.getOrElse { MindToolResult.error(it.message ?: "the action failed") }

    private fun moved(before: StepScreen, after: StepScreen?): Boolean =
        after != null && ExpectCheck.check(Expect.Changed, after, before, emptyList(), null) == Verdict.HELD

    companion object {
        /** A label that can only mean "not now": safe on any screen. */
        private val ALWAYS = setOf("not now", "no thanks", "no, thanks", "maybe later", "remind me later", "later", "skip", "skip for now",
            "dismiss", "got it", "niet nu", "nee bedankt", "nee, bedankt", "misschien later", "later herinneren", "overslaan", "begrepen",
            "nicht jetzt", "später", "pas maintenant", "plus tard", "ahora no", "más tarde", "agora não", "mais tarde", "non ora")
        /** A label that closes a dialog, but on a full screen could also throw away work: only on a dialog-sized screen. */
        private val IN_A_DIALOG = setOf("close", "cancel", "x", "✕", "×", "sluiten", "annuleren", "schließen", "abbrechen", "fermer",
            "annuler", "cerrar", "cancelar", "fechar", "chiudi", "annulla", "close dialog", "close button", "dismiss dialog")
        /** At most this many controls: a dialog over an app, not the app's own page. */
        const val DIALOG_CONTROLS = 8

        /** The control that closes what is in the way, or null when none is safe to press. */
        fun dismissive(screen: StepScreen): StepControl? {
            if (screen.sensitive) return null
            // Never throw away what someone typed.
            if (screen.controls.any { it.editable && !it.value.isNullOrBlank() }) return null
            val dialog = screen.controls.size <= DIALOG_CONTROLS
            val choices = screen.controls.filter { c ->
                val label = InstantGrammar.normalize(c.label).ifBlank { c.label.trim().lowercase() }
                !Pilot.irreversible(c.label) && (label in ALWAYS || (dialog && label in IN_A_DIALOG))
            }
            return choices.distinctBy { InstantGrammar.normalize(it.label) }.singleOrNull() ?: choices.firstOrNull { InstantGrammar.normalize(it.label) in ALWAYS }
        }
    }
}
