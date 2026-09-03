package com.cyclone.mobile.agent.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class PersistentToolAgentRuntimeTest {
    private class ScriptProvider(
        turns: List<(List<AgentConversationEntry>) -> AgentProviderTurn>,
    ) : AgentConversationProvider {
        private val turns = ArrayDeque(turns)
        var calls = 0
        override fun next(conversation: List<AgentConversationEntry>, tools: JSONArray): AgentProviderTurn {
            calls++
            return checkNotNull(turns.pollFirst()) { "No scripted provider turn left" }(conversation)
        }
    }

    private class FakeTools(
        private val mutations: Set<String> = setOf("open_app", "click"),
        results: List<JSONObject> = emptyList(),
        completions: List<AgentCompletionEvidence> = listOf(AgentCompletionEvidence(true, "done", "done")),
    ) : AgentToolExecutor {
        private val results = ArrayDeque(results)
        private val completions = ArrayDeque(completions)
        override fun descriptors(): JSONArray = JSONArray()
            .put(JSONObject().put("name", "open_app").put("mutation", true))
            .put(JSONObject().put("name", "click").put("mutation", true))
            .put(JSONObject().put("name", "search").put("mutation", false))
            .put(JSONObject().put("name", "understand_page").put("mutation", false))

        override fun isMutation(tool: String): Boolean = tool in mutations

        override fun call(tool: String, arguments: JSONObject): JSONObject =
            results.pollFirst() ?: JSONObject().put("success", true)

        override fun verifyCompletion(goal: String): AgentCompletionEvidence =
            completions.pollFirst() ?: AgentCompletionEvidence(true, "done", "done")
    }

    @Test
    fun toolCallResultThenFinalCompletes() {
        val provider = ScriptProvider(listOf(
            { AgentProviderTurn.ToolCalls(listOf(call("1", "open_app", JSONObject().put("name", "Chrome"))), true) },
            { conversation ->
                assertTrue(conversation.any { it is AgentConversationEntry.ToolResult && it.tool == "open_app" })
                AgentProviderTurn.Final("Done.", true)
            },
        ))
        val tools = FakeTools(results = listOf(verifiedAction("com.android.chrome")))
        val runtime = PersistentToolAgentRuntime("Open Chrome", provider, tools, "system")

        val result = runtime.runUntilBoundary()

        assertTrue(result is PersistentAgentRunResult.Completed)
        assertEquals(2, result.state.modelTurns)
        assertEquals(1, result.state.toolTurns)
    }

    @Test
    fun detailedToolFailureReachesNextModelTurnAndStrategyCanChange() {
        val provider = ScriptProvider(listOf(
            { AgentProviderTurn.ToolCalls(listOf(call("1", "click", JSONObject().put("elementId", "old"))), true) },
            { conversation ->
                val result = conversation.filterIsInstance<AgentConversationEntry.ToolResult>().last()
                assertEquals("TARGET_NOT_FOUND", result.payload.optJSONObject("failure")?.optString("errorClass"))
                assertEquals("That control disappeared.", result.payload.optJSONObject("failure")?.optString("message"))
                AgentProviderTurn.ToolCalls(listOf(call("2", "search", JSONObject().put("queries", JSONArray().put("Chrome")))), true)
            },
            { AgentProviderTurn.Final("Found another route.", true) },
        ))
        val tools = FakeTools(
            results = listOf(
                JSONObject()
                    .put("success", false)
                    .put("failure", JSONObject()
                        .put("errorClass", "TARGET_NOT_FOUND")
                        .put("failureLayer", "TARGET")
                        .put("retryable", true)
                        .put("message", "That control disappeared.")),
                JSONObject().put("success", true).put("observationId", "obs-2"),
            ),
        )
        val runtime = PersistentToolAgentRuntime("Open Chrome", provider, tools, "system")

        val result = runtime.runUntilBoundary()

        assertTrue(result is PersistentAgentRunResult.Completed)
        assertEquals(2, result.state.toolTurns)
    }

    @Test
    fun moreThanSixToolTurnsAreAllowedWhenTaskContinues() {
        val turns = mutableListOf<(List<AgentConversationEntry>) -> AgentProviderTurn>()
        val results = mutableListOf<JSONObject>()
        repeat(7) { index ->
            turns += { AgentProviderTurn.ToolCalls(listOf(call("c" + index, "search", JSONObject().put("queries", JSONArray().put("q" + index)))), true) }
            results += JSONObject().put("success", true).put("observationId", "obs-" + index)
        }
        turns += { AgentProviderTurn.Final("Done.", true) }
        val runtime = PersistentToolAgentRuntime(
            "long task",
            ScriptProvider(turns),
            FakeTools(results = results),
            "system",
        )

        val result = runtime.runUntilBoundary()

        assertTrue(result is PersistentAgentRunResult.Completed)
        assertEquals(7, result.state.toolTurns)
        assertEquals(8, result.state.modelTurns)
    }

    @Test
    fun unverifiedFinalAnswerReturnsToSameConversation() {
        val provider = ScriptProvider(listOf(
            { AgentProviderTurn.Final("Done.", true) },
            { conversation ->
                assertTrue(conversation.filterIsInstance<AgentConversationEntry.Text>().any {
                    it.role == "system" && it.content.contains("COMPLETION_NOT_VERIFIED")
                })
                AgentProviderTurn.ToolCalls(listOf(call("2", "understand_page", JSONObject())), true)
            },
            { AgentProviderTurn.Final("Now done.", true) },
        ))
        val runtime = PersistentToolAgentRuntime(
            "Find saved episodes",
            provider,
            FakeTools(
                results = listOf(JSONObject().put("success", true).put("observationId", "new")),
                completions = listOf(
                    AgentCompletionEvidence(false, "same", "not proven"),
                    AgentCompletionEvidence(true, "new", "proven"),
                ),
            ),
            "system",
        )

        val result = runtime.runUntilBoundary()

        assertTrue(result is PersistentAgentRunResult.Completed)
        assertEquals(3, result.state.modelTurns)
    }

    @Test
    fun gateSuspendsAndResumeKeepsConversation() {
        val provider = ScriptProvider(listOf(
            { AgentProviderTurn.ToolCalls(listOf(call("1", "click", JSONObject().put("elementId", "delete"))), true) },
            { conversation ->
                assertTrue(conversation.any { it is AgentConversationEntry.ToolResult })
                assertTrue(conversation.filterIsInstance<AgentConversationEntry.Text>().any { it.content.contains("user boundary is resolved") })
                AgentProviderTurn.Final("Done.", true)
            },
        ))
        val gateResult = JSONObject()
            .put("success", false)
            .put("failure", JSONObject()
                .put("errorClass", "GATE_REQUIRED")
                .put("failureLayer", "POLICY")
                .put("retryable", true)
                .put("message", "Confirmation required."))
        val runtime = PersistentToolAgentRuntime(
            "Delete item",
            provider,
            FakeTools(results = listOf(gateResult)),
            "system",
        )

        val first = runtime.runUntilBoundary()
        assertTrue(first is PersistentAgentRunResult.Suspended)
        assertTrue(runtime.resume())
        val second = runtime.runUntilBoundary()
        assertTrue(second is PersistentAgentRunResult.Completed)
        assertEquals(first.state.taskId, second.state.taskId)
    }

    private fun call(id: String, name: String, args: JSONObject) = AgentToolCall(id, name, args)

    private fun verifiedAction(pkg: String) = JSONObject()
        .put("success", true)
        .put("packageVerified", true)
        .put("resolvedApp", "Chrome")
        .put("execution", JSONObject().put("androidExecutionOk", true))
        .put("verification", JSONObject().put("status", "PASSED").put("passed", true).put("basis", "package"))
        .put("failure", JSONObject().put("errorClass", "NONE").put("failureLayer", "NONE").put("retryable", false))
        .put("after", JSONObject().put("pageKey", "chrome").put("package", pkg))
        .put("delta", JSONObject().put("pageChanged", true))
        .put("learning", JSONObject().put("recorded", true).put("reason", "verified"))
}
