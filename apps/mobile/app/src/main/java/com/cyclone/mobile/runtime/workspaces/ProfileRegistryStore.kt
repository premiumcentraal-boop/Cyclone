package com.cyclone.mobile.runtime.workspaces

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Collection of locally journaled identities. Labels alone never confer ownership. */
data class CycloneProfileRecord(
    val id: String, val label: String, val androidUserId: Int?, val parentUserId: Int,
    val secondaryUser: Boolean, val packages: Set<String>, val stage: String, val ready: Boolean,
    /** Plan 40 P1: set while the profile is in Recently deleted (stopped, hidden, restorable for 7 days). */
    val removedAtMs: Long? = null,
    /** The owner's look for it: an emoji and a colour (ARGB), shown on the slider and in Profiles. */
    val emoji: String? = null,
    val color: Long? = null,
    /**
     * Plan 51: each approved connector's own data on this profile, `connectorId -> JSON object text`. Cyclone keeps it
     * untouched through every save and gives it back only to that connector: never to Glass, the PC, a model or logs.
     */
    val ext: Map<String, String> = emptyMap(),
) {
    val inTrash: Boolean get() = removedAtMs != null
}

/**
 * The registry's stored form (pure, tested). Schema 2 (plan 51) adds `ext`; schema 1 records read as 2 with none.
 * Every field Cyclone knows is written back, and `ext` is carried through every save.
 */
internal object ProfileRegistryCodec {
    const val SCHEMA_VERSION = 2

    fun decode(text: String?): List<CycloneProfileRecord> {
        val array = JSONArray(text ?: "[]")
        return (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            val apps = o.getJSONArray("packages")
            val extObject = o.optJSONObject("ext")
            val ext = extObject?.keys()?.asSequence()?.mapNotNull { key ->
                extObject.optJSONObject(key)?.let { key to it.toString() }
            }?.toMap().orEmpty()
            CycloneProfileRecord(
                o.getString("id"),
                o.getString("label"),
                o.optInt("user", -1).takeIf { it > 0 },
                o.getInt("parent"),
                o.optBoolean("secondary"),
                (0 until apps.length()).map { apps.getString(it) }.toSet(),
                o.getString("stage"),
                o.getBoolean("ready"),
                o.optLong("removed_at", 0L).takeIf { it > 0L },
                o.optString("emoji").takeIf { it.isNotBlank() },
                o.optLong("color", 0L).takeIf { it != 0L },
                ext,
            )
        }
    }

    fun encode(entries: List<CycloneProfileRecord>): String {
        val array = JSONArray()
        entries.forEach { record ->
            val ext = JSONObject()
            record.ext.toSortedMap().forEach { (key, value) -> ext.put(key, JSONObject(value)) }
            array.put(
                JSONObject()
                    .put("id", record.id)
                    .put("label", record.label)
                    .put("user", record.androidUserId ?: -1)
                    .put("parent", record.parentUserId)
                    .put("secondary", record.secondaryUser)
                    .put("packages", JSONArray(record.packages.sorted()))
                    .put("stage", record.stage)
                    .put("ready", record.ready)
                    .put("removed_at", record.removedAtMs ?: 0L)
                    .put("emoji", record.emoji ?: "")
                    .put("color", record.color ?: 0L)
                    .put("ext", ext),
            )
        }
        return array.toString()
    }
}

object ProfileRegistryStore {
    const val JOURNAL_DISPLAY_LABEL = "display_label"

    private fun store(context: Context) = context.getSharedPreferences("cyclone_profile_registry", Context.MODE_PRIVATE)

    @Synchronized fun records(context: Context): List<CycloneProfileRecord> =
        ProfileRegistryCodec.decode(store(context).getString("profiles", "[]"))

    @Synchronized fun checkpoint(context: Context, journal: SharedPreferences) {
        val id = journal.getString("name", null) ?: return
        require(ProfileSetupPlan.validProfileName(id))
        val existing = records(context)
        val previous = existing.singleOrNull { it.id == id }
        val requestedLabel = journal.getString(JOURNAL_DISPLAY_LABEL, null)
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(40)
            ?.takeIf { it.isNotBlank() }
        val fallbackLabel = "Profile ${('B'.code + existing.count { it.id != id }).toChar()}"
        val displayLabel = requestedLabel ?: previous?.label ?: fallbackLabel
        check(existing.none { it.id != id && it.label.equals(displayLabel, ignoreCase = true) }) {
            "Another profile already uses that name."
        }
        val row = CycloneProfileRecord(
            id,
            displayLabel,
            journal.getInt("user", -1).takeIf { it > 0 },
            journal.getInt("parent_user", 0),
            journal.getBoolean("secondary", false),
            journal.getStringSet("plan_apps", emptySet()).orEmpty().toSet(),
            journal.getString("stage", "PLANNED").orEmpty(),
            journal.getBoolean("ready", false),
            removedAtMs = previous?.removedAtMs,
            emoji = previous?.emoji,
            color = previous?.color,
            ext = previous?.ext.orEmpty(),
        )
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
        val clean = cleanLabel(label)
        val entries = records(context)
        check(entries.none { it.id != id && it.label.equals(clean, ignoreCase = true) }) {
            "Another profile already uses that name."
        }
        check(entries.any { it.id == id }) { "Profile not found." }
        save(context, entries.map { if (it.id == id) it.copy(label = clean) else it })
    }

    /** Name, emoji and colour together (plan 40 P1's rename sheet). */
    @Synchronized fun setLook(context: Context, id: String, label: String, emoji: String?, color: Long?) {
        rename(context, id, label)
        val cleanEmoji = emoji?.trim()?.takeIf { it.isNotEmpty() }?.take(16)
        save(context, records(context).map { if (it.id == id) it.copy(emoji = cleanEmoji, color = color) else it })
    }

    /** Moves a profile to Recently deleted. Only [ProfileLifecycle] calls this, after stopping the profile. */
    @Synchronized internal fun markRemoved(context: Context, id: String, atMs: Long) {
        check(records(context).any { it.id == id }) { "Profile not found." }
        save(context, records(context).map { if (it.id == id) it.copy(removedAtMs = atMs) else it })
    }

    /** Takes a profile out of Recently deleted, exactly as it was. */
    @Synchronized fun restore(context: Context, id: String) {
        check(records(context).any { it.id == id && it.inTrash }) { "This profile isn't in Recently deleted." }
        save(context, records(context).map { if (it.id == id) it.copy(removedAtMs = null) else it })
    }

    /** Forgets a profile after Android removed it for good. */
    @Synchronized internal fun drop(context: Context, id: String) {
        save(context, records(context).filterNot { it.id == id })
    }

    /** Plan 51: sets (or, with null, clears) one connector's data on one profile. Rules are checked by the caller. */
    @Synchronized fun setExt(context: Context, id: String, connectorId: String, json: String?) {
        val entries = records(context)
        check(entries.any { it.id == id }) { "Profile not found." }
        save(context, entries.map { record ->
            if (record.id != id) record
            else record.copy(ext = if (json == null) record.ext - connectorId else record.ext + (connectorId to json))
        })
    }

    /** Plan 51: a connector was revoked or uninstalled; its data leaves every profile. */
    @Synchronized fun dropExt(context: Context, connectorId: String) {
        val entries = records(context)
        if (entries.none { connectorId in it.ext }) return
        save(context, entries.map { it.copy(ext = it.ext - connectorId) })
    }

    fun cleanLabel(label: String): String {
        val clean = label.trim().replace(Regex("\\s+"), " ").take(40)
        require(clean.isNotBlank()) { "Give this profile a name." }
        return clean
    }

    private fun save(context: Context, entries: List<CycloneProfileRecord>) {
        val before = records(context)
        check(store(context).edit()
            .putString("profiles", ProfileRegistryCodec.encode(entries))
            .putInt("schema_version", ProfileRegistryCodec.SCHEMA_VERSION)
            .commit()) {
            "Couldn't save profile registry."
        }
        // Plan 51: connectors hear about it. Best effort: the registry never fails because of a connector.
        runCatching { com.cyclone.mobile.connector.ConnectorEvents.changed(context, before, entries) }
    }
}

/**
 * Packages Cyclone may automatically make visible in a secondary user. This is deliberately an
 * allowlist: a model cannot add arbitrary privileged apps, and no package data is copied.
 */
object ProfileRequiredPackages {
    val supportAllowlist: Set<String> = setOf(
        "moe.shizuku.privileged.api", // Shizuku
        "com.topjohnwu.magisk",      // Magisk
        "me.weishu.kernelsu",        // KernelSU
        "com.rifsxd.ksunext",        // KernelSU Next
        "me.bmax.apatch",            // APatch
    )

    fun resolve(
        cyclonePackage: String,
        selected: Set<String>,
        installedSupportPackages: Set<String> = emptySet(),
    ): Set<String> {
        require(ProfileSetupPlan.validPackageName(cyclonePackage))
        require(selected.all(ProfileSetupPlan::validPackageName))
        require(installedSupportPackages.all { it in supportAllowlist })
        return selected + cyclonePackage + installedSupportPackages
    }
}
