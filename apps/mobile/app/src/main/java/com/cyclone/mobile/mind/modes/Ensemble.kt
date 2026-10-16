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
 * Plan 63 (alpha.136): how Cyclone decides what the grammar, the rules and the phone model don't settle. Settings →
 * Speed → Decision engine.
 */
enum class DecisionEngine(val wire: String, val label: String) {
    /**
     * Both decision models (JEV and OpenAI Decisions) answer the same Triage board in parallel, each in two phrasings
     * (plan 65): four answers at once. An action happens only when every model that answered agrees with itself and with
     * the other; any disagreement goes one rung up. When only one model answers in time, only an easy-to-undo action may
     * go ahead on its word. The most careful engine.
     */
    CONSENSUS("consensus", "Consensus"),
    /**
     * The same four answers at once, but as soon as two of them agree, surely, on an easy-to-undo action (the camera, the
     * volume, opening an app) and none disagrees, it acts. Everything else is decided as in [CONSENSUS]. The fastest
     * engine.
     */
    SPRINT("sprint", "Sprint"),
    /** One decision model and Board 0, as in alpha.123 (with its watch and the Triage switch). */
    CLASSIC("classic", "Classic");

    companion object { fun of(wire: String?): DecisionEngine = entries.firstOrNull { it.wire == wire } ?: CONSENSUS }
}

/** One decision model in one phrasing: [provider] is the model ("jev", "openai"), [variant] the phrasing. */
data class Lane(val name: String, val box: TypedBox, val provider: String = name, val variant: Int = 0)

/** What one lane answered (null: no usable answer) and when, counted from the start of the race. */
data class Arrival(val lane: String, val answer: TypedAnswer?, val ms: Long, val provider: String = lane, val variant: Int = 0)

/** What a lane is asked. */
data class Prompt(val state: List<DPart>, val questions: Map<String, DQuestion>)

/** The answers that arrived in time to decide, and every answer once the race has fully settled. */
class RaceResult(val decided: List<Arrival>, val all: CompletableFuture<List<Arrival>>)

/**
 * Plans 63 and 65: asks every lane at the same moment and collects the answers as they arrive.
 *
 * - [run] returns when `stopEarly` says so (it sees every answer so far), or every lane has answered, or the deadline
 *   passes. Once `quorum` holds (say: every model has answered once), the race waits at most [quorumGraceMs] more for
 *   the rest, so one slow copy can't hold a decision to the full deadline. A late answer is no answer for the decision.
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
    private val quorumGraceMs: Long = QUORUM_GRACE_MS,
) {
    init { require(lanes.isNotEmpty()) { "a race needs a lane" } }

    val providers: Set<String> get() = lanes.map { it.provider }.toSet()

    fun run(
        prompt: (Lane) -> Prompt,
        stopEarly: (List<Arrival>) -> Boolean = { false },
        quorum: (List<Arrival>) -> Boolean = { false },
    ): RaceResult {
        val started = System.nanoTime()
        fun since() = (System.nanoTime() - started) / 1_000_000
        val queue = LinkedBlockingQueue<Arrival>()
        lanes.forEach { lane ->
            pool.execute {
                val answer = runCatching { val p = prompt(lane); lane.box.ask(p.state, p.questions) }.getOrNull()
                queue.put(Arrival(lane.name, answer, since(), lane.provider, lane.variant))
            }
        }
        val arrived = mutableListOf<Arrival>()
        var until = deadlineMs
        while (arrived.size < lanes.size) {
            val left = until - since()
            if (left <= 0) break
            val next = queue.poll(left, TimeUnit.MILLISECONDS) ?: break
            arrived += next
            val now = arrived.toList()
            if (runCatching { stopEarly(now) }.getOrDefault(false)) break
            if (until == deadlineMs && runCatching { quorum(now) }.getOrDefault(false)) until = minOf(deadlineMs, since() + quorumGraceMs)
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
        /** Plan 65: once every model has answered once, how long the race still waits for their second phrasing. */
        const val QUORUM_GRACE_MS = 300L
    }
}

/**
 * Plans 63 and 65: the answers of the race combined into one route. Pure.
 *
 * First each model's own answers are merged ([merge]): a model that answered the same thing in both phrasings is
 * sure; one that answered two different things is unsure, and counts as the more careful of its answers (never an
 * action). Then the models are combined ([combine]):
 * - **Agreement acts:** every model says the same Instant action (the same intent and target) → Instant.
 * - **Disagreement goes up:** different actions, or one says Instant and another doesn't → the more careful rung.
 * - **One model is enough only for an easy-to-undo action** ([ModeRouter.REVERSIBLE]); anything else goes up.
 * - **Ignoring a request needs every model;** if another wants an action, that is a disagreement and goes up.
 * - **No answer → Flash.** Unsure always goes up, never down.
 */
object Ensemble {
    data class Opinion(val lane: String, val reading: Triage.Reading, val route: Route, val ms: Long, val variant: Int = 0)

    fun opinion(arrival: Arrival, text: String, world: GrammarWorld, bar: Double): Opinion? {
        val answer = arrival.answer ?: return null
        val reading = Triage.read(answer.reply)
        // A reading with no difficulty and no action is no opinion at all (every question refused or unreadable).
        if (reading.difficulty == null && reading.capability == null && reading.flags.isEmpty()) return null
        return Opinion(arrival.provider, reading, Triage.route(reading, text, world, bar), arrival.ms, arrival.variant)
    }

    /** The action an Instant route takes, in one comparable string ("open_app:com.instagram.android"). */
    fun actionOf(route: Route): String? = route.command?.let { "${ModeRouter.keyOf(it)}:${it.target.orEmpty()}" }

    /** What a route decides, in one comparable value: its rung and, for Instant, its action. */
    fun keyOf(route: Route): Pair<Mode, String?> = route.mode to actionOf(route)

    /** Can this route act on one model's word? Only an Instant action that is easy to undo. */
    fun undoable(route: Route): Boolean =
        route.mode == Mode.INSTANT && route.command?.let { ModeRouter.keyOf(it) in ModeRouter.REVERSIBLE } == true

    /**
     * Sprint: may this one answer count towards acting early? Only an easy-to-undo Instant action, nothing risky, and the
     * model sure of both the action and how easy it is.
     */
    fun sprintMayAct(op: Opinion): Boolean =
        undoable(op.route) && !op.reading.risky && op.reading.capabilityConfidence >= SPRINT_SURE && op.reading.difficultyConfidence >= 0.6

    const val SPRINT_SURE = 0.9

    /**
     * Plan 65: Sprint acts early when **two** answers so far are sure of the same easy-to-undo action and **no** answer so
     * far says anything else. Returns that action's first opinion, or null to keep waiting.
     */
    fun sprintPair(opinions: List<Opinion>): Opinion? {
        if (opinions.size < 2) return null
        val sure = opinions.filter { sprintMayAct(it) }
        val action = sure.groupBy { actionOf(it.route) }.entries.firstOrNull { it.value.size >= 2 }?.key ?: return null
        if (opinions.any { keyOf(it.route) != (Mode.INSTANT to action) }) return null
        return sure.first { actionOf(it.route) == action }
    }

    /** One model's answers (its phrasings) as one opinion: sure when they agree, the careful one when they don't. */
    fun merge(copies: List<Opinion>): Opinion? {
        if (copies.size <= 1) return copies.firstOrNull()
        if (copies.map { keyOf(it.route) }.distinct().size == 1) return copies.minBy { it.reading.capabilityConfidence }
        val lane = copies.first().lane
        val nonIgnore = copies.filter { it.route.mode != Mode.IGNORE }
        val careful = nonIgnore.maxBy { rank(it.route.mode) }
        val route = if (careful.route.mode == Mode.INSTANT) Route(Mode.FLASH, "$lane answered two different ways")
            else careful.route.copy(why = "$lane answered two different ways; the more careful: ${careful.route.why}")
        return careful.copy(route = route, ms = copies.maxOf { it.ms })
    }

    /** Every model's merged opinion, one per model. */
    fun perModel(opinions: List<Opinion>): List<Opinion> = opinions.groupBy { it.lane }.values.mapNotNull { merge(it) }

    /** One opinion per model, combined. */
    fun combine(opinions: List<Opinion>): Route {
        if (opinions.isEmpty()) return Route(Mode.FLASH, "no decision model answered in time")
        if (opinions.size == 1) return single(opinions.first())
        val routes = opinions.map { it.route }
        return when {
            routes.all { it.mode == Mode.INSTANT } ->
                if (routes.map { actionOf(it) }.distinct().size == 1) routes.first().copy(why = "the decision models agree: ${routes.first().why}")
                else Route(Mode.FLASH, "the decision models disagree on the action")
            routes.all { it.mode == Mode.IGNORE } -> routes.first().copy(why = "every decision model: not meant for Cyclone")
            routes.any { it.mode == Mode.IGNORE } -> {
                // One says it isn't meant for Cyclone: a disagreement. An action needs every model, so it goes up;
                // otherwise the other models' (more careful) route stands.
                val others = routes.filter { it.mode != Mode.IGNORE }
                if (others.any { it.mode == Mode.INSTANT }) Route(Mode.FLASH, "the decision models disagree on whether this is meant for Cyclone")
                else others.maxBy { rank(it.mode) }.let { it.copy(why = "another model thought it wasn't meant for Cyclone; ${it.why}") }
            }
            else -> {
                // Different rungs: the most careful one. Instant against anything else is never Instant.
                val careful = routes.maxBy { rank(it.mode) }
                careful.copy(why = "the more careful of the decision models: ${careful.why}")
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

    /** The route's decision in the lessons' shape, with the lowest of the deciding answers' confidences. */
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
 * Plans 63 and 65: [DecisionEngine.CONSENSUS] and [DecisionEngine.SPRINT] over a [DecisionRace]. Every decision ends in
 * an [EngineRecord] for the numbers (who answered, how fast, whether they agreed), given to [record] once the race has
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
        val models = race.providers
        val result = race.run(
            prompt = { lane -> Prompt(Triage.state(text, world, lane.variant), Triage.questions(world, text, lane.variant)) },
            stopEarly = { arrived ->
                engine == DecisionEngine.SPRINT &&
                    Ensemble.sprintPair(arrived.mapNotNull { Ensemble.opinion(it, text, world, bar) })?.also { early = it } != null
            },
            // Every model has answered once: its second phrasing gets a short grace, not the full deadline.
            quorum = { arrived -> arrived.map { it.provider }.toSet().containsAll(models) },
        )
        val ms = (System.nanoTime() - started) / 1_000_000
        val opinions = result.decided.mapNotNull { Ensemble.opinion(it, text, world, bar) }
        val route = early?.let { it.route.copy(why = "two answers were sure of it first, none disagreed, and it is easy to undo: ${it.route.why}") }
            ?: Ensemble.combine(Ensemble.perModel(opinions))
        result.all.thenAccept { all -> runCatching { record(recordOf(atMs, all, route, early?.lane, text, world, bar)) } }
        return route.copy(by = Decider.DECISIONS, decision = Ensemble.guessOf(route, opinions), decideMs = ms)
    }

    private fun recordOf(atMs: Long, all: List<Arrival>, chosen: Route, early: String?, text: String, world: GrammarWorld, bar: Double): EngineRecord {
        val lanes = all.map { a ->
            val op = Ensemble.opinion(a, text, world, bar)
            LaneRecord(a.provider, a.ms, answered = op != null, mode = op?.route?.mode?.name?.lowercase(),
                action = op?.route?.let { Ensemble.actionOf(it) }, copy = a.variant)
        }
        val answered = lanes.filter { it.answered }
        val agreed = if (answered.size < 2) null else answered.map { it.mode to it.action }.distinct().size == 1
        return EngineRecord(atMs, engine.wire, lanes, chosen.mode.name.lowercase(), agreed, early)
    }
}
