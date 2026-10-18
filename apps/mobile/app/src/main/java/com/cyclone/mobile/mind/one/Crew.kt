package com.cyclone.mobile.mind.one

import com.cyclone.mobile.decisions.DReply
import com.cyclone.mobile.mind.MindToolCall
import com.cyclone.mobile.mind.MindToolbox
import org.json.JSONObject

/**
 * Plan 69 §4: everything that isn't a mind, as the conductor sees it. It runs one step at a time, acts at a barrier
 * once the conductor allows it, checks a finish, and takes back easy-to-undo moves when a glance disagrees.
 */
interface Crew {
    fun run(step: Step, score: Score): StepOutcome
    fun actAt(barrier: StepOutcome.AtBarrier, score: Score): StepOutcome
    fun look(image: Boolean): StepScreen?
    /** Code's check of a done predicate on the screen now. */
    fun check(expect: Expect, score: Score): Verdict
    /** The DONE question: the careful yes across providers, or null when nobody answered. */
    fun done(score: Score, screen: StepScreen?): Double?
    /** Takes back [moves] easy-to-undo moves (Back). */
    fun undo(moves: Int)
    /** What the crew recorded since the last call (kind, line); "fact:<name>" kinds carry a fact's value. */
    fun drain(): List<Pair<String, String>> = emptyList()
    /** Alpha 144: the [Recovery] ladder before a check-in; what it did, or null when nothing helped. */
    fun recover(): String? = null
}

/** The DONE call: one Decisions request answered by every usable provider. */
fun interface DoneBoard { fun ask(request: FusedBoard.Request): List<DReply> }

/** The crew built from the step engine of checkpoint 1. Pure apart from what eyes, boards and hands do. */
class EngineCrew(
    private val eyes: StepEyes,
    board: StepBoard,
    private val doneBoard: DoneBoard,
    private val hands: MindToolbox,
    breaker: Breaker,
    private val settings: SettingReader? = null,
    shadow: (StepScreen, Step) -> Unit = { _, _ -> },
    memory: MoveMemory? = null,
) : Crew {
    private val recorded = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()
    private val engine = StepEngine(eyes, board, hands, breaker, settings, record = { kind, line -> recorded += kind to line }, shadow = shadow,
        memory = memory)
    private var undoCalls = 0

    override fun run(step: Step, score: Score): StepOutcome = engine.run(step, score)
    override fun actAt(barrier: StepOutcome.AtBarrier, score: Score): StepOutcome = engine.actAt(barrier, score)
    override fun look(image: Boolean): StepScreen? = runCatching { eyes.look(image) }.getOrNull()

    override fun check(expect: Expect, score: Score): Verdict =
        look(false)?.let { ExpectCheck.check(expect, it, null, score.facts, settings) } ?: Verdict.UNKNOWN

    override fun done(score: Score, screen: StepScreen?): Double? {
        val finished = score.plan?.milestones?.filter { it.state == MilestoneState.DONE }?.map { it.title }.orEmpty()
        // The proving call sees the page: a fresh look with the screenshot (never on a sensitive screen).
        val seen = runCatching { eyes.look(true) }.getOrNull()?.takeIf { !it.sensitive } ?: screen?.takeIf { !it.sensitive }
        val request = FusedBoard.doneRequest(score.goal.text, finished, score.facts, seen)
        return FusedBoard.doneRead(runCatching { doneBoard.ask(request) }.getOrDefault(emptyList()))
    }

    override fun undo(moves: Int) = repeat(moves.coerceIn(0, MAX_UNDO)) {
        runCatching { hands.execute(MindToolCall("undo-${++undoCalls}", "back", "{}"), JSONObject()) }
        recorded += "undo" to "Pressed Back to take back a move"
    }

    override fun drain(): List<Pair<String, String>> = buildList { while (true) add(recorded.poll() ?: break) }

    override fun recover(): String? {
        val done = Recovery({ look(false) }, { name, args -> hands.execute(MindToolCall("recover-${++undoCalls}", name, args.toString()), args) }).attempt()
        recorded += "recover" to (done?.let { "Got unstuck: $it" } ?: "Tried to get unstuck (close, Back, reopen): nothing changed")
        return done
    }

    companion object { const val MAX_UNDO = 6 }
}
