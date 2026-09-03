package com.cyclone.mobile.ai

import android.content.Context
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.agent.runtime.AgentRuntimeEvent
import com.cyclone.mobile.agent.runtime.AgentRuntimeEventSink
import com.cyclone.mobile.agent.runtime.AgentRuntimeEventType
import com.cyclone.mobile.agent.runtime.CycloneAndroidToolRuntime
import com.cyclone.mobile.agent.runtime.OpenRouterToolCallingProvider
import com.cyclone.mobile.agent.runtime.PersistentAgentRunResult
import com.cyclone.mobile.agent.runtime.PersistentAgentStatus
import com.cyclone.mobile.agent.runtime.PersistentToolAgentPolicy
import com.cyclone.mobile.agent.runtime.PersistentToolAgentRuntime
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Cyclone Mobile 3.8.8 persistent native tool harness.
 *
 * Canonical foreground path:
 * LLM -> tool call -> Cyclone compound Android tool -> rich tool result -> same LLM conversation.
 *
 * PageAgentProtocol is deliberately not part of this production reasoning path.
 */
class OpenRouterAdaptiveAgent(private val context: Context) {
    private data class ActiveSession(
        val runtime: PersistentToolAgentRuntime,
        val runId: String,
        val config: QuickAgentConfig,
    )

    @Volatile
    private var activeSession: ActiveSession? = null

    suspend fun execute(
        goal: String,
        config: QuickAgentConfig,
        onProgress: (String) -> Unit = {},
    ): QuickAgentResult = withContext(Dispatchers.IO) {
        val cleanGoal = goal.trim()
        if (cleanGoal.isBlank()) {
            return@withContext QuickAgentResult(false, "Describe what you want Cyclone to do.", 0, config.model.id)
        }

        AgentRunRuntime.initialize(context)
        AdaptiveBrainRuntime.initialize(context)
        AppLearnerRuntime.initialize(context)

        val runId = AgentRunRuntime.start(context, cleanGoal, config.model.id)
        val apiKey = OpenRouterSecretStore.read(context)
        if (apiKey.isBlank()) {
            AgentRunRuntime.finish(
                context,
                runId,
                AgentRunStatus.FAILED,
                "HARD_BLOCKER",
                "Add an OpenRouter API key first.",
            )
            return@withContext QuickAgentResult(
                false,
                "Add an OpenRouter API key first.",
                0,
                config.model.id,
                taskId = runId,
                classification = "HARD_BLOCKER",
            )
        }

        val toolRuntime = CycloneAndroidToolRuntime(context)
        val provider = OpenRouterToolCallingProvider(
            apiKey = apiKey,
            model = config.model,
            providerSort = config.providerSort,
            sessionId = runId,
        )
        val sink = AgentRuntimeEventSink { event ->
            publishRunEvent(runId, event)
        }
        val runtime = PersistentToolAgentRuntime(
            goal = cleanGoal,
            provider = provider,
            tools = toolRuntime,
            systemPrompt = SYSTEM_PROMPT,
            policy = PersistentToolAgentPolicy(
                taskTimeoutMs = 180_000L,
                maxConsecutiveProviderFailures = 3,
                maxRepeatedIdenticalActionWithoutProgress = 2,
                maxCompletionRejectionsWithoutNewEvidence = 4,
                maxTotalToolCalls = 48,
            ),
            events = sink,
            taskId = runId,
        )

        val session = ActiveSession(runtime, runId, config)
        activeSession = session
        drive(session, onProgress)
    }

    suspend fun resume(onProgress: (String) -> Unit = {}): QuickAgentResult = withContext(Dispatchers.IO) {
        val session = activeSession
            ?: return@withContext QuickAgentResult(
                false,
                "There is no suspended Cyclone task to resume.",
                0,
                "cyclone-agent",
                classification = "HARD_BLOCKER",
            )

        if (DeviceState.controller != DeviceState.Controller.AGENT) {
            return@withContext QuickAgentResult(
                false,
                "Cyclone is waiting for control to return to AGENT.",
                session.runtime.snapshot().modelTurns,
                session.config.model.id,
                taskId = session.runId,
                classification = "HUMAN_OR_GATE",
                gateClass = OverlayChromeRuntime.snapshot().gateClass?.wire,
            )
        }

        if (!session.runtime.resume()) {
            return@withContext QuickAgentResult(
                false,
                "The suspended task is no longer resumable.",
                session.runtime.snapshot().modelTurns,
                session.config.model.id,
                taskId = session.runId,
                classification = session.runtime.snapshot().status.name,
            )
        }
        drive(session, onProgress)
    }

    fun cancelActiveTask() {
        activeSession?.runtime?.cancel()
    }

    suspend fun buildWorkflow(
        goal: String,
        config: QuickAgentConfig,
        onProgress: (String) -> Unit = {},
    ): QuickAgentResult = OpenRouterQuickAgent(context).buildWorkflow(goal, config, onProgress)

    private fun drive(session: ActiveSession, onProgress: (String) -> Unit): QuickAgentResult =
        when (val result = session.runtime.runUntilBoundary()) {
            is PersistentAgentRunResult.Completed -> {
                activeSession = null
                AgentRunRuntime.finish(
                    context,
                    session.runId,
                    AgentRunStatus.COMPLETE,
                    "COMPLETE",
                    result.message,
                )
                QuickAgentResult(
                    true,
                    result.message.ifBlank { "Done." },
                    result.state.modelTurns,
                    session.config.model.id,
                    taskId = session.runId,
                    classification = "COMPLETE",
                )
            }

            is PersistentAgentRunResult.Suspended -> {
                activeSession = session
                QuickAgentResult(
                    false,
                    result.message,
                    result.state.modelTurns,
                    session.config.model.id,
                    taskId = session.runId,
                    classification = "HUMAN_OR_GATE",
                    gateClass = OverlayChromeRuntime.snapshot().gateClass?.wire,
                )
            }

            is PersistentAgentRunResult.Cancelled -> {
                activeSession = null
                AgentRunRuntime.finish(
                    context,
                    session.runId,
                    AgentRunStatus.CANCELLED,
                    "CANCELLED",
                    result.message,
                )
                QuickAgentResult(
                    false,
                    result.message,
                    result.state.modelTurns,
                    session.config.model.id,
                    taskId = session.runId,
                    classification = "CANCELLED",
                )
            }

            is PersistentAgentRunResult.Stopped -> {
                activeSession = null
                AgentRunRuntime.finish(
                    context,
                    session.runId,
                    AgentRunStatus.FAILED,
                    result.state.status.name,
                    result.message,
                )
                QuickAgentResult(
                    false,
                    result.message,
                    result.state.modelTurns,
                    session.config.model.id,
                    taskId = session.runId,
                    classification = when (result.state.status) {
                        PersistentAgentStatus.HARD_BLOCKER -> "HARD_BLOCKER"
                        PersistentAgentStatus.NON_CONVERGENCE -> "NON_CONVERGENCE"
                        else -> "FAILED"
                    },
                )
            }
        }

    private fun publishRunEvent(
        runId: String,
        event: AgentRuntimeEvent,
    ) {
        if (event.type == AgentRuntimeEventType.TASK_STARTED) return
        val type = runCatching { AgentRunEventType.valueOf(event.type.name) }.getOrNull() ?: return
        val logged = AgentRunRuntime.event(
            context = context,
            runId = runId,
            type = type,
            message = event.message,
            tool = event.tool,
            modelTurn = event.modelTurn,
            toolTurn = event.toolTurn,
            mutation = event.mutation,
            payload = JSONObject(event.payload.toString()),
            timestampMs = event.timestampMs,
        )
    }

    companion object {
        private val SYSTEM_PROMPT = """
You are Cyclone Mobile's Android task agent.

Work on the user's goal by calling the provided tools. This is a real tool loop:
you may call a tool, inspect its exact result, update your plan, call another tool, and continue.

Core rules:
- Keep simple tasks simple. To open a named app, prefer open_app(name=...) immediately.
- Use understand_page when you need a coherent current Page Card. It already includes useful Brain/App Graph/app-match context.
- Use recall only when additional learned knowledge may help.
- search accepts multiple queries in one call; inspect accepts multiple current element IDs in one call.
- visual_context is a normal evidence tool for ambiguous/custom/WebView screens; screenshots never prove action success.
- After a mutation, old observation-scoped element IDs are stale. Re-read/search before another element-targeted action.
- Read the complete tool result, including typed failure, retryability, fresh after-state, semantic delta and verification.
- If a tool fails, change strategy based on the actual failure instead of repeating blindly.
- Android verification, GATE, credentials/OTP restrictions and policy are authoritative. Never try to bypass them.
- Do not claim that an action succeeded just because Android accepted it; semantic verification is authoritative.
- Do not expose chain-of-thought. Tool calls and short final responses are enough.
- When you believe the user's goal is satisfied, return a concise final answer. Cyclone will independently verify the final state; if it is not proven, you will receive a completion-not-verified message and must continue.
""".trimIndent()
    }
}
