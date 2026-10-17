package com.cyclone.mobile.mind

import org.json.JSONArray

data class MindUsage(val promptTokens: Int = 0, val completionTokens: Int = 0, val costUsd: Double = 0.0) {
    operator fun plus(other: MindUsage) = MindUsage(promptTokens + other.promptTokens,
        completionTokens + other.completionTokens, costUsd + other.costUsd)
}

data class MindModelReply(
    val text: String,
    val toolCalls: List<MindToolCall>,
    val reasoningDetails: JSONArray? = null,
    val usage: MindUsage = MindUsage(),
    val latencyMs: Long = 0,
)

data class MindModelRequest(
    val messages: JSONArray,
    val tools: List<MindToolSpec>,
    val nativeTools: Boolean,
    val budgetMs: Long,
)

/** Typed failures so the loop can decide: wait and retry, switch to the backup model, or stop honestly. */
sealed class MindModelError(message: String) : Exception(message) {
    class RateLimited(message: String, val retryAfterMs: Long = 2_000) : MindModelError(message)
    class Transient(message: String) : MindModelError(message)
    class Deadline(message: String) : MindModelError(message)
    class Cancelled : MindModelError("The mission was stopped.")
    class ToolsUnsupported(message: String) : MindModelError(message)
    class Fatal(message: String) : MindModelError(message)
}

interface MindModel {
    val id: String
    val label: String
    val vision: Boolean
    fun complete(request: MindModelRequest): MindModelReply
}

/**
 * Plan 69 §6: a model that hands over its text while it writes, so a streamed plan starts acting on its first line.
 * [onText] gets each new piece of text in order; the returned reply is the whole answer.
 */
interface StreamingMindModel : MindModel {
    fun stream(request: MindModelRequest, onText: (String) -> Unit): MindModelReply
}
