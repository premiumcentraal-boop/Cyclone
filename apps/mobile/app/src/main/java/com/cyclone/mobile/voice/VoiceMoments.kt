package com.cyclone.mobile.voice

/**
 * Owner Moments as voice turns (plan 24 §5.5). The Owner Moment stays the one source of truth; this is only how it
 * sounds and which answers voice may give. Every answer goes back through Task Kit.
 *
 * | Moment | Cyclone says | The answer becomes |
 * |---|---|---|
 * | QUESTION | the question (≤ 15 words), options as "A, or B?" | `Reply(text)` |
 * | everything else | "That needs you on screen when you're stopped." | nothing by voice |
 */
object VoiceMoments {
    /** The kinds the owner can answer by voice. */
    val VOICE_KINDS: Set<VoiceMoment.Kind> = setOf(VoiceMoment.Kind.QUESTION)

    const val EDITING = "Changing it."
    const val SENDING = "Sending."
    const val NOT_SENT = "Okay, I won't send it."

    /** What Cyclone says when [m] opens. */
    fun prompt(m: VoiceMoment): String = when {
        m.kind == VoiceMoment.Kind.QUESTION -> question(m.text, m.choices)
        else -> VoiceCopy.NEEDS_SCREEN
    }

    /**
     * A question as spoken: quoted messages longer than 25 words become "a long message", and a question longer than
     * 15 words (quotes aside) is cut to its last sentence, which is where the question is.
     */
    fun question(text: String, choices: List<String> = emptyList()): String {
        val safe = VoiceRedaction.spoken(text)
        val quoted = QUOTE.replace(safe) { match ->
            val inner = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
            val said = VoiceCopy.message(inner)
            if (said == inner) match.value else said
        }
        val outsideQuotes = VoiceCopy.words(QUOTE.replace(quoted, "")).size
        val line = if (outsideQuotes <= VoiceCopy.QUESTION_WORDS) quoted
            else VoiceCopy.clip(lastSentence(quoted), VoiceCopy.QUESTION_WORDS)
        val options = VoiceCopy.options(choices)
        return if (options.isBlank()) line else "$line $options"
    }

    /** VALUES by voice: not in this release; the card waits on screen. */
    fun fill(m: VoiceMoment, answer: String): Map<String, String>? = null

    /** "Change it to …" as the instruction the Mind receives while its readback is open. */
    fun edit(text: String): String {
        val t = text.trim()
        return if (t.startsWith("change", ignoreCase = true)) t else "Change the message: $t"
    }

    private fun lastSentence(text: String): String {
        val parts = text.trim().split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        return parts.lastOrNull { it.trimEnd().endsWith("?") } ?: parts.lastOrNull().orEmpty()
    }

    private val QUOTE = Regex("\"([^\"]+)\"|“([^”]+)”|'([^']{3,})'(?=[\\s.,!?]|$)|‘([^’]+)’")
}
