package com.cyclone.mobile.ai

import android.content.Context
import com.cyclone.mobile.ai.model.ModelRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Only public model metadata and explicit selections are persisted here. Keys stay in Keystore. */
object OpenRouterCatalogStore {
    private const val PREFS = "cyclone_openrouter_catalog"
    private const val SELECTION = "selected_models"
    val revision = MutableStateFlow(0)
    @Volatile private var catalog: List<CatalogModel> = emptyList()
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized fun initialize(context: Context) {
        if (catalog.isEmpty()) catalog = runCatching { OpenRouterCatalog.parse(prefs(context).getString("catalog", "{\"data\":[]}")!!) }.getOrDefault(emptyList())
        if (!prefs(context).contains(SELECTION)) {
            val old = context.getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE).getString("openrouter_model", null)
            val id = old?.let { ModelRegistry.resolve(it)?.openRouterSlug ?: it }?.takeIf { OpenRouterCustomModelStore.isValidSlug(it) }
            // Migration must not crash Settings when Android cannot flush preferences immediately.
            prefs(context).edit().putStringSet(SELECTION, setOfNotNull(id) + OpenRouterCustomModelStore.list(context).map { it.id }).apply()
        }
    }
    fun models(context: Context): List<CatalogModel> { initialize(context); return catalog }
    fun lookup(id: String): CatalogModel? = catalog.firstOrNull { it.id == id }
    fun selectedIds(context: Context): Set<String> { initialize(context); return prefs(context).getStringSet(SELECTION, emptySet()).orEmpty().toSet() }
    fun picker(context: Context): List<OpenRouterModelPreset> = selectedIds(context).sorted().map(OpenRouterModelPresets::byId)
    fun activeId(context: Context): String {
        val stored = context.getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE).getString("openrouter_model", "").orEmpty()
        val id = ModelRegistry.resolve(stored)?.openRouterSlug ?: stored
        return id.takeIf { it in selectedIds(context) }.orEmpty()
    }
    @Synchronized fun saveCatalog(context: Context, models: List<CatalogModel>) {
        check(prefs(context).edit().putString("catalog", JSONObject().put("data", JSONArray().also { array -> models.forEach { array.put(it.toJson()) } }).toString()).commit()) { "Could not save model catalog." }
        catalog = models
        revision.value++
    }
    @Synchronized fun select(context: Context, id: String, selected: Boolean) {
        require(OpenRouterCustomModelStore.isValidSlug(id))
        val next = ModelPickerSelection(selectedIds(context), activeId(context)).toggle(id, selected)
        try {
            check(prefs(context).edit().putStringSet(SELECTION, next.ids).commit()) { "Could not save model selection." }
            check(context.getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE).edit()
                .putString("openrouter_model", next.activeId).commit()) { "Could not update selected model." }
        } finally {
            // Even on a failed disk write, Android may update its in-memory preferences.
            revision.value++
        }
    }
}
