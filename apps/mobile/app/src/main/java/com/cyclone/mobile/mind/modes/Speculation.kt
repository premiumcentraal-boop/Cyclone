package com.cyclone.mobile.mind.modes

import com.cyclone.mobile.mind.decide.EngineRecord
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Plan 65 R1, deciding while they speak: the decision engine's answer for words that aren't sent yet (dictation in the
 * Ask bar, a pause in Live voice), kept for a short while. When the request that is finally sent is the same words on
 * the same screen with the same bar, the decision is already there (or on its way) and the race isn't run again.
 *
 * Only the engine's race is done early. The grammar, the rules, Replay and the phone model still run on the final words,
 * and nothing acts until the request is really sent. Pure apart from the [pool] its races run on.
 */
class Speculation(
    private val pool: ExecutorService = DecisionRace.POOL,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = TTL_MS,
    private val max: Int = MAX_KEPT,
) {
    /** One early decision, and its engine record, saved only if the decision is used. */
    private class Entry(val atMs: Long, val future: CompletableFuture<Route?>, val ticket: Ticket)

    /** Holds an engine record until the decision is used; one never used is never counted. */
    class Ticket {
        private var record: EngineRecord? = null
        private var save: ((EngineRecord) -> Unit)? = null

        @Synchronized fun recorded(r: EngineRecord) { save?.invoke(r) ?: run { record = r } }
        @Synchronized fun used(s: (EngineRecord) -> Unit) { record?.let(s) ?: run { save = s }; record = null }
    }

    private val entries = LinkedHashMap<String, Entry>()

    fun keyOf(text: String, world: GrammarWorld, bar: Double): String =
        "${InstantGrammar.normalize(text)}|${world.labels.hashCode()}|${world.apps.hashCode()}|$bar"

    /**
     * Starts the engine's decision for [text] in the background, once per key while it is fresh. [decide] gets a
     * [Ticket] for its engine record. Returns whether a race was started.
     */
    fun prime(text: String, world: GrammarWorld, bar: Double, decide: (Ticket) -> Route?): Boolean {
        if (InstantGrammar.normalize(text).isBlank()) return false
        val key = keyOf(text, world, bar)
        val now = clock()
        val entry = synchronized(entries) {
            entries.values.removeAll { now - it.atMs > ttlMs }
            if (entries.containsKey(key)) return false
            while (entries.size >= max) entries.remove(entries.keys.first())?.future?.cancel(false)
            Entry(now, CompletableFuture(), Ticket()).also { entries[key] = it }
        }
        pool.execute { entry.future.complete(runCatching { decide(entry.ticket) }.getOrNull()) }
        return true
    }

    /**
     * The early decision for exactly these words, screen and bar, waiting up to [waitMs] if its race is still running;
     * null when there is none (or it failed), and the caller decides as usual. The decision is taken out: it is used once.
     */
    fun take(text: String, world: GrammarWorld, bar: Double, waitMs: Long, save: (EngineRecord) -> Unit = {}): Route? {
        val key = keyOf(text, world, bar)
        val now = clock()
        val entry = synchronized(entries) { entries.remove(key) }?.takeIf { now - it.atMs <= ttlMs } ?: return null
        val route = runCatching { entry.future.get(waitMs, TimeUnit.MILLISECONDS) }.getOrNull() ?: return null
        entry.ticket.used(save)
        return route
    }

    fun clear() = synchronized(entries) { entries.values.forEach { it.future.cancel(false) }; entries.clear() }

    companion object {
        /** Dictation can sit in the Ask bar a while before Send; a screen that changed misses anyway. */
        const val TTL_MS = 20_000L
        const val MAX_KEPT = 4
    }
}

/**
 * The engine as the router sees it, with early decisions first: a decision made while the owner spoke is used when the
 * words, the screen and the bar are the same; otherwise [inner] races as usual.
 */
class SpeculativeEngine(
    private val inner: EngineDecider,
    private val speculation: Speculation,
    private val waitMs: Long,
    private val save: (EngineRecord) -> Unit = {},
) : EngineDecider {
    override fun decide(text: String, world: GrammarWorld, bar: Double): Route? {
        val started = System.nanoTime()
        speculation.take(text, world, bar, waitMs, save)?.let { early ->
            val ms = (System.nanoTime() - started) / 1_000_000
            return early.copy(why = "decided while you spoke: ${early.why}", decideMs = ms)
        }
        return inner.decide(text, world, bar)
    }
}
