package com.cyclone.mobile.mind.modes

import com.cyclone.mobile.decisions.DPart
import com.cyclone.mobile.decisions.DQuestion
import com.cyclone.mobile.mind.decide.Decider
import com.cyclone.mobile.mind.decide.EngineRecord
import com.cyclone.mobile.mind.decide.Guess
import com.cyclone.mobile.mind.decide.LaneRecord
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plan 63 (alpha.135): how Cyclone decides what the grammar, the rules and the phone model don't settle. Settings →
 * Speed → Decision engine.
 */
enum class DecisionEngine(val wire: String, val label: String) {
    /**
     * Both decision models (JEV and OpenAI Decisions) answer the same Triage board in parallel. An action happens only
     * when both agree on it; any disagreement goes one rung up. When only one answers in time, only an easy-to-undo
     * action may go ahead on its word. The most careful engine.
     */
    CONSENSUS("consensus", "Consensus"),
    /**
     * Both answer in parallel too, but the first sure answer for an easy-to-undo action acts at once (the camera, the
     * volume, opening an app). Everything else waits for both and is decided as in [CONSENSUS]. The fastest engine.
     */
    SPRINT("sprint", "Sprint"),
    /** One decision model and Board 0, as in alpha.123 (with its watch and the Triage switch). */
    CLASSIC("classic", "Classic");

    companion object { fun of(wire: String?): DecisionEngine = entries.firstOrNull { it.wire == wire } ?: CONSENSUS }
}

/** One decision model in the race. */
data class Lane(val name: String, val box: TypedBox)

/** What one lane answered (null: no usable answer) and when, counted from the start of the race. */
data class Arrival(val lane: String, val answer: TypedAnswer?, val ms: Long)

/** The answers that arrived in time to decide, and every answer once the race has fully settled. */
class RaceResult(val decided: List<Arrival>, val all: CompletableFuture<List<Arrival>>)

/**
 * Plan 63 §6.2: asks every lane the same questions at the same moment and collects the answers as they arrive.
 *
 * - [run] returns as soon as `stopEarly` says so, or every lane has answered, or the deadline passes. A late answer is
 *   no answer for the decision.
 * - [RaceResult.all] completes off the caller's thread with **every** arrival up to a grace period after the deadline,
 *   so the numbers also see the answers that came too late to count.
 *
 * Pure Kotlin and threads; the lanes do the network calls.
 */
class DecisionRace(
    private val lanes: List<Lane>,
    private val deadlineMs: Long,
    private val pool: ExecutorService = POOL,
    private val graceMs: Long = deadlineMs,
) {
    init { require(lanes.isNotEmpty()) { "a race needs a lane" } }

    fun run(
        state: List<DPart>,
        questions: Map<String, DQuestion>,
        stopEarly: (Arrival) -> Boolean = { false },
    ): RaceResult {
        val started = System.nanoTime()
        fun since() = (System.nanoTime() - started) / 1_000_000
        val queue = LinkedBlockingQueue<Arrival>()
        lanes.forEach { lane ->
            pool.execute {
                val answer = runCatching { lane.box.ask(state, questions) }.getOrNull()
                queue.put(Arrival(lane.name, answer, since()))
            }
        }
        val arrived = mutableListOf<Arrival>()
        while (arrived.size < lanes.size) {
            val left = deadlineMs - since()
            if (left <= 0) break
            val next = queue.poll(left, TimeUnit.MILLISECONDS) ?: break
            arrived += next
            if (runCatching { stopEarly(next) }.getOrDefault(false)) break
        }
        val decided = arrived.toList()
        val all = CompletableFuture<List<Arrival>>()
        if (decided.size == lanes.size) all.complete(decided)
        else pool.execute {
            // The rest are collected off this thread, so the numbers see them without making anyone wait.
            val every = decided.toMutableList()
            while (every.size < lanes.size) {
                val left = deadlineMs + graceMs - since()
                if (left <= 0) break
                every += queue.poll(left, TimeUnit.MILLISECONDS) ?: break
            }
            all.complete(every)
        }
        return RaceResult(decided, all)
    }

    companion object {
        /** Daemon threads, created as needed: a race never waits for a free thread. */
        val POOL: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "cyclone-decision-race").apply { isDaemon = true } }
    }
}

/**
 * Plan 63 §6.3: two decision models' Triage answers combined into one route. Pure.
 *
 * The rules, in one sentence each:
 * - **Agreement acts:** both say the same Instant action (the same intent and target) → Instant.
 * - **Disagreement goes up:** different actions, or one says Instant and the other doesn't → the more careful rung.
 * - **One answer is enough only for an easy-to-undo action** ([ModeRouter.REVERSIBLE]); anything else goes up.
 * - **Ignoring a request needs both;** if the other model wants an action, that is a disagreement and goes up.
 * - **No answer → Flash.** Unsure always goes up, never down.
 */
object Ensemble {
    data class Opinion(val lane: String, val reading: Triage.Reading, val route: Route, val ms: Long)

    fun opinion(arrival: Arrival, text: String, world: GrammarWorld, bar: Double): Opinion? {
        val answer = arrival.answer ?: return null
        val reading = Triage.read(answer.reply)
        // A reading with no difficulty and no action is no opinion at all (every question refused or unreadable).
        if (reading.difficulty == null && reading.capability == null && reading.flags.isEmpty()) return null
        return Opinion(arrival.lane, reading, Triage.route(reading, text, world, bar), arrival.ms)
    }

    /** The action an Instant route takes, in one comparable string ("open_app:com.instagram.android"). */
    fun actionOf(route: Route): String? = route.command?.let { "${ModeRouter.keyOf(it)}:${it.target.orEmpty()}" }

    /** Can this route act on one model's word? Only an Instant action that is easy to undo. */
    fun undoable(route: Route): Boolean =
        route.mode == Mode.INSTANT && route.command?.let { ModeRouter.keyOf(it) in ModeRouter.REVERSIBLE } == true

    /**
     * [Sprint][DecisionEngine.SPRINT]: may this one answer act before the other arrives? Only an easy-to-undo Instant
     * action, nothing risky, and the model sure of both the action and how easy it is.
     */
    fun sprintMayAct(op: Opinion): Boolean =
        undoable(op.route) && !op.reading.risky && op.reading.capabilityConfidence >= SPRINT_SURE && op.reading.difficultyConfidence >= 0.6

    const val SPRINT_SURE = 0.9

    fun combine(opinions: List<Opinion>): Route {
        if (opinions.isEmpty()) return Route(Mode.FLASH, "no decision model answered in time")
        if (opinions.size == 1) return single(opinions.first())
        val (a, b) = opinions
        val ra = a.route
        val rb = b.route
        return when {
            ra.mode == Mode.INSTANT && rb.mode == Mode.INSTANT ->
                if (actionOf(ra) == actionOf(rb)) ra.copy(why = "both decision models agree: ${ra.why}")
                else Route(Mode.FLASH, "the decision models disagree on the action")
            ra.mode == Mode.IGNORE && rb.mode == Mode.IGNORE -> ra.copy(why = "both decision models: not meant for Cyclone")
            // One says it isn't meant for Cyclone: a disagreement. An action needs both, so it goes up; otherwise the
            // other model's (more careful) route stands.
            ra.mode == Mode.IGNORE || rb.mode == Mode.IGNORE -> {
                val other = if (ra.mode == Mode.IGNORE) rb else ra
                if (other.mode == Mode.INSTANT) Route(Mode.FLASH, "the decision models disagree on whether this is meant for Cyclone")
                else other.copy(why = "the other model thought it wasn't meant for Cyclone; ${other.why}")
            }
            else -> {
                // Different rungs: the more careful one. Instant against anything else is never Instant.
                val careful = listOf(ra, rb).maxBy { rank(it.mode) }
                careful.copy(why = "the more careful of the two decision models: ${careful.why}")
            }
        }
    }

    private fun single(op: Opinion, note: String = "only ${op.lane} answered in time"): Route = when {
        op.route.mode == Mode.INSTANT && undoable(op.route) -> op.route.copy(why = "$note; an easy-to-undo action: ${op.route.why}")
        op.route.mode == Mode.INSTANT -> Route(Mode.FLASH, "$note, and this action needs both to agree")
        op.route.mode == Mode.IGNORE -> Route(Mode.FLASH, "$note, and ignoring a request needs both to agree")
        else -> op.route.copy(why = "$note: ${op.route.why}")
    }

    /** Instant below Flash below the Mind. Ignore and Answer are never chosen by rank. */
    fun rank(mode: Mode): Int = when (mode) { Mode.INSTANT -> 0; Mode.FLASH -> 1; Mode.MIND -> 2; else -> -1 }

    /** The route's decision in the lessons' shape, with the lower of the deciding models' confidences. */
    fun guessOf(route: Route, opinions: List<Opinion>): Guess {
        val sure = opinions.minOfOrNull { if (route.mode == Mode.INSTANT) minOf(it.reading.capabilityConfidence, it.reading.difficultyConfidence) else it.reading.difficultyConfidence } ?: 0.0
        return Guess(route.mode.name.lowercase(), if (route.mode == Mode.INSTANT) route.command?.let { ModeRouter.keyOf(it) } ?: "none" else "none",
            if (route.mode == Mode.INSTANT) route.command?.targetLabel else null, sure)
    }
}

/** The router's view of an engine: one decision for a request, or null to fall back to Board 0. */
fun interface EngineDecider {
    fun decide(text: String, world: GrammarWorld, bar: Double): Route?
}

/**
 * Plan 63: [DecisionEngine.CONSENSUS] and [DecisionEngine.SPRINT] over a [DecisionRace]. Every decision ends in an
 * [EngineRecord] for the numbers (who answered, how fast, whether they agreed), given to [record] once the race has
 * settled. Pure.
 */
class RaceEngine(
    private val engine: DecisionEngine,
    private val race: DecisionRace,
    private val record: (EngineRecord) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : EngineDecider {
    init { require(engine != DecisionEngine.CLASSIC) { "Classic has no race" } }

    override fun decide(text: String, world: GrammarWorld, bar: Double): Route {
        val atMs = clock()
        val started = System.nanoTime()
        var early: Ensemble.Opinion? = null
        val result = race.run(Triage.state(text, world), Triage.questions(world, text), stopEarly = { arrival ->
            if (engine != DecisionEngine.SPRINT) false
            else Ensemble.opinion(arrival, text, world, bar)?.takeIf { Ensemble.sprintMayAct(it) }?.also { early = it } != null
        })
        val ms = (System.nanoTime() - started) / 1_000_000
        val opinions = result.decided.mapNotNull { Ensemble.opinion(it, text, world, bar) }
        val route = early?.let { it.route.copy(why = "${it.lane} was sure first, and it is easy to undo: ${it.route.why}") }
            ?: Ensemble.combine(opinions)
        result.all.thenAccept { all -> runCatching { record(recordOf(atMs, all, route, early?.lane, text, world, bar)) } }
        return route.copy(by = Decider.DECISIONS, decision = Ensemble.guessOf(route, opinions), decideMs = ms)
    }

    private fun recordOf(atMs: Long, all: List<Arrival>, chosen: Route, early: String?, text: String, world: GrammarWorld, bar: Double): EngineRecord {
        val opinions = all.associate { it.lane to Ensemble.opinion(it, text, world, bar) }
        val lanes = all.map { a ->
            val op = opinions[a.lane]
            LaneRecord(a.lane, a.ms, answered = op != null, mode = op?.route?.mode?.name?.lowercase(), action = op?.route?.let { Ensemble.actionOf(it) })
        }
        val answered = opinions.values.filterNotNull()
        val agreed = if (answered.size < 2) null else answered.map { it.route.mode to Ensemble.actionOf(it.route) }.distinct().size == 1
        return EngineRecord(atMs, engine.wire, lanes, chosen.mode.name.lowercase(), agreed, early)
    }
}
