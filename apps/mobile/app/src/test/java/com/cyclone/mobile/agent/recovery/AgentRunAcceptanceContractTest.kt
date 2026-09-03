package com.cyclone.mobile.agent.recovery

import com.cyclone.mobile.ai.AgentRunEvent
import com.cyclone.mobile.ai.AgentRunEventType
import com.cyclone.mobile.ai.AgentRunSchema
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunAcceptanceContractTest {
    @Test fun openAppFastPathHasNoPageDecisionLoop() {
        val events = listOf(
            e(1, AgentRunEventType.TASK_STARTED),
            e(2, AgentRunEventType.THINKING, modelTurn = 1),
            e(3, AgentRunEventType.TOOL_CALL_REQUESTED, tool = "phone.open_app", mutation = true),
            e(4, AgentRunEventType.TOOL_RESULT, tool = "phone.open_app", payload = verifiedResult()),
            e(5, AgentRunEventType.COMPLETE),
        )
        assertTrue(AgentRunAcceptanceContract.violations(AgentRunAcceptanceScenario.OPEN_APP, events).isEmpty())
        assertTrue(AgentRunAcceptanceContract.violations(AgentRunAcceptanceScenario.SIMPLE_APP_EFFICIENCY, events).isEmpty())
    }

    @Test fun normalPageReadClickVerifyReadCompleteIsAccepted() {
        val events = listOf(
            e(1, AgentRunEventType.TASK_STARTED),
            e(2, AgentRunEventType.READING_PAGE),
            e(3, AgentRunEventType.TOOL_CALL_REQUESTED, tool = "phone.click", mutation = true),
            e(4, AgentRunEventType.TOOL_RESULT, tool = "phone.click", payload = verifiedResult()),
            e(5, AgentRunEventType.READING_PAGE),
            e(6, AgentRunEventType.COMPLETE),
        )
        assertTrue(AgentRunAcceptanceContract.violations(AgentRunAcceptanceScenario.NORMAL_PAGE, events).isEmpty())
    }

    @Test fun ambiguousPageRequiresSearchInspectThenMutation() {
        val events = listOf(
            e(1, AgentRunEventType.TASK_STARTED),
            e(2, AgentRunEventType.READING_PAGE),
            e(3, AgentRunEventType.TOOL_CALL_REQUESTED, tool = "agent.search_batch", mutation = false),
            e(4, AgentRunEventType.TOOL_RESULT, tool = "agent.search_batch", payload = JSONObject().put("ok", true)),
            e(5, AgentRunEventType.TOOL_CALL_REQUESTED, tool = "agent.inspect_batch", mutation = false),
            e(6, AgentRunEventType.TOOL_RESULT, tool = "agent.inspect_batch", payload = JSONObject().put("ok", true)),
            e(7, AgentRunEventType.TOOL_CALL_REQUESTED, tool = "phone.click", mutation = true),
            e(8, AgentRunEventType.TOOL_RESULT, tool = "phone.click", payload = verifiedResult()),
        )
        assertTrue(AgentRunAcceptanceContract.violations(AgentRunAcceptanceScenario.AMBIGUOUS_PAGE, events).isEmpty())
    }

    @Test fun visualPageEscalatesOnlyAfterStructuredRead() {
        val events = listOf(
            e(1, AgentRunEventType.TASK_STARTED),
            e(2, AgentRunEventType.READING_PAGE),
            e(3, AgentRunEventType.USING_VISION),
            e(4, AgentRunEventType.TOOL_CALL_REQUESTED, tool = "phone.click", mutation = true),
            e(5, AgentRunEventType.TOOL_RESULT, tool = "phone.click", payload = verifiedResult()),
        )
        assertTrue(AgentRunAcceptanceContract.violations(AgentRunAcceptanceScenario.VISUAL_PAGE, events).isEmpty())
    }

    @Test fun detailedToolFailureReachesNextModelTurn() {
        val failure = JSONObject()
            .put(AgentRunSchema.Payload.ANDROID_EXECUTION_OK, false)
            .put(AgentRunSchema.Payload.VERIFICATION_PASSED, false)
            .put(AgentRunSchema.Payload.ERROR_CLASS, "TARGET_NOT_FOUND")
        val events = listOf(
            e(1, AgentRunEventType.TASK_STARTED),
            e(2, AgentRunEventType.TOOL_CALL_REQUESTED, tool = "phone.click", mutation = true),
            e(3, AgentRunEventType.TOOL_RESULT, tool = "phone.click", payload = failure),
            e(4, AgentRunEventType.THINKING, modelTurn = 2, payload = JSONObject().put(AgentRunSchema.Payload.LAST_TOOL_ERROR_CLASS, "TARGET_NOT_FOUND")),
            e(5, AgentRunEventType.TOOL_CALL_REQUESTED, tool = "agent.search_batch", mutation = false),
        )
        assertTrue(AgentRunAcceptanceContract.violations(AgentRunAcceptanceScenario.TOOL_FAILURE, events).isEmpty())
    }

    @Test fun gateSuspendsResumesSameTaskAndRefreshesEvidence() {
        val events = listOf(
            e(1, AgentRunEventType.TASK_STARTED),
            e(2, AgentRunEventType.TOOL_CALL_REQUESTED, tool = "phone.click", mutation = true),
            e(3, AgentRunEventType.GATE_REQUIRED),
            e(4, AgentRunEventType.GATE_RESUMED),
            e(5, AgentRunEventType.READING_PAGE),
            e(6, AgentRunEventType.TOOL_CALL_REQUESTED, tool = "phone.click", mutation = true),
        )
        assertTrue(AgentRunAcceptanceContract.violations(AgentRunAcceptanceScenario.GATE, events).isEmpty())
    }

    @Test fun staleTargetForcesFreshPageOrSearchBeforeRetry() {
        val stale = JSONObject()
            .put(AgentRunSchema.Payload.ANDROID_EXECUTION_OK, false)
            .put(AgentRunSchema.Payload.VERIFICATION_PASSED, false)
            .put(AgentRunSchema.Payload.ERROR_CLASS, "STALE_OBSERVATION")
        val events = listOf(
            e(1, AgentRunEventType.TASK_STARTED),
            e(2, AgentRunEventType.TOOL_CALL_REQUESTED, tool = "phone.click", mutation = true),
            e(3, AgentRunEventType.TOOL_RESULT, tool = "phone.click", payload = stale),
            e(4, AgentRunEventType.READING_PAGE),
            e(5, AgentRunEventType.TOOL_CALL_REQUESTED, tool = "phone.click", mutation = true),
        )
        assertTrue(AgentRunAcceptanceContract.violations(AgentRunAcceptanceScenario.STALE_TARGET, events).isEmpty())
    }

    @Test fun androidAcceptanceWithoutVerificationCannotCompleteOrLearn() {
        val unverified = JSONObject()
            .put(AgentRunSchema.Payload.ANDROID_EXECUTION_OK, true)
            .put(AgentRunSchema.Payload.VERIFICATION_PASSED, false)
            .put(AgentRunSchema.Payload.VERIFICATION_STATUS, "OBSERVED")
        val events = listOf(
            e(1, AgentRunEventType.TASK_STARTED),
            e(2, AgentRunEventType.TOOL_CALL_REQUESTED, tool = "phone.click", mutation = true),
            e(3, AgentRunEventType.TOOL_RESULT, tool = "phone.click", payload = unverified),
            e(4, AgentRunEventType.LEARNING_ACCEPTED),
            e(5, AgentRunEventType.COMPLETE),
        )
        val violations = AgentRunAcceptanceContract.violations(AgentRunAcceptanceScenario.NO_FALSE_SUCCESS, events)
        assertTrue(violations.any { it.startsWith("completion_after_unverified") })
        assertTrue(violations.any { it.startsWith("learning_after_unverified") })
    }

    @Test fun v387TraceTypesMapIntoV388RunSchema() {
        assertEquals(AgentRunEventType.READING_PAGE, AgentRunAcceptanceContract.compatibleType(AgenticTraceEventType.OBSERVATION))
        assertEquals(AgentRunEventType.TOOL_CALL_REQUESTED, AgentRunAcceptanceContract.compatibleType(AgenticTraceEventType.ACTION_REQUESTED))
        assertEquals(AgentRunEventType.GATE_REQUIRED, AgentRunAcceptanceContract.compatibleType(AgenticTraceEventType.GATE_SUSPEND))
        assertEquals(AgentRunEventType.COMPLETE, AgentRunAcceptanceContract.compatibleType(AgenticTraceEventType.TASK_COMPLETE))
    }

    private fun verifiedResult() = JSONObject()
        .put(AgentRunSchema.Payload.ANDROID_EXECUTION_OK, true)
        .put(AgentRunSchema.Payload.VERIFICATION_PASSED, true)
        .put(AgentRunSchema.Payload.VERIFICATION_STATUS, "PASSED")
        .put(AgentRunSchema.Payload.VERIFICATION_BASIS, "PAGE_KEY_CHANGED")

    private fun e(
        sequence: Int,
        type: AgentRunEventType,
        tool: String? = null,
        modelTurn: Int? = null,
        mutation: Boolean? = null,
        payload: JSONObject = JSONObject(),
    ) = AgentRunEvent(
        id = "evt-$sequence",
        runId = "run-1",
        sequence = sequence,
        timestampMs = sequence * 1_000L,
        type = type,
        tool = tool,
        modelTurn = modelTurn,
        mutation = mutation,
        payload = payload,
    )
}
