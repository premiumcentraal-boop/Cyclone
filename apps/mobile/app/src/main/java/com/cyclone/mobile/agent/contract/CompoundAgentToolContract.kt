package com.cyclone.mobile.agent.contract

import org.json.JSONArray
import org.json.JSONObject

data class AgentToolDescriptor(
    val name: String,
    val description: String,
    val argumentsSchema: JSONObject,
    val resultSchema: JSONObject,
    val readOnly: Boolean,
    val mutation: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("arguments", JSONObject(argumentsSchema.toString()))
        .put("result", JSONObject(resultSchema.toString()))
        .put("readOnly", readOnly)
        .put("mutation", mutation)
}

data class CompoundInstalledApp(
    val label: String,
    val packageName: String,
    val launcherActivity: String?,
    val openSuccessCount: Int = 0,
    val openFailureCount: Int = 0,
)

data class CompoundAppMatch(
    val app: CompoundInstalledApp,
    val score: Double,
)

data class CompoundVerifiedSkillStep(
    val tool: String,
    val params: JSONObject,
    val label: String,
)

data class CompoundVerifiedSkill(
    val skillId: String,
    val label: String,
    val confidence: Double,
    val source: String,
    val goalHints: String,
    val steps: List<CompoundVerifiedSkillStep>,
)

data class CompoundScreenshotEvidence(
    val observationId: String?,
    val reference: String?,
    val sha256: String?,
    val width: Int?,
    val height: Int?,
    val timestampMs: Long?,
    val failure: AgentFailure? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("observationId", observationId ?: JSONObject.NULL)
        .put("reference", reference ?: JSONObject.NULL)
        .put("sha256", sha256 ?: JSONObject.NULL)
        .put("width", width ?: JSONObject.NULL)
        .put("height", height ?: JSONObject.NULL)
        .put("timestampMs", timestampMs ?: JSONObject.NULL)
        .put("evidenceOnly", true)
        .put("provesSuccess", false)
        .put("failure", failure?.let {
            JSONObject()
                .put("errorClass", it.errorClass.name)
                .put("failureLayer", it.failureLayer.name)
                .put("retryable", it.retryable)
                .put("message", it.message)
                .put("reasonCode", it.reasonCode ?: JSONObject.NULL)
        } ?: JSONObject.NULL)
}

internal fun jsonArray(values: Iterable<Any?>): JSONArray = JSONArray().also { out ->
    values.forEach { out.put(it ?: JSONObject.NULL) }
}
