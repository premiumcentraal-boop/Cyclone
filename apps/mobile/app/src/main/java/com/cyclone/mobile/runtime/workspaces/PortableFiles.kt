package com.cyclone.mobile.runtime.workspaces

import com.cyclone.mobile.mind.MindMemory
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 57 P2 (alpha.120): the files that travel with a carry, next to the settings in [PortableSettings], and how
 * each one merges. Pure; [ProfileCarry] reads and writes them.
 *
 * - **Market installs** (`Cyclone Brain/Marketplace/installed.json`): by id; the install used most recently wins. An
 *   install whose inputs look secret never leaves.
 * - **Owner skills** (`owner-skills.json`, and where each one works on an app's map, `owner-skills-anchors.json`):
 *   only added. The id is a hash of the goal, so the same id is the same skill.
 * - **App manuals** (`manual/dictionaries/<package>.json`): added when this profile has none for that app; a manual
 *   this profile already has is kept.
 *
 * A carry never deletes anything here.
 */
object PortableFiles {
    const val MARKET_INSTALLS = "Cyclone Brain/Marketplace/installed.json"
    const val OWNER_SKILLS = "Cyclone Brain/Marketplace/owner-skills.json"
    const val OWNER_ANCHORS = "Cyclone Brain/Marketplace/owner-skills-anchors.json"
    const val MANUALS = "manual/dictionaries"
    const val ITEM_MAX_CHARS = 32_768
    const val MANUAL_MAX_CHARS = 262_144
    const val MANUALS_MAX = 200
    const val TOTAL_MAX_CHARS = 4_194_304

    private val manualName = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+\\.json$")

    fun validManualName(name: String): Boolean = name.matches(manualName)

    private fun clean(text: String, max: Int): Boolean = text.length <= max && !MindMemory.looksSecret(text)

    private fun array(text: String?): JSONArray = runCatching { JSONArray(text ?: "[]") }.getOrDefault(JSONArray())

    private fun ids(array: JSONArray): LinkedHashMap<String, JSONObject> {
        val out = linkedMapOf<String, JSONObject>()
        for (i in 0 until array.length()) array.optJSONObject(i)?.let { o -> o.optString("id").takeIf { it.isNotBlank() }?.let { out[it] = o } }
        return out
    }

    // Market installs -----------------------------------------------------------------------------------------------

    private fun lastUsed(install: JSONObject): Long =
        maxOf(install.optLong("installedAt"), if (install.isNull("lastRunAt")) 0L else install.optLong("lastRunAt"))

    /** The installs that may leave this profile. */
    fun safeInstalls(text: String?): JSONArray = JSONArray(ids(array(text)).values.filter { install ->
        val inputs = install.optJSONObject("inputs") ?: JSONObject()
        clean(install.toString(), ITEM_MAX_CHARS) && inputs.keys().asSequence().none { MindMemory.looksSecret(inputs.optString(it)) }
    })

    /** Merged installs and how many changed, or null when nothing did. */
    fun mergeInstalls(local: String?, incoming: JSONArray): Pair<String, Int>? {
        val mine = ids(array(local))
        var changed = 0
        ids(safeInstalls(incoming.toString())).forEach { (id, theirs) ->
            val ours = mine[id]
            if (ours == null || lastUsed(theirs) > lastUsed(ours)) { mine[id] = theirs; changed++ }
        }
        return if (changed == 0) null else JSONArray(mine.values.toList()).toString() to changed
    }

    // Owner skills --------------------------------------------------------------------------------------------------

    fun safeSkills(text: String?): JSONArray = JSONArray(ids(array(text)).values.filter {
        it.optString("id").startsWith("you.") && clean(it.toString(), ITEM_MAX_CHARS)
    })

    /** Owner skills are only added. */
    fun mergeSkills(local: String?, incoming: JSONArray): Pair<String, Int>? {
        val mine = ids(array(local))
        var added = 0
        ids(safeSkills(incoming.toString())).forEach { (id, skill) -> if (id !in mine) { mine[id] = skill; added++ } }
        return if (added == 0) null else JSONArray(mine.values.toList()).toString() to added
    }

    fun safeAnchors(text: String?, skills: JSONArray): JSONObject {
        val known = ids(skills).keys
        val all = runCatching { JSONObject(text ?: "{}") }.getOrDefault(JSONObject())
        val out = JSONObject()
        all.keys().asSequence().filter { it in known }.forEach { id ->
            all.optJSONObject(id)?.takeIf { clean(it.toString(), ITEM_MAX_CHARS) }?.let { out.put(id, it) }
        }
        return out
    }

    fun mergeAnchors(local: String?, incoming: JSONObject): String? {
        val mine = runCatching { JSONObject(local ?: "{}") }.getOrDefault(JSONObject())
        var added = 0
        incoming.keys().asSequence().toList().forEach { id ->
            if (!mine.has(id)) incoming.optJSONObject(id)?.takeIf { clean(it.toString(), ITEM_MAX_CHARS) }?.let { mine.put(id, it); added++ }
        }
        return if (added == 0) null else mine.toString()
    }

    // App manuals ---------------------------------------------------------------------------------------------------

    /** Which manuals may leave: a valid name, small enough, readable JSON, nothing secret-shaped; at most [MANUALS_MAX]. */
    fun safeManuals(files: Map<String, String>): Map<String, String> = files.entries
        .filter { (name, text) -> validManualName(name) && clean(text, MANUAL_MAX_CHARS) && runCatching { JSONObject(text) }.isSuccess }
        .sortedBy { it.key }.take(MANUALS_MAX).associate { it.key to it.value }

    /** The manuals to write here: only apps this profile has no manual for. */
    fun manualsToAdd(localNames: Set<String>, incoming: Map<String, String>): Map<String, String> =
        safeManuals(incoming).filterKeys { it !in localNames }
}
