package com.cyclone.mobile.agent.runtime

import com.cyclone.mobile.ai.OpenRouterModelPreset
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * OpenRouter/OpenAI-compatible native tool caller with a provider-neutral compatibility fallback.
 * The fallback still uses tool_call/final envelopes and never reintroduces PageAgentProtocol.
 */
class OpenRouterToolCallingProvider(
    private val apiKey: String,
    private val model: OpenRouterModelPreset,
    private val providerSort: String,
    private val sessionId: String = "cyclone-agent-" + UUID.randomUUID(),
    private val http: OkHttpClient = defaultHttp(),
) : AgentConversationProvider {
    @Volatile private var nativeToolsEnabled = true

    override fun next(conversation: List<AgentConversationEntry>, tools: JSONArray): AgentProviderTurn {
        if (apiKey.isBlank()) return AgentProviderTurn.Failure("Add an OpenRouter API key first.", "API_KEY_MISSING", false)
        if (nativeToolsEnabled) {
            val native = nativeTurn(conversation, tools)
            if (native !is AgentProviderTurn.Failure) return native
            if (!native.retryable || !looksLikeNativeToolCompatibilityFailure(native.message)) return native
            nativeToolsEnabled = false
        }
        return compatibilityTurn(conversation, tools)
    }

    private fun looksLikeNativeToolCompatibilityFailure(message: String): Boolean {
        val lower = message.lowercase()
        return listOf(
            "tool_choice",
            "tool call",
            "tool_calls",
            "tools parameter",
            "function calling",
            "function call",
            "unsupported parameter",
            "does not support tools",
        ).any(lower::contains)
    }

    private fun nativeTurn(conversation: List<AgentConversationEntry>, tools: JSONArray): AgentProviderTurn {
        val body = baseBody()
            .put("messages", nativeMessages(conversation))
            .put("tools", nativeTools(tools))
            .put("tool_choice", "auto")
            .put("parallel_tool_calls", false)
        val response = request(body)
        response.optJSONObject("error")?.let { error ->
            val message = error.optString("message").ifBlank { "OpenRouter native tool request failed." }
            return AgentProviderTurn.Failure(message, "NATIVE_TOOL_REQUEST_FAILED", true)
        }
        val message = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: return AgentProviderTurn.Failure("OpenRouter returned no assistant message.", "EMPTY_PROVIDER_RESPONSE", true)
        val calls = parseNativeCalls(message.optJSONArray("tool_calls"))
        if (calls.isNotEmpty()) return AgentProviderTurn.ToolCalls(calls, native = true)
        val content = message.optString("content").trim()
        if (content.isNotBlank()) return AgentProviderTurn.Final(content, native = true)
        return AgentProviderTurn.Failure("The model returned neither a tool call nor a final answer.", "EMPTY_MODEL_TURN", true)
    }

    private fun compatibilityTurn(conversation: List<AgentConversationEntry>, tools: JSONArray): AgentProviderTurn {
        val prompt = buildString {
            appendLine("You are in Cyclone's compatibility tool-calling adapter.")
            appendLine("Choose exactly one next step. Return JSON only.")
            appendLine("To call a tool: {\"tool_call\":{\"name\":\"tool_name\",\"arguments\":{...}}}")
            appendLine("To finish: {\"final\":\"short user-facing completion message\"}")
            appendLine("Never emit the old act/done/blocked page-decision protocol.")
            appendLine("Tool definitions:")
            appendLine(tools.toString())
            appendLine()
            appendLine("Conversation:")
            appendLine(compatibilityTranscript(conversation))
        }
        val body = baseBody()
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", "Return only the requested compatibility JSON. Do not expose chain-of-thought."))
                .put(JSONObject().put("role", "user").put("content", prompt)))
            .put("response_format", JSONObject().put("type", "json_object"))

        val response = request(body)
        response.optJSONObject("error")?.let { error ->
            return AgentProviderTurn.Failure(
                error.optString("message").ifBlank { "OpenRouter compatibility request failed." },
                "COMPAT_TOOL_REQUEST_FAILED",
                true,
            )
        }
        val raw = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        val json = runCatching { JSONObject(stripFence(raw)) }.getOrElse {
            return AgentProviderTurn.Failure("The compatibility adapter returned invalid JSON.", "INVALID_COMPAT_JSON", true)
        }
        json.optJSONObject("tool_call")?.let { call ->
            val name = call.optString("name")
            if (name.isBlank()) return AgentProviderTurn.Failure("Compatibility tool call had no name.", "INVALID_TOOL_CALL", true)
            val args = call.optJSONObject("arguments") ?: JSONObject()
            return AgentProviderTurn.ToolCalls(
                listOf(AgentToolCall("compat-" + UUID.randomUUID(), name, args)),
                native = false,
            )
        }
        val final = json.optString("final").trim()
        if (final.isNotBlank()) return AgentProviderTurn.Final(final, native = false)
        return AgentProviderTurn.Failure("Compatibility response contained neither tool_call nor final.", "INVALID_COMPAT_TURN", true)
    }

    private fun nativeMessages(conversation: List<AgentConversationEntry>): JSONArray = JSONArray().also { out ->
        conversation.forEach { entry ->
            when (entry) {
                is AgentConversationEntry.Text -> out.put(
                    JSONObject().put("role", entry.role).put("content", entry.content),
                )
                is AgentConversationEntry.AssistantToolCalls -> out.put(
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", entry.content.ifBlank { JSONObject.NULL })
                        .put("tool_calls", JSONArray().also { calls ->
                            entry.calls.forEach { call ->
                                calls.put(JSONObject()
                                    .put("id", call.id)
                                    .put("type", "function")
                                    .put("function", JSONObject()
                                        .put("name", call.name)
                                        .put("arguments", call.arguments.toString())))
                            }
                        }),
                )
                is AgentConversationEntry.ToolResult -> out.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", entry.callId)
                        .put("name", entry.tool)
                        .put("content", entry.payload.toString()),
                )
            }
        }
    }

    private fun nativeTools(descriptors: JSONArray): JSONArray = JSONArray().also { out ->
        for (index in 0 until descriptors.length()) {
            val descriptor = descriptors.optJSONObject(index) ?: continue
            out.put(JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", descriptor.optString("name"))
                    .put("description", descriptor.optString("description"))
                    .put("parameters", descriptor.optJSONObject("arguments") ?: JSONObject().put("type", "object"))))
        }
    }

    private fun parseNativeCalls(array: JSONArray?): List<AgentToolCall> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val function = item.optJSONObject("function") ?: continue
            val name = function.optString("name")
            if (name.isBlank()) continue
            val argsRaw = function.optString("arguments").ifBlank { "{}" }
            val args = runCatching { JSONObject(argsRaw) }.getOrDefault(JSONObject())
            add(AgentToolCall(item.optString("id").ifBlank { "call-" + UUID.randomUUID() }, name, args))
        }
    }

    private fun compatibilityTranscript(conversation: List<AgentConversationEntry>): String = buildString {
        conversation.takeLast(40).forEach { entry ->
            when (entry) {
                is AgentConversationEntry.Text -> appendLine(entry.role.uppercase() + ": " + entry.content.take(5000))
                is AgentConversationEntry.AssistantToolCalls -> entry.calls.forEach { call ->
                    appendLine("ASSISTANT TOOL_CALL " + call.name + " " + call.arguments.toString().take(3000))
                }
                is AgentConversationEntry.ToolResult -> appendLine(
                    "TOOL RESULT " + entry.tool + " " + entry.payload.toString().take(6000),
                )
            }
        }
    }

    private fun baseBody(): JSONObject = JSONObject()
        .put("model", model.id)
        .put("temperature", 0.05)
        .put("max_tokens", if (model.reasoningEffort == "max") 5_000 else 3_500)
        .put("reasoning", JSONObject().put("effort", model.reasoningEffort).put("exclude", true))
        .put("session_id", sessionId)
        .put("provider", JSONObject()
            .put("sort", providerSort)
            .put("allow_fallbacks", true)
            .put("require_parameters", true))
        .put("stream", false)

    private fun request(body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://github.com/premiumcentraal-boop/Cyclone")
            .header("X-Title", "Cyclone Mobile 3.8.8 Agent")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrElse {
                    JSONObject().put("error", JSONObject().put("message", text.ifBlank { "HTTP " + response.code }))
                }
                if (!response.isSuccessful && !json.has("error")) {
                    json.put("error", JSONObject().put("message", "HTTP " + response.code))
                }
                json
            }
        }.getOrElse { error ->
            JSONObject().put("error", JSONObject().put("message", error.message ?: error.javaClass.simpleName))
        }
    }

    private fun stripFence(raw: String): String {
        val fence = Char(96).toString().repeat(3)
        return raw.trim().removePrefix(fence + "json").removePrefix(fence).removeSuffix(fence).trim()
    }

    companion object {
        private fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(75, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .build()
    }
}
