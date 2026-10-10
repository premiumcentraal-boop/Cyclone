package com.cyclone.mobile.mind.lab

import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 58 §11.3 (alpha.123): the decisions golden set, labelled requests every routing change is scored against.
 * Built by `scripts/dev/golden_build.py` into `assets/decisions/golden_v1.jsonl`. Pure: parsing and scoring; the caller
 * supplies the router (the offline scorer in CI with recorded answers, or the phone's Lab with live ones).
 */
data class GoldenRequest(
    val id: String,
    val text: String,
    val lang: String,
    /** Where it belongs: instant, flash, mind, answer or ignore. */
    val rung: String,
    /** Where it may fairly go (its own rung, or one higher when it is borderline). */
    val accept: List<String>,
    val capability: String? = null,
    val target: String? = null,
    val risks: List<String> = emptyList(),
    val steps: Int? = null,
    val tag: String = "",
)

/** How one routing compared with the labels. */
enum class GoldenVerdict {
    /** Routed to an accepted rung. */
    RIGHT,
    /** Routed higher than accepted: safe, but slower than it should be. */
    OVER,
    /** Routed lower than accepted, or dropped (answered or ignored when it was a task). The dangerous direction. */
    UNDER,
    /** The router made no decision (no model in an offline run, or no answer). */
    UNDECIDED,
}

data class GoldenScore(
    val total: Int,
    val verdicts: Map<GoldenVerdict, Int>,
    /** UNDER on a request labelled with a risk flag. The gate: this must be zero. */
    val riskyUnder: List<String>,
    val under: List<String>,
    val byTag: Map<String, Map<GoldenVerdict, Int>>,
) {
    fun count(v: GoldenVerdict): Int = verdicts[v] ?: 0
    val decided: Int get() = total - count(GoldenVerdict.UNDECIDED)
    /** Right among the decided requests. */
    val accuracy: Double? get() = if (decided == 0) null else count(GoldenVerdict.RIGHT).toDouble() / decided

    fun toJson(): JSONObject = JSONObject()
        .put("total", total).put("decided", decided).put("accuracy", accuracy?.let { Math.round(it * 1000) / 1000.0 } ?: JSONObject.NULL)
        .put("verdicts", JSONObject().also { o -> GoldenVerdict.entries.forEach { o.put(it.name.lowercase(), count(it)) } })
        .put("riskyUnder", JSONArray(riskyUnder)).put("under", JSONArray(under))
        .put("byTag", JSONObject().also { o ->
            byTag.toSortedMap().forEach { (tag, v) -> o.put(tag, JSONObject().also { t -> v.forEach { (k, n) -> t.put(k.name.lowercase(), n) } }) }
        })
}

object GoldenSet {
    const val ASSET = "decisions/golden_v1.jsonl"

    /**
     * The phone the golden set is labelled against: a fixed, synthetic screen and app list, so a score never depends on
     * (or carries) what is on the owner's phone.
     */
    val WORLD = com.cyclone.mobile.mind.modes.GrammarWorld(
        labels = listOf("Blue button", "First post", "Follow", "Message", "Share"),
        apps = listOf("Camera", "Photos", "Instagram", "WhatsApp", "Gmail", "Spotify", "YouTube", "Maps", "Settings", "Chrome",
            "Netflix", "Calendar", "Clock", "Phone", "Messages", "Play Store", "Files", "ING", "Amazon", "TikTok")
            .map { it to "app.${it.lowercase().replace(' ', '_')}" },
    )

    /** Requests settled on the phone before any decision is asked (calls, clocks, the phone's own answers). */
    val ON_PHONE_TAGS = setOf("call", "clock", "local")
    private val RUNGS = setOf("instant", "flash", "mind", "answer", "ignore")

    fun parse(text: String): List<GoldenRequest> = text.lineSequence().filter { it.isNotBlank() }.map { line ->
        val o = JSONObject(line)
        fun list(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        GoldenRequest(o.getString("id"), o.getString("text"), o.getString("lang"), o.getString("rung"), list("accept"),
            o.optString("capability").takeIf { it.isNotBlank() }, o.optString("target").takeIf { it.isNotBlank() },
            list("risks"), o.optInt("steps", -1).takeIf { it > 0 }, o.optString("tag"))
    }.toList().also { requests ->
        require(requests.map { it.id }.toSet().size == requests.size) { "duplicate ids" }
        requests.forEach { r -> require(r.rung in RUNGS && r.rung in r.accept && r.accept.all { it in RUNGS }) { "bad labels on ${r.id}" } }
    }

    private fun rank(rung: String): Int? = when (rung) { "instant" -> 0; "flash" -> 1; "mind" -> 2; else -> null }

    fun verdict(request: GoldenRequest, routed: String?): GoldenVerdict {
        routed ?: return GoldenVerdict.UNDECIDED
        if (routed in request.accept) return GoldenVerdict.RIGHT
        val got = rank(routed)
        val lowest = request.accept.mapNotNull { rank(it) }.minOrNull()
        return when {
            // Answered or ignored when it was a task: the request is dropped.
            got == null -> GoldenVerdict.UNDER
            // A task when it was chatter or a local answer: more work than needed, never harmful.
            lowest == null -> GoldenVerdict.OVER
            got < lowest -> GoldenVerdict.UNDER
            else -> GoldenVerdict.OVER
        }
    }

    /** Scores [requests]; [route] returns the rung a router chose, or null when it made no decision. */
    fun score(requests: List<GoldenRequest>, route: (GoldenRequest) -> String?): GoldenScore {
        val results = requests.map { it to verdict(it, route(it)) }
        val byTag = results.groupBy { it.first.tag }.mapValues { (_, l) -> l.groupingBy { it.second }.eachCount() }
        return GoldenScore(
            total = requests.size,
            verdicts = results.groupingBy { it.second }.eachCount(),
            riskyUnder = results.filter { (r, v) -> v == GoldenVerdict.UNDER && r.risks.isNotEmpty() }.map { it.first.id },
            under = results.filter { it.second == GoldenVerdict.UNDER }.map { it.first.id },
            byTag = byTag,
        )
    }
}
