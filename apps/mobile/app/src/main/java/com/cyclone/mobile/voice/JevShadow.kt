package com.cyclone.mobile.voice

import org.json.JSONObject

/**
 * JEV, watching (alpha.52). TypeSafe's JEV is a decision model: it answers typed questions with a calibrated
 * confidence, very fast and nearly free, but it cannot write. In this release it only watches: for each request the
 * understanding model reads, JEV is asked the same "what kind of request is this?" and the two are compared. Its answer
 * never changes what Cyclone does; Settings → Voice shows how often it agreed and how fast it was, so the owner's own
 * car test decides whether it may take over simple decisions later.
 *
 * Pure: the request and the exact reader (`decisions/DecisionsWire`), and the running tally.
 */
object JevShadow {
    const val MODEL = "~typesafe/jev-latest"

    /** The kinds JEV chooses between: the same as the understanding model's. */
    val CHOICES: List<String> = VoiceKind.entries.map { it.wire }

    data class Decision(val kind: VoiceKind, val confidence: Double)

    /** One comparison: what the understanding model said, what JEV said (null: no usable answer), and JEV's time. */
    data class Sample(val model: VoiceKind, val jev: VoiceKind?, val jevMs: Long, val confidence: Double = 0.0) {
        val agreed: Boolean get() = jev == model
    }

    data class Tally(val samples: List<Sample> = emptyList(), val lastError: String? = null) {
        val answered: List<Sample> get() = samples.filter { it.jev != null }
        val agreement: Double? get() = answered.takeIf { it.isNotEmpty() }?.let { a -> a.count { it.agreed }.toDouble() / a.size }
        val medianMs: Long? get() = answered.map { it.jevMs }.sorted().let { if (it.isEmpty()) null else it[(it.size - 1) / 2] }
        /** Agreement among the answers JEV was sure of (confidence ≥ 0.8): the ones it could take over. */
        val confidentAgreement: Double? get() = answered.filter { it.confidence >= 0.8 }.takeIf { it.isNotEmpty() }
            ?.let { c -> c.count { it.agreed }.toDouble() / c.size }

        fun add(sample: Sample): Tally = copy(samples = (samples + sample).takeLast(KEEP), lastError = null)
        fun failed(reason: String): Tally = copy(lastError = reason.take(160))

        /** "Agreed 18 of 20 · 0.2 s" for Settings. */
        fun summary(): String {
            val a = answered
            if (a.isEmpty()) return lastError?.let { "No answer yet: $it" } ?: "No requests yet."
            return "Agreed ${a.count { it.agreed }} of ${a.size} · ${VoiceTimings.seconds(medianMs)}" +
                (confidentAgreement?.let { " · ${(it * 100).toInt()}% when sure" } ?: "")
        }
    }

    const val KEEP = 50

    /** The one typed question JEV answers (plan 58: the documented shape, with guidance per kind). */
    val QUESTIONS: Map<String, com.cyclone.mobile.decisions.DQuestion> = mapOf("kind" to com.cyclone.mobile.decisions.DQuestion.Choice(
        "What kind of request is this?",
        linkedMapOf(
            "task" to "A new job for the phone.",
            "reply" to "A new job to reply to or message someone.",
            "answer" to "An answer to the open question.",
            "confirm" to "Yes to the open readback.",
            "decline" to "No to the open readback.",
            "cancel" to "Never mind.",
            "none" to "Not meant for the assistant.",
            "unclear" to "A job is meant but something essential is missing.",
        ).let { guidance -> CHOICES.associateWith { guidance[it].orEmpty() } },
    ))

    /** The decision request: the situation as text items of the state, and the one typed question. */
    fun request(transcript: String, context: VoiceContext): JSONObject = com.cyclone.mobile.decisions.DecisionsWire.body(
        MODEL,
        listOfNotNull(
            "Classify one spoken request to a phone assistant used while driving.",
            "Spoken: ${transcript.take(VoiceRules.MAX_TRANSCRIPT_CHARS)}",
            context.open?.spoken?.let { "Open question: $it" },
            context.open?.kind?.let { "Open question kind: $it" },
            "A task is running: ${context.taskLive}",
        ).map { com.cyclone.mobile.decisions.DPart.Text(it) },
        QUESTIONS,
    )

    /** JEV's answer to the "kind" question, read exactly (plan 58). Anything else, or a refusal, is no answer. */
    fun parse(body: String?): Decision? {
        val answer = com.cyclone.mobile.decisions.DecisionsWire.parse(body, QUESTIONS)?.choice("kind") ?: return null
        val kind = VoiceKind.fromWire(answer.choice) ?: return null
        return Decision(kind, answer.confidence)
    }
}
