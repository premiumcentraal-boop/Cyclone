package com.cyclone.mobile.mind.one

import com.cyclone.mobile.mind.decide.Replay
import com.cyclone.mobile.mind.mission.MindRedaction
import org.json.JSONArray
import org.json.JSONObject

/** A verified run's plan, kept so the same request starts on it with no planning call (plan 69 §10). */
data class Recipe(val key: String, val goal: String, val plan: Plan, val runs: Int, val updatedAtMs: Long)

/**
 * Plan 69 §14 checkpoint 3: recipes, extending plan 65's [Replay] from one action to a whole plan.
 *
 * - A recipe is compiled only from a run that **passed its done check**, and never from a run the mind drove at the
 *   wheel (the plan would not hold what it did).
 * - It is found by the owner's own words, normalised as [Replay.keyOf] does, and starts automatically.
 * - The steps that ran are kept; steps the run skipped are kept only when a condition decided it (a branch), so the
 *   recipe still skips them when the condition holds and still does them when it doesn't.
 * - Approvals are never carried forward: a plan holds no approval, and every gated step stays gated.
 * - Any miss falls back to the mind, and the run that follows rewrites the recipe (or drops it if it fails).
 *
 * Pure: a book of recipes and its JSON.
 */
object Recipes {
    const val MAX = 200

    fun key(goal: String): String = Replay.keyOf(goal)

    /** The recipe a finished run leaves, or null when it may not leave one. */
    fun compile(score: Score, end: RunEnd, atMs: Long, previous: Recipe?): Recipe? {
        if (end != RunEnd.DONE) return null
        if (score.events.any { it.kind == "wheel" }) return null
        val plan = score.plan ?: return null
        val milestones = plan.milestones.mapNotNull { m ->
            val steps = m.steps.filter { it.state == StepState.DONE || it.state == StepState.SKIPPED && it.branches.isNotEmpty() }
                .map { s -> s.copy(state = StepState.TODO, note = null, args = s.args.mapValues { (_, v) -> MindRedaction.scrubText(v) }) }
            if (steps.isEmpty()) null else m.copy(state = MilestoneState.TODO, steps = steps)
        }
        if (milestones.isEmpty()) return null
        val k = key(score.goals.first().text)
        if (k.isBlank()) return null
        return Recipe(k, score.goals.first().text.take(300), Plan(1, milestones, plan.done), (previous?.runs ?: 0) + 1, atMs)
    }

    /** What a finished run does to the book: a new or rewritten recipe, or the old one dropped after a miss. */
    fun after(book: Map<String, Recipe>, score: Score, end: RunEnd, atMs: Long, usedRecipe: Boolean): Map<String, Recipe> {
        // A steer changed the request: the run says nothing about the words it started from.
        if (score.goals.size > 1) return book
        val k = key(score.goals.first().text)
        val next = compile(score, end, atMs, book[k])
        return when {
            next != null -> (book - k + (k to next)).entries.sortedByDescending { it.value.updatedAtMs }.take(MAX).associate { it.key to it.value }
            usedRecipe || end == RunEnd.FAILED || end == RunEnd.UNCONFIRMED -> book - k
            else -> book
        }
    }

    fun encode(book: Map<String, Recipe>): JSONArray = JSONArray(book.values.map { r ->
        JSONObject().put("key", r.key).put("goal", r.goal).put("plan", ScoreCodec.plan(r.plan)).put("runs", r.runs).put("at", r.updatedAtMs)
    })

    fun decode(a: JSONArray?): Map<String, Recipe> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
        val o = a!!.optJSONObject(i) ?: return@mapNotNull null
        val plan = o.optJSONObject("plan")?.let { ScoreCodec.plan(it) } ?: return@mapNotNull null
        val k = o.optString("key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        k to Recipe(k, o.optString("goal"), plan, o.optInt("runs", 1), o.optLong("at"))
    }.toMap()
}
