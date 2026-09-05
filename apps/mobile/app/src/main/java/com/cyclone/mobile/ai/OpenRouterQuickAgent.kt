package com.cyclone.mobile.ai

import android.content.Context
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.brain.CycloneBrainRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class OpenRouterStructuredOutputMode {
    JSON_SCHEMA,
    JSON_OBJECT,
}

enum class OpenRouterDataPolicy {
    STANDARD,
    CONTRIBUTOR,
}

data class OpenRouterModelPreset(
    val id: String,
    val label: String,
    val vision: Boolean,
    val reasoningEffort: String = "medium",
    val structuredOutputMode: OpenRouterStructuredOutputMode = OpenRouterStructuredOutputMode.JSON_OBJECT,
    val dataPolicy: OpenRouterDataPolicy = OpenRouterDataPolicy.STANDARD,
    val contributorDisclosure: String? = null,
) {
    val isContributor: Boolean get() = dataPolicy == OpenRouterDataPolicy.CONTRIBUTOR

    fun providerRouting(sort: String): JSONObject = JSONObject()
        .put("sort", sort)
        .put("allow_fallbacks", true)
        .put("require_parameters", true)
        .put("data_collection", if (isContributor) "allow" else "deny")
}

/** Curated OpenRouter endpoints supported by Cyclone. Custom slugs still work through byId(). */
object OpenRouterModelPresets {
    val GPT_5_6_LUNA = OpenRouterModelPreset(
        id = "openai/gpt-5.6-luna",
        label = "GPT-5.6 Luna",
        vision = true,
        reasoningEffort = "medium",
    )
    val GEMINI_3_8_FLASH = OpenRouterModelPreset(
        id = "google/gemini-3.8-flash",
        label = "Gemini 3.8 Flash",
        vision = true,
        reasoningEffort = "medium",
    )
    val GLM_5_3_FLASH = OpenRouterModelPreset(
        id = "z-ai/glm-5.3-flash",
        label = "GLM 5.3 Flash",
        vision = true,
        reasoningEffort = "medium",
    )
    val MUSE_SPARK_1_3 = OpenRouterModelPreset(
        id = "meta/muse-spark-1.3",
        label = "Muse Spark 1.3",
        vision = true,
        reasoningEffort = "max",
        structuredOutputMode = OpenRouterStructuredOutputMode.JSON_SCHEMA,
    )
    val MUSE_SPARK_1_3_CONTRIBUTOR = OpenRouterModelPreset(
        id = "meta/muse-spark-1.3-contributor",
        label = "Muse Spark 1.3 Contributor",
        vision = true,
        reasoningEffort = "max",
        structuredOutputMode = OpenRouterStructuredOutputMode.JSON_SCHEMA,
        dataPolicy = OpenRouterDataPolicy.CONTRIBUTOR,
        contributorDisclosure = "Lower-cost contributor tier. Prompts and outputs may be used by the provider to improve models or products.",
    )
    val GPT_6_ASTRA = OpenRouterModelPreset(
        id = "openai/gpt-6-astra",
        label = "GPT-6 Astra",
        vision = true,
        reasoningEffort = "high",
        structuredOutputMode = OpenRouterStructuredOutputMode.JSON_SCHEMA,
    )
    val CLAUDE_FABLE_5_1 = OpenRouterModelPreset(
        id = "anthropic/claude-fable-5.1",
        label = "Claude Fable 5.1",
        vision = true,
        reasoningEffort = "high",
        structuredOutputMode = OpenRouterStructuredOutputMode.JSON_OBJECT,
    )
    val GPT_5_6_SOL = OpenRouterModelPreset(
        id = "openai/gpt-5.6-sol",
        label = "GPT-5.6 Sol",
        vision = true,
        reasoningEffort = "high",
    )

    /** Luna is the inexpensive balanced clean-install default. */
    val DEFAULT = GPT_5_6_LUNA
    val all = listOf(
        GEMINI_3_8_FLASH,
        GPT_5_6_LUNA,
        GLM_5_3_FLASH,
        MUSE_SPARK_1_3,
        MUSE_SPARK_1_3_CONTRIBUTOR,
        GPT_6_ASTRA,
        CLAUDE_FABLE_5_1,
        GPT_5_6_SOL,
    )

    // Compatibility names used by older screens; both resolve to current curated endpoints.
    val GEMINI_3_6_FLASH = GEMINI_3_8_FLASH
    val DEEPSEEK_V4_FLASH = GLM_5_3_FLASH

    // Unknown custom slugs are accepted, but vision support is not assumed until the user picks a
    // known vision preset. This preserves existing behavior and prevents accidental image requests
    // to text-only custom providers. Custom models are always treated as standard/private routing;
    // contributor behavior can only be selected through the explicit curated contributor preset.
    fun byId(id: String): OpenRouterModelPreset = all.firstOrNull { it.id == id }
        ?: OpenRouterModelPreset(id, id, false, reasoningEffort = "medium")
}

data class QuickAgentConfig(
    val model: OpenRouterModelPreset = OpenRouterModelPresets.DEFAULT,
    val visionModel: OpenRouterModelPreset = OpenRouterModelPresets.GEMINI_3_8_FLASH,
    /**
     * Legacy compatibility knob for older workflow/tests. Foreground CycloneLocalAgent execution
     * no longer uses provider-call count as a task termination budget.
     */
    val maxDecisions: Int = 6,
    val safeMode: Boolean = true,
    val accessProfile: CycloneAiAccessProfile = if (safeMode) CycloneAiAccessProfile.BALANCED else CycloneAiAccessProfile.FULL,
    val providerSort: String = "latency",
)

data class QuickAgentResult(
    val ok: Boolean,
    val message: String,
    val decisions: Int,
    val model: String,
    val workflowId: String? = null,
    /** Persistent local task id; present for foreground task sessions and GATE resume. */
    val taskId: String? = null,
    /** CycloneTaskClassification name. Null for legacy one-shot workflow compilation. */
    val classification: String? = null,
    /** Overlay GATE wire value when a deterministic Android boundary can name one. */
    val gateClass: String? = null,
)

/**
 * Compatibility facade. Foreground phone execution is owned by OpenRouterAdaptiveAgent, which is
 * page-aware in V2.8. This class still owns the one-shot workflow compiler used by the UI.
 */
class OpenRouterQuickAgent(private val context: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun execute(goal: String, config: QuickAgentConfig, onProgress: (String) -> Unit = {}): QuickAgentResult =
        OpenRouterAdaptiveAgent(context).execute(goal, config, onProgress)

    suspend fun buildWorkflow(goal: String, config: QuickAgentConfig, onProgress: (String) -> Unit = {}): QuickAgentResult = withContext(Dispatchers.IO) {
        val apiKey = OpenRouterSecretStore.read(context)
        if (apiKey.isBlank()) return@withContext QuickAgentResult(false, "Add an OpenRouter API key first.", 0, config.model.id)
        if (goal.isBlank()) return@withContext QuickAgentResult(false, "Describe the workflow you want Cyclone to build.", 0, config.model.id)

        val traceId = AgentTraceRuntime.start(context, "Build workflow: $goal", config.model.id)
        val environment = MobileContextHarness.observe(context, goal)
        onProgress("Designing deterministic workflow…")
        AgentTraceRuntime.event(context, traceId, "DECISION", "Turning the current page into a reviewable deterministic workflow", code = "workflow.design")
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", WORKFLOW_PROMPT))
            .put(JSONObject().put("role", "user").put("content", "REQUEST:\n$goal\n\nCURRENT_PHONE_CONTEXT:\n$environment"))
        val response = chat(apiKey, config.model, messages, config.providerSort, jsonMode = true)
        val raw = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        if (raw.isBlank()) {
            val result = QuickAgentResult(false, apiError(response), 1, config.model.id)
            return@withContext completeTrace(traceId, result)
        }
        val proposal = runCatching { JSONObject(stripCodeFence(raw)) }.getOrElse {
            return@withContext completeTrace(traceId, QuickAgentResult(false, "The model returned invalid workflow JSON: ${it.message}", 1, config.model.id))
        }
        val result = AutomationRuntime.importAiProposal(context, proposal).fold(
            onSuccess = { automation ->
                AgentTraceRuntime.event(context, traceId, "RESULT", "A deterministic workflow was created and left disabled for review", code = "workflow.saved", ok = true, detail = automation.name)
                QuickAgentResult(true, "Workflow '${automation.name}' was created disabled for review.", 1, config.model.id, automation.id)
            },
            onFailure = {
                AgentTraceRuntime.event(context, traceId, "ERROR", "Cyclone's typed workflow validator rejected the proposal", code = "workflow.rejected", ok = false, detail = it.message)
                QuickAgentResult(false, "Workflow was rejected by Cyclone's validator: ${it.message}", 1, config.model.id)
            },
        )
        completeTrace(traceId, result)
    }

    private fun completeTrace(traceId: String, result: QuickAgentResult): QuickAgentResult {
        val status = if (result.ok) "COMPLETED" else "FAILED"
        AgentTraceRuntime.finish(context, traceId, status, result.message, result.decisions)
        AgentTraceRuntime.store.listSessions(100).firstOrNull { it.id == traceId }?.let { session ->
            runCatching { CycloneBrainRuntime.record(context, session, AgentTraceRuntime.store.events(traceId)) }
        }
        return result
    }

    private fun chat(
        apiKey: String,
        model: OpenRouterModelPreset,
        messages: JSONArray,
        providerSort: String,
        jsonMode: Boolean,
    ): JSONObject {
        val maxTokens = when {
            jsonMode && model.reasoningEffort == "max" -> 12_000
            jsonMode -> 5_000
            model.reasoningEffort == "max" -> 4_096
            else -> 2_500
        }
        val body = JSONObject()
            .put("model", model.id)
            .put("messages", messages)
            .put("temperature", 0.05)
            .put("max_tokens", maxTokens)
            .put("reasoning", JSONObject().put("effort", model.reasoningEffort).put("exclude", true))
            .put("session_id", "cyclone-workflow-${UUID.randomUUID()}")
            .put("provider", model.providerRouting(providerSort))
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("stream", false)
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://github.com/premiumcentraal-boop/Cyclone")
            .header("X-Title", "Cyclone Mobile")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse {
                JSONObject().put("error", JSONObject().put("message", text.ifBlank { "HTTP ${response.code}" }))
            }
            if (!response.isSuccessful && !json.has("error")) json.put("error", JSONObject().put("message", "HTTP ${response.code}"))
            json
        }
    }

    companion object {
        private val WORKFLOW_PROMPT = """
You convert a natural-language Android request into ONE Cyclone Automation Studio proposal as strict JSON.
Return JSON only. The result is compiled and forced disabled until human review.
Prefer known semantic PageContext/App Graph selectors over coordinates. Never include passwords, tokens, API keys or credentials.
Use this shape:
{
  "name":"...",
  "description":"...",
  "trigger":{"type":"manual|notification|schedule|app_opened|cyclone_remote|websocket|calendar_time", "params":{}},
  "steps":[
    {"type":"phone_tool","tool":"phone.open_app|phone.click|phone.type|phone.scroll|phone.back|phone.home|phone.open_notification|phone.wait_for|phone.assert", "params":{}, "selector":{"resourceId":"...","text":"...","textContains":"...","contentDescription":"...","role":"..."}, "recovery":{"maxRetries":1,"onFailure":"request_ai_help"}, "confirmation":"required"}
  ],
  "verification":{"type":"selector_exists","selector":{"textContains":"..."}},
  "recovery":{"onFailure":"request_ai_help"}
}
Consequential actions require confirmation. If context is insufficient, create a safe exploratory skeleton instead of brittle invented coordinates.
""".trimIndent()

        internal fun stripCodeFence(raw: String): String = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        private fun apiError(response: JSONObject): String =
            response.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "OpenRouter request failed." }
    }
}

internal object SafeModeGuard {
    fun allowed(tool: String, params: JSONObject): Boolean =
        CycloneAiAccessPolicy.evaluate(CycloneAiAccessProfile.BALANCED, tool, params).allowed
}
