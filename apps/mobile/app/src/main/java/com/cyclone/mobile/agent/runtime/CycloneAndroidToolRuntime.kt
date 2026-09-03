package com.cyclone.mobile.agent.runtime

import android.content.Context
import com.cyclone.mobile.agent.tools.CycloneCompoundAgentTools
import com.cyclone.mobile.agent.tools.CycloneCompoundAgentToolsApi
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Thin provider-neutral adapter over Agent 2's compound toolbox.
 * It adds only task-level completion verification; Android action authority remains below it.
 */
class CycloneAndroidToolRuntime(
    context: Context,
    private val compound: CycloneCompoundAgentToolsApi = CycloneCompoundAgentTools(context.applicationContext),
) : AgentToolExecutor {
    private val descriptors = compound.descriptors()
    private val mutations: Set<String> = buildSet {
        for (index in 0 until descriptors.length()) {
            val item = descriptors.optJSONObject(index) ?: continue
            if (item.optBoolean("mutation", false)) add(item.optString("name"))
        }
    }
    private var lastMutationTool: String? = null
    private var lastMutationArguments: JSONObject? = null
    private var lastMutationResult: JSONObject? = null

    override fun descriptors(): JSONArray = JSONArray(descriptors.toString())
    override fun isMutation(tool: String): Boolean = tool in mutations

    override fun call(tool: String, arguments: JSONObject): JSONObject {
        val result = compound.call(tool, arguments)
        if (isMutation(tool)) {
            lastMutationTool = tool
            lastMutationArguments = JSONObject(arguments.toString())
            lastMutationResult = JSONObject(result.toString())
        }
        return result
    }

    override fun verifyCompletion(goal: String): AgentCompletionEvidence {
        val packet = compound.understandPage(goal)
        if (!packet.optBoolean("success", false)) {
            return AgentCompletionEvidence(
                verified = false,
                message = packet.optString("message").ifBlank { "Fresh page evidence was unavailable." },
                payload = packet,
            )
        }

        val identity = listOf(
            packet.optString("pageKey"),
            packet.optString("generation"),
            packet.optString("package"),
        ).filter(String::isNotBlank).joinToString("|")

        val last = lastMutationResult
        if (lastMutationTool == "open_app" && last?.optBoolean("success", false) == true && last.optBoolean("packageVerified", false)) {
            val name = last.optString("resolvedApp").lowercase(Locale.US)
            val requested = lastMutationArguments?.optString("name").orEmpty().lowercase(Locale.US)
            val lowerGoal = goal.lowercase(Locale.US)
            if ((name.isNotBlank() && lowerGoal.contains(name)) || (requested.isNotBlank() && lowerGoal.contains(requested))) {
                return AgentCompletionEvidence(
                    true,
                    identity,
                    "The requested app is open and Android package verification passed.",
                    JSONObject(packet.toString()).put("completionBasis", "VERIFIED_OPEN_APP"),
                )
            }
        }

        val targetTokens = finalGoalTokens(goal)
        if (targetTokens.isEmpty()) {
            return AgentCompletionEvidence(false, identity, "The final goal is too ambiguous to verify automatically.", packet)
        }
        val haystack = packet.toString().lowercase(Locale.US)
        val matches = targetTokens.count(haystack::contains)
        val required = if (targetTokens.size == 1) 1 else minOf(2, targetTokens.size)
        val lastVerified = last?.optJSONObject("verification")?.optBoolean("passed", false) == true
        val verified = matches >= required && (lastVerified || lastMutationTool == null)
        return AgentCompletionEvidence(
            verified = verified,
            evidenceIdentity = identity,
            message = if (verified) {
                "Fresh Android evidence matches the requested final state."
            } else {
                "The model's completion claim is not yet proven by the fresh Android page."
            },
            payload = JSONObject(packet.toString())
                .put("completionBasis", if (verified) "GOAL_EVIDENCE" else "INSUFFICIENT_GOAL_EVIDENCE")
                .put("goalTokens", JSONArray(targetTokens))
                .put("matchedGoalTokens", matches)
                .put("requiredGoalTokens", required)
                .put("lastMutationVerified", lastVerified),
        )
    }

    private fun finalGoalTokens(goal: String): List<String> {
        val finalSegment = goal
            .split(Regex("(?i)\\bthen\\b|\\bfinally\\b|->|→|;|,"))
            .map(String::trim)
            .lastOrNull(String::isNotBlank)
            ?: goal
        return finalSegment.lowercase(Locale.US)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .distinct()
            .takeLast(5)
    }

    private companion object {
        val STOP_WORDS = setOf(
            "open", "launch", "start", "find", "show", "navigate", "take", "click", "tap",
            "the", "and", "then", "finally", "please", "page", "screen", "this", "that",
            "with", "from", "into", "your", "you", "for", "cyclone",
        )
    }
}
