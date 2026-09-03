package com.cyclone.mobile.agent.runtime

import org.json.JSONArray
import org.json.JSONObject

data class AgentToolCall(
    val id: String,
    val name: String,
    val arguments: JSONObject,
)

sealed interface AgentConversationEntry {
    data class Text(val role: String, val content: String) : AgentConversationEntry
    data class AssistantToolCalls(val calls: List<AgentToolCall>, val content: String = "") : AgentConversationEntry
    data class ToolResult(val callId: String, val tool: String, val payload: JSONObject) : AgentConversationEntry
}

sealed interface AgentProviderTurn {
    data class ToolCalls(val calls: List<AgentToolCall>, val native: Boolean) : AgentProviderTurn
    data class Final(val content: String, val native: Boolean) : AgentProviderTurn
    data class Failure(val message: String, val code: String = "PROVIDER_ERROR", val retryable: Boolean = true) : AgentProviderTurn
}

interface AgentConversationProvider {
    fun next(conversation: List<AgentConversationEntry>, tools: JSONArray): AgentProviderTurn
}

interface AgentToolExecutor {
    fun descriptors(): JSONArray
    fun isMutation(tool: String): Boolean
    fun call(tool: String, arguments: JSONObject): JSONObject
    fun verifyCompletion(goal: String): AgentCompletionEvidence
}

data class AgentCompletionEvidence(
    val verified: Boolean,
    val evidenceIdentity: String? = null,
    val message: String,
    val payload: JSONObject = JSONObject(),
)

enum class PersistentAgentStatus { RUNNING, COMPLETE, GATE, HARD_BLOCKER, NON_CONVERGENCE, CANCELLED }

data class PersistentAgentTaskState(
    val taskId: String,
    val goal: String,
    val conversation: List<AgentConversationEntry>,
    val status: PersistentAgentStatus,
    val startedAtMs: Long,
    val modelTurns: Int,
    val toolTurns: Int,
    val providerFailures: Int,
    val verifiedProgressCount: Int,
    val lastEvidenceIdentity: String?,
    val lastToolErrorClass: String?,
    val repeatedIdenticalNoProgress: Int,
    val lastActionSignature: String?,
    val completionRejections: Int,
    val gateSuspended: Boolean,
)

enum class AgentRuntimeEventType {
    TASK_STARTED, THINKING, TOOL_CALL_REQUESTED, TOOL_RUNNING, TOOL_RESULT,
    READING_PAGE, USING_BRAIN, USING_VISION, VERIFYING, RECOVERING,
    GATE_REQUIRED, GATE_RESUMED, LEARNING_ACCEPTED, LEARNING_REJECTED, COMPLETE, FAILED,
}

data class AgentRuntimeEvent(
    val type: AgentRuntimeEventType,
    val taskId: String,
    val timestampMs: Long,
    val tool: String? = null,
    val modelTurn: Int? = null,
    val toolTurn: Int? = null,
    val mutation: Boolean? = null,
    val message: String = "",
    val payload: JSONObject = JSONObject(),
)

fun interface AgentRuntimeEventSink {
    fun emit(event: AgentRuntimeEvent)
    object NoOp : AgentRuntimeEventSink { override fun emit(event: AgentRuntimeEvent) = Unit }
}

sealed interface PersistentAgentRunResult {
    val state: PersistentAgentTaskState
    data class Completed(override val state: PersistentAgentTaskState, val message: String) : PersistentAgentRunResult
    data class Suspended(override val state: PersistentAgentTaskState, val message: String) : PersistentAgentRunResult
    data class Stopped(override val state: PersistentAgentTaskState, val message: String) : PersistentAgentRunResult
    data class Cancelled(override val state: PersistentAgentTaskState, val message: String) : PersistentAgentRunResult
}
