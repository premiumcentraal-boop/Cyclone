package com.cyclone.mobile.runtime.workspaces

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 57 P2 (alpha.120): what a profile has, as last checked on a switch into it. Shown in Profiles ("Profile C has:
 * Cyclone 5.0.0-alpha.120 ✓ · Magisk ✓ root ✓ · Shizuku ✓ · Cloak ✓ · 47 settings ✓ · 312 skills ✓") and in the debug
 * file. Names and counts only.
 */
data class ProfileInventory(
    val profileId: String,
    val atMs: Long,
    val cycloneVersion: String?,
    /** `MAGISK`, `KERNELSU`, `APATCH` or null. */
    val rootManager: String?,
    /** Root proven from the profile's own Cyclone; null when not checked. */
    val rootProven: Boolean?,
    val apps: List<App>,
    val settings: Int = -1,
    val skills: Int = -1,
    val files: Int = -1,
) {
    data class App(
        val packageName: String,
        val role: ProfileCornerstones.Role,
        val label: String,
        val installed: Boolean,
        /** Magisk root grant shared into the profile; null when it didn't apply. */
        val rootShared: Boolean? = null,
        val note: String? = null,
    )

    /** "Cyclone 5.0.0-alpha.120 ✓ · Magisk ✓ root ✓ · Shizuku ✓ · Cloak ✓ · 2 of your apps ✓ · 47 settings ✓ · 312 skills ✓" */
    fun line(): String {
        fun mark(ok: Boolean?) = when (ok) { true -> "✓"; false -> "✗"; null -> "?" }
        val parts = mutableListOf<String>()
        apps.filter { it.role == ProfileCornerstones.Role.CYCLONE }.forEach {
            parts += "Cyclone${cycloneVersion?.let { v -> " $v" } ?: ""} ${mark(it.installed)}"
        }
        val managers = apps.filter { it.role == ProfileCornerstones.Role.ROOT_MANAGER }
        if (managers.isNotEmpty() || rootManager != null || rootProven != null) {
            val name = rootManager?.let(ProfileProblem::managerName) ?: managers.firstOrNull()?.label
            val installed = if (managers.isEmpty()) null else managers.all { it.installed }
            val head = when { name == null -> null; installed == null -> name; else -> "$name ${mark(installed)}" }
            parts += listOfNotNull(head, "root ${mark(rootProven)}").joinToString(" ")
        }
        apps.filter { it.role == ProfileCornerstones.Role.SUPPORT || it.role == ProfileCornerstones.Role.CLOAK }
            .forEach { parts += "${it.label} ${mark(it.installed)}" }
        val owners = apps.filter { it.role == ProfileCornerstones.Role.OWNER }
        if (owners.isNotEmpty()) {
            val ok = owners.count { it.installed }
            parts += if (ok == owners.size) "${owners.size} of your apps ✓" else "$ok of ${owners.size} of your apps ✗"
        }
        if (settings >= 0) parts += "$settings settings ✓"
        if (skills >= 0) parts += "$skills skills ✓"
        return parts.joinToString(" · ")
    }

    /** True when something a profile is built on is missing. */
    val missing: List<App> get() = apps.filter { !it.installed }

    fun toJson(): JSONObject = JSONObject().put("profileId", profileId).put("at", atMs)
        .put("cycloneVersion", cycloneVersion ?: JSONObject.NULL).put("rootManager", rootManager ?: JSONObject.NULL)
        .put("rootProven", rootProven ?: JSONObject.NULL).put("settings", settings).put("skills", skills).put("files", files)
        .put("apps", JSONArray(apps.map {
            JSONObject().put("package", it.packageName).put("role", it.role.name).put("label", it.label).put("installed", it.installed)
                .put("rootShared", it.rootShared ?: JSONObject.NULL).put("note", it.note ?: JSONObject.NULL)
        }))

    companion object {
        fun fromJson(o: JSONObject): ProfileInventory? = runCatching {
            val apps = o.optJSONArray("apps") ?: JSONArray()
            ProfileInventory(
                o.getString("profileId"), o.optLong("at"),
                o.optString("cycloneVersion").takeIf { !o.isNull("cycloneVersion") },
                o.optString("rootManager").takeIf { !o.isNull("rootManager") },
                if (o.isNull("rootProven")) null else o.optBoolean("rootProven"),
                (0 until apps.length()).mapNotNull { i ->
                    val a = apps.optJSONObject(i) ?: return@mapNotNull null
                    val role = runCatching { ProfileCornerstones.Role.valueOf(a.optString("role")) }.getOrNull() ?: return@mapNotNull null
                    App(a.optString("package"), role, a.optString("label"), a.optBoolean("installed"),
                        if (a.isNull("rootShared")) null else a.optBoolean("rootShared"),
                        if (a.isNull("note")) null else a.optString("note"))
                },
                o.optInt("settings", -1), o.optInt("skills", -1), o.optInt("files", -1),
            )
        }.getOrNull()
    }
}

/** The last inventory of each profile, kept by the Cyclone that switched into it. */
object ProfileInventoryStore {
    const val PREFS = "cyclone_profile_inventory"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, profileId: String): ProfileInventory? =
        prefs(context).getString(profileId, null)?.let { runCatching { ProfileInventory.fromJson(JSONObject(it)) }.getOrNull() }

    fun all(context: Context): List<ProfileInventory> = prefs(context).all.values.mapNotNull { v ->
        (v as? String)?.let { runCatching { ProfileInventory.fromJson(JSONObject(it)) }.getOrNull() }
    }.sortedBy { it.profileId }

    @Synchronized fun save(context: Context, inventory: ProfileInventory) {
        require(ProfileSetupPlan.validProfileName(inventory.profileId))
        prefs(context).edit().putString(inventory.profileId, inventory.toJson().toString()).apply()
    }

    @Synchronized fun update(context: Context, profileId: String, change: (ProfileInventory) -> ProfileInventory) {
        get(context, profileId)?.let { save(context, change(it)) }
    }

    @Synchronized fun forget(context: Context, profileId: String) { prefs(context).edit().remove(profileId).apply() }
}
