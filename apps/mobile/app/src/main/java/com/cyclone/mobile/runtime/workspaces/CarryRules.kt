package com.cyclone.mobile.runtime.workspaces

import com.cyclone.mobile.mind.MindMemory
import org.json.JSONArray
import org.json.JSONObject

/**
 * What one carry brought into this profile. Counts and names only, never content. Plan 57 P2 adds [files] (installs,
 * owner skills and manuals taken in), [settingsInPlace] (carried settings that now match the other profile) and
 * [skillsTotal] (skills and paths this profile now holds; -1 when unknown).
 */
data class CarryReport(
    val atMs: Long,
    val fromLabel: String,
    val memories: Int,
    val forgotten: Int,
    val skills: Int,
    val settings: Int,
    val files: Int = 0,
    val settingsInPlace: Int = -1,
    val skillsTotal: Int = -1,
    /** Plan 57 P3: what became of Cyclone Cloak's carried approval here (a ConnectorCarry.Outcome name), or null. */
    val cloak: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("at", atMs).put("from", fromLabel).put("memories", memories)
        .put("forgotten", forgotten).put("skills", skills).put("settings", settings)
        .put("files", files).put("settingsInPlace", settingsInPlace).put("skillsTotal", skillsTotal)
        .put("cloak", cloak ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject) = CarryReport(json.getLong("at"), json.getString("from"), json.optInt("memories"),
            json.optInt("forgotten"), json.optInt("skills"), json.optInt("settings"), json.optInt("files"),
            json.optInt("settingsInPlace", -1), json.optInt("skillsTotal", -1),
            json.optString("cloak").takeIf { json.has("cloak") && !json.isNull("cloak") && it.matches(Regex("[A-Z_]{1,40}")) })
    }
}

/**
 * Plan 40 P2, Cyclone Carry: what crosses between the owner's profiles on every switch, and how it merges. Pure.
 *
 * - **Carried, both ways:** people memory (labelled by profile, see `MemoryCarry`), the Brain's verified skills and
 *   paths, notes, how well Cyclone opens each app, and the Drive and look settings.
 * - **Never:** keys, tokens, pairing, sessions, the vault, chat history, missions and run logs. A setting whose name
 *   sounds like one of those, or whose value looks like a secret, is not carried.
 * - **Merging:** the row used most recently wins; notes are only added; nothing is deleted by a carry except
 *   memories the owner forgot.
 */
object CarryRules {
    const val VERSION = 1
    /** How the main profile names itself in a carry (other profiles use their `Cyclone_…` id). */
    const val MAIN = "main"
    const val MAIN_LABEL = "Profile A"
    const val ROWS_PER_TABLE = 2_000

    /** The Brain's tables that travel, with the columns carried, the key and the column that says which is newer. */
    data class Table(val name: String, val key: String, val time: String, val columns: List<String>, val onlyAdd: Boolean = false)

    val tables = listOf(
        Table("micro_skills", "signature", "last_used_at", listOf("signature", "name", "tool", "params_json", "goal_hints", "from_package",
            "from_fingerprint", "to_package", "to_fingerprint", "success_count", "failure_count", "confidence", "source", "last_used_at")),
        Table("learned_paths", "signature", "last_used_at",
            listOf("signature", "goal_key", "skills_json", "success_count", "failure_count", "confidence", "last_used_at")),
        Table("user_notes", "id", "created_at", listOf("id", "text", "source", "created_at"), onlyAdd = true),
    )

    /** Words the owner reads: the text columns checked for secrets before a row leaves or lands. */
    private val readable = setOf("name", "params_json", "goal_hints", "goal_key", "text")

    /** Whether the incoming row replaces (or adds to) this profile's. */
    fun takes(table: Table, localTime: Long?, incomingTime: Long): Boolean =
        localTime == null || (!table.onlyAdd && incomingTime > localTime)

    /** A row travels only whole, with a key, and with nothing in it that looks like a secret. */
    fun safeRow(table: Table, row: JSONObject): Boolean =
        table.columns.all(row::has) && row.optString(table.key).isNotBlank() &&
            !MindMemory.looksSecret(table.columns.filter { it in readable }.joinToString(" ") { row.optString(it) })

    /**
     * The settings that travel: the file, then the keys (null means every key in it). Plan 57 P2: derived from the one
     * classification table, [PortableSettings].
     */
    val settings: Map<String, Set<String>?> get() = PortableSettings.carried
    private val privateName = Regex("(?i)(^|_)(key|keys|token|secret|password|pin|otp|pairing|paired|session|auth|cookie|account)(_|$)")

    fun carriesSetting(file: String, key: String, value: Any?): Boolean {
        val entry = PortableSettings.entry(file)?.takeIf { it.kind == PortableSettings.Kind.CARRY } ?: return false
        if (entry.keys != null && key !in entry.keys) return false
        if (privateName.containsMatchIn(key)) return false
        // An id array is filtered item by item ([safeItems]); here only its shape is checked.
        if (key in entry.idArrays) return value is String && value.length <= ID_ARRAY_MAX_CHARS
        return when (value) {
            is Boolean, is Int, is Long, is Float -> true
            is String -> value.length <= entry.maxChars && !MindMemory.looksSecret(value)
            is Set<*> -> key in entry.sets && value.size <= 200 &&
                value.all { it is String && it.length <= 200 && !MindMemory.looksSecret(it) }
            else -> false
        }
    }

    const val ID_ARRAY_MAX_CHARS = 1_048_576

    /** The items of an id array that may leave: whole objects with an id, small enough, nothing secret-shaped. */
    fun safeItems(file: String, key: String, text: String?): JSONArray {
        val entry = PortableSettings.entry(file)?.takeIf { it.kind == PortableSettings.Kind.CARRY && key in it.idArrays } ?: return JSONArray()
        val array = runCatching { JSONArray(text ?: "[]") }.getOrNull() ?: return JSONArray()
        val out = JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val raw = item.toString()
            if (item.optString("id").isBlank() || raw.length > entry.maxChars || MindMemory.looksSecret(raw)) continue
            out.put(item)
        }
        return out
    }

    /**
     * Merges an incoming id array into this profile's: the same id is replaced, new ids are added, ids only here stay
     * (a carry never deletes a routine). Returns the merged text and how many items changed, or null when nothing did.
     */
    fun mergeItems(file: String, key: String, local: String?, incoming: JSONArray): Pair<String, Int>? {
        val safe = safeItems(file, key, incoming.toString())
        val mine = runCatching { JSONArray(local ?: "[]") }.getOrDefault(JSONArray())
        val merged = linkedMapOf<String, JSONObject>()
        for (i in 0 until mine.length()) mine.optJSONObject(i)?.let { merged[it.optString("id")] = it }
        var changed = 0
        for (i in 0 until safe.length()) {
            val item = safe.getJSONObject(i)
            val id = item.optString("id")
            if (merged[id]?.toString() != item.toString()) { merged[id] = item; changed++ }
        }
        if (changed == 0) return null
        val text = JSONArray(merged.values.toList()).toString()
        return if (text.length > ID_ARRAY_MAX_CHARS) null else text to changed
    }

    /** The context bound into a bundle's encryption: which profile it is for, and for which switch. */
    fun cipherContext(target: Int, nonce: String) = "cyclone-carry-v$VERSION:$target:$nonce"

    /** "Brought 12 memories and 40 skills from Work" for Profiles, or null when nothing came. */
    fun line(report: CarryReport): String? {
        val parts = listOfNotNull(
            report.memories.takeIf { it > 0 }?.let { "$it ${if (it == 1) "memory" else "memories"}" },
            report.skills.takeIf { it > 0 }?.let { "$it ${if (it == 1) "skill" else "skills"}" },
            report.settings.takeIf { it > 0 }?.let { "your settings" },
            report.files.takeIf { it > 0 }?.let { "$it ${if (it == 1) "saved item" else "saved items"}" },
        )
        if (parts.isEmpty()) return if (report.forgotten > 0) "Forgot ${report.forgotten} as you did in ${report.fromLabel}" else null
        val list = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        return "Brought $list from ${report.fromLabel}"
    }
}
