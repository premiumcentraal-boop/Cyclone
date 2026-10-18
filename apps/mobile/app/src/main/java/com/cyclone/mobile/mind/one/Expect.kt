package com.cyclone.mobile.mind.one

import com.cyclone.mobile.mind.modes.InstantGrammar
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the crew sees of a screen: the app, a page key, the controls with their refs, the visible text, and (only when
 * asked for) a screenshot. The Android side fills it from the toolbox's observation; tests build it by hand.
 */
data class StepScreen(
    val packageName: String,
    val appLabel: String = packageName,
    val pageKey: String = "",
    val fingerprint: String = "",
    val controls: List<StepControl> = emptyList(),
    val texts: List<String> = emptyList(),
    /** The tree carries little (games, canvases, web views): rung 2 may need the screenshot. */
    val weak: Boolean = false,
    val imageDataUrl: String? = null,
    /** A password or code field, or a keep-off app: nothing leaves the phone and no model decides here. */
    val sensitive: Boolean = false,
    /** Alpha 144: which screen this is, ignoring a feed's own motion ([com.cyclone.mobile.applearner.PageContext.screenIdentity]). */
    val identity: String = "",
) {
    fun allText(): List<String> = texts + controls.map { it.label } + controls.mapNotNull { it.value }
}

data class StepControl(
    val ref: String,
    val label: String,
    val role: String = "",
    val editable: Boolean = false,
    val focused: Boolean = false,
    val value: String? = null,
)

/**
 * Plan 69 §4.4: a postcondition the code checks after a step, and the done predicate of a whole plan. When code can't
 * tell ([Verdict.UNKNOWN]), the VERIFY question in the next fused call decides. Text matching ignores case and accents.
 */
sealed interface Expect {
    /** The app on screen is this one (a package, or words of its name). */
    data class AppIs(val app: String) : Expect
    /** This text is visible somewhere (texts, labels or field values). */
    data class Shows(val text: String) : Expect
    data class Absent(val text: String) : Expect
    /** A control whose label contains this exists. */
    data class Control(val label: String) : Expect
    /** Some editable field has focus. */
    data object Focused : Expect
    /** Some field's value contains this text. */
    data class FieldHas(val text: String) : Expect
    /** The screen is not the one before the step. */
    data object Changed : Expect
    /** An Android setting reads this value (read by the phone, not the screen). */
    data class Setting(val key: String, val value: String) : Expect
    /** A fact of this name has been found. */
    data class HasFact(val name: String) : Expect
    /** Words only a model can judge ("the chat with Anna is open"): code says UNKNOWN, VERIFY decides. */
    data class Described(val text: String) : Expect
    data class All(val parts: List<Expect>) : Expect
}

enum class Verdict { HELD, MISSED, UNKNOWN }

/** Reads Android settings for [Expect.Setting]; the phone implements it with `PhoneSettingReader`. */
fun interface SettingReader { fun read(key: String): String? }

object ExpectCheck {
    fun check(e: Expect, now: StepScreen, before: StepScreen?, facts: List<Fact>, settings: SettingReader? = null): Verdict = when (e) {
        is Expect.AppIs -> if (now.packageName.equals(e.app, true) || contains(now.appLabel, e.app) || contains(now.packageName, e.app)) Verdict.HELD else Verdict.MISSED
        is Expect.Shows -> if (now.allText().any { contains(it, e.text) }) Verdict.HELD else if (now.weak) Verdict.UNKNOWN else Verdict.MISSED
        is Expect.Absent -> if (now.allText().none { contains(it, e.text) }) (if (now.weak) Verdict.UNKNOWN else Verdict.HELD) else Verdict.MISSED
        is Expect.Control -> if (now.controls.any { contains(it.label, e.label) }) Verdict.HELD else if (now.weak) Verdict.UNKNOWN else Verdict.MISSED
        Expect.Focused -> if (now.controls.any { it.editable && it.focused }) Verdict.HELD else Verdict.UNKNOWN
        is Expect.FieldHas -> if (now.controls.any { it.editable && it.value?.let { v -> contains(v, e.text) } == true }) Verdict.HELD else Verdict.UNKNOWN
        Expect.Changed -> when {
            before == null -> Verdict.UNKNOWN
            // Alpha 144: another screen, or a field that took the keyboard; a feed moving by itself is not a change.
            before.packageName != now.packageName -> Verdict.HELD
            before.identity.isNotBlank() && now.identity.isNotBlank() ->
                if (before.identity != now.identity || (now.controls.any { it.editable && it.focused } && before.controls.none { it.editable && it.focused })) Verdict.HELD
                else Verdict.MISSED
            before.fingerprint.isNotBlank() && now.fingerprint.isNotBlank() -> if (before.fingerprint != now.fingerprint) Verdict.HELD else Verdict.MISSED
            else -> if (before.pageKey != now.pageKey || before.allText() != now.allText()) Verdict.HELD else Verdict.MISSED
        }
        is Expect.Setting -> settings?.read(e.key)?.let { if (it.equals(e.value, true)) Verdict.HELD else Verdict.MISSED } ?: Verdict.UNKNOWN
        is Expect.HasFact -> if (facts.any { it.name == e.name }) Verdict.HELD else Verdict.MISSED
        is Expect.Described -> Verdict.UNKNOWN
        is Expect.All -> {
            val parts = e.parts.map { check(it, now, before, facts, settings) }
            when {
                parts.any { it == Verdict.MISSED } -> Verdict.MISSED
                parts.all { it == Verdict.HELD } -> Verdict.HELD
                else -> Verdict.UNKNOWN
            }
        }
    }

    /** What a model must judge, as words for the VERIFY question, or null when code settled it. */
    fun describe(e: Expect): String = when (e) {
        is Expect.AppIs -> "the app ${e.app} is open"
        is Expect.Shows -> "\"${e.text}\" is visible"
        is Expect.Absent -> "\"${e.text}\" is no longer visible"
        is Expect.Control -> "there is a control \"${e.label}\""
        Expect.Focused -> "a text field is ready for typing"
        is Expect.FieldHas -> "a field contains \"${e.text}\""
        Expect.Changed -> "the screen changed after the action"
        is Expect.Setting -> "the setting ${e.key} is ${e.value}"
        is Expect.HasFact -> "the ${e.name} was found"
        is Expect.Described -> e.text
        is Expect.All -> e.parts.joinToString(", and ") { describe(it) }
    }

    private fun contains(hay: String, needle: String): Boolean {
        val n = InstantGrammar.normalize(needle)
        return n.isNotBlank() && InstantGrammar.normalize(hay).contains(n)
    }
}

object ExpectCodec {
    fun encode(e: Expect): JSONObject = when (e) {
        is Expect.AppIs -> JSONObject().put("app", e.app)
        is Expect.Shows -> JSONObject().put("shows", e.text)
        is Expect.Absent -> JSONObject().put("absent", e.text)
        is Expect.Control -> JSONObject().put("control", e.label)
        Expect.Focused -> JSONObject().put("focused", true)
        is Expect.FieldHas -> JSONObject().put("field_has", e.text)
        Expect.Changed -> JSONObject().put("changed", true)
        is Expect.Setting -> JSONObject().put("setting", e.key).put("value", e.value)
        is Expect.HasFact -> JSONObject().put("fact", e.name)
        is Expect.Described -> JSONObject().put("described", e.text)
        is Expect.All -> JSONObject().put("all", JSONArray(e.parts.map(::encode)))
    }

    /** One JSON object, as a plan line writes it; unknown shapes read as null (no postcondition), never a crash. */
    fun decode(j: JSONObject): Expect? = when {
        j.has("all") -> j.optJSONArray("all")?.let { a -> Expect.All((0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::decode) }) }
        j.has("app") -> Expect.AppIs(j.getString("app"))
        j.has("shows") -> Expect.Shows(j.getString("shows"))
        j.has("absent") -> Expect.Absent(j.getString("absent"))
        j.has("control") -> Expect.Control(j.getString("control"))
        j.has("focused") -> Expect.Focused
        j.has("field_has") -> Expect.FieldHas(j.getString("field_has"))
        j.has("changed") -> Expect.Changed
        j.has("setting") -> Expect.Setting(j.getString("setting"), j.optString("value"))
        j.has("fact") -> Expect.HasFact(j.getString("fact"))
        j.has("described") -> Expect.Described(j.getString("described"))
        else -> null
    }
}
