package com.cyclone.mobile.mind.one

import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 69 §3: the Score, one run's typed state and the one shared truth between the minds and the crew. Pure data: it
 * is written by code from what happened, saved with the mission journal and read by briefings, the card and Glass.
 *
 * The separation rule lives here: the crew may only mark steps and add facts and events; only a mind changes the
 * plan ([replan]); only the owner changes the goal ([steer]).
 */
enum class Difficulty { INSTANT, FLASH, MIND }

/** How easy a step's action is to take back. Code decides it; a plan may only raise it. */
enum class Risk {
    /** Back, scroll, open, read: undone with Back or by doing nothing. */
    UNDOABLE,
    /** Sends, pays, deletes, grants, posts: the code gate and the owner decide. */
    GATED,
    /** Cannot be taken back even with approval in hand (a final submit, a purchase). */
    IRREVERSIBLE;

    fun atLeast(other: Risk): Risk = if (other.ordinal > ordinal) other else this
}

enum class StepState { TODO, RUNNING, DONE, FAILED, BLOCKED, SKIPPED }
enum class MilestoneState { TODO, RUNNING, DONE, FAILED }

/** A condition written into a Flash plan ("if dark mode is already on → skip"), settled by a yes/no in the step's call. */
data class Branch(val condition: String, val then: BranchAction) {
    enum class BranchAction { SKIP, DONE, NEXT_MILESTONE }
}

/**
 * One step of a plan. [action] names what to do (a tool such as "tap", "type_text", "open_app", or a typed tool step);
 * [target] is the hint for what to act on ("the search field"); [args] are fixed arguments; [expect] is what must be
 * true afterwards; [slots] name facts the step needs ("amount" → the fact found earlier).
 */
data class Step(
    val id: String,
    val action: String,
    val target: String? = null,
    val args: Map<String, String> = emptyMap(),
    val expect: Expect? = null,
    val risk: Risk = Risk.UNDOABLE,
    val slots: Map<String, String> = emptyMap(),
    val branches: List<Branch> = emptyList(),
    val state: StepState = StepState.TODO,
    val note: String? = null,
)

data class Milestone(
    val id: String,
    val title: String,
    val steps: List<Step>,
    /** The mind asked to look in when this milestone ends (plan 69 §5.3). */
    val review: Boolean = false,
    val state: MilestoneState = MilestoneState.TODO,
)

data class Plan(val version: Int, val milestones: List<Milestone>, val done: Expect? = null) {
    val steps: List<Step> get() = milestones.flatMap { it.steps }
    fun current(): Pair<Milestone, Step>? = milestones.firstNotNullOfOrNull { m ->
        m.steps.firstOrNull { it.state == StepState.TODO || it.state == StepState.RUNNING }?.let { m to it }
    }
    fun remainingMilestones(): Int = milestones.count { it.state == MilestoneState.TODO || it.state == MilestoneState.RUNNING }
}

data class GoalVersion(val version: Int, val text: String, val atMs: Long, val byOwner: Boolean)

/** A value the run found, kept exactly with where it came from. Numbers, names and amounts live only here. */
data class Fact(val name: String, val value: String, val source: String, val atMs: Long)

/** One thing that happened, as one line. [kind] is a stable machine word; [line] is the human line for briefings. */
data class Event(val seq: Long, val atMs: Long, val kind: String, val line: String, val stepId: String? = null)

/** What a mind asked the crew to watch for (plan 69 §5.2). [question] is yes/no; [factOver] compares a fact in code. */
data class StandingOrder(
    val id: String,
    val text: String,
    val kind: Kind,
    val question: String? = null,
    val fact: String? = null,
    val over: Double? = null,
) {
    enum class Kind { WAKE_WHEN, WAKE_IF_OVER, DISMISS_POPUPS, QUIET_UNTIL }
}

/** A point no work may cross while a review is open. */
data class Barrier(val stepId: String, val reason: String)

data class Score(
    val runId: String,
    val difficulty: Difficulty,
    val goals: List<GoalVersion>,
    val plan: Plan? = null,
    val facts: List<Fact> = emptyList(),
    val events: List<Event> = emptyList(),
    val orders: List<StandingOrder> = emptyList(),
    val barriers: List<Barrier> = emptyList(),
) {
    val goal: GoalVersion get() = goals.last()
    fun fact(name: String): Fact? = facts.lastOrNull { it.name == name }

    // ---- the crew's writes ------------------------------------------------------------------------------------------

    fun event(kind: String, line: String, atMs: Long, stepId: String? = null): Score {
        val next = Event((events.lastOrNull()?.seq ?: 0) + 1, atMs, kind, line.take(MAX_LINE), stepId)
        return copy(events = (events + next).takeLast(MAX_EVENTS))
    }

    fun fact(name: String, value: String, source: String, atMs: Long): Score =
        copy(facts = facts + Fact(name, value.take(MAX_FACT), source, atMs))

    fun mark(stepId: String, state: StepState, note: String? = null): Score {
        val p = plan ?: return this
        val milestones = p.milestones.map { m ->
            val steps = m.steps.map { s -> if (s.id == stepId) s.copy(state = state, note = note ?: s.note) else s }
            val mState = when {
                steps.all { it.state == StepState.DONE || it.state == StepState.SKIPPED } -> MilestoneState.DONE
                steps.any { it.state == StepState.FAILED } -> MilestoneState.FAILED
                steps.any { it.state != StepState.TODO } -> MilestoneState.RUNNING
                // Every step back to do (a glance's undo, a retry): the milestone is to do again, not done.
                else -> MilestoneState.TODO
            }
            m.copy(steps = steps, state = mState)
        }
        return copy(plan = p.copy(milestones = milestones))
    }

    // ---- a mind's writes ------------------------------------------------------------------------------------------

    /** A new plan from a mind. Milestones already done and verified are kept as they were. */
    fun replan(next: Plan): Score {
        val done = plan?.milestones?.filter { it.state == MilestoneState.DONE }.orEmpty()
        val doneIds = done.map { it.id }.toSet()
        return copy(plan = next.copy(version = (plan?.version ?: 0) + 1, milestones = done + next.milestones.filter { it.id !in doneIds }))
    }

    fun orders(next: List<StandingOrder>): Score = copy(orders = next.take(MAX_ORDERS))
    fun barriers(next: List<Barrier>): Score = copy(barriers = next)

    // ---- the owner's write ----------------------------------------------------------------------------------------

    fun steer(text: String, atMs: Long): Score = copy(goals = goals + GoalVersion(goal.version + 1, text, atMs, byOwner = true))

    companion object {
        const val MAX_EVENTS = 2_000
        const val MAX_LINE = 240
        const val MAX_FACT = 500
        const val MAX_ORDERS = 8

        fun start(runId: String, difficulty: Difficulty, goal: String, atMs: Long) =
            Score(runId, difficulty, listOf(GoalVersion(1, goal, atMs, byOwner = true)))
    }
}

/** The Score as JSON for the mission journal, and back. Unknown or broken parts read as absent, never as a crash. */
object ScoreCodec {
    fun encode(s: Score): JSONObject = JSONObject()
        .put("runId", s.runId).put("difficulty", s.difficulty.name)
        .put("goals", JSONArray(s.goals.map { JSONObject().put("v", it.version).put("text", it.text).put("at", it.atMs).put("owner", it.byOwner) }))
        .put("plan", s.plan?.let(::plan) ?: JSONObject.NULL)
        .put("facts", JSONArray(s.facts.map { JSONObject().put("name", it.name).put("value", it.value).put("source", it.source).put("at", it.atMs) }))
        .put("events", JSONArray(s.events.map { e ->
            JSONObject().put("seq", e.seq).put("at", e.atMs).put("kind", e.kind).put("line", e.line).also { o -> e.stepId?.let { o.put("step", it) } }
        }))
        .put("orders", JSONArray(s.orders.map { o ->
            JSONObject().put("id", o.id).put("text", o.text).put("kind", o.kind.name).also { j ->
                o.question?.let { j.put("question", it) }; o.fact?.let { j.put("fact", it) }; o.over?.let { j.put("over", it) }
            }
        }))
        .put("barriers", JSONArray(s.barriers.map { JSONObject().put("step", it.stepId).put("reason", it.reason) }))

    fun decode(j: JSONObject): Score? = runCatching {
        Score(
            runId = j.getString("runId"),
            difficulty = Difficulty.valueOf(j.getString("difficulty")),
            goals = j.getJSONArray("goals").objects().map { GoalVersion(it.getInt("v"), it.getString("text"), it.getLong("at"), it.optBoolean("owner", true)) }
                .ifEmpty { return null },
            plan = j.optJSONObject("plan")?.let(::plan),
            facts = j.optJSONArray("facts")?.objects()?.map { Fact(it.getString("name"), it.getString("value"), it.optString("source"), it.optLong("at")) }.orEmpty(),
            events = j.optJSONArray("events")?.objects()?.map {
                Event(it.getLong("seq"), it.getLong("at"), it.getString("kind"), it.getString("line"), it.optString("step").takeIf { s -> s.isNotBlank() })
            }.orEmpty(),
            orders = j.optJSONArray("orders")?.objects()?.mapNotNull { o ->
                runCatching {
                    StandingOrder(o.getString("id"), o.getString("text"), StandingOrder.Kind.valueOf(o.getString("kind")),
                        o.optString("question").takeIf { it.isNotBlank() }, o.optString("fact").takeIf { it.isNotBlank() },
                        o.optDouble("over", Double.NaN).takeIf { !it.isNaN() })
                }.getOrNull()
            }.orEmpty(),
            barriers = j.optJSONArray("barriers")?.objects()?.map { Barrier(it.getString("step"), it.optString("reason")) }.orEmpty(),
        )
    }.getOrNull()

    fun plan(p: Plan): JSONObject = JSONObject().put("version", p.version)
        .put("done", p.done?.let { ExpectCodec.encode(it) } ?: JSONObject.NULL)
        .put("milestones", JSONArray(p.milestones.map { m ->
            JSONObject().put("id", m.id).put("title", m.title).put("review", m.review).put("state", m.state.name)
                .put("steps", JSONArray(m.steps.map(::step)))
        }))

    private fun step(s: Step): JSONObject = JSONObject().put("id", s.id).put("action", s.action).put("risk", s.risk.name).put("state", s.state.name)
        .also { j ->
            s.target?.let { j.put("target", it) }
            if (s.args.isNotEmpty()) j.put("args", JSONObject(s.args))
            if (s.slots.isNotEmpty()) j.put("slots", JSONObject(s.slots))
            s.expect?.let { j.put("expect", ExpectCodec.encode(it)) }
            if (s.branches.isNotEmpty()) j.put("branches", JSONArray(s.branches.map { b -> JSONObject().put("if", b.condition).put("then", b.then.name) }))
            s.note?.let { j.put("note", it) }
        }

    fun plan(j: JSONObject): Plan? = runCatching {
        Plan(j.optInt("version", 1), j.getJSONArray("milestones").objects().map { m ->
            Milestone(m.getString("id"), m.getString("title"), m.getJSONArray("steps").objects().map(::step),
                m.optBoolean("review"), runCatching { MilestoneState.valueOf(m.optString("state")) }.getOrDefault(MilestoneState.TODO))
        }, j.optJSONObject("done")?.let { ExpectCodec.decode(it) })
    }.getOrNull()

    fun step(j: JSONObject): Step = Step(
        id = j.getString("id"),
        action = j.getString("action"),
        target = j.optString("target").takeIf { it.isNotBlank() },
        args = j.optJSONObject("args")?.strings().orEmpty(),
        expect = j.optJSONObject("expect")?.let { ExpectCodec.decode(it) },
        risk = runCatching { Risk.valueOf(j.optString("risk")) }.getOrDefault(Risk.UNDOABLE),
        slots = j.optJSONObject("slots")?.strings().orEmpty(),
        branches = j.optJSONArray("branches")?.objects()?.mapNotNull { b ->
            runCatching { Branch(b.getString("if"), Branch.BranchAction.valueOf(b.getString("then"))) }.getOrNull()
        }.orEmpty(),
        state = runCatching { StepState.valueOf(j.optString("state")) }.getOrDefault(StepState.TODO),
        note = j.optString("note").takeIf { it.isNotBlank() },
    )

    internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONObject.strings(): Map<String, String> = keys().asSequence().associateWith { optString(it) }
}
