package com.cyclone.mobile.ai

data class AgentActivityMessageState(
    val lastType: AgentRunEventType? = null,
    val lastEmittedAtMs: Long = Long.MIN_VALUE,
    val lastTool: String? = null,
    val lastToolAtMs: Long = Long.MIN_VALUE,
    val lastThinkingAtMs: Long = Long.MIN_VALUE,
    val emittedCount: Int = 0,
)

data class AgentActivityMessageDecision(
    val message: String?,
    val state: AgentActivityMessageState,
)

object AgentActivityMessageMapper {
    fun map(event: AgentRunEvent, state: AgentActivityMessageState): AgentActivityMessageDecision {
        val shortTool = event.tool.orEmpty().removePrefix("phone.").removePrefix("agent.")
        val now = event.timestampMs

        if (event.type == AgentRunEventType.THINKING &&
            (state.lastType == AgentRunEventType.THINKING || elapsed(now, state.lastThinkingAtMs) < THINKING_COOLDOWN_MS)
        ) {
            return AgentActivityMessageDecision(null, state.copy(lastThinkingAtMs = now))
        }
        if (event.type == AgentRunEventType.TOOL_RUNNING &&
            state.lastType == AgentRunEventType.TOOL_CALL_REQUESTED &&
            state.lastTool == shortTool && elapsed(now, state.lastToolAtMs) < TOOL_STATUS_COOLDOWN_MS
        ) {
            return AgentActivityMessageDecision(null, state)
        }
        if (event.type == state.lastType && elapsed(now, state.lastEmittedAtMs) < DUPLICATE_COOLDOWN_MS) {
            return AgentActivityMessageDecision(null, state)
        }

        val message = messageFor(event, state.emittedCount)
        if (message == null) {
            return AgentActivityMessageDecision(
                null,
                state.copy(
                    lastThinkingAtMs = if (event.type == AgentRunEventType.THINKING) now else state.lastThinkingAtMs,
                    lastTool = if (event.type in TOOL_EVENTS) shortTool.ifBlank { state.lastTool } else state.lastTool,
                    lastToolAtMs = if (event.type in TOOL_EVENTS) now else state.lastToolAtMs,
                ),
            )
        }
        return AgentActivityMessageDecision(
            message,
            state.copy(
                lastType = event.type,
                lastEmittedAtMs = now,
                lastTool = if (event.type in TOOL_EVENTS) shortTool.ifBlank { state.lastTool } else state.lastTool,
                lastToolAtMs = if (event.type in TOOL_EVENTS) now else state.lastToolAtMs,
                lastThinkingAtMs = if (event.type == AgentRunEventType.THINKING) now else state.lastThinkingAtMs,
                emittedCount = state.emittedCount + 1,
            ),
        )
    }

    private fun messageFor(event: AgentRunEvent, cursor: Int): String? = when (event.type) {
        AgentRunEventType.TASK_STARTED -> variant(cursor, "On it ⚡", "Got it.", "Let's do it.")
        AgentRunEventType.THINKING -> variant(cursor, "Thinking…", "Working it out 🧠", "Got a plan.")
        AgentRunEventType.READING_PAGE -> variant(cursor, "Reading the page 👀", "Taking a look…", "Checking what's here.")
        AgentRunEventType.USING_BRAIN -> variant(cursor, "Checking what I know 🧠", "I remember this.", "Found a familiar route.")
        AgentRunEventType.TOOL_CALL_REQUESTED -> toolMessage(event, cursor)
        AgentRunEventType.TOOL_RUNNING -> null
        AgentRunEventType.TOOL_RESULT -> null
        AgentRunEventType.USING_VISION -> variant(cursor, "Let's take a closer look 📸", "Checking visually…", "Looking a little closer 👀")
        AgentRunEventType.VERIFYING -> variant(cursor, "Checking it worked ✓", "Making sure…", "Verifying that.")
        AgentRunEventType.RECOVERING -> variant(cursor, "Another way.", "That wasn't it. Trying something else.", "Hmm — let's try a different route.")
        AgentRunEventType.GATE_REQUIRED -> variant(cursor, "I need you for this one.", "Your turn for a moment.")
        AgentRunEventType.GATE_RESUMED -> variant(cursor, "Got it — continuing.", "Back on it ⚡")
        AgentRunEventType.LEARNING_ACCEPTED -> "I'll remember that route."
        AgentRunEventType.LEARNING_REJECTED -> null
        AgentRunEventType.COMPLETE -> variant(cursor, "Done ✨", "Got it ✓", "All set.")
        AgentRunEventType.FAILED -> variant(cursor, "I couldn't finish that safely.", "Stopped safely.", "I hit a blocker.")
    }

    private fun toolMessage(event: AgentRunEvent, cursor: Int): String {
        val short = event.tool.orEmpty().removePrefix("phone.").removePrefix("agent.")
        val args = event.payload.optJSONObject(AgentRunSchema.Payload.SAFE_ARGUMENTS)
        return when (short) {
            "open_app" -> {
                val name = listOf("name", "app", "label", "package").firstNotNullOfOrNull { key ->
                    args?.optString(key)?.takeIf(String::isNotBlank)
                }
                if (name != null) "Opening ${AgentRunSanitizer.cleanText(name).take(60)} ⚡" else "Opening the app ⚡"
            }
            "scroll" -> "Scrolling a little…"
            "search", "search_batch" -> "Looking for the right control…"
            "inspect", "inspect_batch" -> "Checking the best matches…"
            "visual_context" -> "Let's take a closer look 📸"
            else -> variant(cursor, "Trying that…", "Using the tool…", "Making that move…")
        }
    }

    private fun variant(cursor: Int, vararg values: String): String = values[Math.floorMod(cursor, values.size)]

    private fun elapsed(now: Long, then: Long): Long = if (then == Long.MIN_VALUE) Long.MAX_VALUE else (now - then).coerceAtLeast(0)

    private val TOOL_EVENTS = setOf(
        AgentRunEventType.TOOL_CALL_REQUESTED,
        AgentRunEventType.TOOL_RUNNING,
        AgentRunEventType.TOOL_RESULT,
    )
    private const val THINKING_COOLDOWN_MS = 1_500L
    private const val TOOL_STATUS_COOLDOWN_MS = 1_200L
    private const val DUPLICATE_COOLDOWN_MS = 650L
}

object AgentActivityStreamRuntime {
    private val lock = Any()
    private val states = linkedMapOf<String, AgentActivityMessageState>()

    fun message(event: AgentRunEvent): String? = synchronized(lock) {
        val key = event.runId
        val current = states[key] ?: AgentActivityMessageState()
        val decision = AgentActivityMessageMapper.map(event, current)
        states[key] = decision.state
        if (event.type == AgentRunEventType.COMPLETE || event.type == AgentRunEventType.FAILED) states.remove(key)
        decision.message
    }

    fun reset(runId: String) = synchronized(lock) { states.remove(runId); Unit }
}
