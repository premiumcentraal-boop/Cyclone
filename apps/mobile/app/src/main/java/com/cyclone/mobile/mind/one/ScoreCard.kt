package com.cyclone.mobile.mind.one

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Plan 69 §10: what the owner sees of a run, built by code from the Score and the ledger: milestones with their state,
 * the current step line, each wake and why, and the cost so far. The same summary goes to the PC and Glass, as counts
 * and lines only, never screen text. Pure.
 */
object ScoreCard {
    /** Milestone titles for the card: "✓" done, "✗" failed, the rest as they are. */
    fun milestones(score: Score): List<String> = score.plan?.milestones.orEmpty().map { m ->
        when (m.state) {
            MilestoneState.DONE -> "✓ ${m.title}"
            MilestoneState.FAILED -> "✗ ${m.title}"
            else -> m.title
        }.take(80)
    }

    fun index(score: Score): Int {
        val p = score.plan ?: return 0
        val i = p.milestones.indexOfFirst { it.state == MilestoneState.RUNNING || it.state == MilestoneState.TODO }
        return if (i < 0) (p.milestones.size - 1).coerceAtLeast(0) else i
    }

    /** "Tapping "Send"", "Opening WhatsApp": the step the crew is on, from the plan. */
    fun stepLine(score: Score): String? {
        val step = score.plan?.current()?.second ?: return null
        val target = step.target?.let { "\"${it.take(40)}\"" }
        return when (step.action) {
            "tap" -> "Tapping ${target ?: "the next control"}"
            "type_text" -> "Typing into ${target ?: "the field"}"
            "long_press" -> "Pressing ${target ?: "and holding"}"
            "scroll" -> "Scrolling"
            "open_app" -> "Opening ${step.args["app"] ?: "the app"}"
            "open_link" -> "Opening a link"
            "open_settings" -> "Opening Settings"
            "back" -> "Going back"
            "home" -> "Going home"
            "wait" -> "Waiting"
            "port_send" -> "Sending to your PC"
            "port_wait" -> "Waiting for your PC"
            else -> step.action.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.ROOT) } + (target?.let { " $it" }.orEmpty())
        }
    }

    fun wakes(score: Score): List<String> = score.events.filter { it.kind == "wake" }.map { it.line }

    fun cost(usd: Double): String = if (usd < 0.01) "<$0.01" else String.format(Locale.ROOT, "$%.2f", usd)

    /** One line for the card: the step (or the last wake), the wakes and the cost. */
    fun line(score: Score, ledger: Ledger): String {
        val last = score.events.lastOrNull()
        val now = if (last?.kind == "wake") "Thinking: ${last.line.substringAfter(": ").take(90)}" else stepLine(score) ?: "Working"
        val wakes = wakes(score).size
        return "$now · ${if (wakes == 1) "1 wake" else "$wakes wakes"} · ${cost(ledger.totalCost())}"
    }

    /** For the PC's task feed and Glass: counts and lines, never screen text. */
    fun summary(score: Score, ledger: Ledger): JSONObject = JSONObject()
        .put("difficulty", score.difficulty.name.lowercase())
        .put("goalVersion", score.goal.version)
        .put("planVersion", score.plan?.version ?: 0)
        .put("milestones", JSONArray(score.plan?.milestones.orEmpty().take(12).map { m ->
            JSONObject().put("title", m.title.take(90)).put("state", m.state.name.lowercase())
        }))
        .put("step", stepLine(score) ?: JSONObject.NULL)
        .put("wakes", wakes(score).size)
        .put("wakeLines", JSONArray(wakes(score).takeLast(5).map { it.take(160) }))
        .put("calls", ledger.calls())
        .put("costUsd", Math.round(ledger.totalCost() * 10_000.0) / 10_000.0)

    /** The end report (plan 69 §10), built by code: what was done and what was found by name. */
    fun report(score: Score, end: RunEnd, summary: String): String = when (end) {
        RunEnd.DONE -> summary
        RunEnd.UNCONFIRMED -> summary.takeIf { it.startsWith("Couldn't confirm") } ?: "Couldn't confirm: please check. $summary"
        else -> summary
    }
}

/** The latest card summary of each running OneMind task, for the PC feeds (by task id). */
object OneMindLive {
    private val live = ConcurrentHashMap<String, JSONObject>()
    fun put(taskId: String, summary: JSONObject) { live[taskId] = summary }
    fun get(taskId: String?): JSONObject? = taskId?.let { live[it] }
    fun clear(taskId: String) { live.remove(taskId) }
}
