package com.cyclone.mobile.mind.decide

import com.cyclone.mobile.mind.modes.InstantGrammar
import com.cyclone.mobile.mind.modes.ModeRouter

/**
 * Plan 65: the best decision call is no call. People repeat themselves: the same few words for the same easy action,
 * many times a day, in whatever language they speak. When this phone has already done exactly that request, the same
 * way, at least [STREAK] times in a row and the phone confirmed it each time, the next one is done at once, with no
 * model asked: 0 ms instead of a race.
 *
 * Nothing here is a word list: the key is the owner's own words (normalised), the answer is what the phone proved.
 * Only easy-to-undo actions ([ModeRouter.REVERSIBLE]) replay. Any later failure, hand-up or cancel of the same request
 * forgets it, and a different verified answer starts a new streak. Pure.
 */
object Replay {
    /** How many verified, identical runs in a row before a request replays. */
    const val STREAK = 2

    /** The owner's words as a replay key: case, accents, punctuation and spacing don't matter. */
    fun keyOf(request: String): String = InstantGrammar.normalize(request)

    /** Every request that replays, keyed by [keyOf], from the lessons oldest first. */
    fun build(lessons: List<Lesson>): Map<String, Guess> {
        val streaks = HashMap<String, Pair<Guess, Int>>()
        for (lesson in lessons) {
            val key = keyOf(lesson.request).takeIf { it.isNotBlank() } ?: continue
            when (lesson.outcome) {
                // A broken, handed-up or cancelled run of the same words: whatever replayed is no longer trusted.
                Outcome.FAILED, Outcome.PROMOTED, Outcome.CANCELLED -> streaks.remove(key)
                Outcome.VERIFIED -> {
                    val g = lesson.decision
                    if (g.mode != "instant" || g.intent !in ModeRouter.REVERSIBLE) { streaks.remove(key); continue }
                    val before = streaks[key]
                    streaks[key] = if (before != null && before.first.agrees(g)) before.first to before.second + 1 else g to 1
                }
                // Routed elsewhere this time (Flash, the Mind, an answer): the request isn't one fixed action.
                Outcome.HANDED, Outcome.NONE -> streaks.remove(key)
            }
        }
        return streaks.filterValues { it.second >= STREAK }.mapValues { (_, v) -> v.first.copy(confidence = 1.0) }
    }
}
