package com.cyclone.mobile.mind.decide

import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 58 (alpha.123): what the watch saw for one request. The acting decision, and beside it what the watching
 * provider answered: Board 0 (route, intent, target) and the Triage reading (rung, difficulty, action, raised flags,
 * slot plan). Never acted on. Kept on this phone; the health report carries the counts only. No request text.
 */
data class WatchRecord(
    val atMs: Long,
    val provider: String,
    /** "ok", a failure's wire name, or "skipped" (the breaker was open). */
    val outcome: String,
    val ms: Long,
    val actedBy: Decider,
    val acted: Guess,
    val board0: Guess? = null,
    val triage: TriageRecord? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("atMs", atMs).put("provider", provider).put("outcome", outcome).put("ms", ms)
        .put("actedBy", actedBy.name).put("acted", acted.toJson()).put("board0", board0?.toJson() ?: JSONObject.NULL)
        .put("triage", triage?.toJson() ?: JSONObject.NULL)

    companion object {
        fun fromJson(value: JSONObject): WatchRecord? = runCatching {
            WatchRecord(value.getLong("atMs"), value.getString("provider"), value.getString("outcome"), value.optLong("ms"),
                Decider.valueOf(value.getString("actedBy")), Guess.fromJson(value.optJSONObject("acted")) ?: return null,
                Guess.fromJson(value.optJSONObject("board0")), value.optJSONObject("triage")?.let { TriageRecord.fromJson(it) })
        }.getOrNull()
    }
}

/** The Triage reading as recorded: no text, only the typed answers. */
data class TriageRecord(
    val mode: String,
    val difficulty: Double?,
    val capability: String?,
    val capabilityConfidence: Double,
    val raised: List<String>,
    val steps: List<String>,
    val refused: Int,
) {
    fun toJson(): JSONObject = JSONObject().put("mode", mode).put("difficulty", difficulty ?: JSONObject.NULL)
        .put("capability", capability ?: JSONObject.NULL).put("capabilityConfidence", capabilityConfidence)
        .put("raised", JSONArray(raised)).put("steps", JSONArray(steps)).put("refused", refused)

    companion object {
        fun fromJson(value: JSONObject): TriageRecord? = runCatching {
            fun list(key: String) = value.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            TriageRecord(value.getString("mode"), value.optDouble("difficulty", Double.NaN).takeIf { !it.isNaN() },
                value.optString("capability").takeIf { it.isNotBlank() && !value.isNull("capability") },
                value.optDouble("capabilityConfidence", 0.0), list("raised"), list("steps"), value.optInt("refused"))
        }.getOrNull()
    }
}

/** The watch log as bounded text, and the numbers the Lab gate reads. Pure. */
object WatchLog {
    const val MAX_KEPT = 1_000

    fun encode(records: List<WatchRecord>): String = records.takeLast(MAX_KEPT).joinToString("\n") { it.toJson().toString() }

    fun decode(text: String?): List<WatchRecord> = text.orEmpty().lineSequence().filter { it.isNotBlank() }
        .mapNotNull { runCatching { WatchRecord.fromJson(JSONObject(it)) }.getOrNull() }.toList().takeLast(MAX_KEPT)

    /** Instant below Flash below the Mind; an answer or an ignore is outside the ladder. */
    fun rank(mode: String): Int? = when (mode) { "instant" -> 0; "flash" -> 1; "mind" -> 2; else -> null }

    /**
     * The gate's numbers (plan 58 §11.4): how often the watching provider answered; how often its Board 0 agreed with the
     * acting provider's; how often Triage's rung matched the route taken; and the one number that must stay at zero:
     * Triage putting a request **lower** than the rules did (the rules send writing, money, deleting and accounts up).
     */
    fun summary(records: List<WatchRecord>): JSONObject {
        val answered = records.filter { it.outcome == "ok" }
        val board0 = answered.filter { it.board0 != null && it.actedBy == Decider.DECISIONS }
        val triaged = answered.mapNotNull { r -> r.triage?.let { r to it } }
        val laddered = triaged.filter { (r, t) -> rank(r.acted.mode) != null && rank(t.mode) != null }
        val underRules = triaged.count { (r, t) ->
            r.actedBy == Decider.RULES && rank(t.mode) != null && rank(r.acted.mode) != null && rank(t.mode)!! < rank(r.acted.mode)!!
        }
        val modes = JSONObject()
        triaged.groupBy { it.second.mode }.toSortedMap().forEach { (mode, l) -> modes.put(mode, l.size) }
        val chains = triaged.count { it.second.steps.size >= 2 }
        return JSONObject()
            .put("providers", JSONArray(records.map { it.provider }.distinct().sorted()))
            .put("watched", records.size)
            .put("answerRate", share(answered.size, records.size))
            .put("board0Agreement", share(board0.count { it.board0!!.agrees(it.acted) }, board0.size))
            .put("board0Compared", board0.size)
            .put("triaged", triaged.size)
            .put("triageRungAgreement", share(laddered.count { (r, t) -> r.acted.mode == t.mode }, laddered.size))
            .put("triageBelowRules", underRules)
            .put("triageModes", modes)
            .put("triageChains", chains)
            .put("triageRefusals", triaged.sumOf { it.second.refused })
    }

    private fun share(part: Int, whole: Int): Any = if (whole == 0) JSONObject.NULL else Math.round(part * 1000.0 / whole) / 1000.0
}
