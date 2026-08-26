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

data class OpenRouterModelPreset(
    val id: String,
    val label: String,
    val vision: Boolean,
    val reasoningEffort: String = "medium",
)

/**
 * Built-in V2.8 provider presets. Custom OpenRouter model slugs still work through byId().
 *
 * The requested "Gemini 3.7 Flash" is not an official Google model as of August 2026. Google's
 * current stable Flash model is Gemini 3.6 Flash, so Cyclone exposes that real supported endpoint
 * at High reasoning instead of silently inventing an unsupported 3.7 slug.
 */
object OpenRouterModelPresets {
    val GPT_5_6_LUNA = OpenRouterModelPreset(
        id = "openai/gpt-5.6-luna",
        label = "GPT-5.6 Luna · Max",
        vision = true,
        reasoningEffort = "max",
    )
    val GEMINI_3_6_FLASH = OpenRouterModelPreset(
        id = "google/gemini-3.6-flash",
        label = "Gemini 3.6 Flash · High",
        vision = true,
        reasoningEffort = "high",
    )
    val QWEN_3_8_27B = OpenRouterModelPreset(
        id = "qwen/qwen3.8-27b",
        label = "Qwen3.8 27B",
        vision = false,
        reasoningEffort = "medium",
    )
    val DEEPSEEK_V4_FLASH = OpenRouterModelPreset(
        "deepseek/deepseek-v4-flash-0731",
        "DeepSeek V4 Flash 0731",
        false,
        "medium",
    )
    val GEMMA_4_26B = OpenRouterModelPreset(
        "google/gemma-4-26b-a4b-it",
        "Gemma 4 26B A4B",
        true,
        "medium",
    )
    val GEMMA_4_31B = OpenRouterModelPreset(
        "google/gemma-4-31b-it",
        "Gemma 4 31B",
        true,
        "medium",
    )

    /** Luna Max is the clean-install V2.8 default requested for Cyclone phone control. */
    val DEFAULT = GPT_5_6_LUNA
    val all = listOf(GPT_5_6_LUNA, GEMINI_3_6_FLASH, QWEN_3_8_27B, DEEPSEEK_V4_FLASH, GEMMA_4_26B, GEMMA_4_31B)

    // Unknown custom slugs are accepted, but vision support is not assumed until the user picks a
    // known vision preset. This preserves existing behavior and prevents accidental image requests
    // to text-only custom providers.
    fun byId(id: String): OpenRouterModelPreset = all.firstOrNull { it.id == id }
        ?: OpenRouterModelPreset(id, id, false, reasoningEffort = "medium")
}

data class QuickAgentConfig(
    val model: OpenRouterModelPreset = OpenRouterModelPresets.DEFAULT,
    val visionModel: OpenRouterModelPreset = OpenRouterModelPresets.GEMINI_3_6_FLASH,
    /** V2.8 counts provider calls on unknown semantic pages, not raw phone actions/events. */
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
            .put("provider", JSONObject().put("sort", providerSort).put("allow_fallbacks", true).put("require_parameters", true))
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("stream", false)
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://github.com/premiumcentraal-boop/Cyclone")
            .header("X-Title", "Cyclone Mobile V2.8")
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
