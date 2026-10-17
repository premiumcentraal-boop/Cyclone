package com.cyclone.mobile.ui.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Plan 70: the overlay is as big as the work. Pure rules; the chrome and the cards read them.
 *
 * - **Instant** whispers: the Ask bar becomes a small capsule (Cy working, and Stop) and Cyclone just does it. No step
 *   words, no card. A short happy hop says it worked. Words appear only when there is something to read: an answer, a
 *   call's cancel window, or an action that didn't happen.
 * - **Flash** folds: it runs as the island, one line of what it does now. The card opens when the owner opens it or when
 *   the run grows into the Mind.
 * - **The Mind** opens its card: the steps, the progress line, a plan diversion as one quiet line.
 * - A run only grows on its own (whisper → island → card); only the owner folds it back.
 */
object RunPresentation {
    enum class Size { WHISPER, ISLAND, CARD }

    enum class Kind { INSTANT, FLASH, MIND }

    /** The chrome session of an Instant run (CycloneModes); missions have their own ids. */
    const val INSTANT_PREFIX = "instant-"

    fun isInstant(sessionId: String): Boolean = sessionId.startsWith(INSTANT_PREFIX)

    /** Where a run starts. [loud]: it has words the owner must see now (a call's cancel window), so the island. */
    fun start(kind: Kind, loud: Boolean = false): Size = when (kind) {
        Kind.INSTANT -> if (loud) Size.ISLAND else Size.WHISPER
        Kind.FLASH -> Size.ISLAND
        Kind.MIND -> Size.CARD
    }

    /** A run only grows: a later, smaller size never shrinks it (the owner can still fold it). */
    fun grow(from: Size, to: Size): Size = if (to.ordinal > from.ordinal) to else from

    /** How an Instant run ended, for the whisper's last moment: done, or what didn't happen, in a few words. */
    data class Ending(val seq: Long, val done: Boolean, val words: String?)

    /** How long the ending stays: a hop for a plain done, longer when there are words to read (capped). */
    fun endMs(ending: Ending): Long {
        val words = ending.words?.length ?: 0
        return if (words == 0) END_MS else (END_MS + words * 45L).coerceAtMost(END_WORDS_MAX_MS)
    }

    const val END_MS = 1_400L
    const val END_WORDS_MAX_MS = 6_000L

    private val _ending = MutableStateFlow<Ending?>(null)

    /** The latest whisper ending; the bar shows it for [endMs], then folds back. */
    val ending: StateFlow<Ending?> = _ending

    private var seq = 0L

    fun ended(done: Boolean, words: String?) {
        synchronized(this) { seq++; _ending.value = Ending(seq, done, words?.trim()?.takeIf { it.isNotEmpty() }?.take(160)) }
    }

    internal fun resetForTests() {
        _ending.value = null
        seq = 0L
    }
}
