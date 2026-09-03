package com.cyclone.mobile.ai

import org.json.JSONArray
import org.json.JSONObject

enum class AgentRunStatus {
    RUNNING,
    COMPLETE,
    FAILED,
    GATE,
    CANCELLED,
}

enum class AgentRunEventType {
    TASK_STARTED,
    THINKING,
    READING_PAGE,
    USING_BRAIN,
    TOOL_CALL_REQUESTED,
    TOOL_RUNNING,
    TOOL_RESULT,
    USING_VISION,
    VERIFYING,
    RECOVERING,
    GATE_REQUIRED,
    GATE_RESUMED,
    LEARNING_ACCEPTED,
    LEARNING_REJECTED,
    COMPLETE,
    FAILED,
}

data class AgentRunEvent(
    val id: String,
    val runId: String,
    val sequence: Int,
    val timestampMs: Long,
    val type: AgentRunEventType,
    val message: String = "",
    val tool: String? = null,
    val modelTurn: Int? = null,
    val toolTurn: Int? = null,
    val mutation: Boolean? = null,
    val payload: JSONObject = JSONObject(),
)

data class AgentRunRecord(
    val id: String,
    val goal: String,
    val model: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val status: AgentRunStatus = AgentRunStatus.RUNNING,
    val summary: String = "",
    val finalClassification: String = "RUNNING",
    val events: List<AgentRunEvent> = emptyList(),
    val schema: String = AgentRunSchema.RUN_SCHEMA,
)

data class AgentRunMetrics(
    val modelTurns: Int,
    val toolCalls: Int,
    val readToolCalls: Int,
    val mutationToolCalls: Int,
    val screenshots: Int,
    val failedTools: Int,
    val verifiedActions: Int,
    val recoveryCycles: Int,
    val gateEvents: Int,
    val durationMs: Long,
    val complete: Boolean,
)

object AgentRunSchema {
    const val RUN_SCHEMA = "cyclone-agent-run-v388/1"
    const val EVENT_SCHEMA = "cyclone-agent-run-event-v388/1"

    object Payload {
        const val SAFE_ARGUMENTS = "safeArguments"
        const val TOOL_RESULT = "toolResult"
        const val ANDROID_EXECUTION_OK = "androidExecutionOk"
        const val VERIFICATION_STATUS = "verificationStatus"
        const val VERIFICATION_BASIS = "verificationBasis"
        const val VERIFICATION_PASSED = "verificationPassed"
        const val ERROR_CLASS = "errorClass"
        const val FAILURE_LAYER = "failureLayer"
        const val RETRYABLE = "retryable"
        const val BEFORE_PAGE_KEY = "beforePageKey"
        const val AFTER_PAGE_KEY = "afterPageKey"
        const val BEFORE_PACKAGE = "beforePackage"
        const val AFTER_PACKAGE = "afterPackage"
        const val SEMANTIC_DELTA = "semanticDelta"
        const val RECOVERY_DECISION = "recoveryDecision"
        const val BRAIN_REFS = "brainRefs"
        const val APP_GRAPH_REFS = "appGraphRefs"
        const val SKILL_REFS = "skillRefs"
        const val SCREENSHOT = "screenshot"
        const val GATE_CLASS = "gateClass"
        const val LEARNING_ACCEPTED = "learningAccepted"
        const val LAST_TOOL_ERROR_CLASS = "lastToolErrorClass"
        const val APPROACH = "approach"
    }
}

object AgentRunCodec {
    fun toJson(record: AgentRunRecord): JSONObject = JSONObject()
        .put("schema", record.schema)
        .put("id", record.id)
        .put("goal", record.goal)
        .put("model", record.model)
        .put("startedAtMs", record.startedAtMs)
        .put("endedAtMs", record.endedAtMs ?: JSONObject.NULL)
        .put("status", record.status.name)
        .put("summary", record.summary)
        .put("finalClassification", record.finalClassification)
        .put("metrics", metricsJson(AgentRunInsights.metrics(record)))
        .put("events", JSONArray().also { array -> record.events.forEach { array.put(eventToJson(it)) } })

    fun fromJson(json: JSONObject): AgentRunRecord {
        val events = mutableListOf<AgentRunEvent>()
        val array = json.optJSONArray("events") ?: JSONArray()
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let { events += eventFromJson(it) }
        }
        return AgentRunRecord(
            id = json.optString("id"),
            goal = json.optString("goal"),
            model = json.optString("model"),
            startedAtMs = json.optLong("startedAtMs"),
            endedAtMs = if (json.isNull("endedAtMs")) null else json.optLong("endedAtMs"),
            status = runCatching { AgentRunStatus.valueOf(json.optString("status")) }.getOrDefault(AgentRunStatus.FAILED),
            summary = json.optString("summary"),
            finalClassification = json.optString("finalClassification").ifBlank { json.optString("status", "FAILED") },
            events = events.sortedWith(compareBy<AgentRunEvent> { it.sequence }.thenBy { it.timestampMs }),
            schema = json.optString("schema").ifBlank { AgentRunSchema.RUN_SCHEMA },
        )
    }

    fun eventToJson(event: AgentRunEvent): JSONObject = JSONObject()
        .put("schema", AgentRunSchema.EVENT_SCHEMA)
        .put("id", event.id)
        .put("runId", event.runId)
        .put("sequence", event.sequence)
        .put("timestampMs", event.timestampMs)
        .put("type", event.type.name)
        .put("message", event.message)
        .put("tool", event.tool ?: JSONObject.NULL)
        .put("modelTurn", event.modelTurn ?: JSONObject.NULL)
        .put("toolTurn", event.toolTurn ?: JSONObject.NULL)
        .put("mutation", event.mutation ?: JSONObject.NULL)
        .put("payload", event.payload)

    fun eventFromJson(json: JSONObject): AgentRunEvent = AgentRunEvent(
        id = json.optString("id"),
        runId = json.optString("runId"),
        sequence = json.optInt("sequence"),
        timestampMs = json.optLong("timestampMs"),
        type = runCatching { AgentRunEventType.valueOf(json.optString("type")) }.getOrDefault(AgentRunEventType.FAILED),
        message = json.optString("message"),
        tool = json.optString("tool").takeIf { it.isNotBlank() && it != "null" },
        modelTurn = json.optInt("modelTurn").takeIf { json.has("modelTurn") && !json.isNull("modelTurn") },
        toolTurn = json.optInt("toolTurn").takeIf { json.has("toolTurn") && !json.isNull("toolTurn") },
        mutation = json.optBoolean("mutation").takeIf { json.has("mutation") && !json.isNull("mutation") },
        payload = json.optJSONObject("payload")?.let { JSONObject(it.toString()) } ?: JSONObject(),
    )

    fun metricsJson(metrics: AgentRunMetrics): JSONObject = JSONObject()
        .put("modelTurns", metrics.modelTurns)
        .put("toolCalls", metrics.toolCalls)
        .put("readToolCalls", metrics.readToolCalls)
        .put("mutationToolCalls", metrics.mutationToolCalls)
        .put("screenshots", metrics.screenshots)
        .put("failedTools", metrics.failedTools)
        .put("verifiedActions", metrics.verifiedActions)
        .put("recoveryCycles", metrics.recoveryCycles)
        .put("gateEvents", metrics.gateEvents)
        .put("durationMs", metrics.durationMs)
        .put("complete", metrics.complete)
}

object AgentRunInsights {
    private val defaultMutationTools = setOf(
        "click", "long_press", "tap", "scroll", "swipe", "type", "replace_text", "back", "home", "open_app",
    )

    fun metrics(record: AgentRunRecord): AgentRunMetrics {
        val calls = record.events.filter { it.type == AgentRunEventType.TOOL_CALL_REQUESTED }
        val modelTurns = record.events.mapNotNull { it.modelTurn }.filter { it > 0 }.distinct().size
            .takeIf { it > 0 }
            ?: record.events.count { it.type == AgentRunEventType.THINKING }
        val mutationCalls = calls.count { event ->
            event.mutation == true || (event.mutation == null && shortTool(event.tool) in defaultMutationTools)
        }
        val failedTools = record.events.count { event ->
            event.type == AgentRunEventType.TOOL_RESULT &&
                (event.payload.optBoolean(AgentRunSchema.Payload.ANDROID_EXECUTION_OK, true) == false ||
                    (event.payload.optString(AgentRunSchema.Payload.ERROR_CLASS).isNotBlank() &&
                    !event.payload.optString(AgentRunSchema.Payload.ERROR_CLASS).equals("NONE", true)))
        }
        val verifiedActions = record.events.count { event ->
            event.type == AgentRunEventType.TOOL_RESULT && event.payload.optBoolean(AgentRunSchema.Payload.VERIFICATION_PASSED, false)
        }
        val end = record.endedAtMs ?: record.events.lastOrNull()?.timestampMs ?: record.startedAtMs
        return AgentRunMetrics(
            modelTurns = modelTurns,
            toolCalls = calls.size,
            readToolCalls = (calls.size - mutationCalls).coerceAtLeast(0),
            mutationToolCalls = mutationCalls,
            screenshots = record.events.count { it.type == AgentRunEventType.USING_VISION },
            failedTools = failedTools,
            verifiedActions = verifiedActions,
            recoveryCycles = record.events.count { it.type == AgentRunEventType.RECOVERING },
            gateEvents = record.events.count { it.type == AgentRunEventType.GATE_REQUIRED },
            durationMs = (end - record.startedAtMs).coerceAtLeast(0),
            complete = record.status == AgentRunStatus.COMPLETE,
        )
    }

    fun toolsUsed(record: AgentRunRecord): List<String> = record.events
        .asSequence()
        .filter { it.type == AgentRunEventType.TOOL_CALL_REQUESTED }
        .mapNotNull { it.tool?.takeIf(String::isNotBlank) }
        .distinct()
        .toList()

    fun finalVerifiedPage(record: AgentRunRecord): Pair<String?, String?> {
        val event = record.events.asReversed().firstOrNull { it.payload.optBoolean(AgentRunSchema.Payload.VERIFICATION_PASSED, false) }
            ?: return null to null
        return event.payload.optString(AgentRunSchema.Payload.AFTER_PAGE_KEY).takeIf(String::isNotBlank) to
            event.payload.optString(AgentRunSchema.Payload.AFTER_PACKAGE).takeIf(String::isNotBlank)
    }

    fun errors(record: AgentRunRecord): List<String> = record.events.mapNotNull { event ->
        val errorClass = event.payload.optString(AgentRunSchema.Payload.ERROR_CLASS).takeIf(String::isNotBlank)
        when {
            errorClass != null -> errorClass
            event.type == AgentRunEventType.FAILED && event.message.isNotBlank() -> event.message
            else -> null
        }
    }.distinct()

    fun recoveries(record: AgentRunRecord): List<String> = record.events
        .filter { it.type == AgentRunEventType.RECOVERING }
        .map { event -> event.payload.optString(AgentRunSchema.Payload.RECOVERY_DECISION).ifBlank { event.message.ifBlank { "Recovery attempted" } } }

    fun knowledgeRefs(record: AgentRunRecord): List<String> = buildList {
        record.events.forEach { event ->
            addJsonValues(event.payload.opt(AgentRunSchema.Payload.BRAIN_REFS))
            addJsonValues(event.payload.opt(AgentRunSchema.Payload.APP_GRAPH_REFS))
            addJsonValues(event.payload.opt(AgentRunSchema.Payload.SKILL_REFS))
        }
    }.filter(String::isNotBlank).distinct().take(30)

    fun screenshotRefs(record: AgentRunRecord): List<String> = record.events
        .filter { it.type == AgentRunEventType.USING_VISION }
        .mapNotNull { event ->
            val shot = event.payload.optJSONObject(AgentRunSchema.Payload.SCREENSHOT) ?: event.payload
            listOf("id", "screenshotId", "hash", "sha256", "reference").firstNotNullOfOrNull { key ->
                shot.optString(key).takeIf(String::isNotBlank)
            }
        }
        .distinct()

    fun learningSummary(record: AgentRunRecord): String {
        val last = record.events.asReversed().firstOrNull {
            it.type == AgentRunEventType.LEARNING_ACCEPTED || it.type == AgentRunEventType.LEARNING_REJECTED
        } ?: return "No learning decision recorded"
        return when (last.type) {
            AgentRunEventType.LEARNING_ACCEPTED -> "Accepted"
            AgentRunEventType.LEARNING_REJECTED -> "Rejected"
            else -> "Unknown"
        }
    }

    private fun shortTool(tool: String?): String = tool.orEmpty().removePrefix("phone.").removePrefix("agent.")

    private fun MutableList<String>.addJsonValues(value: Any?) {
        when (value) {
            is JSONArray -> for (index in 0 until value.length()) value.optString(index).takeIf(String::isNotBlank)?.let(::add)
            is String -> if (value.isNotBlank()) add(value)
        }
    }
}

object AgentRunTimeline {
    fun title(event: AgentRunEvent): String = when (event.type) {
        AgentRunEventType.TASK_STARTED -> "Started"
        AgentRunEventType.THINKING -> "Thinking"
        AgentRunEventType.READING_PAGE -> "Reading page"
        AgentRunEventType.USING_BRAIN -> "Checking Brain"
        AgentRunEventType.TOOL_CALL_REQUESTED -> "Tool: ${event.tool?.removePrefix("phone.") ?: "request"}"
        AgentRunEventType.TOOL_RUNNING -> "Using tool"
        AgentRunEventType.TOOL_RESULT -> if (event.payload.optBoolean(AgentRunSchema.Payload.ANDROID_EXECUTION_OK, true)) "Android execution accepted" else "Tool failed"
        AgentRunEventType.USING_VISION -> "Visual check"
        AgentRunEventType.VERIFYING -> when {
            event.payload.optBoolean(AgentRunSchema.Payload.VERIFICATION_PASSED, false) -> "Verification passed"
            event.payload.optString(AgentRunSchema.Payload.VERIFICATION_STATUS).equals("FAILED", true) -> "Verification failed"
            else -> "Checking verification"
        }
        AgentRunEventType.RECOVERING -> "Recovery"
        AgentRunEventType.GATE_REQUIRED -> "Waiting for you"
        AgentRunEventType.GATE_RESUMED -> "Resumed"
        AgentRunEventType.LEARNING_ACCEPTED -> "Learning accepted"
        AgentRunEventType.LEARNING_REJECTED -> "Learning rejected"
        AgentRunEventType.COMPLETE -> "Completed"
        AgentRunEventType.FAILED -> "Failed"
    }
}
