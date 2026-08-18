package com.cyclone.mobile.automation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class AutomationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized fun listAutomations(): List<AutomationDefinition> = decodeArray(KEY_AUTOMATIONS, AutomationCodec::automationFromJson)
    @Synchronized fun getAutomation(id: String): AutomationDefinition? = listAutomations().firstOrNull { it.id == id }
    @Synchronized fun saveAutomation(value: AutomationDefinition) = replaceById(KEY_AUTOMATIONS, value.id, AutomationCodec.automationToJson(value))
    @Synchronized fun deleteAutomation(id: String) = deleteById(KEY_AUTOMATIONS, id)

    @Synchronized fun listSkills(): List<SkillDefinition> = decodeArray(KEY_SKILLS, AutomationCodec::skillFromJson)
    @Synchronized fun getSkill(id: String): SkillDefinition? = listSkills().firstOrNull { it.id == id }
    @Synchronized fun saveSkill(value: SkillDefinition) = replaceById(KEY_SKILLS, value.id, AutomationCodec.skillToJson(value))
    @Synchronized fun deleteSkill(id: String) = deleteById(KEY_SKILLS, id)

    @Synchronized fun appendRun(run: AutomationRun) {
        val existing = rawArray(KEY_RUNS).let { array ->
            buildList { for (i in 0 until array.length()) add(array.getJSONObject(i)) }
        }.filterNot { it.optString("id") == run.id }.toMutableList()
        existing.add(runToJson(run))
        val trimmed = existing.takeLast(MAX_RUNS)
        prefs.edit().putString(KEY_RUNS, JSONArray(trimmed).toString()).apply()
    }

    @Synchronized fun listRuns(limit: Int = 50): List<AutomationRun> {
        val array = rawArray(KEY_RUNS)
        val values = buildList {
            for (i in 0 until array.length()) runCatching { runFromJson(array.getJSONObject(i)) }.getOrNull()?.let(::add)
        }
        return values.takeLast(limit.coerceAtLeast(1)).reversed()
    }

    @Synchronized fun getRun(id: String): AutomationRun? = listRuns(MAX_RUNS).firstOrNull { it.id == id }

    @Synchronized fun saveCheckpoint(checkpoint: Checkpoint) {
        val all = rawObject(KEY_CHECKPOINTS)
        all.put(checkpoint.runId, checkpointToJson(checkpoint))
        prefs.edit().putString(KEY_CHECKPOINTS, all.toString()).apply()
    }

    @Synchronized fun getCheckpoint(runId: String): Checkpoint? = rawObject(KEY_CHECKPOINTS).optJSONObject(runId)?.let(::checkpointFromJson)

    @Synchronized fun deleteCheckpoint(runId: String) {
        val all = rawObject(KEY_CHECKPOINTS)
        all.remove(runId)
        prefs.edit().putString(KEY_CHECKPOINTS, all.toString()).apply()
    }

    @Synchronized fun exportAutomation(id: String): String? = getAutomation(id)?.let { AutomationCodec.automationToJson(it).toString(2) }

    @Synchronized fun importAutomation(raw: String): AutomationDefinition = AutomationCodec.automationFromJson(JSONObject(raw)).also(::saveAutomation)

    private fun <T> decodeArray(key: String, decoder: (JSONObject) -> T): List<T> {
        val array = rawArray(key)
        return buildList {
            for (i in 0 until array.length()) runCatching { decoder(array.getJSONObject(i)) }.getOrNull()?.let(::add)
        }
    }

    private fun replaceById(key: String, id: String, value: JSONObject) {
        val array = rawArray(key)
        val values = mutableListOf<JSONObject>()
        for (i in 0 until array.length()) array.optJSONObject(i)?.takeIf { it.optString("id") != id }?.let(values::add)
        values.add(value)
        prefs.edit().putString(key, JSONArray(values).toString()).apply()
    }

    private fun deleteById(key: String, id: String) {
        val array = rawArray(key)
        val values = mutableListOf<JSONObject>()
        for (i in 0 until array.length()) array.optJSONObject(i)?.takeIf { it.optString("id") != id }?.let(values::add)
        prefs.edit().putString(key, JSONArray(values).toString()).apply()
    }

    private fun rawArray(key: String) = runCatching { JSONArray(prefs.getString(key, "[]") ?: "[]") }.getOrElse { JSONArray() }
    private fun rawObject(key: String) = runCatching { JSONObject(prefs.getString(key, "{}") ?: "{}") }.getOrElse { JSONObject() }

    private fun runToJson(run: AutomationRun) = JSONObject().apply {
        put("id", run.id)
        put("automationId", run.automationId)
        put("automationName", run.automationName)
        put("state", run.state.name)
        put("trigger", JSONObject().put("type", run.trigger.type.name).put("payload", JSONObject(run.trigger.payload)).put("timestamp", run.trigger.timestamp))
        put("startedAt", run.startedAt)
        put("endedAt", run.endedAt ?: JSONObject.NULL)
        put("error", run.error ?: JSONObject.NULL)
        put("variables", JSONObject(run.variables))
        put("steps", JSONArray().apply { run.steps.forEach { step ->
            put(JSONObject().apply {
                put("stepId", step.stepId); put("name", step.name); put("state", step.state.name)
                put("startedAt", step.startedAt ?: JSONObject.NULL); put("endedAt", step.endedAt ?: JSONObject.NULL)
                put("attempt", step.attempt); put("message", step.message ?: JSONObject.NULL); put("output", JSONObject(step.output))
            })
        } })
    }

    private fun runFromJson(json: JSONObject): AutomationRun {
        val trigger = json.getJSONObject("trigger")
        val stepsJson = json.optJSONArray("steps") ?: JSONArray()
        val steps = List(stepsJson.length()) { index ->
            val step = stepsJson.getJSONObject(index)
            RunStepRecord(
                stepId = step.getString("stepId"), name = step.getString("name"), state = enumValueOf(step.getString("state")),
                startedAt = step.optLongOrNull("startedAt"), endedAt = step.optLongOrNull("endedAt"), attempt = step.optInt("attempt"),
                message = step.optStringOrNull("message"), output = step.optJSONObject("output").toStringMap()
            )
        }
        return AutomationRun(
            id = json.getString("id"), automationId = json.getString("automationId"), automationName = json.getString("automationName"),
            state = enumValueOf(json.getString("state")), trigger = TriggerEvent(enumValueOf(trigger.getString("type")), trigger.optJSONObject("payload").toStringMap(), trigger.optLong("timestamp")),
            startedAt = json.optLong("startedAt"), endedAt = json.optLongOrNull("endedAt"), steps = steps,
            variables = json.optJSONObject("variables").toStringMap(), error = json.optStringOrNull("error")
        )
    }

    private fun checkpointToJson(value: Checkpoint) = JSONObject().put("runId", value.runId).put("automationId", value.automationId)
        .put("nextStepIndex", value.nextStepIndex).put("variables", JSONObject(value.variables)).put("waitingForHuman", value.waitingForHuman).put("updatedAt", value.updatedAt)

    private fun checkpointFromJson(json: JSONObject) = Checkpoint(json.getString("runId"), json.getString("automationId"), json.getInt("nextStepIndex"), json.optJSONObject("variables").toStringMap(), json.optBoolean("waitingForHuman"), json.optLong("updatedAt"))

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap { keys().forEach { key -> put(key, optString(key)) } }
    }
    private fun JSONObject.optLongOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else optLong(key)
    private fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)

    companion object {
        private const val PREFS_NAME = "cyclone_automation_studio"
        private const val KEY_AUTOMATIONS = "automations"
        private const val KEY_SKILLS = "skills"
        private const val KEY_RUNS = "runs"
        private const val KEY_CHECKPOINTS = "checkpoints"
        private const val MAX_RUNS = 100
    }
}
