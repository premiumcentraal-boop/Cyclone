package com.cyclone.mobile.mind.one

import android.content.Context
import org.json.JSONArray
import java.io.File

/** Plan 69: OneMind's owner setting (Settings → Speed → "Compact history at") and the recipe book on disk. */
object OneMindStore {
    private const val PREFS = "cyclone_ai"
    private const val COMPACT_KEY = "onemind_compact_at"
    private val lock = Any()

    fun compactAt(context: Context): CompactAt =
        CompactAt.of(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(COMPACT_KEY, null))

    fun setCompactAt(context: Context, value: CompactAt) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(COMPACT_KEY, value.name).apply()
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, "Cyclone Brain/OneMind recipes.json")

    fun recipes(context: Context): Map<String, Recipe> = synchronized(lock) {
        runCatching { file(context).takeIf { it.isFile }?.readText()?.let { Recipes.decode(JSONArray(it)) } }.getOrNull().orEmpty()
    }

    /** Plan 75 alpha 148: recipes saved under the old key (the exact words) still replay. */
    fun recipe(context: Context, goal: String): Recipe? = recipes(context).let { book ->
        book[Recipes.key(goal)] ?: book[com.cyclone.mobile.mind.decide.Replay.keyOf(goal)]
    }

    /** A finished run updates the book: a verified run writes its recipe, a miss on one drops it. */
    fun afterRun(context: Context, score: Score, end: RunEnd, usedRecipe: Boolean) = synchronized(lock) {
        runCatching {
            val before = recipes(context)
            val after = Recipes.after(before, score, end, System.currentTimeMillis(), usedRecipe)
            if (after != before) {
                val f = file(context)
                f.parentFile?.mkdirs()
                val temp = File(f.parentFile, f.name + ".tmp")
                temp.writeText(Recipes.encode(after).toString())
                if (!temp.renameTo(f)) { f.delete(); temp.renameTo(f) }
            }
        }
    }
}
