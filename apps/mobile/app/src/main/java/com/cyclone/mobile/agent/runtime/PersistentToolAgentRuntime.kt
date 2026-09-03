package com.cyclone.mobile.agent.runtime

import org.json.JSONObject
import java.util.UUID

data class PersistentToolAgentPolicy(
    val taskTimeoutMs: Long = 180_000L,
    val maxConsecutiveProviderFailures: Int = 3,
    val maxRepeatedIdenticalActionWithoutProgress: Int = 2,
    val maxCompletionRejectionsWithoutNewEvidence: Int = 4,
    val maxTotalToolCalls: Int = 48,
) {
    init {
        require(taskTimeoutMs > 0)
        require(maxConsecutiveProviderFailures > 0)
        require(maxRepeatedIdenticalActionWithoutProgress > 0)
        require(maxCompletionRejectionsWithoutNewEvidence > 0)
        require(maxTotalToolCalls > 0)
    }
}

/**
 * Persistent LLM -> tool call -> tool result -> LLM runtime.
 *
 * The model chooses tools and recovery strategy. Cyclone only enforces deterministic safety,
 * verification, cancellation, GATE suspension and bounded convergence.
 */
class PersistentToolAgentRuntime(
    goal: String,
    private val provider: AgentConversationProvider,
    private val tools: AgentToolExecutor,
    private val systemPrompt: String,
    private val policy: PersistentToolAgentPolicy = PersistentToolAgentPolicy(),
    private val events: AgentRuntimeEventSink = AgentRuntimeEventSink.NoOp,
    private val now: () -> Long = System::currentTimeMillis,
    private val externallyCancelled: () -> Boolean = { false },
    taskId: String = "agent-" + UUID.randomUUID(),
) {
    private var cancelled = false
    private var freshReadRequiredAfterResume = false
    private var state = PersistentAgentTaskState(
        taskId = taskId,
        goal = goal,
        conversation = listOf(
            AgentConversationEntry.Text("system", systemPrompt),
            AgentConversationEntry.Text("user", goal),
        ),
        status = PersistentAgentStatus.RUNNING,
        startedAtMs = now(),
        modelTurns = 0,
        toolTurns = 0,
        providerFailures = 0,
        verifiedProgressCount = 0,
        lastEvidenceIdentity = null,
        lastToolErrorClass = null,
        repeatedIdenticalNoProgress = 0,
        lastActionSignature = null,
        completionRejections = 0,
        gateSuspended = false,
    )

    init {
        require(goal.isNotBlank())
        emit(AgentRuntimeEventType.TASK_STARTED, message = "Task started")
    }

    fun snapshot(): PersistentAgentTaskState = state
    fun cancel() { cancelled = true }

    fun resume(): Boolean {
        if (!state.gateSuspended || state.status != PersistentAgentStatus.GATE) return false
        freshReadRequiredAfterResume = true
        state = state.copy(
            status = PersistentAgentStatus.RUNNING,
            gateSuspended = false,
            providerFailures = 0,
            repeatedIdenticalNoProgress = 0,
            conversation = state.conversation + AgentConversationEntry.Text(
                "system",
                "The user boundary is resolved. Continue the SAME task. Before another UI mutation, obtain fresh current evidence because observation-scoped element IDs may be stale.",
            ),
        )
        emit(AgentRuntimeEventType.GATE_RESUMED, message = "Gate resumed")
        return true
    }

    fun runUntilBoundary(): PersistentAgentRunResult {
        if (state.status == PersistentAgentStatus.COMPLETE) return PersistentAgentRunResult.Completed(state, "Done.")
        if (state.gateSuspended) return PersistentAgentRunResult.Suspended(state, "Cyclone is waiting for you.")

        while (true) {
            cancellation()?.let { return it }
            if (now() - state.startedAtMs > policy.taskTimeoutMs) {
                return stop(PersistentAgentStatus.NON_CONVERGENCE, "Cyclone stopped because the task timed out without verified completion.")
            }
            if (state.toolTurns >= policy.maxTotalToolCalls) {
                return stop(PersistentAgentStatus.NON_CONVERGENCE, "Cyclone stopped because too many tools ran without verified completion.")
            }

            emit(
                AgentRuntimeEventType.THINKING,
                modelTurn = state.modelTurns + 1,
                message = "Planning next tool",
                payload = JSONObject().apply {
                    state.lastToolErrorClass?.let { put("lastToolErrorClass", it) }
                },
            )

            when (val turn = provider.next(state.conversation, tools.descriptors())) {
                is AgentProviderTurn.Failure -> {
                    state = state.copy(
                        modelTurns = state.modelTurns + 1,
                        providerFailures = state.providerFailures + 1,
                    )
                    emit(
                        AgentRuntimeEventType.RECOVERING,
                        modelTurn = state.modelTurns,
                        message = turn.message,
                        payload = JSONObject().put("errorClass", turn.code).put("retryable", turn.retryable),
                    )
                    if (!turn.retryable || state.providerFailures >= policy.maxConsecutiveProviderFailures) {
                        return stop(PersistentAgentStatus.HARD_BLOCKER, turn.message)
                    }
                }

                is AgentProviderTurn.ToolCalls -> {
                    state = state.copy(
                        modelTurns = state.modelTurns + 1,
                        providerFailures = 0,
                        conversation = state.conversation + AgentConversationEntry.AssistantToolCalls(turn.calls),
                    )
                    if (turn.calls.isEmpty()) {
                        state = state.copy(
                            conversation = state.conversation + AgentConversationEntry.Text(
                                "system",
                                "No tool call was supplied. Choose a useful tool or return a final answer only if the goal is already satisfied.",
                            ),
                        )
                        continue
                    }

                    val containsMutation = turn.calls.any { tools.isMutation(it.name) }
                    turn.calls.forEachIndexed { index, call ->
                        cancellation()?.let { return it }

                        // Native providers occasionally ignore parallel_tool_calls=false. Read-only
                        // batches are safe, but once a turn includes mutation the model must see the
                        // first result before any later call can be trusted against fresh Android state.
                        if (containsMutation && index > 0) {
                            deferUntilReplan(call)
                            return@forEachIndexed
                        }

                        val result = executeTool(call)
                        when (result.boundary) {
                            ToolBoundary.GATE -> return suspendForGate(call.name)
                            ToolBoundary.HARD_BLOCKER -> {
                                val message = toolMessage(result.payload).ifBlank { "Cyclone reached a deterministic blocker." }
                                return stop(PersistentAgentStatus.HARD_BLOCKER, message)
                            }
                            ToolBoundary.NON_CONVERGENCE -> {
                                val message = toolMessage(result.payload).ifBlank { "Cyclone stopped because the same action kept making no progress." }
                                return stop(PersistentAgentStatus.NON_CONVERGENCE, message)
                            }
                            ToolBoundary.CONTINUE -> Unit
                        }
                    }
                }

                is AgentProviderTurn.Final -> {
                    state = state.copy(
                        modelTurns = state.modelTurns + 1,
                        providerFailures = 0,
                        conversation = state.conversation + AgentConversationEntry.Text("assistant", turn.content),
                    )
                    emit(AgentRuntimeEventType.VERIFYING, modelTurn = state.modelTurns, message = "Verifying final goal")
                    val completion = tools.verifyCompletion(state.goal)
                    if (completion.verified) {
                        state = state.copy(
                            status = PersistentAgentStatus.COMPLETE,
                            lastEvidenceIdentity = completion.evidenceIdentity ?: state.lastEvidenceIdentity,
                        )
                        emit(
                            AgentRuntimeEventType.COMPLETE,
                            modelTurn = state.modelTurns,
                            message = completion.message,
                            payload = completion.payload,
                        )
                        return PersistentAgentRunResult.Completed(
                            state,
                            turn.content.ifBlank { completion.message.ifBlank { "Done." } },
                        )
                    }

                    val sameEvidence = completion.evidenceIdentity != null && completion.evidenceIdentity == state.lastEvidenceIdentity
                    val rejected = if (sameEvidence) state.completionRejections + 1 else 1
                    state = state.copy(
                        completionRejections = rejected,
                        lastEvidenceIdentity = completion.evidenceIdentity ?: state.lastEvidenceIdentity,
                        conversation = state.conversation + AgentConversationEntry.Text(
                            "system",
                            JSONObject()
                                .put("type", "COMPLETION_NOT_VERIFIED")
                                .put("message", completion.message)
                                .put("evidenceIdentity", completion.evidenceIdentity ?: JSONObject.NULL)
                                .put("instruction", "The task is still active. Inspect current evidence or use another tool; do not merely repeat the final answer.")
                                .toString(),
                        ),
                    )
                    emit(
                        AgentRuntimeEventType.RECOVERING,
                        modelTurn = state.modelTurns,
                        message = "Completion was not verified",
                        payload = completion.payload,
                    )
                    if (rejected >= policy.maxCompletionRejectionsWithoutNewEvidence) {
                        return stop(PersistentAgentStatus.NON_CONVERGENCE, "Cyclone stopped because repeated completion claims were not verified.")
                    }
                }
            }
        }
    }

    private fun executeTool(call: AgentToolCall): ToolExecution {
        val mutation = tools.isMutation(call.name)
        val signature = actionSignature(call, mutation)
        val repeated = if (mutation && signature == state.lastActionSignature) state.repeatedIdenticalNoProgress + 1
        else if (mutation) 1 else state.repeatedIdenticalNoProgress

        val nextToolTurn = state.toolTurns + 1

        if (mutation && freshReadRequiredAfterResume) {
            val payload = JSONObject()
                .put("success", false)
                .put("errorClass", "FRESH_OBSERVATION_REQUIRED")
                .put("failureLayer", "OBSERVATION")
                .put("retryable", true)
                .put("message", "Read the current page after human/GATE resume before another mutation.")
            state = state.copy(
                toolTurns = nextToolTurn,
                lastToolErrorClass = "FRESH_OBSERVATION_REQUIRED",
                conversation = state.conversation + AgentConversationEntry.ToolResult(call.id, call.name, payload),
            )
            emit(
                AgentRuntimeEventType.RECOVERING,
                tool = call.name,
                modelTurn = state.modelTurns,
                toolTurn = nextToolTurn,
                mutation = true,
                message = payload.optString("message"),
                payload = tracePayload(payload, safeArguments(call.name, call.arguments)),
            )
            return ToolExecution(ToolBoundary.CONTINUE, payload)
        }

        if (mutation && repeated > policy.maxRepeatedIdenticalActionWithoutProgress * 2) {
            val payload = JSONObject()
                .put("success", false)
                .put("errorClass", "NON_CONVERGENCE")
                .put("failureLayer", "CONVERGENCE")
                .put("retryable", false)
                .put("message", "The same mutation kept repeating after Cyclone asked for a different strategy.")
            state = state.copy(
                toolTurns = nextToolTurn,
                repeatedIdenticalNoProgress = repeated,
                lastActionSignature = signature,
                lastToolErrorClass = "NON_CONVERGENCE",
                conversation = state.conversation + AgentConversationEntry.ToolResult(call.id, call.name, payload),
            )
            return ToolExecution(ToolBoundary.NON_CONVERGENCE, payload)
        }

        if (mutation && repeated > policy.maxRepeatedIdenticalActionWithoutProgress) {
            val blockedRepeat = JSONObject()
                .put("success", false)
                .put("errorClass", "REPEATED_NO_PROGRESS")
                .put("failureLayer", "CONVERGENCE")
                .put("retryable", true)
                .put("message", "This exact mutation already failed to make verified progress. Choose a materially different tool or target.")
            state = state.copy(
                toolTurns = nextToolTurn,
                repeatedIdenticalNoProgress = repeated,
                lastActionSignature = signature,
                lastToolErrorClass = "REPEATED_NO_PROGRESS",
                conversation = state.conversation + AgentConversationEntry.ToolResult(call.id, call.name, blockedRepeat),
            )
            val safeArgs = safeArguments(call.name, call.arguments)
            emit(
                AgentRuntimeEventType.RECOVERING,
                tool = call.name,
                modelTurn = state.modelTurns,
                toolTurn = nextToolTurn,
                mutation = true,
                message = blockedRepeat.optString("message"),
                payload = tracePayload(blockedRepeat, safeArgs),
            )
            return ToolExecution(ToolBoundary.CONTINUE, blockedRepeat)
        }

        val safeArgs = safeArguments(call.name, call.arguments)
        emit(
            AgentRuntimeEventType.TOOL_CALL_REQUESTED,
            tool = call.name,
            modelTurn = state.modelTurns,
            toolTurn = nextToolTurn,
            mutation = mutation,
            message = "Tool requested",
            payload = JSONObject().put("safeArguments", safeArgs),
        )
        emit(
            eventTypeForTool(call.name),
            tool = call.name,
            modelTurn = state.modelTurns,
            toolTurn = nextToolTurn,
            mutation = mutation,
            message = "Tool running",
            payload = JSONObject().put("safeArguments", safeArgs),
        )

        val payload = runCatching { tools.call(call.name, call.arguments) }
            .getOrElse { error ->
                JSONObject()
                    .put("success", false)
                    .put("errorClass", "TOOL_EXCEPTION")
                    .put("failureLayer", "RUNTIME")
                    .put("retryable", true)
                    .put("message", error.message ?: error.javaClass.simpleName)
            }

        state = state.copy(
            toolTurns = nextToolTurn,
            conversation = state.conversation + AgentConversationEntry.ToolResult(call.id, call.name, JSONObject(payload.toString())),
        )

        val success = payload.optBoolean("success", false)
        val verificationPassed = payload.optJSONObject("verification")?.optBoolean("passed", false) ?: false
        val errorClass = extractErrorClass(payload)
        val evidenceIdentity = extractEvidenceIdentity(payload)
        val verifiedProgress = mutation && success && verificationPassed
        val newEvidence = evidenceIdentity != null && evidenceIdentity != state.lastEvidenceIdentity

        if (!mutation && payload.optBoolean("success", false) && call.name in FRESH_EVIDENCE_TOOLS) {
            freshReadRequiredAfterResume = false
        }

        state = state.copy(
            verifiedProgressCount = state.verifiedProgressCount + if (verifiedProgress) 1 else 0,
            lastEvidenceIdentity = evidenceIdentity ?: state.lastEvidenceIdentity,
            lastToolErrorClass = errorClass.takeIf { it.isNotBlank() && it != "NONE" },
            repeatedIdenticalNoProgress = when {
                !mutation -> state.repeatedIdenticalNoProgress
                verifiedProgress || newEvidence -> 0
                else -> repeated
            },
            lastActionSignature = if (mutation && !verifiedProgress && !newEvidence) signature else null,
            completionRejections = if (verifiedProgress || newEvidence) 0 else state.completionRejections,
        )

        emit(
            AgentRuntimeEventType.TOOL_RESULT,
            tool = call.name,
            modelTurn = state.modelTurns,
            toolTurn = nextToolTurn,
            mutation = mutation,
            message = toolMessage(payload),
            payload = tracePayload(payload, safeArgs),
        )

        val learning = payload.optJSONObject("learning")
        if (learning != null) {
            emit(
                if (learning.optBoolean("recorded", false)) AgentRuntimeEventType.LEARNING_ACCEPTED else AgentRuntimeEventType.LEARNING_REJECTED,
                tool = call.name,
                modelTurn = state.modelTurns,
                toolTurn = nextToolTurn,
                mutation = mutation,
                message = learning.optString("reason"),
            )
        }

        val gate = errorClass in setOf("GATE_REQUIRED", "HUMAN_HAS_CONTROL", "AUTH_REQUIRED")
        val retryable = payload.optBoolean("retryable", payload.optJSONObject("failure")?.optBoolean("retryable", false) ?: false)
        val hard = errorClass == "POLICY_DENIED" && !retryable

        return ToolExecution(
            boundary = when {
                gate -> ToolBoundary.GATE
                hard -> ToolBoundary.HARD_BLOCKER
                else -> ToolBoundary.CONTINUE
            },
            payload = payload,
        )
    }

    private fun deferUntilReplan(call: AgentToolCall) {
        val mutation = tools.isMutation(call.name)
        val nextToolTurn = state.toolTurns + 1
        val safeArgs = safeArguments(call.name, call.arguments)
        val payload = JSONObject()
            .put("success", false)
            .put("errorClass", "SERIAL_REPLAN_REQUIRED")
            .put("failureLayer", "RUNTIME")
            .put("retryable", true)
            .put("message", "A previous tool from this model turn must be observed before this call can run safely. Replan using the returned results.")
        state = state.copy(
            toolTurns = nextToolTurn,
            lastToolErrorClass = "SERIAL_REPLAN_REQUIRED",
            conversation = state.conversation + AgentConversationEntry.ToolResult(call.id, call.name, payload),
        )
        emit(
            AgentRuntimeEventType.TOOL_RESULT,
            tool = call.name,
            modelTurn = state.modelTurns,
            toolTurn = nextToolTurn,
            mutation = mutation,
            message = payload.optString("message"),
            payload = tracePayload(payload, safeArgs),
        )
    }

    private fun suspendForGate(tool: String): PersistentAgentRunResult {
        state = state.copy(status = PersistentAgentStatus.GATE, gateSuspended = true)
        emit(
            AgentRuntimeEventType.GATE_REQUIRED,
            tool = tool,
            modelTurn = state.modelTurns,
            toolTurn = state.toolTurns,
            mutation = true,
            message = "Human or GATE boundary",
        )
        return PersistentAgentRunResult.Suspended(state, "Cyclone is waiting for you.")
    }

    private fun stop(status: PersistentAgentStatus, message: String): PersistentAgentRunResult {
        state = state.copy(status = status)
        emit(AgentRuntimeEventType.FAILED, message = message)
        return PersistentAgentRunResult.Stopped(state, message)
    }

    private fun cancellation(): PersistentAgentRunResult.Cancelled? {
        if (!cancelled && !externallyCancelled()) return null
        state = state.copy(status = PersistentAgentStatus.CANCELLED)
        emit(AgentRuntimeEventType.FAILED, message = "Task cancelled")
        return PersistentAgentRunResult.Cancelled(state, "Cyclone task cancelled.")
    }

    private fun eventTypeForTool(tool: String): AgentRuntimeEventType = when (tool) {
        "understand_page", "search", "inspect" -> AgentRuntimeEventType.READING_PAGE
        "recall", "run_skill" -> AgentRuntimeEventType.USING_BRAIN
        "visual_context" -> AgentRuntimeEventType.USING_VISION
        else -> AgentRuntimeEventType.TOOL_RUNNING
    }

    private fun extractErrorClass(payload: JSONObject): String {
        val direct = payload.optString("errorClass")
        if (direct.isNotBlank()) return direct
        return payload.optJSONObject("failure")?.optString("errorClass").orEmpty()
    }

    private fun extractEvidenceIdentity(payload: JSONObject): String? {
        val after = payload.optJSONObject("after")
        val observation = payload.optJSONObject("observation")
        val candidates = listOf(
            after?.optString("pageKey"),
            after?.optString("contentKey"),
            payload.optString("pageKey"),
            payload.optString("observationId"),
            observation?.optString("afterId"),
        ).filterNotNull().filter(String::isNotBlank)
        return candidates.takeIf { it.isNotEmpty() }?.joinToString("|")
    }

    private fun actionSignature(call: AgentToolCall, mutation: Boolean): String? {
        if (!mutation) return null
        val args = call.arguments
        return listOf(
            call.name,
            args.optString("name"),
            args.optString("elementId"),
            args.optString("direction"),
            args.optString("skillId"),
        ).joinToString("|")
    }

    private fun safeArguments(tool: String, args: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = args.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val normalized = key.lowercase()
            if (normalized in setOf("value", "text", "password", "pin", "otp", "secret", "token", "authorization")) {
                out.put(key, "[REDACTED]")
            } else {
                val value = args.opt(key)
                out.put(key, when (value) {
                    is JSONObject -> JSONObject(value.toString())
                    else -> value
                })
            }
        }
        if (tool in setOf("type", "replace_text")) {
            if (out.has("value")) out.put("value", "[REDACTED]")
            if (out.has("text")) out.put("text", "[REDACTED]")
        }
        return out
    }

    private fun tracePayload(payload: JSONObject, safeArgs: JSONObject): JSONObject {
        val verification = payload.optJSONObject("verification")
        val failure = payload.optJSONObject("failure")
        val before = payload.optJSONObject("before")
        val after = payload.optJSONObject("after")
        val delta = payload.optJSONObject("delta")
        return JSONObject()
            .put("safeArguments", safeArgs)
            .put("toolResult", sanitizedToolResult(payload))
            .put("androidExecutionOk", payload.optJSONObject("execution")?.optBoolean("androidExecutionOk", false) ?: false)
            .put("verificationStatus", verification?.optString("status").orEmpty())
            .put("verificationBasis", verification?.optString("basis").orEmpty())
            .put("verificationPassed", verification?.optBoolean("passed", false) ?: false)
            .put("errorClass", extractErrorClass(payload))
            .put("failureLayer", failure?.optString("failureLayer").orEmpty())
            .put("retryable", payload.optBoolean("retryable", failure?.optBoolean("retryable", false) ?: false))
            .put("beforePageKey", before?.optString("pageKey").orEmpty())
            .put("afterPageKey", after?.optString("pageKey").orEmpty())
            .put("beforePackage", before?.optString("package").orEmpty())
            .put("afterPackage", after?.optString("package").orEmpty())
            .put("semanticDelta", delta?.toString().orEmpty())
            .put("learningAccepted", payload.optJSONObject("learning")?.optBoolean("recorded", false) ?: false)
    }

    private fun sanitizedToolResult(payload: JSONObject): JSONObject {
        val copy = JSONObject(payload.toString())
        redactRecursive(copy)
        return copy
    }

    private fun redactRecursive(obj: JSONObject) {
        val keys = mutableListOf<String>()
        val iterator = obj.keys()
        while (iterator.hasNext()) keys += iterator.next()
        keys.forEach { key ->
            val value = obj.opt(key)
            val normalized = key.lowercase()
            when {
                normalized in setOf("value", "password", "pin", "otp", "token", "secret", "authorization", "base64", "imagebytes") ->
                    obj.put(key, "[REDACTED]")
                value is JSONObject -> redactRecursive(value)
            }
        }
    }

    private fun toolMessage(payload: JSONObject): String =
        payload.optString("message").ifBlank {
            payload.optJSONObject("failure")?.optString("message").orEmpty()
        }.ifBlank {
            if (payload.optBoolean("success", false)) "Tool succeeded" else "Tool result received"
        }

    private fun emit(
        type: AgentRuntimeEventType,
        tool: String? = null,
        modelTurn: Int? = null,
        toolTurn: Int? = null,
        mutation: Boolean? = null,
        message: String = "",
        payload: JSONObject = JSONObject(),
    ) {
        events.emit(
            AgentRuntimeEvent(
                type = type,
                taskId = state.taskId,
                timestampMs = now(),
                tool = tool,
                modelTurn = modelTurn,
                toolTurn = toolTurn,
                mutation = mutation,
                message = message,
                payload = payload,
            ),
        )
    }

    private enum class ToolBoundary { CONTINUE, GATE, HARD_BLOCKER, NON_CONVERGENCE }

    private companion object {
        val FRESH_EVIDENCE_TOOLS = setOf("understand_page", "search", "inspect", "visual_context")
    }
    private data class ToolExecution(val boundary: ToolBoundary, val payload: JSONObject)
}
