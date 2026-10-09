package com.cyclone.mobile.mind.decide

import com.cyclone.mobile.decisions.DecisionsResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 58: one decisions call as the stats see it: which provider and board, how long, and how it ended. Counts, times
 * and cost only: never the request, the answer or the key.
 */
data class CallRecord(
    val atMs: Long,
    val provider: String,
    /** "board0", "triage", "instant", "pilot", "watch". */
    val board: String,
    val ms: Long,
    /** "ok" or a [com.cyclone.mobile.decisions.DecisionsFailure] wire name. */
    val outcome: String,
    val status: Int? = null,
    val cost: Double? = null,
) {
    val ok: Boolean get() = outcome == OK

    fun toJson(): JSONObject = JSONObject().put("atMs", atMs).put("provider", provider).put("board", board).put("ms", ms)
        .put("outcome", outcome).put("status", status ?: JSONObject.NULL).put("cost", cost ?: JSONObject.NULL)

    companion object {
        const val OK = "ok"

        fun of(atMs: Long, provider: String, board: String, result: DecisionsResult): CallRecord = when (result) {
            is DecisionsResult.Ok -> CallRecord(atMs, provider, board, result.ms, OK, 200, costOf(result.body))
            is DecisionsResult.Failed -> CallRecord(atMs, provider, board, result.ms, result.failure.wire, result.status)
        }

        private fun costOf(body: String): Double? = runCatching {
            JSONObject(body).optJSONObject("usage")?.optDouble("cost", Double.NaN)?.takeIf { !it.isNaN() }
        }.getOrNull()

        fun fromJson(value: JSONObject): CallRecord? = runCatching {
            CallRecord(value.getLong("atMs"), value.getString("provider"), value.getString("board"), value.getLong("ms"),
                value.getString("outcome"), value.optInt("status", -1).takeIf { it >= 0 && !value.isNull("status") },
                value.optDouble("cost", Double.NaN).takeIf { !it.isNaN() })
        }.getOrNull()
    }
}

/** The call log as bounded text, and its numbers per provider and board. Pure. */
object CallLog {
    const val MAX_KEPT = 1_000

    fun encode(records: List<CallRecord>): String = records.takeLast(MAX_KEPT).joinToString("\n") { it.toJson().toString() }

    fun decode(text: String?): List<CallRecord> = text.orEmpty().lineSequence().filter { it.isNotBlank() }
        .mapNotNull { runCatching { CallRecord.fromJson(JSONObject(it)) }.getOrNull() }.toList().takeLast(MAX_KEPT)

    /** Per provider, per board: calls, answer rate, p50/p95 of answered calls, failures by kind, total cost. */
    fun summary(records: List<CallRecord>): JSONArray = JSONArray().also { out ->
        records.groupBy { it.provider to it.board }.toSortedMap(compareBy({ it.first }, { it.second })).forEach { (key, list) ->
            val ok = list.filter { it.ok }
            val failures = JSONObject()
            list.filterNot { it.ok }.groupBy { it.outcome }.toSortedMap().forEach { (kind, l) -> failures.put(kind, l.size) }
            out.put(JSONObject()
                .put("provider", key.first).put("board", key.second).put("calls", list.size)
                .put("answerRate", Math.round(ok.size * 1000.0 / list.size) / 1000.0)
                .put("p50", DecisionStats.percentile(ok.map { it.ms }, 50) ?: JSONObject.NULL)
                .put("p95", DecisionStats.percentile(ok.map { it.ms }, 95) ?: JSONObject.NULL)
                .put("failures", failures)
                .put("cost", Math.round(list.sumOf { it.cost ?: 0.0 } * 1_000_000) / 1_000_000.0))
        }
    }
}
