package com.cyclone.mobile.mind.one

import org.json.JSONObject

/**
 * Plan 69 §4.6: tools before taps. Steps that need no screen decision (open an app, a link or a settings page, Back,
 * Home, wait, timers, alarms, calendar, contacts, notifications, memory, Cyclone Ports) become direct tool calls on the
 * Mind's own toolbox, at rung 0. Arguments come from the plan, with `{fact}` placeholders filled from the run's facts
 * by code; a missing fact is never guessed.
 */
object ToolSteps {
    sealed interface Call {
        data class Ready(val name: String, val args: JSONObject) : Call
        data class Missing(val what: String) : Call
    }

    /** Tools the crew may call directly, with the arguments each accepts. */
    val DIRECT: Map<String, Set<String>> = mapOf(
        "open_app" to setOf("app"),
        "open_link" to setOf("url"),
        "open_settings" to setOf("page", "app"),
        "back" to emptySet(),
        "home" to emptySet(),
        "wait" to setOf("seconds", "until_text"),
        "set_timer" to setOf("hours", "minutes", "seconds", "label"),
        "set_alarm" to setOf("hour", "minute", "label"),
        "calendar_find" to setOf("from", "to", "query"),
        "calendar_add" to setOf("title", "start", "end", "duration_minutes", "all_day", "location", "notes", "reminder_minutes"),
        "contact_find" to setOf("query"),
        "notifications" to emptySet(),
        "recall" to setOf("topic"),
        "remember" to setOf("text"),
        "note" to setOf("text"),
        "port_send" to setOf("port", "text"),
        "port_wait" to setOf("port", "seconds"),
    )

    /** Tools that reach other people or devices: always a barrier. */
    val BARRIER_TOOLS = setOf("port_send", "calendar_add")

    private val INTEGER = setOf("hours", "minutes", "seconds", "hour", "minute", "duration_minutes", "reminder_minutes")
    private val BOOLEAN = setOf("all_day")
    private val PLACEHOLDER = Regex("\\{([a-z][a-z0-9_]{0,40})\\}")

    /** The call for a direct step, or null when the step needs a screen decision. */
    fun direct(step: Step, score: Score): Call? {
        val allowed = DIRECT[step.action] ?: return null
        val args = JSONObject()
        for ((key, raw) in step.args) {
            if (key !in allowed) continue
            val value = fill(raw, score) ?: return Call.Missing(missing(raw, score) ?: key)
            when (key) {
                in INTEGER -> value.trim().toIntOrNull()?.let { args.put(key, it) } ?: return Call.Missing("a number for $key")
                in BOOLEAN -> args.put(key, value.trim().equals("true", true))
                else -> args.put(key, value)
            }
        }
        for ((key, factName) in step.slots) {
            if (key !in allowed) continue
            val fact = score.fact(factName) ?: return Call.Missing("the $factName")
            args.put(key, fact.value)
        }
        return Call.Ready(step.action, args)
    }

    /** The text a typing step types: its `text` argument with facts filled in, or its `text` slot. Null when missing. */
    fun text(step: Step, score: Score): String? {
        step.slots["text"]?.let { name -> return score.fact(name)?.value }
        return step.args["text"]?.let { fill(it, score) }
    }

    /** [raw] with every `{fact}` replaced by that fact's exact value; null if any fact is missing. */
    fun fill(raw: String, score: Score): String? {
        var missing = false
        val out = PLACEHOLDER.replace(raw) { m -> score.fact(m.groupValues[1])?.value ?: run { missing = true; m.value } }
        return if (missing) null else out
    }

    private fun missing(raw: String, score: Score): String? =
        PLACEHOLDER.findAll(raw).map { it.groupValues[1] }.firstOrNull { score.fact(it) == null }?.let { "the $it" }
}
