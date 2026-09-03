package com.cyclone.mobile.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActivityMessagesTest {
    @Test
    fun variantsRotateDeterministicallyWithoutRandomness() {
        var state = AgentActivityMessageState()
        val first = AgentActivityMessageMapper.map(event(1, AgentRunEventType.TASK_STARTED), state)
        state = first.state
        val read = AgentActivityMessageMapper.map(event(2_000, AgentRunEventType.READING_PAGE), state)
        state = read.state
        val complete = AgentActivityMessageMapper.map(event(4_000, AgentRunEventType.COMPLETE), state)

        assertEquals("On it ⚡", first.message)
        assertEquals("Taking a look…", read.message)
        assertEquals("All set.", complete.message)
    }

    @Test
    fun duplicateThinkingAndRapidToolRunningAreSuppressed() {
        var state = AgentActivityMessageState()
        val thinking = AgentActivityMessageMapper.map(event(1_000, AgentRunEventType.THINKING), state)
        state = thinking.state
        val duplicateThinking = AgentActivityMessageMapper.map(event(1_200, AgentRunEventType.THINKING), state)
        state = duplicateThinking.state
        val call = AgentActivityMessageMapper.map(event(3_000, AgentRunEventType.TOOL_CALL_REQUESTED, "phone.click"), state)
        state = call.state
        val running = AgentActivityMessageMapper.map(event(3_100, AgentRunEventType.TOOL_RUNNING, "phone.click"), state)

        assertTrue(thinking.message!!.isNotBlank())
        assertNull(duplicateThinking.message)
        assertTrue(call.message!!.isNotBlank())
        assertNull(running.message)
    }

    @Test
    fun openAppMessageUsesOnlySafeStructuredArgument() {
        val event = event(
            1_000,
            AgentRunEventType.TOOL_CALL_REQUESTED,
            "phone.open_app",
            JSONObject().put(AgentRunSchema.Payload.SAFE_ARGUMENTS, JSONObject().put("name", "Chrome")),
        )

        val decision = AgentActivityMessageMapper.map(event, AgentActivityMessageState())

        assertEquals("Opening Chrome ⚡", decision.message)
    }

    @Test
    fun technicalToolResultsDoNotSpamOverlay() {
        val event = event(
            1_000,
            AgentRunEventType.TOOL_RESULT,
            "phone.click",
            JSONObject().put(AgentRunSchema.Payload.ERROR_CLASS, "STALE_OBSERVATION"),
        )
        val decision = AgentActivityMessageMapper.map(event, AgentActivityMessageState())
        assertNull(decision.message)
    }

    private fun event(
        time: Long,
        type: AgentRunEventType,
        tool: String? = null,
        payload: JSONObject = JSONObject(),
    ) = AgentRunEvent("evt-$time-$type", "run-1", time.toInt(), time, type, tool = tool, payload = payload)
}
