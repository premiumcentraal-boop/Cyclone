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
 * board races every usable decision provider on the fused call (images only to providers that take them); the vision
 * shadow asks JEV's Box on the side and only records. Every call is counted in the run's ledger.
 */
object AndroidCrew {
    /** The fused call's deadline: a step waits at most this long for its decision. */
    const val STEP_DEADLINE_MS = 1_800L

    fun eyes(toolbox: PhoneMindToolbox): StepEyes = StepEyes { image -> runCatching { toolbox.stepScreen(image) }.getOrNull() }

    fun board(key: String, ledger: Ledger, zdr: Boolean, clock: () -> Long = System::currentTimeMillis): StepBoard = StepBoard { request, ask ->
        val providers = DecisionProvider.entries.filter { it.usable && (!ask.withImage || it.vision) }
        if (providers.isEmpty()) return@StepBoard emptyList()
        val lanes = providers.map { Lane(it.wire, ProviderTypedBox(key, it, STEP_DEADLINE_MS, "step", zdr), it.wire) }
        val result = DecisionRace(lanes, STEP_DEADLINE_MS).run(prompt = { Prompt(request.state, request.questions) })
        result.decided.mapNotNull { arrival ->
            val answer = arrival.answer
            val provider = providers.firstOrNull { it.wire == arrival.provider }
            ledger.add(LedgerEntry(clock(), "decisions", answer?.reply?.model ?: provider?.model.orEmpty(),
                inputTokens = answer?.reply?.inputTokens ?: 0, costUsd = answer?.reply?.cost ?: 0.0, ms = arrival.ms, ok = answer != null))
            answer?.let { FusedBoard.read(it.reply, ask) }
        }
    }

    /** JEV's Box on the side, off the step's path: it records where it would tap and never taps. */
    fun visionShadow(key: String, ledger: Ledger, record: (String) -> Unit, clock: () -> Long = System::currentTimeMillis): (StepScreen, Step) -> Unit =
        { screen, step ->
            VisionShadow.request(screen, step)?.let { req ->
                DecisionRace.POOL.execute {
                    runCatching {
                        val body = DecisionsWire.body(DecisionProvider.JEV.model, req.state, req.questions)
                        val result = Decisions.call(key, DecisionProvider.JEV, body, "Cyclone OneMind", "vision_shadow", 8_000)
                        val ok = result as? DecisionsResult.Ok
                        val reply = ok?.let { DecisionsWire.parse(it.body, req.questions) }
                        ledger.add(LedgerEntry(clock(), "decisions", reply?.model ?: DecisionProvider.JEV.model,
                            inputTokens = reply?.inputTokens ?: 0, costUsd = reply?.cost ?: 0.0, ms = ok?.ms ?: 0, ok = reply != null))
                        reply?.let { record(VisionShadow.line(step, VisionShadow.read(it))) }
                    }
                }
            }
        }
}
