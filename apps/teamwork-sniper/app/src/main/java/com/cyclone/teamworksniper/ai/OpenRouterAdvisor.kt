package com.cyclone.teamworksniper.ai

import android.content.Context
import com.cyclone.teamworksniper.data.AiSettingsStore
import com.cyclone.teamworksniper.data.RuleMatch
import com.cyclone.teamworksniper.data.TriggerEvent
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class AiDecisionTrace(
    val orderedMatches: List<RuleMatch>,
    val engine: String,
    val note: String? = null,
)

object AiDecisionPolicy {
    fun candidateId(match: RuleMatch): String =
        match.ruleId + "|" + match.date + "|" + match.shifts.joinToString(",") { it.stableKey }

    fun deterministic(matches: List<RuleMatch>): List<RuleMatch> =
        matches.sortedWith(
            compareBy<RuleMatch> { it.date }
                .thenBy { match -> match.shifts.mapNotNull { it.startTime }.minOrNull() ?: LocalTime.MAX }
                .thenBy { it.ruleId },
        )

    fun applyPriority(matches: List<RuleMatch>, preferredCandidateId: String?): List<RuleMatch> {
        val ordered = deterministic(matches)
        val selected = preferredCandidateId?.let { id ->
            ordered.firstOrNull { candidateId(it) == id }
        } ?: return ordered
        return listOf(selected) + ordered.filterNot { it === selected }
    }
}

class OpenRouterAdvisor(context: Context) {
    private val settings = AiSettingsStore(context.applicationContext)
    private val secret = OpenRouterSecretStore(context.applicationContext)
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(900, TimeUnit.MILLISECONDS)
        .readTimeout(1600, TimeUnit.MILLISECONDS)
        .writeTimeout(900, TimeUnit.MILLISECONDS)
        .callTimeout(2200, TimeUnit.MILLISECONDS)
        .build()

    suspend fun prioritize(matches: List<RuleMatch>, trigger: TriggerEvent): AiDecisionTrace {
        val local = AiDecisionPolicy.deterministic(matches)
        val config = settings.load()
        if (!config.enabled) return AiDecisionTrace(local, "DETERMINISTIC", "AI assist disabled")
        if (local.size < 2) return AiDecisionTrace(local, "DETERMINISTIC", "Single safe match; AI not needed")
        val key = secret.read()
        if (key.isNullOrBlank()) return AiDecisionTrace(local, "DETERMINISTIC_FALLBACK", "AI assist has no OpenRouter key")

        return withContext(Dispatchers.IO) {
            runCatching {
                val candidates = JSONArray()
                local.forEach { match ->
                    val shifts = JSONArray()
                    match.shifts.forEach { shift ->
                        shifts.put(
                            JSONObject()
                                .put("code", shift.code.name)
                                .put("start", shift.startTime?.toString())
                                .put("end", shift.endTime?.toString()),
                        )
                    }
                    candidates.put(
                        JSONObject()
                            .put("candidateId", AiDecisionPolicy.candidateId(match))
                            .put("date", match.date.toString())
                            .put("rule", match.ruleName)
                            .put("ruleType", match.ruleType.name)
                            .put("shifts", shifts),
                    )
                }

                val system = """
                    You are an optional advisor inside Teamwork Sniper.
                    Every candidate below already passed deterministic user rules and semantic open-shift checks.
                    Choose only which candidate should be tried first, or choose NONE.
                    Never invent a shift, date, rule, or candidate ID.
                    Return only JSON: {"candidateId":"<exact candidate ID or NONE>","reason":"short reason"}.
                """.trimIndent()

                val user = JSONObject()
                    .put("notificationTitle", trigger.notificationTitle?.take(160))
                    .put("notificationText", trigger.notificationText?.take(200))
                    .put("candidates", candidates)
                    .toString()

                val messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
                val payload = JSONObject()
                    .put("model", config.model)
                    .put("temperature", 0)
                    .put("messages", messages)

                val request = Request.Builder()
                    .url("https://openrouter.ai/api/v1/chat/completions")
                    .header("Authorization", "Bearer " + key)
                    .header("X-Title", "Teamwork Sniper")
                    .post(payload.toString().toRequestBody(mediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP " + response.code)
                    val body = response.body?.string().orEmpty()
                    val content = JSONObject(body)
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")
                        .orEmpty()
                    val adviceJson = extractJson(content)
                    val candidate = adviceJson.optString("candidateId").trim()
                    val reason = adviceJson.optString("reason").replace(Regex("\s+"), " ").trim().take(180)
                    val validId = candidate.takeUnless { it.equals("NONE", ignoreCase = true) }
                        ?.takeIf { id -> local.any { AiDecisionPolicy.candidateId(it) == id } }
                    val ordered = AiDecisionPolicy.applyPriority(local, validId)
                    AiDecisionTrace(
                        orderedMatches = ordered,
                        engine = "AI_ASSISTED",
                        note = if (validId == null) {
                            "OpenRouter kept deterministic order: " + reason
                        } else {
                            "OpenRouter priority: " + reason
                        },
                    )
                }
            }.getOrElse { failure ->
                AiDecisionTrace(
                    orderedMatches = local,
                    engine = "DETERMINISTIC_FALLBACK",
                    note = "OpenRouter unavailable; local rules kept authority (" + failure.javaClass.simpleName + ")",
                )
            }
        }
    }

    private fun extractJson(content: String): JSONObject {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        require(start >= 0 && end > start) { "OpenRouter response did not contain JSON" }
        return JSONObject(content.substring(start, end + 1))
    }
}
