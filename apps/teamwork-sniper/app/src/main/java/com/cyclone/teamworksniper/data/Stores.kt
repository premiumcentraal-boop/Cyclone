package com.cyclone.teamworksniper.data

import android.content.Context
import android.content.SharedPreferences
import com.cyclone.teamworksniper.rules.RuleJson
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS = "teamwork_sniper"
private const val RULES = "rules_json"
private const val ENABLED = "sniper_enabled"
private const val ARMED = "sniper_armed"
private const val AI_ENABLED = "ai_enabled"
private const val AI_MODEL = "ai_model"
private const val ACTIVITY = "activity_json"
private const val UI_MAP = "ui_map_hint_json"
private const val MAX = 100

fun sniperPreferences(context: Context): SharedPreferences =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

class RuleStore(context: Context) {
    private val p = sniperPreferences(context)
    fun load() = RuleJson.decode(p.getString(RULES, null))
    fun save(value: List<ShiftRule>) {
        p.edit().putString(RULES, RuleJson.encode(value)).apply()
    }
}

class SettingsStore(context: Context) {
    private val p = sniperPreferences(context)
    fun load() = SniperSettings(p.getBoolean(ENABLED, true), p.getBoolean(ARMED, false))
    fun save(value: SniperSettings) {
        p.edit().putBoolean(ENABLED, value.enabled).putBoolean(ARMED, value.armed).apply()
    }
}

class AiSettingsStore(context: Context) {
    private val p = sniperPreferences(context)
    fun load() = AiSettings(
        enabled = p.getBoolean(AI_ENABLED, false),
        model = p.getString(AI_MODEL, "openrouter/auto").orEmpty().ifBlank { "openrouter/auto" },
    )
    fun save(value: AiSettings) {
        p.edit()
            .putBoolean(AI_ENABLED, value.enabled)
            .putString(AI_MODEL, value.model.trim().ifBlank { "openrouter/auto" })
            .apply()
    }
}

class UiMapStore(context: Context) {
    private val p = sniperPreferences(context)

    fun load(): UiMapHint? {
        val raw = p.getString(UI_MAP, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            UiMapHint(
                resourceId = json.optString("resourceId").takeIf { it.isNotBlank() },
                semanticLabel = json.optString("semanticLabel").takeIf { it.isNotBlank() },
                updatedAtEpochMs = json.optLong("updatedAtEpochMs"),
            )
        }.getOrNull()
    }

    fun save(resourceId: String?, semanticLabel: String?) {
        val id = resourceId?.trim()?.takeIf { it.isNotBlank() }?.take(200)
        val label = semanticLabel?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.isNotBlank() }?.take(160)
        if (id == null && label == null) return
        val json = JSONObject()
            .put("resourceId", id ?: JSONObject.NULL)
            .put("semanticLabel", label ?: JSONObject.NULL)
            .put("updatedAtEpochMs", System.currentTimeMillis())
        p.edit().putString(UI_MAP, json.toString()).apply()
    }
}

class ActivityLogStore(context: Context) {
    private val p = sniperPreferences(context)

    @Synchronized
    fun append(entry: ActivityEntry) {
        save((listOf(entry) + load()).take(MAX))
    }

    @Synchronized
    fun load(): List<ActivityEntry> {
        val raw = p.getString(ACTIVITY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    decode(array.optJSONObject(index) ?: continue)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(values: List<ActivityEntry>) {
        val array = JSONArray()
        values.forEach { array.put(encode(it)) }
        p.edit().putString(ACTIVITY, array.toString()).apply()
    }

    private fun encode(entry: ActivityEntry) = JSONObject().apply {
        put("id", entry.id)
        put("source", entry.triggerSource.name)
        put("time", entry.triggerEpochMs)
        putN("title", entry.notificationTitle.safe())
        putN("text", entry.notificationText.safe())
        putN("openLatency", entry.teamworkOpenLatencyMs)
        putN("compareLatency", entry.firstComparisonLatencyMs)
        putN("evaluationDuration", entry.evaluationDurationMs)
        putN("claimDuration", entry.claimDurationMs)
        put("shifts", JSONArray(entry.openShifts))
        put("rules", JSONArray(entry.evaluatedRules))
        put("decision", entry.decision)
        put("armed", entry.armedState)
        put("attempted", entry.claimAttempted)
        putN("claim", entry.claimResult)
        putN("verify", entry.verificationResult)
        putN("failure", entry.failureReason)
        put("decisionEngine", entry.decisionEngine)
        putN("aiAdvice", entry.aiAdvice.safe())
    }

    private fun decode(json: JSONObject): ActivityEntry? {
        val source = runCatching { TriggerSource.valueOf(json.getString("source")) }.getOrNull() ?: return null
        return ActivityEntry(
            id = json.optString("id"),
            triggerSource = source,
            triggerEpochMs = json.optLong("time"),
            notificationTitle = json.s("title"),
            notificationText = json.s("text"),
            teamworkOpenLatencyMs = json.l("openLatency"),
            firstComparisonLatencyMs = json.l("compareLatency"),
            evaluationDurationMs = json.l("evaluationDuration"),
            claimDurationMs = json.l("claimDuration"),
            openShifts = json.optJSONArray("shifts").strings(),
            evaluatedRules = json.optJSONArray("rules").strings(),
            decision = json.optString("decision"),
            armedState = json.optBoolean("armed"),
            claimAttempted = json.optBoolean("attempted"),
            claimResult = json.s("claim"),
            verificationResult = json.s("verify"),
            failureReason = json.s("failure"),
            decisionEngine = json.optString("decisionEngine", "DETERMINISTIC").ifBlank { "DETERMINISTIC" },
            aiAdvice = json.s("aiAdvice"),
        )
    }
}

private fun JSONObject.putN(key: String, value: Any?) {
    put(key, value ?: JSONObject.NULL)
}

private fun JSONObject.s(key: String) =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.l(key: String) =
    if (isNull(key) || !has(key)) null else optLong(key)

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

private fun String?.safe(): String? {
    if (isNullOrBlank()) return null
    val lower = lowercase()
    if (listOf("password", "verification code", "one-time code", "otp").any(lower::contains)) return null
    return replace(Regex("""\s+"""), " ").trim().take(240)
}
