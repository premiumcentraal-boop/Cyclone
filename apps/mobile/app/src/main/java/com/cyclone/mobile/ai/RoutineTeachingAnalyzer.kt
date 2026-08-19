package com.cyclone.mobile.ai

import android.content.Context
import com.cyclone.mobile.guided.RoutineTeachingSession
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * One compact, optional post-session model pass.
 *
 * The model never receives raw screenshots or the complete Accessibility tree here. Those remain
 * local evidence. It receives the semantic timeline, native Android actions and user corrections so
 * it can explain what was learned and propose faster replay strategies without multiplying API calls.
 */
object RoutineTeachingAnalyzer {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun analyze(context: Context, session: RoutineTeachingSession): String? {
        val apiKey = OpenRouterSecretStore.read(context)
        if (apiKey.isBlank() || session.modelId.isBlank()) return null
        val model = OpenRouterModelPresets.byId(session.modelId)
        val compact = JSONArray().also { array ->
            session.steps.take(120).forEach { step ->
                array.put(JSONObject()
                    .put("n", step.index)
                    .put("kind", step.kind)
                    .put("title", step.title.take(180))
                    .put("app", step.packageName ?: JSONObject.NULL)
                    .put("page", step.pageTitle ?: JSONObject.NULL)
                    .put("native_signal", step.semanticSignal ?: JSONObject.NULL)
                    .put("replay", step.replayStrategy)
                    .put("actions", JSONArray(step.advertisedActions.take(12)))
                    .put("note", step.note.take(500)))
            }
        }
        val prompt = """
You are Cyclone's post-teaching analyst. The user just demonstrated a phone routine.
Summarize only evidence present in the timeline. Do not invent buttons or routes.
Identify reusable subskills, semantic Android actions that can replace slow gestures, waits that can become state assertions, and uncertain areas that need another demonstration.
Never expose or infer passwords, OTPs, tokens, payment details or hidden chain-of-thought.
Return strict JSON:
{
  "summary":"short plain-English report",
  "reusable_skills":["..."],
  "speedups":["..."],
  "uncertain":["..."],
  "next_training":"one useful next teaching suggestion"
}
""".trimIndent()
        val body = JSONObject()
            .put("model", model.id)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", prompt))
                .put(JSONObject().put("role", "user").put("content", JSONObject()
                    .put("session", session.name)
                    .put("apps", session.appsSeen)
                    .put("pages", session.pagesSeen)
                    .put("paths", session.pathsLearned)
                    .put("timeline", compact).toString())))
            .put("temperature", 0.05)
            .put("max_tokens", 1800)
            .put("reasoning", JSONObject().put("effort", model.reasoningEffort).put("exclude", true))
            .put("provider", JSONObject().put("sort", "latency").put("allow_fallbacks", true))
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("stream", false)
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://github.com/premiumcentraal-boop/Cyclone")
            .header("X-Title", "Cyclone Mobile V2.9")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string().orEmpty())
                val raw = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val result = JSONObject(clean)
                buildString {
                    result.optString("summary").takeIf(String::isNotBlank)?.let { appendLine(it) }
                    val skills = result.optJSONArray("reusable_skills")
                    if (skills != null && skills.length() > 0) {
                        appendLine("\nReusable skills:")
                        for (i in 0 until skills.length()) appendLine("• ${skills.optString(i)}")
                    }
                    val speedups = result.optJSONArray("speedups")
                    if (speedups != null && speedups.length() > 0) {
                        appendLine("\nPossible speedups:")
                        for (i in 0 until speedups.length()) appendLine("• ${speedups.optString(i)}")
                    }
                    val uncertain = result.optJSONArray("uncertain")
                    if (uncertain != null && uncertain.length() > 0) {
                        appendLine("\nNeeds more evidence:")
                        for (i in 0 until uncertain.length()) appendLine("• ${uncertain.optString(i)}")
                    }
                    result.optString("next_training").takeIf(String::isNotBlank)?.let { appendLine("\nNext teaching suggestion: $it") }
                }.trim()
            }
        }.getOrNull()?.takeIf(String::isNotBlank)
    }
}
