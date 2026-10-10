package com.cyclone.mobile.mind.lab

import com.cyclone.mobile.mind.decide.DecisionStats
import org.json.JSONArray
import org.json.JSONObject

/** One golden request as a live provider answered it in the decisions Lab. */
data class LabSample(
    val request: GoldenRequest,
    /** The rung Triage's rules chose from the provider's answers, or null when there was no answer. */
    val rung: String?,
    val ms: Long,
    /** A failure's wire name, "unreadable", or null when answered. */
    val failure: String? = null,
    val cost: Double? = null,
    val capability: String? = null,
    val capabilityConfidence: Double = 0.0,
)

/**
 * Plan 58 §11.3 (alpha.123): the decisions Lab's report for one provider. The golden set score (with the one number
 * that must be zero: risky requests routed too low), speed, failures, cost, and calibration: when the provider says it
 * is 90% sure of the action, is it right 90% of the time? Counts and ids only, no request text. Pure.
 */
object DecisionLabReport {
    fun build(provider: String, model: String, atMs: Long, samples: List<LabSample>): JSONObject {
        val score = GoldenSet.score(samples.map { it.request }) { r -> samples.first { it.request.id == r.id }.rung }
        val answered = samples.filter { it.failure == null && it.rung != null }
        val failures = JSONObject()
        samples.mapNotNull { it.failure }.groupingBy { it }.eachCount().toSortedMap().forEach { (k, v) -> failures.put(k, v) }
        return JSONObject()
            .put("provider", provider).put("model", model).put("atMs", atMs)
            .put("requests", samples.size).put("answered", answered.size)
            .put("p50", DecisionStats.percentile(answered.map { it.ms }, 50) ?: JSONObject.NULL)
            .put("p95", DecisionStats.percentile(answered.map { it.ms }, 95) ?: JSONObject.NULL)
            .put("failures", failures)
            .put("cost", Math.round(samples.sumOf { it.cost ?: 0.0 } * 1_000_000) / 1_000_000.0)
            .put("score", score.toJson())
            .put("calibration", calibration(answered))
    }

    /**
     * Reliability bins for the action question, on requests labelled with one action: per confidence tenth from 0.5,
     * how many answers and how many named the labelled action.
     */
    fun calibration(samples: List<LabSample>): JSONArray {
        val labelled = samples.filter { it.request.capability != null && it.capability != null && it.capabilityConfidence >= 0.5 }
        return JSONArray().also { out ->
            labelled.groupBy { (it.capabilityConfidence * 10).toInt().coerceAtMost(9) }.toSortedMap().forEach { (bin, list) ->
                out.put(JSONObject().put("from", bin / 10.0).put("to", (bin + 1) / 10.0).put("answers", list.size)
                    .put("right", list.count { it.capability == it.request.capability }))
            }
        }
    }
}
