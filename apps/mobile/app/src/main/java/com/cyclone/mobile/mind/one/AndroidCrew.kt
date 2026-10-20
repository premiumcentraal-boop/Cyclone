package com.cyclone.mobile.mind.one

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

    /**
     * Plan 72, two that decide: every usable model gets the same fused call in one race, the screenshot only for the
     * model that reads images (OpenAI Decisions); JEV gets the text. One call per model, never a second round.
     */
    fun board(key: String, ledger: Ledger, zdr: Boolean, clock: () -> Long = System::currentTimeMillis): StepBoard = StepBoard { request, ask ->
        val providers = DecisionProvider.entries.filter { it.usable }
        if (providers.isEmpty()) return@StepBoard emptyList()
        val lanes = providers.map { Lane(it.wire, ProviderTypedBox(key, it, STEP_DEADLINE_MS, "step", zdr), it.wire) }
        val result = DecisionRace(lanes, STEP_DEADLINE_MS).run(prompt = { lane ->
            val sees = providers.firstOrNull { it.wire == lane.provider }?.vision == true
            Prompt(if (sees) request.state else FusedBoard.textOnly(request.state), request.questions)
        })
        result.decided.mapNotNull { arrival ->
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
