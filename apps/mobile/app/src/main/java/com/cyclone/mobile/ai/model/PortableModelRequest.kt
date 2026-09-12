package com.cyclone.mobile.ai.model

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Shared compatibility contract. No invented effort names or provider-specific sampling settings. */
object PortableModelRequest {
    fun body(modelId: String, messages: JSONArray, providers: List<String> = emptyList(), outputTokens: Int = 8192): JSONObject {
        val profile = ModelRegistry.resolve(modelId)
        val maximum = com.cyclone.mobile.ai.OpenRouterCatalogStore.lookup(modelId)?.maxOutputTokens ?: 16384
        val provider = JSONObject().put("sort", "latency")
            .put("allow_fallbacks", profile?.allowProviderFallbacks ?: !modelId.contains("contributor", true))
        if (providers.isNotEmpty()) provider.put("only", JSONArray(providers))
        return JSONObject().put("model", modelId).put("messages", messages).put("stream", false)
            .put("max_tokens", outputTokens.coerceIn(1, minOf(maximum, 16384)))
            .put("provider", provider)
    }
}

/** Public capability discovery only. Account access is established separately by qualification. */
object ModelEndpointCatalog {
    private data class Entry(val at: Long, val tags: List<String>)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    fun verifiedTags(modelId: String, http: OkHttpClient): List<String> {
        val now = System.currentTimeMillis()
        cache[modelId]?.takeIf { now - it.at in 0..900000 }?.let { return it.tags }
        val request = Request.Builder().url("https://openrouter.ai/api/v1/models/$modelId/endpoints").build()
        val tags = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Endpoint discovery returned HTTP ${response.code}")
            val data = JSONObject(response.body?.string().orEmpty()).getJSONObject("data")
            if (data.optString("id") != modelId) throw IOException("Endpoint identity mismatch")
            eligibleTags(data.getJSONArray("endpoints"))
        }
        if (tags.isEmpty()) throw IOException("No available endpoint supports the portable request contract")
        cache[modelId] = Entry(now, tags)
        return tags
    }
    fun eligibleTags(endpoints: JSONArray): List<String> = (0 until endpoints.length()).mapNotNull { i ->
        val endpoint = endpoints.optJSONObject(i) ?: return@mapNotNull null
        val parameters = endpoint.optJSONArray("supported_parameters") ?: return@mapNotNull null
        if (endpoint.optInt("status", -1) != 0 || (0 until parameters.length()).none { parameters.optString(it) == "max_tokens" }) return@mapNotNull null
        endpoint.optString("tag").takeIf { it.matches(Regex("[a-zA-Z0-9_./:-]+")) }
    }.distinct()
}
