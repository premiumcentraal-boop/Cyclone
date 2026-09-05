package com.cyclone.mobile.ai

/** Provider failures are task blockers, not malformed plans to be retried on the same screen. */
internal object ProviderFailure {
    fun code(httpStatus: Int): String = when (httpStatus) {
        401, 403 -> "provider.authentication"
        402 -> "provider.credit"
        429 -> "provider.rate_limit"
        0, 408, 504 -> "provider.timeout_or_network"
        else -> "provider.unavailable"
    }

    fun message(code: String): String? = when (code) {
        "provider.authentication" -> "The model provider rejected the API key or access. Check the key and model in Settings."
        "provider.credit" -> "The model provider reports insufficient credit. Check your OpenRouter account."
        "provider.rate_limit" -> "The model provider is rate-limiting requests. Wait before trying again."
        "provider.timeout_or_network" -> "The model request timed out or the network is unavailable. Check your connection and try again."
        "provider.unavailable" -> "The model provider could not serve this request. Try again or select another model."
        else -> null
    }
}
