package com.cyclone.mobile.ai.model

import android.content.Context
import com.cyclone.mobile.ai.OpenRouterModelPreset
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.ProviderFailure
import com.cyclone.mobile.ai.ProviderFailureClass
import com.cyclone.mobile.ai.SanitizedProviderFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface ModelQualificationOutcome {
    data class Passed(
        val profile: ModelProfile,
        val cached: Boolean,
        val repaired: Boolean = false,
        val providerName: String? = null,
        val requestId: String? = null,
    ) : ModelQualificationOutcome

    data class Failed(
        val profile: ModelProfile,
        val failure: SanitizedProviderFailure,
    ) : ModelQualificationOutcome
}

object ModelQualificationRuntime {
    val cache = InMemoryModelQualificationCache()
}

/**
 * Provider-only qualification. No phone APIs, observations, screenshots, task history or user goal
 * are accepted by this class, so failure cannot mutate the phone or become navigation evidence.
 */
class ModelQualificationRunner(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(55, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun qualify(model: OpenRouterModelPreset): ModelQualificationOutcome = withContext(Dispatchers.IO) {
        val profile = ModelRegistry.profileForPreset(model) ?: ModelProfile(
            cycloneId = model.id,
            displayName = model.label,
            openRouterSlug = model.id,
            providerFamily = ProviderFamily.OTHER,
            imageInput = model.vision,
            structuredOutputMode = StructuredOutputMode.PORTABLE_JSON,
            reasoningEffort = model.reasoningEffort,
        )
        if (ModelQualificationRuntime.cache.isQualified(profile)) {
            return@withContext ModelQualificationOutcome.Passed(profile, cached = true)
        }

        val apiKey = OpenRouterSecretStore.read(context)
        if (apiKey.isBlank()) {
            return@withContext ModelQualificationOutcome.Failed(
                profile,
                SanitizedProviderFailure(
                    failureClass = ProviderFailureClass.PROVIDER_AUTH_FAILED,
                    httpStatus = 0,
                    providerMessage = "OpenRouter API key is missing.",
                    selectedModelId = profile.cycloneId,
                    retryable = false,
                ),
            )
        }

        val first = request(profile, apiKey, profile.structuredOutputMode)
        val response = if (
            first is ModelQualificationOutcome.Failed &&
            first.failure.failureClass == ProviderFailureClass.PARAMETER_UNSUPPORTED &&
            profile.structuredOutputMode == StructuredOutputMode.SCHEMA_CONSTRAINED
        ) {
            // One bounded capability fallback for the SAME model. This never substitutes models.
            request(profile, apiKey, StructuredOutputMode.PORTABLE_JSON)
        } else first

        if (response is ModelQualificationOutcome.Passed) {
            ModelQualificationRuntime.cache.markQualified(profile)
        }
        response
    }

    private fun request(
        profile: ModelProfile,
        apiKey: String,
        mode: StructuredOutputMode,
    ): ModelQualificationOutcome {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", ModelQualificationContract.SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", ModelQualificationContract.USER_PROMPT))
        val provider = JSONObject()
            .put("sort", "latency")
            .put("allow_fallbacks", profile.allowProviderFallbacks)
            .put("require_parameters", mode == StructuredOutputMode.SCHEMA_CONSTRAINED)
        val body = JSONObject()
            .put("model", profile.openRouterSlug)
            .put("messages", messages)
            .put("provider", provider)
            .put("temperature", 0.0)
            .put("max_tokens", 300)
            .put("stream", false)
        if (mode == StructuredOutputMode.SCHEMA_CONSTRAINED) {
            body.put("response_format", JSONObject().put("type", "json_object"))
        }

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://github.com/premiumcentraal-boop/Cyclone")
            .header("X-Title", "Cyclone Mobile Model Qualification")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                val requestId = response.header("x-request-id")
                    ?: response.header("x-openrouter-request-id")
                val providerName = json?.optString("provider")?.takeIf { it.isNotBlank() }
                if (!response.isSuccessful) {
                    return ModelQualificationOutcome.Failed(
                        profile,
                        ProviderFailure.classify(
                            httpStatus = response.code,
                            rawBody = text,
                            selectedModelId = profile.cycloneId,
                            providerName = providerName,
                            requestId = requestId,
                        ),
                    )
                }
                val raw = json?.optJSONArray("choices")
                    ?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                parseQualified(profile, raw, providerName, requestId)
            }
        } catch (io: IOException) {
            ModelQualificationOutcome.Failed(
                profile,
                SanitizedProviderFailure(
                    failureClass = ProviderFailureClass.NETWORK_FAILURE,
                    httpStatus = 0,
                    providerMessage = ProviderFailure.sanitize(io.message.orEmpty()).takeIf { it.isNotBlank() },
                    selectedModelId = profile.cycloneId,
                    retryable = true,
                ),
            )
        }
    }

    private fun parseQualified(
        profile: ModelProfile,
        raw: String,
        providerName: String?,
        requestId: String?,
    ): ModelQualificationOutcome {
        val direct = runCatching { JSONObject(raw.trim()) }.getOrNull()
        val extracted = if (direct == null) BoundedJsonRepair.extractSingleObject(raw) else null
        val parsed = direct ?: extracted?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (parsed == null) return malformed(profile, providerName, requestId)
        val actions = parsed.optJSONArray("actions") ?: JSONArray()
        val valid = ModelQualificationContract.isQualifiedResult(
            status = parsed.optString("status"),
            answer = parsed.optString("answer").takeIf { it.isNotBlank() },
            actionCount = actions.length(),
        ) && parsed.optString("reason").isBlank()
        if (!valid) return malformed(profile, providerName, requestId)
        return ModelQualificationOutcome.Passed(
            profile = profile,
            cached = false,
            repaired = direct == null && extracted != null,
            providerName = providerName,
            requestId = requestId,
        )
    }

    private fun malformed(
        profile: ModelProfile,
        providerName: String?,
        requestId: String?,
    ): ModelQualificationOutcome.Failed = ModelQualificationOutcome.Failed(
        profile,
        SanitizedProviderFailure(
            failureClass = ProviderFailureClass.MALFORMED_MODEL_OUTPUT,
            httpStatus = 200,
            selectedModelId = profile.cycloneId,
            providerName = providerName,
            requestId = requestId,
            retryable = false,
        ),
    )
}
