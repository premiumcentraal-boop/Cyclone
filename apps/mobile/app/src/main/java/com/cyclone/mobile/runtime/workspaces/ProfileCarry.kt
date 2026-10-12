package com.cyclone.mobile.runtime.workspaces

import android.content.Context
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.mind.MemoryCarry
import com.cyclone.mobile.mind.mission.MindMissions
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 40 P2, Cyclone Carry: on every profile switch the Cyclone you leave packs what it knows, and the Cyclone you
 * open takes it in (see [CarryRules] for what crosses and how it merges). Packing and taking in happen inside each
 * profile's own Cyclone, so people memory is opened with one profile's Keystore key and sealed again with the other's;
 * in between it only exists inside an encrypted bundle ([ProfileTransferCipher.seal]).
 *
 * Trusted local switching only, like the rest of profiles: no model tool, no gateway call.
 */
internal object ProfileCarry {
    private const val PREFS = "cyclone_carry"
    private const val KEY_LAST = "last"
    private const val KEY_SENT_OK = "sent_ok"

    /** This profile's carry identity: [CarryRules.MAIN] for the main profile, else its `Cyclone_…` id; and its name. */
    fun me(context: Context): Pair<String, String> {
        val origin = context.createDeviceProtectedStorageContext().getSharedPreferences("cyclone_profile_origin", Context.MODE_PRIVATE)
            .getString("profile", null)?.takeIf(ProfileSetupPlan::validProfileName)
            ?: return CarryRules.MAIN to CarryRules.MAIN_LABEL
        val label = runCatching { ProfileRegistryStore.records(context).firstOrNull { it.id == origin }?.label }.getOrNull()
        return origin to (label?.takeIf { it.isNotBlank() } ?: "Another profile")
    }

    /** Everything this profile carries, as plain JSON: it is sealed before it leaves this process. */
    fun pack(context: Context): JSONObject {
        AdaptiveBrainRuntime.initialize(context)
        val (me, label) = me(context)
        return JSONObject().put("v", CarryRules.VERSION).put("from", me).put("from_label", label).put("at", System.currentTimeMillis())
            .put("memory", MindMissions.memory(context).export(me, label).toJson())
            .put("brain", AdaptiveBrainRuntime.store.carryRows())
            .put("settings", settings(context))
            // Plan 57 P1: the profiles, so every profile's Cyclone knows every profile (Main is the authority).
            .put("registry", ProfileRegistryCodec.encode(ProfileRegistryStore.records(context)))
            // Plan 57 P2: Market installs, owner skills and app manuals (see PortableFiles).
            .put("files", files(context))
            // Plan 57 P3: Cyclone Cloak's approval, re-verified by the receiving profile before it counts.
            .put("connectors", JSONArray(com.cyclone.mobile.connector.ConnectorRuntime.outgoing(context).map { it.toJson() }))
    }

    /** Takes in a bundle from another profile, and remembers what came for Profiles to show. */
    fun absorb(context: Context, bundle: JSONObject): CarryReport {
        require(bundle.optInt("v") == CarryRules.VERSION) { "Unknown carry bundle." }
        val (me, _) = me(context)
        val from = bundle.getString("from")
        require(from != me && (from == CarryRules.MAIN || ProfileSetupPlan.validProfileName(from))) { "Carry identity mismatch." }
        AdaptiveBrainRuntime.initialize(context)
        val memory = MindMissions.memory(context).absorb(MemoryCarry.Parcel.fromJson(bundle.optJSONObject("memory") ?: JSONObject()), me)
        val skills = AdaptiveBrainRuntime.store.absorbRows(bundle.optJSONObject("brain") ?: JSONObject())
        runCatching { AdaptiveBrainRuntime.store.writeMirror() }
        val (settings, inPlace) = applySettings(context, bundle.optJSONArray("settings") ?: JSONArray())
        val files = runCatching { applyFiles(context, bundle.optJSONObject("files") ?: JSONObject()) }.getOrDefault(0)
        val connectors = runCatching {
            val list = bundle.optJSONArray("connectors") ?: JSONArray()
            com.cyclone.mobile.connector.ConnectorRuntime.adoptCarried(context,
                (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let(com.cyclone.mobile.connector.ConnectorApproval::fromJson) })
        }.getOrDefault(emptyMap())
        bundle.optString("registry").takeIf { it.isNotBlank() }?.let { text ->
            runCatching {
                val incoming = ProfileRegistryCodec.decode(text)
                ProfileRegistryStore.replaceAll(context,
                    ProfileSwitch.mergeRegistry(ProfileRegistryStore.records(context), incoming, fromMain = from == CarryRules.MAIN))
            }
        }
        val report = CarryReport(System.currentTimeMillis(), bundle.optString("from_label").take(60).ifBlank { "another profile" },
            memory.added + memory.updated, memory.removed, skills, settings, files, inPlace,
            runCatching { AdaptiveBrainRuntime.store.skillCount() }.getOrDefault(-1),
            connectors[com.cyclone.mobile.connector.CycloneCloakProfileBinding.CONNECTOR_ID]?.name)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST, report.toJson().toString()).apply()
        return report
    }

    /** Whether the last carry out of this profile arrived, for Profiles to say so when it didn't. */
    fun noteSent(context: Context, ok: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SENT_OK, ok).apply()
    }

    fun lastSentFailed(context: Context): Boolean = !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SENT_OK, true)

    fun lastReport(context: Context): CarryReport? = runCatching {
        CarryReport.fromJson(JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, null)!!))
    }.getOrNull()

    private fun settings(context: Context): JSONArray {
        val out = JSONArray()
        CarryRules.settings.keys.forEach { file ->
            val entry = PortableSettings.entry(file) ?: return@forEach
            context.getSharedPreferences(file, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                if (!CarryRules.carriesSetting(file, key, value)) return@forEach
                val row = JSONObject().put("file", file).put("key", key)
                when {
                    key in entry.idArrays -> row.put("type", "items").put("value", CarryRules.safeItems(file, key, value as String))
                    value is Set<*> -> row.put("type", "ss").put("value", JSONArray(value.filterIsInstance<String>().sorted()))
                    else -> row.put("type", when (value) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; else -> "s" })
                        .put("value", value)
                }
                out.put(row)
            }
        }
        return out
    }

    /** Applies carried settings; returns how many changed and how many now match the other profile. */
    private fun applySettings(context: Context, settings: JSONArray): Pair<Int, Int> {
        val current = CarryRules.settings.keys.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all }
        val edits = CarryRules.settings.keys.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit() }
        var count = 0
        var inPlace = 0
        for (index in 0 until settings.length()) {
            val entry = settings.optJSONObject(index) ?: continue
            val file = entry.optString("file")
            val key = entry.optString("key")
            val edit = edits[file] ?: continue
            if (entry.optString("type") == "items") {
                // Plan 57 P2: routines merge by id; nothing here is deleted by a carry.
                val incoming = entry.optJSONArray("value") ?: continue
                inPlace++
                val (text, changed) = CarryRules.mergeItems(file, key, current[file]?.get(key) as? String, incoming) ?: continue
                if (!CarryRules.carriesSetting(file, key, text)) continue
                edit.putString(key, text)
                count += changed
                continue
            }
            val value: Any = when (entry.optString("type")) {
                "b" -> entry.optBoolean("value")
                "i" -> entry.optInt("value")
                "l" -> entry.optLong("value")
                "f" -> entry.optDouble("value").toFloat()
                "s" -> entry.optString("value")
                "ss" -> entry.optJSONArray("value")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: continue
                else -> continue
            }
            if (!CarryRules.carriesSetting(file, key, value)) continue
            inPlace++
            if (current[file]?.get(key) == value) continue
            when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is String -> edit.putString(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
            count++
        }
        edits.values.forEach { it.apply() }
        return count to inPlace
    }

    // Plan 57 P2: files ------------------------------------------------------------------------------------------------

    private fun read(context: Context, path: String): String? = runCatching {
        java.io.File(context.filesDir, path).takeIf { it.isFile }?.readText()
    }.getOrNull()

    private fun write(context: Context, path: String, text: String) {
        val file = java.io.File(context.filesDir, path)
        file.parentFile?.mkdirs()
        val atomic = android.util.AtomicFile(file)
        val out = atomic.startWrite()
        try { out.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
    }

    private fun files(context: Context): JSONObject {
        val skills = PortableFiles.safeSkills(read(context, PortableFiles.OWNER_SKILLS))
        val out = JSONObject()
            .put("installs", PortableFiles.safeInstalls(read(context, PortableFiles.MARKET_INSTALLS)))
            .put("skills", skills)
            .put("anchors", PortableFiles.safeAnchors(read(context, PortableFiles.OWNER_ANCHORS), skills))
        val folder = java.io.File(context.filesDir, PortableFiles.MANUALS)
        val manuals = folder.listFiles().orEmpty().filter { it.isFile && PortableFiles.validManualName(it.name) && it.length() <= PortableFiles.MANUAL_MAX_CHARS }
            .associate { it.name to it.readText() }
        var room = PortableFiles.TOTAL_MAX_CHARS - out.toString().length
        val kept = JSONObject()
        PortableFiles.safeManuals(manuals).forEach { (name, text) -> if (text.length < room) { kept.put(name, text); room -= text.length } }
        return out.put("manuals", kept)
    }

    private fun applyFiles(context: Context, files: JSONObject): Int {
        var count = 0
        files.optJSONArray("installs")?.let { incoming ->
            PortableFiles.mergeInstalls(read(context, PortableFiles.MARKET_INSTALLS), incoming)?.let { (text, n) -> write(context, PortableFiles.MARKET_INSTALLS, text); count += n }
        }
        files.optJSONArray("skills")?.let { incoming ->
            PortableFiles.mergeSkills(read(context, PortableFiles.OWNER_SKILLS), incoming)?.let { (text, n) -> write(context, PortableFiles.OWNER_SKILLS, text); count += n }
        }
        files.optJSONObject("anchors")?.let { incoming ->
            PortableFiles.mergeAnchors(read(context, PortableFiles.OWNER_ANCHORS), incoming)?.let { write(context, PortableFiles.OWNER_ANCHORS, it) }
        }
        files.optJSONObject("manuals")?.let { incoming ->
            val folder = java.io.File(context.filesDir, PortableFiles.MANUALS)
            val local = folder.listFiles().orEmpty().map { it.name }.toSet()
            val all = incoming.keys().asSequence().associateWith { incoming.optString(it) }
            val add = PortableFiles.manualsToAdd(local, all)
            add.forEach { (name, text) -> write(context, "${PortableFiles.MANUALS}/$name", text) }
            if (add.isNotEmpty()) runCatching { com.cyclone.mobile.manual.ManualRuntime.forgetCached() }
            count += add.size
        }
        return count
    }
}
