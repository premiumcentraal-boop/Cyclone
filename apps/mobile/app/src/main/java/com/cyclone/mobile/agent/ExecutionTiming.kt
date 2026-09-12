package com.cyclone.mobile.agent

/** Monotonic, payload-free causal spans. No UI text, model content or action arguments. */
enum class ExecutionPhase { OBSERVATION, PLAN_OR_RECALL, LOCAL_POLICY, ROUTE_RECALL, PROMPT, PROVIDER_WAIT, GROUNDING, DISPATCH, SETTLING, VERIFICATION }
data class ExecutionSpan(
    val schema: Int = 1,
    val decisionId: Int,
    val spanId: Long,
    val phase: ExecutionPhase,
    val startMs: Long,
    val durationMs: Long,
    val result: String,
)
class ExecutionTiming(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sink: (ExecutionSpan) -> Unit = {},
) {
    private var sequence = 0L
    fun <T> measure(decision: Int, phase: ExecutionPhase, cancelled: () -> Boolean = { false }, block: () -> T): T {
        val start = clock()
        val id = ++sequence
        var result = "incomplete"
        try {
            val value = block()
            result = if (cancelled()) "cancelled" else "returned"
            return value
        } finally {
            sink(ExecutionSpan(decisionId = decision, spanId = id, phase = phase,
                startMs = start, durationMs = (clock() - start).coerceAtLeast(0), result = result))
        }
    }
}

