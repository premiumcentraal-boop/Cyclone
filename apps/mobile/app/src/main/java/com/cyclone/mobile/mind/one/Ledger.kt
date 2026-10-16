package com.cyclone.mobile.mind.one

import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 69 §3: every model call of a run, counted. There are no cost caps (owner's decision); the ledger is what the
 * card and Glass show, and what the Lab reads. Counts, tokens, cost and time only: never a prompt or an answer.
 */
data class LedgerEntry(
    val atMs: Long,
    /** "mind", "flash", "decisions", "luna", "pilot", "advisor". */
    val purpose: String,
    val model: String,
    val inputTokens: Int = 0,
    val cachedTokens: Int = 0,
    val outputTokens: Int = 0,
    val costUsd: Double = 0.0,
    val ms: Long = 0,
    val ok: Boolean = true,
)

class Ledger(entries: List<LedgerEntry> = emptyList()) {
    private val list = entries.toMutableList()

    @Synchronized fun add(entry: LedgerEntry) { list += entry; if (list.size > MAX) list.removeAt(0) }
    @Synchronized fun entries(): List<LedgerEntry> = list.toList()

    @Synchronized fun totalCost(): Double = list.sumOf { it.costUsd }
    @Synchronized fun calls(purpose: String? = null): Int = list.count { purpose == null || it.purpose == purpose }

    /** Per purpose: calls, tokens, cost. */
    @Synchronized fun summary(): JSONObject {
        val by = JSONObject()
        list.groupBy { it.purpose }.toSortedMap().forEach { (purpose, rows) ->
            by.put(purpose, JSONObject().put("calls", rows.size).put("input", rows.sumOf { it.inputTokens })
                .put("cached", rows.sumOf { it.cachedTokens }).put("output", rows.sumOf { it.outputTokens })
                .put("cost", round(rows.sumOf { it.costUsd })).put("failed", rows.count { !it.ok }))
        }
        return JSONObject().put("cost", round(totalCost())).put("calls", list.size).put("by", by)
    }

    @Synchronized fun encode(): JSONArray = JSONArray(list.map { e ->
        JSONObject().put("at", e.atMs).put("p", e.purpose).put("m", e.model).put("in", e.inputTokens).put("c", e.cachedTokens)
            .put("out", e.outputTokens).put("usd", e.costUsd).put("ms", e.ms).put("ok", e.ok)
    })

    companion object {
        const val MAX = 5_000

        fun decode(a: JSONArray?): Ledger = Ledger((0 until (a?.length() ?: 0)).mapNotNull { i ->
            a?.optJSONObject(i)?.let { o ->
                runCatching {
                    LedgerEntry(o.getLong("at"), o.getString("p"), o.optString("m"), o.optInt("in"), o.optInt("c"), o.optInt("out"),
                        o.optDouble("usd", 0.0), o.optLong("ms"), o.optBoolean("ok", true))
                }.getOrNull()
            }
        })

        private fun round(v: Double) = Math.round(v * 1_000_000.0) / 1_000_000.0
    }
}
