package com.cyclone.mobile.agent.recovery

import com.cyclone.mobile.ai.AgentRunEvent
import com.cyclone.mobile.ai.AgentRunEventType
import com.cyclone.mobile.ai.AgentRunSchema

enum class AgentRunAcceptanceScenario {
    OPEN_APP,
    NORMAL_PAGE,
    AMBIGUOUS_PAGE,
    VISUAL_PAGE,
    TOOL_FAILURE,
    GATE,
    STALE_TARGET,
    SIMPLE_APP_EFFICIENCY,
    NO_FALSE_SUCCESS,
}

object AgentRunAcceptanceContract {
    fun violations(scenario: AgentRunAcceptanceScenario, events: List<AgentRunEvent>): List<String> {
        if (events.isEmpty()) return listOf("trace_empty")
        val errors = mutableListOf<String>()
        if (events.first().type != AgentRunEventType.TASK_STARTED) errors += "task_started_must_be_first"
        validateNoFalseSuccess(events, errors)
        when (scenario) {
            AgentRunAcceptanceScenario.OPEN_APP -> validateOpenApp(events, errors)
            AgentRunAcceptanceScenario.NORMAL_PAGE -> validateNormalPage(events, errors)
            AgentRunAcceptanceScenario.AMBIGUOUS_PAGE -> validateAmbiguous(events, errors)
            AgentRunAcceptanceScenario.VISUAL_PAGE -> validateVisual(events, errors)
            AgentRunAcceptanceScenario.TOOL_FAILURE -> validateFailureFeedback(events, errors)
            AgentRunAcceptanceScenario.GATE -> validateGate(events, errors)
            AgentRunAcceptanceScenario.STALE_TARGET -> validateStale(events, errors)
            AgentRunAcceptanceScenario.SIMPLE_APP_EFFICIENCY -> validateSimpleEfficiency(events, errors)
            AgentRunAcceptanceScenario.NO_FALSE_SUCCESS -> Unit
        }
        return errors.distinct()
    }

    fun compatibleType(type: AgenticTraceEventType): AgentRunEventType = when (type) {
        AgenticTraceEventType.TASK_STARTED -> AgentRunEventType.TASK_STARTED
        AgenticTraceEventType.OBSERVATION, AgenticTraceEventType.AFTER_OBSERVATION -> AgentRunEventType.READING_PAGE
        AgenticTraceEventType.KNOWN_ROUTE_LOOKUP -> AgentRunEventType.USING_BRAIN
        AgenticTraceEventType.SEMANTIC_SEARCH, AgenticTraceEventType.ELEMENT_INSPECTION -> AgentRunEventType.TOOL_CALL_REQUESTED
        AgenticTraceEventType.VISION_CAPTURE -> AgentRunEventType.USING_VISION
        AgenticTraceEventType.MODEL_DECISION -> AgentRunEventType.THINKING
        AgenticTraceEventType.ACTION_REQUESTED -> AgentRunEventType.TOOL_CALL_REQUESTED
        AgenticTraceEventType.ANDROID_EXECUTION -> AgentRunEventType.TOOL_RESULT
        AgenticTraceEventType.VERIFICATION, AgenticTraceEventType.PROGRESS_CLASSIFIED -> AgentRunEventType.VERIFYING
        AgenticTraceEventType.RECOVERY_SELECTED, AgenticTraceEventType.BACKTRACK -> AgentRunEventType.RECOVERING
        AgenticTraceEventType.GATE_SUSPEND -> AgentRunEventType.GATE_REQUIRED
        AgenticTraceEventType.GATE_RESUME -> AgentRunEventType.GATE_RESUMED
        AgenticTraceEventType.LEARNING_ACCEPTED -> AgentRunEventType.LEARNING_ACCEPTED
        AgenticTraceEventType.LEARNING_REJECTED -> AgentRunEventType.LEARNING_REJECTED
        AgenticTraceEventType.TASK_COMPLETE -> AgentRunEventType.COMPLETE
        AgenticTraceEventType.TASK_BLOCKED,
        AgenticTraceEventType.TASK_NON_CONVERGENCE,
        AgenticTraceEventType.TASK_CANCELLED,
        -> AgentRunEventType.FAILED
    }

    private fun validateOpenApp(events: List<AgentRunEvent>, errors: MutableList<String>) {
        val thinking = index(events, AgentRunEventType.THINKING)
        val call = indexTool(events, "open_app")
        val result = verifiedToolResultAfter(events, call)
        val complete = index(events, AgentRunEventType.COMPLETE)
        if (thinking < 0 || call < 0 || result < 0 || complete < 0 || !(thinking < call && call < result && result < complete)) {
            errors += "open_app_sequence_invalid"
        }
        if (events.count { it.type == AgentRunEventType.READING_PAGE } > 1) errors += "open_app_unnecessary_page_loops"
    }

    private fun validateNormalPage(events: List<AgentRunEvent>, errors: MutableList<String>) {
        val read1 = index(events, AgentRunEventType.READING_PAGE)
        val click = indexTool(events, "click", after = read1)
        val verified = verifiedToolResultAfter(events, click)
        val read2 = index(events, AgentRunEventType.READING_PAGE, after = verified)
        val complete = index(events, AgentRunEventType.COMPLETE, after = read2)
        if (minOf(read1, click, verified, read2, complete) < 0) errors += "normal_page_sequence_invalid"
    }

    private fun validateAmbiguous(events: List<AgentRunEvent>, errors: MutableList<String>) {
        val read = index(events, AgentRunEventType.READING_PAGE)
        val search = indexAnyTool(events, setOf("search", "search_batch"), read)
        val inspect = indexAnyTool(events, setOf("inspect", "inspect_batch"), search)
        val click = indexTool(events, "click", inspect)
        val verified = verifiedToolResultAfter(events, click)
        if (minOf(read, search, inspect, click, verified) < 0) errors += "ambiguous_page_sequence_invalid"
    }

    private fun validateVisual(events: List<AgentRunEvent>, errors: MutableList<String>) {
        val read = index(events, AgentRunEventType.READING_PAGE)
        val vision = index(events, AgentRunEventType.USING_VISION, read)
        val action = events.indexOfFirstAfter(vision) { it.type == AgentRunEventType.TOOL_CALL_REQUESTED && isMutation(it) }
        val verified = verifiedToolResultAfter(events, action)
        if (minOf(read, vision, action, verified) < 0) errors += "visual_page_sequence_invalid"
    }

    private fun validateFailureFeedback(events: List<AgentRunEvent>, errors: MutableList<String>) {
        val failedResult = events.indexOfFirst { event ->
            event.type == AgentRunEventType.TOOL_RESULT && event.payload.optString(AgentRunSchema.Payload.ERROR_CLASS).isNotBlank()
        }
        if (failedResult < 0) {
            errors += "tool_failure_missing_typed_error"
            return
        }
        val errorClass = events[failedResult].payload.optString(AgentRunSchema.Payload.ERROR_CLASS)
        val nextModel = events.indexOfFirstAfter(failedResult) { it.type == AgentRunEventType.THINKING }
        if (nextModel < 0 || events[nextModel].payload.optString(AgentRunSchema.Payload.LAST_TOOL_ERROR_CLASS) != errorClass) {
            errors += "tool_failure_not_forwarded_to_next_model_turn"
        }
        val nextAction = events.indexOfFirstAfter(nextModel) { it.type == AgentRunEventType.TOOL_CALL_REQUESTED }
        if (nextAction < 0) errors += "tool_failure_model_did_not_change_approach"
    }

    private fun validateGate(events: List<AgentRunEvent>, errors: MutableList<String>) {
        val call = events.indexOfFirst { it.type == AgentRunEventType.TOOL_CALL_REQUESTED }
        val gate = index(events, AgentRunEventType.GATE_REQUIRED, call)
        val resume = index(events, AgentRunEventType.GATE_RESUMED, gate)
        val fresh = index(events, AgentRunEventType.READING_PAGE, resume)
        if (minOf(call, gate, resume, fresh) < 0) errors += "gate_suspend_resume_fresh_evidence_invalid"
    }

    private fun validateStale(events: List<AgentRunEvent>, errors: MutableList<String>) {
        val stale = events.indexOfFirst { event ->
            event.type == AgentRunEventType.TOOL_RESULT &&
                event.payload.optString(AgentRunSchema.Payload.ERROR_CLASS) == "STALE_OBSERVATION"
        }
        if (stale < 0) {
            errors += "stale_target_missing_explicit_error"
            return
        }
        val refresh = events.indexOfFirstAfter(stale) { event ->
            event.type == AgentRunEventType.READING_PAGE ||
                (event.type == AgentRunEventType.TOOL_CALL_REQUESTED && shortTool(event) in setOf("search", "search_batch"))
        }
        val nextAction = events.indexOfFirstAfter(refresh) { it.type == AgentRunEventType.TOOL_CALL_REQUESTED }
        if (refresh < 0 || nextAction < 0) errors += "stale_target_did_not_relocate"
    }

    private fun validateSimpleEfficiency(events: List<AgentRunEvent>, errors: MutableList<String>) {
        if (events.count { it.type == AgentRunEventType.READING_PAGE } > 1) errors += "simple_task_multiple_page_cycles"
        val calls = events.filter { it.type == AgentRunEventType.TOOL_CALL_REQUESTED }
        if (calls.size != 1 || shortTool(calls.singleOrNull()) != "open_app") errors += "simple_task_not_direct_open_app"
    }

    private fun validateNoFalseSuccess(events: List<AgentRunEvent>, errors: MutableList<String>) {
        events.forEachIndexed { index, event ->
            if (event.type != AgentRunEventType.TOOL_RESULT) return@forEachIndexed
            val accepted = event.payload.optBoolean(AgentRunSchema.Payload.ANDROID_EXECUTION_OK, false)
            val verified = event.payload.optBoolean(AgentRunSchema.Payload.VERIFICATION_PASSED, false)
            if (!accepted || verified) return@forEachIndexed
            val later = events.drop(index + 1)
            val laterVerified = later.indexOfFirst { candidate ->
                candidate.type == AgentRunEventType.TOOL_RESULT &&
                    candidate.payload.optBoolean(AgentRunSchema.Payload.VERIFICATION_PASSED, false)
            }
            val completion = later.indexOfFirst { it.type == AgentRunEventType.COMPLETE }
            val learning = later.indexOfFirst { it.type == AgentRunEventType.LEARNING_ACCEPTED }
            if (completion >= 0 && (laterVerified < 0 || completion < laterVerified)) {
                errors += "completion_after_unverified_android_acceptance@${index}"
            }
            if (learning >= 0 && (laterVerified < 0 || learning < laterVerified)) {
                errors += "learning_after_unverified_android_acceptance@${index}"
            }
        }
    }

    private fun isMutation(event: AgentRunEvent): Boolean = event.mutation == true || shortTool(event) in setOf(
        "click", "long_press", "tap", "scroll", "swipe", "type", "replace_text", "back", "home", "open_app",
    )

    private fun verifiedToolResultAfter(events: List<AgentRunEvent>, after: Int): Int = events.indexOfFirstAfter(after) { event ->
        event.type == AgentRunEventType.TOOL_RESULT && event.payload.optBoolean(AgentRunSchema.Payload.VERIFICATION_PASSED, false)
    }

    private fun index(events: List<AgentRunEvent>, type: AgentRunEventType, after: Int = -1): Int =
        events.indexOfFirstAfter(after) { it.type == type }

    private fun indexTool(events: List<AgentRunEvent>, tool: String, after: Int = -1): Int =
        events.indexOfFirstAfter(after) { it.type == AgentRunEventType.TOOL_CALL_REQUESTED && shortTool(it) == tool }

    private fun indexAnyTool(events: List<AgentRunEvent>, tools: Set<String>, after: Int = -1): Int =
        events.indexOfFirstAfter(after) { it.type == AgentRunEventType.TOOL_CALL_REQUESTED && shortTool(it) in tools }

    private fun shortTool(event: AgentRunEvent?): String = event?.tool.orEmpty().removePrefix("phone.").removePrefix("agent.")

    private inline fun List<AgentRunEvent>.indexOfFirstAfter(index: Int, predicate: (AgentRunEvent) -> Boolean): Int {
        for (i in (index + 1).coerceAtLeast(0) until size) if (predicate(this[i])) return i
        return -1
    }
}
