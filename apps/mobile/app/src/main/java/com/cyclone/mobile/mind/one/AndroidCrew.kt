package com.cyclone.mobile.mind.one

import com.cyclone.mobile.decisions.DReply
import com.cyclone.mobile.decisions.DecisionsResult
import com.cyclone.mobile.decisions.DecisionsWire
import com.cyclone.mobile.mind.PhoneMindToolbox
import com.cyclone.mobile.mind.decide.DecisionProvider
import com.cyclone.mobile.mind.decide.Decisions
import com.cyclone.mobile.mind.decide.ProviderTypedBox
import com.cyclone.mobile.mind.modes.DecisionRace
import com.cyclone.mobile.mind.modes.Lane
import com.cyclone.mobile.mind.modes.Prompt

/**
 * Plan 69 checkpoint 1: the crew's phone side. The eyes are the Mind's own toolbox (same refs as its tools); the
 * board races every usable decision provider on the fused call (images only to providers that take them). Every call is
 * counted in the run's ledger. Plan 75 (alpha 145): the unused vision shadow (JEV's Box on the side) is gone; it would
 * have sent screenshots to a text-only model outside zero-retention routing.
 */
object AndroidCrew {
    /** The fused call's deadline: a step waits at most this long for its decision. */
    const val STEP_DEADLINE_MS = 1_800L

    fun eyes(toolbox: PhoneMindToolbox): StepEyes = StepEyes { image -> runCatching { toolbox.stepScreen(image) }.getOrNull() }

    /** Plan 75 alpha 146: a clean, sure answer to an easy-to-undo move is enough to act once the grace below has passed. */
    const val EARLY_SURE = 0.8

    /** Plan 75 alpha 146: how long the other model gets after a clean answer, before the move is made. */
    const val EARLY_GRACE_MS = 300L

    /** Plan 75 alpha 146: a provider that missed the deadline this many moves in a row is not waited for in this run. */
    const val SET_ASIDE_MISSES = 3

    /**
     * Whether [reply] is one clean, sure answer that can be acted on alone: a control (not a verb), sure on its own and
     * the reversed order, no doubt from the cross-checks, and a control whose press can be taken back. Pure.
     */
    fun settled(reply: DReply?, ask: FusedBoard.Ask): Boolean {
        reply ?: return false
        val r = FusedBoard.read(reply, ask)
        val pick = r.next ?: return false
        if (FusedBoard.isVerb(pick) || r.doubt != null || r.nextSure < EARLY_SURE) return false
        val label = ask.screen.controls.firstOrNull { it.ref == pick }?.label ?: return false
        return !com.cyclone.mobile.mind.pilot.Pilot.irreversible(label)
    }

    /**
     * Plan 75 alpha 146: counts, per provider, the moves in a row it missed the deadline; from [SET_ASIDE_MISSES] on it is
     * left out of the race for the rest of the run (its answers come too late to count anyway). One per run. Not thread-safe:
     * a run asks one step at a time.
     */
    class LaneMisses {
        private val misses = HashMap<String, Int>()
        fun waited(provider: String): Boolean = (misses[provider] ?: 0) < SET_ASIDE_MISSES
        fun record(provider: String, answered: Boolean) { misses[provider] = if (answered) 0 else (misses[provider] ?: 0) + 1 }
    }

    /**
     * Plan 72, two that decide: every usable model gets the same fused call in one race, the screenshot only for the
     * model that reads images (OpenAI Decisions); JEV gets the text. One call per model, never a second round.
     *
     * Plan 75 alpha 146: for an easy-to-undo move the race ends [EARLY_GRACE_MS] after one clean, sure answer, and a
     * provider that keeps missing the deadline is set aside for the run ([LaneMisses]). A move that can't be undone keeps
     * waiting for both, as before.
     */
    fun board(key: String, ledger: Ledger, zdr: Boolean, clock: () -> Long = System::currentTimeMillis): StepBoard {
        val misses = LaneMisses()
        return StepBoard { request, ask -> race(key, ledger, zdr, clock, misses, request, ask) }
    }

    private fun race(key: String, ledger: Ledger, zdr: Boolean, clock: () -> Long, misses: LaneMisses,
                     request: FusedBoard.Request, ask: FusedBoard.Ask): List<FusedBoard.Reading> {
        val usable = DecisionProvider.entries.filter { it.usable }
        val waited = usable.filter { misses.waited(it.wire) }.ifEmpty { usable }
        if (waited.isEmpty()) return emptyList()
        val providers = waited
        val lanes = providers.map { Lane(it.wire, ProviderTypedBox(key, it, STEP_DEADLINE_MS, "step", zdr), it.wire) }
        val early = ask.step.risk == Risk.UNDOABLE && !ask.tripwire
        val result = DecisionRace(lanes, STEP_DEADLINE_MS, quorumGraceMs = EARLY_GRACE_MS).run(prompt = { lane ->
            val sees = providers.firstOrNull { it.wire == lane.provider }?.vision == true
            Prompt(if (sees) request.state else FusedBoard.textOnly(request.state), request.questions)
        }, quorum = { arrived -> early && arrived.any { settled(it.answer?.reply, ask) } })
        providers.forEach { p -> misses.record(p.wire, result.decided.any { it.provider == p.wire && it.answer != null }) }
        return result.decided.mapNotNull { arrival ->
            val answer = arrival.answer
            val provider = providers.firstOrNull { it.wire == arrival.provider }
            ledger.add(LedgerEntry(clock(), "decisions", answer?.reply?.model ?: provider?.model.orEmpty(),
                inputTokens = answer?.reply?.inputTokens ?: 0, costUsd = answer?.reply?.cost ?: 0.0, ms = arrival.ms, ok = answer != null,
                stage = "step"))
            answer?.let { FusedBoard.read(it.reply, ask) }
        }
    }

    /**
     * The DONE check (plan 69 §4.4), plan 72's one that proves: a single call, to the model that sees the screenshot
     * (OpenAI Decisions), or to JEV with the text when that model isn't usable. Counted in the ledger.
     */
    fun doneBoard(key: String, ledger: Ledger, zdr: Boolean, clock: () -> Long = System::currentTimeMillis): DoneBoard = DoneBoard { request ->
        val usable = DecisionProvider.entries.filter { it.usable }
        val providers = listOfNotNull(usable.firstOrNull { it.vision } ?: usable.firstOrNull())
        if (providers.isEmpty()) return@DoneBoard emptyList()
        val lanes = providers.map { Lane(it.wire, ProviderTypedBox(key, it, DONE_DEADLINE_MS, "done", zdr), it.wire) }
        val sees = providers.single().vision
        DecisionRace(lanes, DONE_DEADLINE_MS).run(prompt = { Prompt(if (sees) request.state else FusedBoard.textOnly(request.state), request.questions) }).decided.mapNotNull { arrival ->
            val answer = arrival.answer
            val provider = providers.firstOrNull { it.wire == arrival.provider }
            ledger.add(LedgerEntry(clock(), "decisions", answer?.reply?.model ?: provider?.model.orEmpty(),
                inputTokens = answer?.reply?.inputTokens ?: 0, costUsd = answer?.reply?.cost ?: 0.0, ms = arrival.ms, ok = answer != null,
                stage = "done"))
            answer?.reply
        }
    }

    /** The done check may take a little longer than a step: nothing waits on it but the finish. */
    const val DONE_DEADLINE_MS = 4_000L
}
