package com.cyclone.mobile.mind.modes

import com.cyclone.mobile.mind.one.LedgerEntry
import org.json.JSONObject

/**
 * Plan 75 §3.6 (alpha 145): one record per request of where its time and money went, the same shape for Instant,
 * Flash and Mind. Stage times, every model call by the rung that made it (router, step, done, plan, consult…), how the
 * crew's moves were decided (code or the decision models, how often the two disagreed), the mind's wakes, recipe use and
 * questions to the owner.
 *
 * Numbers and fixed names only: never the request, screen text, a typed value or a model's answer. Ask status, the
 * mission record and the Lab read it. Pure.
 */
object RunRecord {
    /** How the crew's moves were decided in one run (StepEngine counts them). */
    data class Moves(
        /** Moves code settled alone: a direct tool or a single matching control. */
        val byCode: Int = 0,
        /** Moves the decision models were asked about (one fused call each). */
        val asked: Int = 0,
        /** Calls where the two decision models picked different things. */
        val splits: Int = 0,
        /** Calls whose own cross-checks withheld the pick (the reversed order, a rival). */
        val doubts: Int = 0,
        /** Calls no decision model answered in time. */
        val noAnswer: Int = 0,
        val reads: Int = 0,
        /** Plan 75 alpha 146: reads not taken because the read after the last move was still fresh. */
        val reused: Int = 0,
        /** Plan 75 alpha 146: follow-up questions asked on a split, and how many of them settled the move. */
        val settles: Int = 0,
        val settled: Int = 0,
        val readMs: Long = 0,
        val decideMs: Long = 0,
        val actMs: Long = 0,
    ) {
        fun json(): JSONObject = JSONObject().put("byCode", byCode).put("asked", asked).put("splits", splits).put("doubts", doubts)
            .put("noAnswer", noAnswer).put("reads", reads).put("reused", reused).put("settles", settles)
            .put("settled", settled).put("readMs", readMs).put("decideMs", decideMs).put("actMs", actMs)
    }

    /** One model call, from a run's ledger or the router's tally. */
    data class Call(val rung: String, val provider: String, val ms: Long, val inputTokens: Int, val costUsd: Double, val ok: Boolean) {
        companion object {
            /** A ledger row: the rung is its stage ("step", "done", "plan", "consult"…), else its purpose. */
            fun of(e: LedgerEntry) = Call(e.stage ?: e.purpose, e.model, e.ms, e.inputTokens, e.costUsd, e.ok)
        }
    }

    /** The calls by rung: count, failures, time (p50 and the slowest) and cost. */
    fun calls(calls: List<Call>): JSONObject {
        val by = JSONObject()
        calls.groupBy { it.rung }.toSortedMap().forEach { (rung, rows) ->
            val ms = rows.map { it.ms }.sorted()
            by.put(rung, JSONObject().put("calls", rows.size).put("failed", rows.count { !it.ok })
                .put("p50Ms", ms[(ms.size - 1) / 2]).put("maxMs", ms.last())
                .put("inputTokens", rows.sumOf { it.inputTokens }).put("costUsd", usd(rows.sumOf { it.costUsd })))
        }
        return JSONObject().put("count", calls.size).put("costUsd", usd(calls.sumOf { it.costUsd })).put("byRung", by)
    }

    /** The whole record. [stages] are fixed stage names with their time; [wakes] counts the mind's wakes by kind. */
    fun build(
        totalMs: Long,
        stages: List<Pair<String, Long>> = emptyList(),
        calls: List<Call> = emptyList(),
        moves: Moves? = null,
        wakes: Map<String, Int> = emptyMap(),
        recipe: Boolean = false,
        asks: Int = 0,
        end: String? = null,
    ): JSONObject {
        val stageJson = JSONObject()
        stages.groupBy({ it.first }, { it.second }).forEach { (name, times) -> stageJson.put(name, times.sum()) }
        return JSONObject()
            .put("totalMs", totalMs)
            .put("stages", stageJson)
            .put("calls", calls(calls))
            .put("moves", moves?.json() ?: JSONObject.NULL)
            .put("wakes", JSONObject(wakes.toSortedMap() as Map<*, *>))
            .put("recipe", recipe)
            .put("asks", asks)
            .put("end", end ?: JSONObject.NULL)
    }

    /** Joins a request's own part (routing, Instant) with the mission it became: times add up, calls are listed together. */
    fun merge(request: JSONObject?, mission: JSONObject?): JSONObject? {
        if (request == null) return mission
        if (mission == null) return request
        val out = JSONObject(mission.toString())
        out.put("totalMs", request.optLong("totalMs") + mission.optLong("totalMs"))
        val stages = out.optJSONObject("stages") ?: JSONObject()
        request.optJSONObject("stages")?.let { s -> s.keys().forEach { k -> stages.put(k, stages.optLong(k) + s.optLong(k)) } }
        out.put("stages", stages)
        val a = request.optJSONObject("calls"); val b = mission.optJSONObject("calls")
        val by = JSONObject(b?.optJSONObject("byRung")?.toString() ?: "{}")
        a?.optJSONObject("byRung")?.let { r -> r.keys().forEach { k -> if (!by.has(k)) by.put(k, r.getJSONObject(k)) } }
        out.put("calls", JSONObject().put("count", (a?.optInt("count") ?: 0) + (b?.optInt("count") ?: 0))
            .put("costUsd", usd((a?.optDouble("costUsd", 0.0) ?: 0.0) + (b?.optDouble("costUsd", 0.0) ?: 0.0))).put("byRung", by))
        return out
    }

    /** Wake kinds from a Score's events ("Plan: …", "Consult: …"). */
    fun wakes(lines: List<String>): Map<String, Int> =
        lines.mapNotNull { l -> l.substringBefore(':', "").trim().lowercase().takeIf { it in WAKE_KINDS } }.groupingBy { it }.eachCount()

    private val WAKE_KINDS = setOf("plan", "replan", "consult", "glance", "wheel")

    private fun usd(v: Double) = Math.round(v * 1_000_000.0) / 1_000_000.0
}

/**
 * Plan 75: the decision calls a request makes while it is being routed and run as Instant (Decisions.call adds to it
 * while one is open). One request is routed at a time; a call that lands after the tally closed is not counted here
 * (it is still in the call log).
 */
class CallTally {
    private val list = java.util.concurrent.ConcurrentLinkedQueue<RunRecord.Call>()
    @Volatile var open = true
        private set

    /** At most [MAX] calls: a tally a crashed run never closed can't grow without end. */
    fun add(call: RunRecord.Call) { if (open && list.size < MAX) list += call }
    fun close(): List<RunRecord.Call> { open = false; return list.toList() }

    companion object {
        const val MAX = 200
        @Volatile var current: CallTally? = null
    }
}
