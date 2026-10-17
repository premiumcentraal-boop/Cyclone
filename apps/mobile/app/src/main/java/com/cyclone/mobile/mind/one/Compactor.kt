package com.cyclone.mobile.mind.one

import com.cyclone.mobile.mind.MindConversation
import com.cyclone.mobile.mind.MindMessage

/** Plan 69 §8: Settings → Speed → "Compact history at". */
enum class CompactAt(val tokens: Int?, val label: String) {
    K50(50_000, "50k tokens"),
    K100(100_000, "100k tokens"),
    K200(200_000, "200k tokens"),
    /** Until the model's own limit. */
    OFF(null, "Off");

    companion object {
        val DEFAULT = K100
        fun of(name: String?): CompactAt = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** The one Luna call (GPT-6 Luna): writes a short text from [material] as [instructions] say. Null on any failure. */
fun interface StoryWriter {
    fun write(instructions: String, material: String): String?
}

/**
 * Plan 69 §8: compaction, one setting and only when really needed. Like Claude and ChatGPT, a mind's conversation is
 * compacted only past the limit the owner set:
 *
 * - at the next milestone boundary, or at once when the conversation is 20% past the limit;
 * - old evidence (briefings, screens, tool results) folds to one line each; the mind's own words are kept whole and
 *   facts are copied exactly;
 * - one Luna call writes the story so far for the folded part, with a code version when Luna fails;
 * - `recall` reopens anything folded.
 *
 * It never compacts the Flash mind's conversation (it is small) and never below the limit. The same path writes the
 * Flash → Mind handover summary. Pure apart from what [story] does.
 */
class Compactor(private val limit: CompactAt = CompactAt.DEFAULT, private val story: StoryWriter? = null) {
    private val folded = mutableListOf<String>()
    var compactions = 0
        private set

    /** The conversation's size in tokens, estimated by code (about four characters a token; a screenshot ≈ 1,000). */
    fun tokens(conversation: MindConversation): Int = conversation.chars() / CHARS_PER_TOKEN

    /** True when [conversation] should be compacted now. */
    fun due(conversation: MindConversation, atBoundary: Boolean): Boolean {
        val max = limit.tokens ?: return false
        val now = tokens(conversation)
        return now > max && (atBoundary || now > max * (1 + OVER_FRACTION))
    }

    data class Result(val folded: Int, val tokensBefore: Int, val tokensAfter: Int, val byLuna: Boolean)

    /**
     * Folds messages before [keepFrom] (the system prompt stays as it is) and appends the story so far. Returns null
     * when there was nothing left to fold.
     */
    fun compact(conversation: MindConversation, score: Score, keepFrom: Int): Result? {
        val before = tokens(conversation)
        val end = keepFrom.coerceIn(1, conversation.size())
        val messages = conversation.all()
        val material = StringBuilder()
        var count = 0
        for (i in 1 until end) {
            when (val m = messages[i]) {
                is MindMessage.User -> {
                    val isBrief = Briefing.isBriefing(m.text) && !m.text.contains(FOLDED_MARK)
                    if (isBrief || m.imageDataUrl != null) {
                        val first = m.text.lineSequence().firstOrNull().orEmpty()
                        if (isBrief) { folded += m.text; material.append(m.text.take(MATERIAL_PER_MESSAGE)).append("\n\n") }
                        conversation.set(i, m.copy(imageDataUrl = null, text = if (isBrief) "$first $FOLDED_MARK" else m.text + " [earlier screenshot removed]"))
                        count++
                    }
                }
                is MindMessage.Tool -> if (!m.compacted) {
                    folded += "${m.name}: ${m.full}"
                    material.append("${m.name}: ${m.brief}\n")
                    conversation.set(i, m.copy(compacted = true))
                    count++
                }
                is MindMessage.Assistant -> if (m.reasoningDetails != null) conversation.set(i, m.copy(reasoningDetails = null))
                is MindMessage.System -> Unit
            }
        }
        if (count == 0) return null
        val luna = story?.let { s -> runCatching { s.write(STORY_INSTRUCTIONS, material.toString().takeLast(MAX_MATERIAL)) }.getOrNull() }
            ?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_STORY)
        conversation.add(MindMessage.User(storyMessage(luna, score, before), origin = MindMessage.User.Origin.HARNESS))
        compactions++
        return Result(count, before, tokens(conversation), luna != null)
    }

    /** What `recall` reopens: folded lines that mention [what], newest first. */
    fun recall(what: String): String {
        val words = what.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        val hits = folded.asReversed().filter { text -> words.isNotEmpty() && words.any { text.lowercase().contains(it) } }
        if (hits.isEmpty()) return "RECALL \"$what\": nothing in the folded history mentions it."
        return "RECALL \"$what\":\n" + hits.take(3).joinToString("\n---\n") { it.take(MATERIAL_PER_MESSAGE) }
    }

    /** Plan 69 §6/§8: the Flash run, summarised once for OneMind's first briefing (Luna, with the code version as fallback). */
    fun handover(flash: MindConversation, score: Score, why: String): String {
        val material = flash.all().drop(1).joinToString("\n") { m ->
            when (m) {
                is MindMessage.User -> m.text.take(MATERIAL_PER_MESSAGE)
                is MindMessage.Assistant -> "Flash mind: ${m.text.take(MATERIAL_PER_MESSAGE)}"
                is MindMessage.Tool -> "${m.name}: ${m.brief}"
                is MindMessage.System -> ""
            }
        }
        val luna = story?.let { s -> runCatching { s.write(HANDOVER_INSTRUCTIONS, material.takeLast(MAX_MATERIAL)) }.getOrNull() }
            ?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_STORY)
        return buildString {
            append("HANDOVER from the Flash mind: $why\n")
            append(luna ?: codeStory(score))
            append("\n").append(finished(score))
        }.trimEnd()
    }

    private fun storyMessage(luna: String?, score: Score, tokensBefore: Int): String = buildString {
        append("STORY SO FAR (older history was folded at about ${tokensBefore / 1000}k tokens; recall reopens it)\n")
        append(luna ?: codeStory(score))
        append("\n").append(finished(score))
    }.trimEnd()

    /** The code version: what was done, what is open, recent events. */
    private fun codeStory(score: Score): String = buildString {
        val p = score.plan
        if (p != null) {
            val open = p.milestones.filter { it.state != MilestoneState.DONE }.map { it.title }
            if (open.isNotEmpty()) append("Still open: ${open.joinToString("; ")}\n")
        }
        val recent = score.events.takeLast(12)
        if (recent.isNotEmpty()) append("Recent events:\n").append(recent.joinToString("\n") { "- ${it.line}" }).append("\n")
    }

    /** Finished milestones as one verified line each, and the facts copied exactly. */
    private fun finished(score: Score): String = buildString {
        val done = score.plan?.milestones?.filter { it.state == MilestoneState.DONE }.orEmpty()
        if (done.isNotEmpty()) append("Finished (verified):\n").append(done.joinToString("\n") { "- ✓ ${it.title}" }).append("\n")
        val facts = score.facts.groupBy { it.name }.values.map { it.last() }
        if (facts.isNotEmpty()) append("Facts (exact):\n").append(facts.joinToString("\n") { "- ${it.name} = ${it.value}" }).append("\n")
    }

    companion object {
        const val CHARS_PER_TOKEN = 4
        const val OVER_FRACTION = 0.2
        const val FOLDED_MARK = "(folded; recall reopens it)"
        const val MATERIAL_PER_MESSAGE = 1_500
        const val MAX_MATERIAL = 24_000
        const val MAX_STORY = 2_000

        const val STORY_INSTRUCTIONS = "You write the 'story so far' of a phone task for the AI overseeing it. From the folded history " +
            "below, write at most 8 short lines: what was learned, what was tried and failed, and what is still open. Do not write " +
            "numbers, amounts, codes or names: the facts are listed separately, exactly. Plain text only."
        const val HANDOVER_INSTRUCTIONS = "A short phone task grew beyond its quick mode and is being handed to a stronger overseer. From " +
            "the run below, write at most 8 short lines: what the task is, what has been done, what went wrong, and what is still " +
            "open. Do not write numbers, amounts, codes or names: the facts are listed separately, exactly. Plain text only."
    }
}
