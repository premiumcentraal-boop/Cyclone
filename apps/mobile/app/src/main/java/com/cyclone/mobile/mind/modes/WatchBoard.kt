package com.cyclone.mobile.mind.modes

import com.cyclone.mobile.decisions.DQuestion
import com.cyclone.mobile.decisions.DReply
import com.cyclone.mobile.mind.decide.Decider
import com.cyclone.mobile.mind.decide.TriageRecord
import com.cyclone.mobile.mind.decide.WatchRecord

/**
 * Plan 58 (alpha.123): what the watch asks about one routed request, in one call. Beside a provider that is not deciding
 * it asks Board 0 (so the two providers can be compared) and, while Triage is in shadow, the Triage questions (they
 * share Board 0's `target`). Pure.
 */
object WatchBoard {
    /**
     * The questions, or empty when there is nothing to watch. [comparing] is true when the watching provider is not the
     * one deciding; [triage] is the Triage switch.
     */
    fun questions(text: String, world: GrammarWorld, comparing: Boolean, routedByBoard0: Boolean, triage: StageSwitch): Map<String, DQuestion> {
        val out = linkedMapOf<String, DQuestion>()
        if (comparing && routedByBoard0) ModeRouter.board0(text, world).questions.forEach { out[it.id] = it.typed() }
        if (triage == StageSwitch.SHADOW || (comparing && triage == StageSwitch.ON)) out.putAll(Triage.questions(world, text))
        return out
    }

    fun record(atMs: Long, provider: String, ms: Long, route: Route, reply: DReply?, failure: String?, text: String,
               world: GrammarWorld, bar: Double, asked: Map<String, DQuestion>): WatchRecord {
        val acted = route.decision ?: ModeRouter.guessOf(route.mode, route.command)
        if (reply == null) return WatchRecord(atMs, provider, failure ?: "skipped", ms, route.by, acted)
        val board0 = if ("route" in asked) BoxWire.fromDecisions(reply)?.let { r ->
            if (r.choice("route") == null) null else ModeRouter.decisionOf(r)
        } else null
        val triage = if ("difficulty" in asked) Triage.read(reply).let { reading ->
            val r = Triage.route(reading, text, world, bar)
            TriageRecord(r.mode.name.lowercase(), reading.difficulty, reading.capability, reading.capabilityConfidence,
                reading.raised(), reading.steps, reading.refused.size)
        } else null
        return WatchRecord(atMs, provider, "ok", ms, route.by, acted, board0, triage)
    }
}
