package com.cyclone.mobile.ai

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

enum class OpenRouterFailureCode {
    MODEL_NOT_FOUND,
    MODEL_ACCESS_DENIED,
    PROVIDER_AUTH_FAILED,
    NO_PROVIDER_AVAILABLE,
    RATE_LIMITED,
    ROUTING_CONSTRAINT_UNSATISFIED,
    PARAMETER_UNSUPPORTED,
    CONTEXT_LIMIT,
    MALFORMED_MODEL_OUTPUT,
    NETWORK_FAILURE,
}

data class OpenRouterProviderFailure(
    val code: OpenRouterFailureCode,
    val httpStatus: Int?,
    val safeMessage: String,
    val provider: String? = null,
    val requestId: String? = null,
    val retryable: Boolean = false,
)

data class OpenRouterHttpResult(
    val json: JSONObject,
    val failure: OpenRouterProviderFailure? = null,
) {
    val ok: Boolean get() = failure == null
}

/**
 * Normalizes the OpenRouter boundary so provider/model failures cannot masquerade as Android
 * navigation failures. Only sanitized metadata is preserved; prompts, authorization headers and
 * raw sensitive payloads never enter this contract.
 */
object OpenRouterProviderReliability {
    fun parseHttp(status: Int, body: String, requestId: String? = null): OpenRouterHttpResult {
        val json = runCatching { JSONObject(body) }.getOrElse {
            JSONObject().put("error", JSONObject().put("message", body.take(600).ifBlank { "HTTP $status" }))
        }
        if (status in 200..299 && !json.has("error")) return OpenRouterHttpResult(json)

        val error = json.optJSONObject("error") ?: JSONObject().put("message", "HTTP $status")
        val message = error.optString("message").ifBlank { "OpenRouter request failed (HTTP $status)." }
        val metadata = error.optJSONObject("metadata")
        val provider = metadata?.optString("provider_name")?.takeIf { it.isNotBlank() }
            ?: metadata?.optString("provider")?.takeIf { it.isNotBlank() }
        val code = classify(status, message, error.opt("code")?.toString().orEmpty())
        return OpenRouterHttpResult(
            json = json,
            failure = OpenRouterProviderFailure(
                code = code,
                httpStatus = status,
                safeMessage = sanitizeMessage(message, status),
                provider = provider?.take(120),
                requestId = requestId?.take(160),
                retryable = code in setOf(
                    OpenRouterFailureCode.RATE_LIMITED,
                    OpenRouterFailureCode.NO_PROVIDER_AVAILABLE,
                    OpenRouterFailureCode.NETWORK_FAILURE,
                ),
            ),
        )
    }

    fun networkFailure(error: Throwable): OpenRouterProviderFailure = OpenRouterProviderFailure(
        code = OpenRouterFailureCode.NETWORK_FAILURE,
        httpStatus = null,
        safeMessage = error.message?.take(300)?.ifBlank { null } ?: "OpenRouter network request failed.",
        retryable = true,
    )

    private fun classify(status: Int, message: String, rawCode: String): OpenRouterFailureCode {
        val text = "$message $rawCode".lowercase()
        return when {
            status == 401 -> OpenRouterFailureCode.PROVIDER_AUTH_FAILED
            status == 403 -> OpenRouterFailureCode.MODEL_ACCESS_DENIED
            status == 404 -> OpenRouterFailureCode.MODEL_NOT_FOUND
            status == 429 -> OpenRouterFailureCode.RATE_LIMITED
            status == 413 || listOf("context length", "context window", "too many tokens", "maximum context").any(text::contains) ->
                OpenRouterFailureCode.CONTEXT_LIMIT
            listOf("no endpoints", "no provider", "provider unavailable", "no available provider").any(text::contains) ->
                OpenRouterFailureCode.NO_PROVIDER_AVAILABLE
            listOf("data_collection", "zero data retention", "zdr", "routing", "provider preferences").any(text::contains) ->
                OpenRouterFailureCode.ROUTING_CONSTRAINT_UNSATISFIED
            listOf("unsupported parameter", "unsupported param", "response_format", "require_parameters", "not support").any(text::contains) ->
                OpenRouterFailureCode.PARAMETER_UNSUPPORTED
            status >= 500 -> OpenRouterFailureCode.NETWORK_FAILURE
            else -> OpenRouterFailureCode.NETWORK_FAILURE
        }
    }

    private fun sanitizeMessage(message: String, status: Int): String {
        val clean = message
            .replace(Regex("(?i)bearer\\s+[A-Za-z0-9._~+/-]+"), "Bearer [REDACTED]")
            .replace(Regex("(?i)(api[_ -]?key|token|authorization)\\s*[:=]\\s*[^\\s,;]+"), "$1=[REDACTED]")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(500)
        return clean.ifBlank { "OpenRouter request failed (HTTP $status)." }
    }
}

/** Successful compatibility probes are cached so ordinary deterministic tasks do not pay a probe cost. */
internal object OpenRouterQualificationCache {
    private const val TTL_MS = 6L * 60L * 60L * 1000L
    private val qualifiedUntil = ConcurrentHashMap<String, Long>()

    fun isQualified(modelId: String, nowMillis: Long = System.currentTimeMillis()): Boolean =
        (qualifiedUntil[modelId] ?: 0L) > nowMillis

    fun markQualified(modelId: String, nowMillis: Long = System.currentTimeMillis()) {
        qualifiedUntil[modelId] = nowMillis + TTL_MS
    }

    fun invalidate(modelId: String) {
        qualifiedUntil.remove(modelId)
    }

    internal fun clearForTests() {
        qualifiedUntil.clear()
    }
}
