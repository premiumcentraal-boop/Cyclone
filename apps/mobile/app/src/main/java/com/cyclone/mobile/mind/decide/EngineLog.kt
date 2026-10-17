package com.cyclone.mobile.mind.decide

import org.json.JSONArray
import org.json.JSONObject

/** One decision model's part in a race: how long, whether it answered usably, and what it chose. No request text. */
data class LaneRecord(
    val lane: String,
    val ms: Long,
    val answered: Boolean,
    /** "instant", "flash", "mind" or "ignore", when it answered. */
    val mode: String? = null,
    /** The Instant action it chose ("camera:", "open_app:com.instagram.android"), when it chose one. */
    val action: String? = null,
    /** Plan 65: which phrasing of the question this answer came from (0 or 1). */
    val copy: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("lane", lane).put("ms", ms).put("answered", answered)
        .put("mode", mode ?: JSONObject.NULL).put("action", action ?: JSONObject.NULL).put("copy", copy)

    companion object {
        fun fromJson(o: JSONObject): LaneRecord? = runCatching {
            LaneRecord(o.getString("lane"), o.getLong("ms"), o.getBoolean("answered"),
                o.optString("mode").takeIf { it.isNotBlank() && !o.isNull("mode") },
                o.optString("action").takeIf { it.isNotBlank() && !o.isNull("action") }, o.optInt("copy", 0))
        }.getOrNull()
    }
}

/**
 * Plan 63 (alpha.136): one engine decision as the numbers see it: which engine, each lane's part, the rung chosen,
 * whether the two models agreed (null when fewer than two answered), and which lane acted alone first (Sprint).
 */
data class EngineRecord(
    val atMs: Long,
    val engine: String,
    val lanes: List<LaneRecord>,
    val chosen: String,
    val agreed: Boolean?,
    val early: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("atMs", atMs).put("engine", engine)
        .put("lanes", JSONArray(lanes.map { it.toJson() })).put("chosen", chosen)
        .put("agreed", agreed ?: JSONObject.NULL).put("early", early ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): EngineRecord? = runCatching {
            val lanes = o.getJSONArray("lanes").let { a -> (0 until a.length()).mapNotNull { LaneRecord.fromJson(a.getJSONObject(it)) } }
            EngineRecord(o.getLong("atMs"), o.getString("engine"), lanes, o.getString("chosen"),
                if (o.isNull("agreed")) null else o.getBoolean("agreed"),
                o.optString("early").takeIf { it.isNotBlank() && !o.isNull("early") })
        }.getOrNull()
    }
}

/** The engine log as bounded text, and its numbers. Pure. */
object EngineLog {
    const val MAX_KEPT = 1_000

    fun encode(records: List<EngineRecord>): String = records.takeLast(MAX_KEPT).joinToString("\n") { it.toJson().toString() }

    fun decode(text: String?): List<EngineRecord> = text.orEmpty().lineSequence().filter { it.isNotBlank() }
        .mapNotNull { runCatching { EngineRecord.fromJson(JSONObject(it)) }.getOrNull() }.toList().takeLast(MAX_KEPT)

    /**
     * Per engine: decisions, how often both models answered, how often they agreed, how often one acted first, the rungs
     * chosen; per model: the answer rate, p50/p95, how often it was the first to answer and how often its two
     * phrasings agreed.
     */
    fun summary(records: List<EngineRecord>): JSONObject {
        val engines = JSONObject()
        records.groupBy { it.engine }.toSortedMap().forEach { (engine, list) ->
            val both = list.filter { r -> r.lanes.filter { it.answered }.map { it.lane }.toSet().size >= 2 }
            val compared = list.filter { it.agreed != null }
            val chosen = JSONObject()
            list.groupingBy { it.chosen }.eachCount().toSortedMap().forEach { (k, v) -> chosen.put(k, v) }
            engines.put(engine, JSONObject()
                .put("decisions", list.size)
                .put("bothAnswered", share(both.size, list.size))
                .put("agreement", share(compared.count { it.agreed == true }, compared.size))
                .put("earlyActs", list.count { it.early != null })
                .put("chosen", chosen))
        }
        val lanes = JSONObject()
        val all = records.flatMap { it.lanes }
        all.groupBy { it.lane }.toSortedMap().forEach { (lane, list) ->
            val ok = list.filter { it.answered }.map { it.ms }
            val firsts = records.count { r -> r.lanes.filter { it.answered }.minByOrNull { it.ms }?.lane == lane }
            // Plan 65: when one model answered both phrasings, how often it gave the same answer to itself.
            val twice = records.map { r -> r.lanes.filter { it.lane == lane && it.answered } }.filter { it.size >= 2 }
            val steady = twice.count { copies -> copies.map { it.mode to it.action }.toSet().size == 1 }
            lanes.put(lane, JSONObject()
                .put("selfAgreement", share(steady, twice.size))
                .put("asked", list.size)
                .put("answerRate", share(ok.size, list.size))
                .put("p50", DecisionStats.percentile(ok, 50) ?: JSONObject.NULL)
                .put("p95", DecisionStats.percentile(ok, 95) ?: JSONObject.NULL)
                .put("firstShare", share(firsts, records.size)))
        }
        return JSONObject().put("engines", engines).put("lanes", lanes)
    }

    private fun share(part: Int, whole: Int): Any = if (whole == 0) JSONObject.NULL else Math.round(part * 1000.0 / whole) / 1000.0
}
