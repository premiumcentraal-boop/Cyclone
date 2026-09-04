package com.cyclone.mobile.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunDiagnosticV39Test {
    private fun session(status: String = "FAILED") = AiTraceSession(
        id = "ai-test-session",
        goal = "go to ad.nl",
        model = "test/model",
        status = status,
        startedAt = 1_000L,
        endedAt = 4_000L,
        result = "Stopped after invalid tool request",
        decisions = 2,
    )

    @Test
    fun highSignalTraceContainsDecisionsToolsFailuresVerificationAndRecovery() {
        val events = listOf(
            AiTraceEvent("1", "ai-test-session", 1_100, "PAGE", "Current page understood: Cyclone AI", "page.capture", true, "8 semantic controls"),
            AiTraceEvent("2", "ai-test-session", 1_200, "PLAN", "Understanding this page and choosing the next local step", "model.page_decision", true, "Provider request 1"),
            AiTraceEvent("3", "ai-test-session", 1_300, "ACTION_REQUESTED", "Open Chrome", "phone.open_app", null, null),
            AiTraceEvent("4", "ai-test-session", 1_400, "ANDROID_EXECUTION", "Android rejected the action", "INVALID_REQUEST", false, "package is required"),
            AiTraceEvent("5", "ai-test-session", 1_500, "VERIFICATION", "Execution did not prove semantic success", "EXECUTION_FAILED", false, null),
            AiTraceEvent("6", "ai-test-session", 1_600, "RECOVERY_SELECTED", "known verified route", "KNOWN_VERIFIED_ROUTE", true, null),
        )
        val text = AgentRunDiagnosticV39.format(session(), events)
        assertTrue(text.contains("MODEL SAW / CONTEXT"))
        assertTrue(text.contains("MODEL DECISION"))
        assertTrue(text.contains("TOOL REQUEST"))
        assertTrue(text.contains("phone.open_app"))
        assertTrue(text.contains("INVALID_REQUEST"))
        assertTrue(text.contains("package is required"))
        assertTrue(text.contains("VERIFICATION"))
        assertTrue(text.contains("RECOVERY"))
        assertTrue(text.contains("FINAL RESULT"))
    }

    @Test
    fun secretsAndLargeBinaryPayloadsAreRedacted() {
        val base64 = "A".repeat(500)
        val events = listOf(
            AiTraceEvent("1", "ai-test-session", 1_100, "TOOL_RESULT", "token=supersecret", "phone.screenshot", false,
                "Authorization: Bearer abcdefghijklmnop pngBase64=\"$base64\" password=hunter2 otp=123456"),
        )
        val text = AgentRunDiagnosticV39.format(session(), events)
        assertFalse(text.contains("supersecret"))
        assertFalse(text.contains("abcdefghijklmnop"))
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains(base64))
        assertTrue(text.contains("[REDACTED]"))
    }

    @Test
    fun largeLogsRemainBoundedAndKeepPrivacyTail() {
        val events = (1..120).map { index ->
            AiTraceEvent(index.toString(), "ai-test-session", 1_000L + index, "MODEL_CONTEXT", "context $index", null, true, "x".repeat(7_500))
        }
        val text = AgentRunDiagnosticV39.format(session(), events)
        assertTrue(text.toByteArray().size <= AgentRunDiagnosticV39.MAX_BYTES)
        assertTrue(text.contains("DIAGNOSTIC TIMELINE TRUNCATED"))
        assertTrue(text.contains("PRIVACY"))
    }

    @Test
    fun metricsCountUsefulRunSignals() {
        val events = listOf(
            AiTraceEvent("1", "s", 1, "ACTION_REQUESTED", "a", "phone.click", null, null),
            AiTraceEvent("2", "s", 2, "ANDROID_EXECUTION", "bad", "ACTION_FAILED", false, null),
            AiTraceEvent("3", "s", 3, "VERIFICATION", "ok", "PAGE_CHANGED", true, null),
            AiTraceEvent("4", "s", 4, "RECOVERY_SELECTED", "r", "SEARCH", true, null),
            AiTraceEvent("5", "s", 5, "VISION_ESCALATION", "v", null, true, null),
        )
        val metrics = AgentRunDiagnosticV39.metrics(events)
        assertTrue(metrics.toolCalls == 1)
        assertTrue(metrics.failures == 1)
        assertTrue(metrics.verifiedActions == 1)
        assertTrue(metrics.recoveries == 1)
        assertTrue(metrics.visionChecks == 1)
    }
}
