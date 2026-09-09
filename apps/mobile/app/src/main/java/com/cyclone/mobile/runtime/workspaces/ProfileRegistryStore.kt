package com.cyclone.mobile.runtime.workspaces

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Collection of locally journaled identities. Labels alone never confer ownership. */
data class CycloneProfileRecord(
    val id: String, val label: String, val androidUserId: Int?, val parentUserId: Int,
    val secondaryUser: Boolean, val packages: Set<String>, val stage: String, val ready: Boolean,
)

object ProfileRegistryStore {
    private fun store(context: Context) = context.getSharedPreferences("cyclone_profile_registry", Context.MODE_PRIVATE)
    @Synchronized fun records(context: Context): List<CycloneProfileRecord> {
        val array = JSONArray(store(context).getString("profiles", "[]"))
        return (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            val apps = o.getJSONArray("packages")
            CycloneProfileRecord(o.getString("id"), o.getString("label"), o.optInt("user", -1).takeIf { it > 0 },
                o.getInt("parent"), o.optBoolean("secondary"),
                (0 until apps.length()).map { apps.getString(it) }.toSet(), o.getString("stage"), o.getBoolean("ready"))
        }
    }
    @Synchronized fun checkpoint(context: Context, journal: SharedPreferences) {
        val id = journal.getString("name", null) ?: return
        require(ProfileSetupPlan.validProfileName(id))
        val existing = records(context)
        val previous = existing.singleOrNull { it.id == id }
        val row = CycloneProfileRecord(id, previous?.label ?: "Profile ${('B'.code + existing.size).toChar()}",
            journal.getInt("user", -1).takeIf { it > 0 }, journal.getInt("parent_user", 0),
            journal.getBoolean("secondary", false), journal.getStringSet("plan_apps", emptySet()).orEmpty().toSet(),
            journal.getString("stage", "PLANNED").orEmpty(), journal.getBoolean("ready", false))
        save(context, existing.filterNot { it.id == id } + row)
    }

    @Synchronized fun findByLabel(context: Context, label: String): CycloneProfileRecord? {
        val wanted = label.trim()
        if (wanted.isBlank()) return null
        return records(context).singleOrNull {
            it.label.equals(wanted, ignoreCase = true) || it.id.equals(wanted, ignoreCase = true)
        }
    }

    @Synchronized fun rename(context: Context, id: String, label: String) {
        val clean = label.trim().replace(Regex("\\s+"), " ").take(40)
        require(clean.isNotBlank())
        val entries = records(context)
        check(entries.none { it.id != id && it.label.equals(clean, ignoreCase = true) }) { "Another profile already uses that name." }
        check(entries.any { it.id == id }) { "Profile not found." }
        save(context, entries.map { if (it.id == id) it.copy(label = clean) else it })
    }

    private fun save(context: Context, entries: List<CycloneProfileRecord>) {
        val array = JSONArray()
        entries.forEach { record ->
            array.put(JSONObject().put("id", record.id).put("label", record.label).put("user", record.androidUserId ?: -1)
                .put("parent", record.parentUserId).put("secondary", record.secondaryUser)
                .put("packages", JSONArray(record.packages.sorted())).put("stage", record.stage).put("ready", record.ready))
        }
        check(store(context).edit().putString("profiles", array.toString()).commit()) { "Couldn't save profile registry." }
    }
}

/** Root is supplied by the host su daemon. No root-manager APK is required in another user. */
object ProfileRequiredPackages {
    fun resolve(cyclonePackage: String, selected: Set<String>): Set<String> {
        require(ProfileSetupPlan.validPackageName(cyclonePackage))
        require(selected.all(ProfileSetupPlan::validPackageName))
        return selected + cyclonePackage
    }
}
