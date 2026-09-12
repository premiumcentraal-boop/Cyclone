package com.cyclone.mobile.ai

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class CatalogModel(
    val id: String,
    val name: String,
    val imageInput: Boolean,
    val textOutput: Boolean,
    val contextLength: Int,
    val maxOutputTokens: Int?,
) {
    fun preset() = OpenRouterModelPreset(id, name, imageInput)
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name)
        .put("architecture", JSONObject().put("input_modalities", JSONArray(if (imageInput) listOf("text", "image") else listOf("text")))
            .put("output_modalities", JSONArray(if (textOutput) listOf("text") else listOf("other"))))
        .put("context_length", contextLength)
        .put("top_provider", JSONObject().put("max_completion_tokens", maxOutputTokens ?: JSONObject.NULL))
}

object OpenRouterCatalog {
    fun parse(body: String): List<CatalogModel> {
        val rows = JSONObject(body).getJSONArray("data")
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id").trim()
            if (!OpenRouterCustomModelStore.isValidSlug(id)) return@mapNotNull null
            val architecture = row.optJSONObject("architecture")
            fun has(array: JSONArray?, value: String): Boolean = array != null && (0 until array.length()).any { array.optString(it) == value }
            CatalogModel(id, row.optString("name").ifBlank { id }.take(200),
                has(architecture?.optJSONArray("input_modalities"), "image"),
                architecture?.optJSONArray("output_modalities")?.let { has(it, "text") } ?: true,
                row.optInt("context_length", 0),
                row.optJSONObject("top_provider")?.optInt("max_completion_tokens", 0)?.takeIf { it > 0 })
        }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }
    fun search(models: List<CatalogModel>, query: String): List<CatalogModel> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        return models.filter { model -> terms.all { it in "${model.name} ${model.id}".lowercase() } }
    }
}

data class CatalogFetch(val models: List<CatalogModel>, val availableIds: Set<String>?, val warning: String? = null)

/** Fixed OpenRouter origins only; no redirects carrying the user's bearer token. */
class OpenRouterCatalogClient(private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()) {
    fun fetch(apiKey: String): CatalogFetch {
        require(apiKey.isNotBlank()) { "Add your OpenRouter API key first." }
        fun get(path: String): List<CatalogModel> {
            val request = Request.Builder().url("https://openrouter.ai/api/v1/$path")
                .header("Authorization", "Bearer $apiKey").header("X-Title", "Cyclone Mobile").build()
            return http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || runCatching { JSONObject(body).has("error") }.getOrDefault(false)) {
                    val failure = ProviderFailure.classify(response.code, body)
                    throw IOException("OpenRouter HTTP ${failure.httpStatus}: ${failure.userMessage}")
                }
                try { OpenRouterCatalog.parse(body) } catch (_: Exception) { throw IOException("OpenRouter returned an invalid model catalog. Refresh to try again.") }
            }
        }
        // Omitting offset and limit requests the complete catalog, including non-text models.
        val all = get("models?output_modalities=all")
        require(all.isNotEmpty()) { "OpenRouter returned an empty catalog. Refresh to try again." }
        return try {
            CatalogFetch(all, get("models/user?output_modalities=all").map { it.id }.toSet())
        } catch (error: IOException) { CatalogFetch(all, null, error.message) }
    }
}
