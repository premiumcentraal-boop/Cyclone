package com.cyclone.mobile.ai

enum class ProviderFailureClass {
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
    PROVIDER_CREDIT_EXHAUSTED,
}

data class SanitizedProviderFailure(
    val failureClass: ProviderFailureClass,
    val httpStatus: Int,
    val providerCode: String? = null,
    val providerMessage: String? = null,
    val selectedModelId: String? = null,
    val providerName: String? = null,
    val requestId: String? = null,
    val retryable: Boolean = false,
) {
    val code: String get() = failureClass.name
    val userMessage: String get() = ProviderFailure.message(code) ?: "The model provider could not serve this request."
}

/** Provider failures are task blockers, never Android-navigation evidence. */
internal object ProviderFailure {
    fun classify(
        httpStatus: Int,
        rawBody: String? = null,
        selectedModelId: String? = null,
        providerName: String? = null,
        requestId: String? = null,
    ): SanitizedProviderFailure {
        val body = rawBody.orEmpty()
        val lower = body.lowercase()
        val providerCode = extractJsonScalar(body, "code")?.take(120)
        val providerMessage = extractJsonString(body, "message")
            ?.let(::sanitize)
            ?.take(600)
        val failureClass = when {
            httpStatus == 401 -> ProviderFailureClass.PROVIDER_AUTH_FAILED
            httpStatus == 403 -> ProviderFailureClass.MODEL_ACCESS_DENIED
            httpStatus == 404 -> ProviderFailureClass.MODEL_NOT_FOUND
            httpStatus == 402 -> ProviderFailureClass.PROVIDER_CREDIT_EXHAUSTED
            httpStatus == 429 -> ProviderFailureClass.RATE_LIMITED
            httpStatus == 0 || httpStatus == 408 || httpStatus == 504 -> ProviderFailureClass.NETWORK_FAILURE
            "context" in lower && ("limit" in lower || "length" in lower || "too long" in lower) -> ProviderFailureClass.CONTEXT_LIMIT
            (httpStatus == 400 || httpStatus == 422) &&
                ("response_format" in lower || "response format" in lower || "unsupported parameter" in lower || "unsupported_param" in lower) ->
                ProviderFailureClass.PARAMETER_UNSUPPORTED
            "routing" in lower && ("constraint" in lower || "require_parameters" in lower || "provider" in lower) ->
                ProviderFailureClass.ROUTING_CONSTRAINT_UNSATISFIED
            ("no provider" in lower || "no endpoints" in lower || "no endpoint" in lower) ->
                ProviderFailureClass.NO_PROVIDER_AVAILABLE
            (httpStatus == 400 || httpStatus == 422) && "model" in lower &&
                ("not found" in lower || "unknown" in lower || "invalid" in lower) -> ProviderFailureClass.MODEL_NOT_FOUND
            httpStatus in 500..599 -> ProviderFailureClass.NO_PROVIDER_AVAILABLE
            else -> ProviderFailureClass.NO_PROVIDER_AVAILABLE
        }
        return SanitizedProviderFailure(
            failureClass = failureClass,
            httpStatus = httpStatus,
            providerCode = providerCode,
            providerMessage = providerMessage,
            selectedModelId = selectedModelId?.take(180),
            providerName = providerName?.let(::sanitize)?.take(120),
            requestId = requestId?.let(::sanitize)?.take(180),
            retryable = failureClass in setOf(
                ProviderFailureClass.NO_PROVIDER_AVAILABLE,
                ProviderFailureClass.RATE_LIMITED,
                ProviderFailureClass.NETWORK_FAILURE,
            ),
        )
    }

    /** Compatibility helper used by existing agent-boundary code. */
    fun code(httpStatus: Int): String = classify(httpStatus).code

    fun message(code: String): String? = when (runCatching { ProviderFailureClass.valueOf(code) }.getOrNull()) {
        ProviderFailureClass.MODEL_NOT_FOUND -> "The selected model was not found by the provider. Choose another explicitly supported model or try again later."
        ProviderFailureClass.MODEL_ACCESS_DENIED -> "The provider denied access to the selected model. Check model access for this OpenRouter account."
        ProviderFailureClass.PROVIDER_AUTH_FAILED -> "The provider rejected the OpenRouter credentials. Check the API key in Settings."
        ProviderFailureClass.NO_PROVIDER_AVAILABLE -> "No compatible provider could serve this model request. Try again or explicitly choose another model."
        ProviderFailureClass.RATE_LIMITED -> "The model provider is rate-limiting requests. Wait before trying again."
        ProviderFailureClass.ROUTING_CONSTRAINT_UNSATISFIED -> "No provider route satisfied this model's required capability and privacy constraints."
        ProviderFailureClass.PARAMETER_UNSUPPORTED -> "The provider does not support a required request parameter for this route."
        ProviderFailureClass.CONTEXT_LIMIT -> "The model request exceeded the provider's context limit."
        ProviderFailureClass.MALFORMED_MODEL_OUTPUT -> "The model returned output that Cyclone could not validate safely."
        ProviderFailureClass.NETWORK_FAILURE -> "The model request timed out or the network is unavailable. Check the connection and try again."
        ProviderFailureClass.PROVIDER_CREDIT_EXHAUSTED -> "The model provider reports insufficient credit. Check the OpenRouter account."
        null -> null
    }

    fun sanitize(value: String): String = value
        .replace(Regex("(?i)authorization\\s*[:=]\\s*(?:bearer\\s+)?[^,;\\s}]+"), "Authorization=[REDACTED]")
        .replace(Regex("(?i)bearer\\s+[a-z0-9._~+/-]{8,}"), "Bearer [REDACTED]")
        .replace(Regex("(?i)sk-[a-z0-9_-]{8,}"), "[API_KEY_REDACTED]")
        .replace(Regex("(?i)(api[_ -]?key|token|secret)\\s*[:=]\\s*[^,;\\s}]+")) { "${it.groupValues[1]}=[REDACTED]" }
        .take(1200)

    private fun extractJsonString(raw: String, key: String): String? {
        val match = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").find(raw) ?: return null
        return match.groupValues[1]
            .replace("\\n", " ")
            .replace("\\r", " ")
            .replace("\\t", " ")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun extractJsonScalar(raw: String, key: String): String? {
        extractJsonString(raw, key)?.let { return it }
        return Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*([^,}\\s]+)")
            .find(raw)?.groupValues?.getOrNull(1)?.trim()?.trim('"')
    }
}
