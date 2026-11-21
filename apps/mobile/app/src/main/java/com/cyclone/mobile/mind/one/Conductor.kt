package com.cyclone.mobile.mind.one

import com.cyclone.mobile.mind.MindConversation
import com.cyclone.mobile.mind.MindMessage
import com.cyclone.mobile.mind.MindModel
import com.cyclone.mobile.mind.MindModelError
import com.cyclone.mobile.mind.MindModelReply
import com.cyclone.mobile.mind.MindModelRequest
import com.cyclone.mobile.mind.MindToolSpec
import com.cyclone.mobile.mind.StreamingMindModel
import com.cyclone.mobile.mind.modes.InstantGrammar

/** Plan 69 §5.5: the wheel is today's Mind loop, driving turn by turn inside the same conversation. */
interface Wheel {
    /** The tools the wheel offers; wake requests carry them once the wheel has been used, so the history stays valid. */
    fun specs(): List<MindToolSpec>
    fun drive(conversation: MindConversation, why: String): WheelEnd
}

sealed interface WheelEnd {
    /** The mind wrote the plan for the rest (plan_write): the crew takes over again. */
    data class HandedBack(val lines: String) : WheelEnd
    data class Finished(val summary: String, val evidence: String? = null) : WheelEnd
    data class GaveUp(val summary: String) : WheelEnd
    data class Failed(val summary: String) : WheelEnd
    data object Cancelled : WheelEnd
}

/** The owner as the conductor reaches them: stops, steers, questions and the check-in card. */
interface OwnerLine {
    fun cancelled(): Boolean = false
    /** Steers sent since the last call (each is a new goal version). */
    fun steers(): List<String> = emptyList()
    /** Messages the owner sent while the run works (not a steer): the mind reads them at its next wake. */
    fun messages(): List<String> = emptyList()
    /** Plan 38: the owner paused the run; it holds before its next step. */
    fun paused(): Boolean = false
    /** A question that only the owner can answer. Null: no answer (stopped, or the owner went away). */
    fun ask(question: String, choices: List<String>): String?
    /** Plan 69 §7: "This seems stuck on ⟨reason⟩. Keep going, change course, or stop?" */
    fun checkIn(reason: String): CheckIn
}

sealed interface CheckIn {
    data object KeepGoing : CheckIn
    data object Stop : CheckIn
    data class Steer(val text: String) : CheckIn
}

interface ConductorListener {
    fun onScore(score: Score) {}
    fun onWake(kind: WakeKind, trigger: String, reason: String) {}
    fun onNotice(text: String) {}
    /** After every step and wake: the run's state, for the journal (a resume after process death reloads it). */
    fun onCheckpoint(score: Score, conversation: MindConversation) {}
}

enum class RunEnd { DONE, UNCONFIRMED, GAVE_UP, FAILED, STOPPED, CANCELLED }

data class ConductorOutcome(
    val end: RunEnd,
    val summary: String,
    val score: Score,
    val wakes: Int,
    val handedOver: Boolean,
    val evidence: String? = null,
)

/** The two profiles of the one conductor (plan 69 §2, §6). */
data class Profile(
    val difficulty: Difficulty,
    /** Flash: replans before it hands over. Null: free. */
    val maxReplans: Int?,
    /** Flash: plan steps before it hands over. */
    val maxSteps: Int?,
    /** Flash: surprises in one milestone before it hands over. */
    val maxSurprises: Int?,
    /** Mind: glances on chosen milestones and forced reviews before consequential ones; barriers wait for them. */
    val reviews: Boolean,
    /** Mind: the conversation is compacted past the owner's limit. */
    val compacts: Boolean,
    /** Flash: the plan streams into action. */
    val streams: Boolean,
    /** Plan 75 alpha 147: a barrier waits for the first officer's check; a doubt goes to the captain (Flash). */
    val barrierReview: Boolean = false,
    /** Plan 75 alpha 150: the captain reviews a Flash run's chosen milestones in the background, as Mind's glances do. */
    val glances: Boolean = false,
) {
    companion object {
        val MIND = Profile(Difficulty.MIND, null, null, null, reviews = true, compacts = true, streams = false)
        val FLASH = Profile(Difficulty.FLASH, maxReplans = 3, maxSteps = 12, maxSurprises = 2, reviews = false, compacts = false, streams = true,
            barrierReview = true, glances = true)
    }
}

/**
 * Plan 69 §5–§8: the one conductor, with a Mind profile (OneMind) and a Flash profile (the Flash mind).
 *
 * One append-only conversation per run: each wake appends a code-built briefing and the mind's typed reply, so the
 * provider's prompt cache keeps hitting. Between wakes the crew runs the plan. The conductor:
 *
 * - wakes the mind with the four kinds (Glance, Consult, Replan, the wheel), each at its effort;
 * - holds work at barriers while a review is open, lets the crew go on with easy-to-undo moves during a glance, and
 *   takes them back when the glance disagrees;
 * - applies local replans and detours (a stack, at most 3 deep);
 * - treats a doubted finish as not done (at most 2 re-checks, then "Couldn't confirm: please check");
 * - asks the owner a quick check-in when the run spirals;
 * - hands a Flash run over to OneMind automatically, once;
 * - compacts a Mind conversation only past the owner's limit.
 *
 * Code still owns safety: every action goes through the crew's hands, whose gates and approvals are unchanged. Pure
 * apart from what the crew, the models, the wheel and the owner do.
 */
class Conductor(
    private val runId: String,
    private val goalText: String,
    profile: Profile,
    private val crew: Crew,
    /** The owner's selected model, at low effort or at the owner's effort. */
    private val brain: (Effort) -> MindModel,
    private val owner: OwnerLine,
    private val wheel: Wheel? = null,
    private val compactor: Compactor = Compactor(),
    private val breaker: Breaker = Breaker(),
    val ledger: Ledger = Ledger(),
    private val listener: ConductorListener = object : ConductorListener {},
    /** The phone's state for the system prompt; never secrets. */
    private val situation: () -> String = { "" },
    /** Where a glance runs; the crew keeps going meanwhile. A direct call runs it before the next step. */
    private val glanceRunner: (() -> Unit) -> Unit = { task -> Thread(task, "onemind-glance").apply { isDaemon = true }.start() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val callMs: Long = 120_000L,
    /** A run that already has a Score: an Instant run handed up (the baton), or a run resumed from its journal. */
    start: Score? = null,
    /** A resumed run's conversation; with it the run continues instead of planning again. */
    resumed: MindConversation? = null,
    /** A verified run's plan for the same request (plan 69 §10): the crew starts on it with no planning call. */
    private val recipe: Plan? = null,
    /** What the first briefing also carries: memory, recent missions, an attachment, the Instant baton. */
    opening: List<String> = emptyList(),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    /**
     * Plan 75 (alpha 145): the owner's backup model, used from the first call the selected model can't answer until the
     * run ends. Null: the selected model only, as before.
     */
    private val backup: ((Effort) -> MindModel)? = null,
    /** Plan 75: the owner's working time for this run (time waiting on the owner and paused doesn't count). Null: none. */
    private val workingMs: Long? = null,
    /** Working time a resumed run already spent. */
    spentMs: Long = 0L,
    /** Plan 75 alpha 147: the first officer's model for a Flash run (null: the captain answers everything, as before). */
    private val firstOfficer: ((Effort) -> MindModel)? = null,
) {
    private var profile = profile
    var score: Score = (start ?: Score.start(runId, profile.difficulty, goalText, clock())).copy(difficulty = profile.difficulty)
        private set
    var conversation = resumed ?: MindConversation(listOf(MindMessage.System(systemPrompt())))
        private set
    private val resuming = resumed != null
    private var pendingNotes = opening.filter { it.isNotBlank() }
    var wakes = 0
        private set
    private var seenSeq = 0L
    private var handedOver = false
    private var usedWheel = false
    private var replans = 0
    private val stepsRun = HashSet<String>()
    private var surprises = 0
    private var minorDoubts = 0
    private var currentMilestone: String? = null
    private var milestoneStartIndex = 1
    private val reviewed = HashSet<String>()
    /** Alpha 144: milestones whose recovery ladder already ran (once each). */
    private val recovered = HashSet<String>()
    private val askedOnce = HashSet<String>()
    private val detours = ArrayDeque<Plan>()
    private var glance: PendingGlance? = null
    private val movesSinceGlance = mutableListOf<String>()
    private var feed: Feed? = null
    private var depth = 0
    /** Plan 75: true once the run moved to the backup model; it stays there. */
    private var switched = false
    private val startedAt = clock() - spentMs
    /** Time spent waiting for the owner (a check-in, a question) or paused: not working time. */
    private var waitedMs = 0L

    /** The model for [effort]: the selected one, or the backup once the run switched to it. */
    /** Plan 75 alpha 147: the officer's model once built; set aside when it failed (the captain answers from then on). */
    private var officerModel: MindModel? = null
    private var officerFailed = false
    /** Plan 75 alpha 147: the done check the run started with. A re-plan keeps it (the contract). */
    private var contractDone: Expect? = score.plan?.done
    /** Plan 75 alpha 147: the last answer read came from the officer, and for which wake. */
    private var lastAnswerFromOfficer = false
    private var lastAnswerKind: WakeKind? = null

    /** The model that answers a wake of [kind]: the officer for a Flash run's plan, consults and glances, else the captain. */
    private fun modelFor(kind: WakeKind): MindModel {
        val officer = firstOfficer
        if (officer != null && profile.difficulty == Difficulty.FLASH && FirstOfficer.answers(kind) && !officerFailed) {
            return officerModel ?: officer(Effort.LOW).also { officerModel = it }
        }
        return captain(kind.effort)
    }

    /** The captain: the owner's model at [effort], or the backup once the run switched to it. */
    private fun captain(effort: Effort): MindModel = (if (switched) backup else null)?.invoke(effort) ?: brain(effort)

    /** Working time so far: the run's time less what it spent waiting on the owner. */
    fun workedMs(): Long = (clock() - startedAt - waitedMs).coerceAtLeast(0L)

    private inline fun <T> waiting(block: () -> T): T {
        val from = clock()
        try { return block() } finally { waitedMs += clock() - from }
    }

    private class End(val outcome: ConductorOutcome) : RuntimeException(null, null, false, false)

    fun run(): ConductorOutcome = try {
        publish()
        when {
            resuming -> {
                currentStep()?.let(::retry)
                wake(WakeKind.REPLAN, "resumed", "The run was interrupted and resumed: the phone may have changed since", null, screen = true)
            }
            recipe != null -> {
                contractDone = recipe.done
                score = score.replan(recipe.copy(milestones = recipe.milestones.map { m ->
                    m.copy(state = MilestoneState.TODO, steps = m.steps.map { it.copy(state = StepState.TODO) })
                })).event("recipe", "Started from a recipe: this request ran this way before and was checked", clock())
            }
            else -> plan(WakeKind.PLAN, "start", "A new task")
        }
        loop()
    } catch (end: End) {
        glance = null
        end.outcome
    }

    // ---- the run ----------------------------------------------------------------------------------------------------

    private fun loop(): Nothing {
        while (true) {
            if (owner.cancelled()) end(RunEnd.CANCELLED, "Stopped by the owner.")
            holdWhilePaused()
            outOfTime()
            steers()
            pollGlance()
            owner.messages().filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { said ->
                said.forEach { score = score.event("owner", "The owner says: ${it.take(200)}", clock()) }
                currentStep()?.let(::retry)
                wake(WakeKind.CONSULT, "owner_message", "The owner sent a message: ${said.joinToString(" / ").take(400)}", currentStep())
                continue
            }
            StandingOrders.firedByFacts(score).firstOrNull()?.let { o ->
                score = StandingOrders.spend(score, o.id)
                wake(WakeKind.CONSULT, "order", "Your order fired: ${o.text}", null)
                continue
            }
            val next = nextStep()
            if (next == null) { finish(crew.finalAnswer()); continue }
            val (milestone, step) = next
            enter(milestone)
            profile.maxSteps?.let { max -> if (stepsRun.add(step.id) && stepsRun.size > max) { handover("the Flash run passed $max steps"); continue } }
            score = score.mark(step.id, StepState.RUNNING)
            val outcome = runCatching { crew.run(step, score) }
                .getOrElse { StepOutcome.NeedsMind(step, "crew_error", "The crew failed: ${it.message ?: it.javaClass.simpleName}") }
            drainCrew()
            handle(outcome)
            publish()
            checkpoint()
        }
    }

    private fun holdWhilePaused() {
        if (!owner.paused()) return
        score = score.event("pause", "Paused by the owner", clock())
        publish()
        waiting { while (owner.paused() && !owner.cancelled()) sleep(PAUSE_POLL_MS) }
        if (owner.cancelled()) end(RunEnd.CANCELLED, "Stopped by the owner.")
        score = score.event("pause", "Resumed", clock())
    }

    private fun checkpoint() = runCatching { listener.onCheckpoint(score, conversation) }

    private fun handle(o: StepOutcome) {
        when (o) {
            is StepOutcome.Done -> {
                score = score.mark(o.step.id, StepState.DONE).event("step", "✓ ${line(o.step)} (rung ${o.rung})", clock(), o.step.id)
                if (glance != null) movesSinceGlance += o.step.id
                if (o.divergence == 1 && ++minorDoubts >= 2) {
                    minorDoubts = 0
                    wake(WakeKind.REPLAN, "divergence", "The run looked slightly off course twice in this milestone", o.step, screen = true)
                }
                milestoneEnd()
            }
            is StepOutcome.Branched -> {
                score = score.mark(o.step.id, StepState.SKIPPED).event("branch", "Skipped ${line(o.step)}: ${o.condition}", clock(), o.step.id)
                when (o.action) {
                    Branch.BranchAction.SKIP -> milestoneEnd()
                    Branch.BranchAction.NEXT_MILESTONE -> { skipRestOfMilestone(); milestoneEnd() }
                    Branch.BranchAction.DONE -> { skipRestOfMilestone(); finish(crew.finalAnswer()) }
                }
            }
            is StepOutcome.Missed -> { retry(o.step); wake(WakeKind.REPLAN, "missed", o.reason, o.step, screen = true) }
            is StepOutcome.PreviousMissed -> {
                retry(o.step)
                currentStep()?.let { retry(it) }
                wake(WakeKind.REPLAN, "previous_missed", "The step before (${line(o.step)}) didn't do what it should: ${ExpectCheck.describe(o.expect)}", o.step, screen = true)
            }
            is StepOutcome.Blocked -> { retry(o.step); wake(WakeKind.REPLAN, "blocked", "Blocked by a ${o.kind.replace('_', ' ')}", o.step, screen = true) }
            is StepOutcome.NeedsMind -> {
                retry(o.step)
                val kind = if (o.trigger in REPLAN_TRIGGERS) WakeKind.REPLAN else WakeKind.CONSULT
                if (o.trigger in SURPRISE_TRIGGERS) surprises++
                val max = profile.maxSurprises
                if (max != null && surprises > max) { handover("$surprises surprises in one milestone"); return }
                wake(kind, o.trigger, o.reason, o.step, screen = true)
            }
            is StepOutcome.OrderFired -> {
                retry(o.step)
                val order = score.orders.firstOrNull { it.id == o.orderId }
                score = StandingOrders.spend(score, o.orderId)
                if (order?.kind == StandingOrder.Kind.QUIET_UNTIL) {
                    score = score.event("order", "Quiet ended: ${order.text}", clock())
                } else wake(WakeKind.CONSULT, "order", "Your order fired: ${order?.text ?: o.orderId}", o.step, screen = true)
            }
            is StepOutcome.AtBarrier -> barrier(o)
            is StepOutcome.Halt -> {
                retry(o.step)
                when (val call = o.call) {
                    is Breaker.Call.Stop -> end(RunEnd.FAILED, "Stopped: ${call.reason}.")
                    is Breaker.Call.CheckIn -> checkIn(call.reason)
                    else -> wake(WakeKind.REPLAN, "stuck", "The crew is going round in circles", o.step, screen = true)
                }
            }
        }
    }

    /** Plan 69 §5.4: no work crosses a barrier while a review is open. */
    private fun barrier(o: StepOutcome.AtBarrier) {
        val milestone = milestoneOf(o.step.id)
        if (profile.reviews || profile.barrierReview) {
            if (awaitGlance()) return
            if (milestone != null && milestone.id !in reviewed) {
                reviewed += milestone.id
                val reply = think(WakeKind.GLANCE, "barrier", "The next move ${o.label?.let { "(\"$it\") " }.orEmpty()}isn't easy to undo: " +
                    "the crew waits here until you say continue", screen = true)
                if (reply.kind != WakeReply.Kind.CONTINUE) {
                    retry(o.step)
                    // Plan 75 alpha 147: a Flash run's officer that doubts the move hands the decision to the captain.
                    if (!profile.reviews) {
                        wake(WakeKind.REPLAN, "barrier_doubt", "The first officer doubts the move at the barrier: " +
                            reply.why.ifBlank { "it may not be what the goal asked for" }, o.step, screen = true)
                        return
                    }
                    apply(reply, o.step); return
                }
                applyNotes(reply)
            }
        }
        score = score.event("barrier", "Crossing the barrier: ${line(o.step)}", clock(), o.step.id)
        val res = runCatching { crew.actAt(o, score) }
            .getOrElse { StepOutcome.NeedsMind(o.step, "crew_error", "The crew failed at the barrier: ${it.message}") }
        drainCrew()
        // Acting at a barrier never stops at the same barrier again.
        if (res is StepOutcome.AtBarrier) { retry(o.step); wake(WakeKind.CONSULT, "barrier", "The move at the barrier couldn't be made", o.step, screen = true) }
        else handle(res)
    }

    /** The owner sees one approval per consequential move from the code gates; here a run only checks it's not stuck. */
    private fun checkIn(reason: String) {
        // Alpha 144: first the recovery ladder (close what is in the way, Back, reopen the app), once per milestone. The
        // owner is asked only when that changed nothing.
        val milestone = currentStep()?.let { milestoneOf(it.id)?.id } ?: ""
        if (milestone !in recovered) {
            recovered += milestone
            val done = runCatching { crew.recover() }.getOrNull()
            drainCrew()
            if (done != null) {
                breaker.checkedIn()
                score = score.event("recover", "Stuck on $reason; got unstuck: $done", clock())
                return
            }
        }
        // Plan 74 (alpha 149): stuck is never a card. A Flash run hands over to the captain (once); otherwise the run stops
        // honestly, saying where it got stuck and that it can be continued. No owner question for "I am stuck".
        if (NeedGate.raisesCard(NeedKind.STUCK)) return
        score = score.event("stuck", "Stuck on $reason", clock())
        if (profile.difficulty == Difficulty.FLASH) {
            handover("stuck on $reason")
            if (handedOver) { breaker.checkedIn(); return }
        }
        end(RunEnd.GAVE_UP, "Couldn't finish: stuck on $reason. Continue to try again.")
    }

    // ---- milestones ---------------------------------------------------------------------------------------------------

    private fun enter(m: Milestone) {
        if (m.id == currentMilestone) return
        currentMilestone = m.id
        milestoneStartIndex = conversation.size()
        surprises = 0
        minorDoubts = 0
        // Code forces a review before any milestone that sends, pays or deletes (plan 69 §5.3).
        if ((profile.reviews || profile.glances) && consequential(m) && m.id !in reviewed) {
            reviewed += m.id
            startGlance("forced_review", "The next milestone \"${m.title}\" sends, pays or deletes: look before the crew gets there", forced = true)
        }
    }

    private fun milestoneEnd() {
        val id = currentMilestone ?: return
        val m = score.plan?.milestones?.firstOrNull { it.id == id } ?: return
        if (m.state != MilestoneState.DONE) return
        currentMilestone = null
        breaker.milestoneDone()
        score = score.event("milestone", "✓ ${m.title}", clock())
        if (profile.reviews || profile.glances) awaitGlance()
        if (profile.compacts && compactor.due(conversation, atBoundary = true)) compact(conversation.size())
        if ((profile.reviews || profile.glances) && m.review && !StandingOrders.quiet(score)) startGlance("review", "Milestone \"${m.title}\" is done", forced = false)
    }

    private fun consequential(m: Milestone): Boolean = m.steps.any { s ->
        s.risk != Risk.UNDOABLE || s.action in ToolSteps.BARRIER_TOOLS ||
            com.cyclone.mobile.mind.pilot.Pilot.irreversible(s.target.orEmpty()) || com.cyclone.mobile.mind.pilot.Pilot.irreversible(s.action.replace('_', ' '))
    }

    private fun skipRestOfMilestone() {
        val id = currentMilestone ?: return
        score.plan?.milestones?.firstOrNull { it.id == id }?.steps?.filter { it.state == StepState.TODO || it.state == StepState.RUNNING }
            ?.forEach { score = score.mark(it.id, StepState.SKIPPED) }
    }

    private fun nextStep(): Pair<Milestone, Step>? {
        while (true) {
            if (owner.cancelled()) end(RunEnd.CANCELLED, "Stopped by the owner.")
            syncFeed()
            score.plan?.current()?.let { return it }
            val f = feed
            if (f != null) { if (f.awaitMore(stepCount())) continue; closeFeed(); continue }
            if (detours.isNotEmpty()) {
                val paused = detours.removeLast()
                score = score.copy(plan = paused.copy(version = (score.plan?.version ?: paused.version) + 1))
                    .event("detour", "Back from the side trip: resuming the paused milestone", clock())
                currentMilestone = null
                continue
            }
            return null
        }
    }

    // ---- wakes ------------------------------------------------------------------------------------------------------

    private fun wake(kind: WakeKind, trigger: String, reason: String, step: Step?, screen: Boolean = false) {
        awaitGlance()
        val notes = mutableListOf<String>()
        when (val call = breaker.wake(trigger)) {
            is Breaker.Call.CheckIn -> checkIn(call.reason)
            Breaker.Call.ChangeApproach -> notes += OneMindPrompt.CHANGE_APPROACH
            else -> Unit
        }
        apply(think(kind, trigger, reason, screen, notes), step)
    }

    /** The first call of a run, and of a handover: a plan must come back. */
    private fun plan(kind: WakeKind, trigger: String, reason: String, notes: List<String> = emptyList()) {
        val model = modelFor(kind)
        if (profile.streams && model is StreamingMindModel) { streamPlan(model, kind, trigger, reason, notes); return }
        var reply = think(kind, trigger, reason, screen = true, notes = notes)
        if (reply.plan == null && reply.kind in NEEDS_A_PLAN) {
            reply = think(kind, "no_plan", "No plan lines came back. Write the plan now, one JSON object per line.")
        }
        if (reply.plan == null && reply.kind in NEEDS_A_PLAN) {
            if (wheel != null) { driveWheel("no plan could be written"); return }
            end(RunEnd.FAILED, "The mind couldn't write a plan for this task.")
        }
        apply(reply.copy(kind = if (reply.kind in NEEDS_A_PLAN) WakeReply.Kind.REPLAN else reply.kind), null)
    }

    /** Appends a briefing, asks the mind, appends its reply and reads the typed decision. */
    private fun think(kind: WakeKind, trigger: String, reason: String, screen: Boolean = false, notes: List<String> = emptyList()): WakeReply {
        awaitGlance()
        awaitFeed()
        if (profile.compacts && compactor.due(conversation, atBoundary = false)) compact((conversation.size() - KEEP_RECENT).coerceAtLeast(1))
        val brief = brief(kind, trigger, reason, screen, notes)
        conversation.add(MindMessage.User(brief.text, brief.imageDataUrl, MindMessage.User.Origin.HARNESS))
        return answer(kind, trigger, reason, call(kind))
    }

    private fun brief(kind: WakeKind, trigger: String, reason: String, screen: Boolean, notes: List<String>): Briefing.Brief {
        wakes++
        val evidence = if (screen || kind == WakeKind.PLAN) Briefing.Evidence(runCatching { crew.look(true) }.getOrNull()) else null
        val brief = Briefing.build(wakes, kind, trigger, reason, score, seenSeq, evidence, pendingNotes + notes)
        pendingNotes = emptyList()
        seenSeq = brief.lastSeq
        score = score.event("wake", "${kind.name.lowercase().replaceFirstChar { it.uppercase() }}: $reason", clock())
        listener.onWake(kind, trigger, reason)
        return brief
    }

    private fun answer(kind: WakeKind, trigger: String, reason: String, reply: MindModelReply): WakeReply {
        conversation.add(MindMessage.Assistant(reply.text, reply.toolCalls, reply.reasoningDetails))
        if (reply.toolCalls.isNotEmpty()) {
            // The mind wanted to act itself: every call gets its answer, and that is a choice of the wheel.
            reply.toolCalls.forEach { conversation.add(MindMessage.Tool(it.id, it.name, OneMindPrompt.NOT_A_WHEEL_TURN, OneMindPrompt.NOT_A_WHEEL_TURN)) }
            return WakeReply(WakeReply.Kind.WHEEL, why = "it wanted to act itself ($reason)")
        }
        return WakeReply.read(reply.text, goalText, "w$wakes.")
    }

    /**
     * One mind call, with recovery (plan 75, alpha 145): a transient failure or a slow answer is asked again once; a
     * model that can't answer (twice, rate-limited or refusing the request) hands the run to the owner's backup model,
     * which keeps it to the end; with no backup left, a rate limit is waited out (its Retry-After, at most
     * [MAX_RATE_WAIT_MS], [MAX_RATE_WAITS] times). Only then does the run fail.
     */
    private fun call(kind: WakeKind): MindModelReply {
        var lastError: Exception? = null
        var failures = 0
        var waits = 0
        while (true) {
            val model = modelFor(kind)
            if (owner.cancelled()) end(RunEnd.CANCELLED, "Stopped by the owner.")
            val started = clock()
            try {
                val reply = model.complete(request())
                record(kind, model, reply, clock() - started, ok = true)
                lastAnswerFromOfficer = model === officerModel
                lastAnswerKind = kind
                return reply
            } catch (error: MindModelError.Cancelled) {
                end(RunEnd.CANCELLED, "Stopped by the owner.")
            } catch (error: Exception) {
                lastError = error
                failures++
                record(kind, model, null, clock() - started, ok = false)
                val why = com.cyclone.mobile.mind.mission.FailureText.describe(error)
                val canSwitch = !switched && backup != null
                val hopeless = error is MindModelError.RateLimited || error is MindModelError.Fatal || error is MindModelError.ToolsUnsupported
                when {
                    // Plan 75 alpha 147: the officer can't answer: the captain takes over at once, for the rest of the run.
                    officerModel != null && model === officerModel && !officerFailed -> {
                        officerFailed = true
                        failures = 0
                        score = score.event("model", "The first officer couldn't answer; the captain takes over: $why", clock())
                        listener.onNotice("The first officer couldn't answer; the captain takes over: $why")
                    }
                    !hopeless && failures < 2 -> listener.onNotice("${model.label} didn't answer; asking again: $why")
                    canSwitch -> {
                        switched = true
                        failures = 0
                        conversation.forgetReasoning()
                        val next = captain(kind.effort)
                        score = score.event("model", "Switched to the backup model ${next.label}: ${model.label} couldn't answer", clock())
                        listener.onNotice("Switched from ${model.label} to ${next.label}: $why")
                    }
                    error is MindModelError.RateLimited && waits < MAX_RATE_WAITS -> {
                        waits++
                        val wait = error.retryAfterMs.coerceIn(1_000L, MAX_RATE_WAIT_MS)
                        listener.onNotice("${model.label} is rate-limited; waiting ${wait / 1000} s.")
                        sleep(wait)
                    }
                    else -> end(RunEnd.FAILED, "${model.label} could not answer: ${lastError.message ?: "no reply"}")
                }
            }
        }
    }

    /** Plan 75: the owner's working time is used up: an honest stop that can be continued, never a silent one. */
    private fun outOfTime() {
        val limit = workingMs ?: return
        if (workedMs() < limit) return
        val minutes = (limit / 60_000L).coerceAtLeast(1L)
        score = score.event("time", "Working time used up ($minutes min)", clock())
        end(RunEnd.GAVE_UP, "Used the $minutes minutes of working time before finishing. ${report()}")
    }

    private fun request() = MindModelRequest(conversation.toWire(true), if (usedWheel) wheel?.specs().orEmpty() else emptyList(), true, callMs)

    private fun record(kind: WakeKind, model: MindModel, reply: MindModelReply?, ms: Long, ok: Boolean) {
        ledger.add(LedgerEntry(clock(), if (profile.difficulty == Difficulty.FLASH) "flash" else "mind", model.id,
            inputTokens = reply?.usage?.promptTokens ?: 0, outputTokens = reply?.usage?.completionTokens ?: 0,
            costUsd = reply?.usage?.costUsd ?: 0.0, ms = ms, ok = ok,
            stage = (if (model === officerModel) "officer_" else "") + kind.name.lowercase()))
        score = score.event("ledger", "${kind.name.lowercase()} call: ${model.label}${if (ok) "" else " (failed)"}", clock())
    }

    /** Applies a mind's decision. [step] is the step the wake was about, if any. */
    private fun apply(reply: WakeReply, step: Step?) {
        if (++depth > MAX_DEPTH) { depth--; end(RunEnd.FAILED, "The mind kept answering without a usable decision.") }
        try {
            applyNotes(reply)
            when (reply.kind) {
                WakeReply.Kind.CONTINUE -> Unit
                WakeReply.Kind.SKIP -> step?.let { score = score.mark(it.id, StepState.SKIPPED).event("skip", "Skipped ${line(it)}", clock(), it.id) }
                WakeReply.Kind.ADJUST -> { countReplan(); adjust(reply.plan!!) }
                WakeReply.Kind.REPLAN -> {
                    val plan = reply.plan!!
                    val before = score.plan
                    // Plan 75 alpha 147: the officer re-plans its own milestone; anything wider goes to the captain.
                    if (lastAnswerFromOfficer && lastAnswerKind != WakeKind.PLAN && before != null &&
                        !FirstOfficer.keepsMilestones(before, plan, before.current()?.first?.id)) {
                        score = score.event("officer", "The first officer's plan reached beyond this milestone; the captain decides", clock())
                        wake(WakeKind.REPLAN, "officer_scope", "The first officer's plan changed more than the current milestone. Plan the whole run again.", step, screen = true)
                        return
                    }
                    if (score.plan != null) countReplan()
                    // Plan 75 alpha 147: the contract. A plan keeps the done check the run started with.
                    val fixed = contractDone?.let { plan.copy(done = it) } ?: plan
                    contractDone = contractDone ?: fixed.done
                    score = score.replan(fixed).event("plan", "Plan version ${(score.plan?.version ?: 0) + 1}: ${reply.why.ifBlank { "new plan" }}", clock())
                    currentMilestone = null
                }
                WakeReply.Kind.DETOUR -> detour(reply)
                WakeReply.Kind.WHEEL -> driveWheel(reply.why.ifBlank { "the mind chose to drive" })
                WakeReply.Kind.ASK -> ask(reply, step)
                WakeReply.Kind.FINISH -> finish(reply.summary)
                WakeReply.Kind.GIVE_UP -> end(RunEnd.GAVE_UP, reply.summary ?: reply.why.ifBlank { "The mind gave up." })
                WakeReply.Kind.NEEDS_FRONTIER ->
                    if (profile.difficulty == Difficulty.FLASH) handover(reply.why.ifBlank { "the Flash mind asked for OneMind" })
                    else score = score.event("note", "The mind asked for a stronger mind; it is already OneMind", clock())
                WakeReply.Kind.RECALL -> {
                    conversation.add(MindMessage.User(compactor.recall(reply.recall!!), origin = MindMessage.User.Origin.HARNESS))
                    apply(answer(WakeKind.CONSULT, "recall", "recall", call(WakeKind.CONSULT)), step)
                }
            }
        } finally { depth-- }
    }

    /** Orders, facts and chosen reviews ride on any decision. */
    private fun applyNotes(reply: WakeReply) {
        reply.facts.forEach { (name, value) -> score = score.fact(name, value, "mind", clock()) }
        reply.orders?.let { score = score.orders(StandingOrders.compile(it)).event("orders", "Standing orders: ${it.size}", clock()) }
        if (reply.reviews.isNotEmpty()) score.plan?.let { p ->
            score = score.copy(plan = p.copy(milestones = p.milestones.map { m -> if (m.id in reply.reviews) m.copy(review = true) else m }))
        }
    }

    /** The rest of the current milestone is replaced; its finished steps stay. */
    private fun adjust(lines: Plan) {
        val p = score.plan ?: run { score = score.replan(lines); return }
        val id = currentMilestone ?: p.current()?.first?.id ?: run { score = score.replan(lines); return }
        val fresh = lines.steps
        score = score.copy(plan = p.copy(version = p.version + 1, milestones = p.milestones.map { m ->
            if (m.id != id) m else m.copy(steps = m.steps.filter { it.state == StepState.DONE || it.state == StepState.SKIPPED } + fresh,
                state = MilestoneState.RUNNING)
        })).event("plan", "Adjusted the rest of this milestone (${fresh.size} steps)", clock())
    }

    /** Plan 69 §5.5: a side trip with its own plan; the paused milestone keeps its state and resumes after it. */
    private fun detour(reply: WakeReply) {
        val p = score.plan
        if (p == null || detours.size >= MAX_DETOURS) { adjust(reply.plan!!); return }
        detours.addLast(p)
        score = score.copy(plan = reply.plan!!.copy(version = p.version + 1))
            .event("detour", "Side trip: ${reply.why.ifBlank { reply.plan.milestones.first().title }}", clock())
        currentMilestone = null
    }

    /** Plan 69 §4.4 ASK: a question is tried once more by the mind first; consent always reaches the owner. */
    private fun ask(reply: WakeReply, step: Step?) {
        val question = reply.question!!
        val key = InstantGrammar.normalize(question)
        if (!reply.consent && askedOnce.add(key)) {
            apply(think(WakeKind.CONSULT, "ask", "You wanted to ask the owner: $question", notes = listOf(OneMindPrompt.ASK_HINT)), step)
            return
        }
        // Plan 74 (alpha 149): a clarification between choices is settled by assuming the first, and saying so; no card.
        val kind = NeedGate.kindOf(reply.consent, reply.choices)
        if (!NeedGate.raisesCard(kind)) {
            val assumed = reply.choices.first { it.isNotBlank() }
            score = score.event("assumed", "Assumed \"$assumed\" for: ${question.take(120)}", clock())
            listener.onNotice("Assumed \"$assumed\" (change it in the task): ${question.take(120)}")
            apply(think(WakeKind.CONSULT, "assumed", "You asked \"$question\"; Cyclone assumed \"$assumed\". The owner can change it; carry on with it."), step)
            return
        }
        score = score.event("ask", "Asked the owner: ${question.take(160)}", clock())
        val answer = waiting { runCatching { owner.ask(question, reply.choices) }.getOrNull() }
        if (answer == null) {
            if (owner.cancelled()) end(RunEnd.CANCELLED, "Stopped by the owner.")
            end(RunEnd.STOPPED, "Waiting for the owner: $question")
        }
        score = score.event("owner", "The owner answered: ${answer.take(200)}", clock())
        apply(think(WakeKind.CONSULT, "owner_answer", "You asked \"$question\" and the owner answered: $answer"), step)
    }

    // ---- the finish -------------------------------------------------------------------------------------------------

    /**
     * Plan 69 §4.4 DONE: code's predicate first, then the DONE question. Doubt means not done.
     *
     * Alpha 143: a predicate that only says "the app is open" or "the screen changed" can't prove a goal like "open my
     * profile in Instagram" (the app was open on its home page), so it never finishes alone: the DONE question must
     * answer yes. Only a predicate that names what the goal shows (text, a control, a field, a setting, a fact) may
     * finish when no decision model answered.
     */
    private fun finish(summary: String?) {
        awaitFeed()
        awaitGlance()
        val screen = runCatching { crew.look(false) }.getOrNull()
        val predicate = score.plan?.done
        val code = predicate?.let { e -> runCatching { crew.check(e, score) }.getOrDefault(Verdict.UNKNOWN) } ?: Verdict.UNKNOWN
        val yes = runCatching { crew.done(score, screen) }.getOrNull()
        // Plan 75 alpha 148: no model answered and the proof is strong: the proof must hold again on a fresh read, after a
        // short settle, before the run may finish without a model's yes.
        val strongProof = predicate?.let(::strong) == true
        val again = if (code == Verdict.HELD && strongProof && yes == null) {
            sleep(DONE_SETTLE_MS)
            runCatching { crew.check(predicate!!, score) }.getOrNull()
        } else null
        val confirmed = doneConfirmed(code, strongProof, yes, again)
        if (confirmed) {
            score = score.event("done", "Done, and checked", clock())
            end(RunEnd.DONE, summary ?: report())
        }
        val why = when {
            code == Verdict.MISSED -> "The goal's own check doesn't hold: ${ExpectCheck.describe(score.plan!!.done!!)}"
            yes != null -> "The done check isn't sure the goal holds (%.2f)".format(yes)
            else -> "Nothing could confirm that the goal holds"
        }
        score = score.event("done_doubt", why, clock())
        if (!breaker.doneDoubt()) end(RunEnd.UNCONFIRMED, "Couldn't confirm: please check. ${summary ?: report()}")
        wake(WakeKind.REPLAN, "done_doubt", "$why. Fix it, or explain and finish again.", null, screen = true)
    }

    /** The end report, built by code from milestones and facts. */

    private fun report(): String {
        val done = score.plan?.milestones?.filter { it.state == MilestoneState.DONE }?.map { it.title }.orEmpty()
        return if (done.isEmpty()) "Done." else "Done: ${done.joinToString(", ")}."
    }

    // ---- the wheel, the handover, steers ----------------------------------------------------------------------------

    private fun driveWheel(why: String) {
        val w = wheel ?: run {
            apply(think(WakeKind.REPLAN, "no_wheel", "The wheel isn't available in this run: write a plan for the crew instead."), currentStep())
            return
        }
        awaitGlance()
        awaitFeed()
        usedWheel = true
        wakes++
        conversation.add(MindMessage.User("${OneMindPrompt.WHEEL_START}\nWhy: $why", origin = MindMessage.User.Origin.HARNESS))
        score = score.event("wheel", "OneMind took the wheel: $why", clock())
        listener.onWake(WakeKind.WHEEL, "wheel", why)
        publish()
        when (val e = runCatching { w.drive(conversation, why) }.getOrElse { WheelEnd.Failed("The wheel failed: ${it.message}") }) {
            is WheelEnd.HandedBack -> {
                score = score.event("wheel", "OneMind handed back to the crew", clock())
                val reply = WakeReply.read(e.lines, goalText, "w$wakes.")
                currentStep()?.let(::retry)
                apply(if (reply.plan != null && reply.kind == WakeReply.Kind.CONTINUE) reply.copy(kind = WakeReply.Kind.REPLAN) else reply, currentStep())
            }
            is WheelEnd.Finished -> finish(e.summary)
            is WheelEnd.GaveUp -> end(RunEnd.GAVE_UP, e.summary)
            is WheelEnd.Failed -> end(RunEnd.FAILED, e.summary)
            WheelEnd.Cancelled -> end(RunEnd.CANCELLED, "Stopped by the owner.")
        }
    }

    /** Plan 69 §6: Flash hands over to OneMind automatically, at most once; the Score is kept. */
    private fun handover(why: String) {
        if (profile.difficulty != Difficulty.FLASH || !breaker.handover()) {
            score = score.event("note", "No second handover: $why", clock())
            return
        }
        awaitFeed()
        val summary = compactor.handover(conversation, score, why)
        currentStep()?.let(::retry)
        profile = Profile.MIND
        handedOver = true
        score = score.copy(difficulty = Difficulty.MIND).event("handover", "Flash handed over to OneMind: $why", clock())
        conversation = MindConversation(listOf(MindMessage.System(systemPrompt())))
        seenSeq = 0
        currentMilestone = null
        listener.onNotice("Handed over to OneMind: $why")
        plan(WakeKind.PLAN, "handover", "The Flash mind handed this task over: $why", listOf(summary))
    }

    private fun steers() {
        owner.steers().filter { it.isNotBlank() }.forEach(::steer)
    }

    /** Plan 38 / §5.5: a steer is a new goal version, and the plan must follow it. */
    private fun steer(text: String) {
        score = score.steer(text, clock()).event("steer", "The owner changed the task: ${text.take(160)}", clock())
        currentStep()?.let(::retry)
        apply(think(WakeKind.REPLAN, "steer", "The owner changed the task (version ${score.goal.version}): $text").let { r ->
            if (r.kind == WakeReply.Kind.CONTINUE && r.plan != null) r.copy(kind = WakeReply.Kind.REPLAN) else r
        }, currentStep())
    }

    // ---- glances ----------------------------------------------------------------------------------------------------

    private class PendingGlance {
        private val lock = Object()
        @Volatile var result: Pair<WakeReply, MindModelReply?>? = null
            private set
        fun finish(r: Pair<WakeReply, MindModelReply?>) = synchronized(lock) { result = r; lock.notifyAll() }
        fun await(cancelled: () -> Boolean): Pair<WakeReply, MindModelReply?>? = synchronized(lock) {
            while (result == null && !cancelled()) lock.wait(GLANCE_POLL_MS)
            result
        }
    }

    /**
     * A glance runs beside the crew: the briefing is appended now, the mind answers on [glanceRunner], and the crew
     * keeps going on easy-to-undo moves until the next barrier.
     */
    private fun startGlance(trigger: String, reason: String, forced: Boolean) {
        if (glance != null) return
        if (!forced && StandingOrders.quiet(score)) return
        awaitFeed()
        val brief = brief(WakeKind.GLANCE, trigger, reason, screen = false, notes = emptyList())
        conversation.add(MindMessage.User(brief.text, brief.imageDataUrl, MindMessage.User.Origin.HARNESS))
        val pending = PendingGlance()
        glance = pending
        movesSinceGlance.clear()
        // Plan 75 alpha 150: a glance is the captain's review, in the background; the first officer answers the barrier check instead.
        val model = captain(Effort.LOW)
        val request = request()
        glanceRunner {
            val started = clock()
            val reply = runCatching { model.complete(request) }.getOrNull()
            pending.finish((reply?.let { WakeReply.read(it.text, goalText, "g$wakes.") } ?: WakeReply(WakeReply.Kind.CONTINUE, "the glance failed")) to reply)
            ledger.add(LedgerEntry(clock(), "mind", model.id, reply?.usage?.promptTokens ?: 0, 0, reply?.usage?.completionTokens ?: 0,
                reply?.usage?.costUsd ?: 0.0, clock() - started, reply != null, stage = "glance"))
        }
    }

    private fun pollGlance() {
        val g = glance ?: return
        val r = g.result ?: return
        glance = null
        applyGlance(r)
    }

    /** Waits for an open glance and applies it. True when it changed the plan. */
    private fun awaitGlance(): Boolean {
        val g = glance ?: return false
        val r = g.await(owner::cancelled) ?: end(RunEnd.CANCELLED, "Stopped by the owner.")
        glance = null
        return applyGlance(r)
    }

    private fun applyGlance(r: Pair<WakeReply, MindModelReply?>): Boolean {
        val (reply, raw) = r
        conversation.add(MindMessage.Assistant(raw?.text ?: "{\"decision\":\"continue\",\"why\":\"no answer\"}", emptyList(), raw?.reasoningDetails))
        val moves = movesSinceGlance.toList()
        movesSinceGlance.clear()
        if (reply.kind == WakeReply.Kind.CONTINUE) { applyNotes(reply); return false }
        if (moves.isNotEmpty()) {
            runCatching { crew.undo(moves.size) }
            drainCrew()
            moves.forEach { score = score.mark(it, StepState.TODO) }
            score = score.event("undo", "Took back ${moves.size} move${if (moves.size == 1) "" else "s"} made during the glance", clock())
        }
        currentStep()?.let(::retry)
        apply(reply, currentStep())
        return true
    }

    // ---- streamed plans (Flash) --------------------------------------------------------------------------------------

    private inner class Feed(val draft: PlanLines.Draft) {
        private val lock = Object()
        @Volatile var open = true
        var reply: MindModelReply? = null
        var error: Exception? = null
        var started = 0L
        fun steps(): Int = synchronized(lock) { draft.plan()?.steps?.size ?: 0 }
        fun line(text: String) = synchronized(lock) { draft.line(text); lock.notifyAll() }
        fun close(reply: MindModelReply?, error: Exception?) = synchronized(lock) { this.reply = reply; this.error = error; open = false; lock.notifyAll() }
        fun plan(): Plan? = synchronized(lock) { draft.plan() }
        /** Waits until the draft has more than [seen] steps (true) or the stream ended (false). */
        fun awaitMore(seen: Int): Boolean = synchronized(lock) {
            while (open && (draft.plan()?.steps?.size ?: 0) <= seen && !owner.cancelled()) lock.wait(GLANCE_POLL_MS)
            (draft.plan()?.steps?.size ?: 0) > seen
        }
    }

    /** Plan 69 §6: step 1 starts as soon as it is parsed; the rest of the plan arrives while the crew works. */
    private fun streamPlan(model: StreamingMindModel, kind: WakeKind, trigger: String, reason: String, notes: List<String>) {
        val brief = brief(kind, trigger, reason, screen = true, notes = notes)
        conversation.add(MindMessage.User(brief.text, brief.imageDataUrl, MindMessage.User.Origin.HARNESS))
        val f = Feed(PlanLines.Draft(goalText, "w$wakes."))
        f.started = clock()
        feed = f
        val request = request()
        val lines = PlanLines.Stream(f::line)
        Thread({
            try {
                val reply = model.stream(request) { lines.feed(it) }
                lines.finish()
                f.close(reply, null)
            } catch (error: Exception) {
                f.close(null, error)
            }
        }, "onemind-plan").apply { isDaemon = true }.start()
        f.awaitMore(0)
        syncFeed()
        if (score.plan == null) closeFeed()
    }

    private fun stepCount(): Int = score.plan?.steps?.size ?: 0

    /** The streamed draft so far becomes the plan; steps already run keep their state. */
    private fun syncFeed() {
        val f = feed ?: return
        val draft = f.plan() ?: return
        val old = score.plan
        val states = old?.steps?.associate { it.id to it.state }.orEmpty()
        val oldMilestones = old?.milestones?.associateBy { it.id }.orEmpty()
        score = score.copy(plan = draft.copy(version = 1, milestones = draft.milestones.map { m ->
            m.copy(state = oldMilestones[m.id]?.state ?: m.state, steps = m.steps.map { s -> s.copy(state = states[s.id] ?: s.state) })
        }))
    }

    private fun awaitFeed() {
        val f = feed ?: return
        while (f.open && !owner.cancelled()) f.awaitMore(Int.MAX_VALUE)
        closeFeed()
    }

    /** The stream ended: its reply joins the conversation and its decision applies. */
    private fun closeFeed() {
        val f = feed ?: return
        if (f.open) return
        syncFeed()
        feed = null
        val model = modelFor(WakeKind.PLAN)
        val raw = f.reply
        if (raw == null) {
            record(WakeKind.PLAN, model, null, clock() - f.started, ok = false)
            if (score.plan == null) {
                // The stream failed before any step: plan the ordinary way.
                conversation.add(MindMessage.Assistant("{\"decision\":\"continue\",\"why\":\"the plan didn't arrive\"}", emptyList()))
                apply(think(WakeKind.PLAN, "no_plan", "The plan didn't arrive (${f.error?.message ?: "no reply"}). Write it again.").let { r ->
                    if (r.plan != null && r.kind == WakeReply.Kind.CONTINUE) r.copy(kind = WakeReply.Kind.REPLAN) else r
                }, null)
            } else conversation.add(MindMessage.Assistant("(the plan stream broke off after ${stepCount()} steps)", emptyList()))
            return
        }
        record(WakeKind.PLAN, model, raw, clock() - f.started, ok = true)
        lastAnswerFromOfficer = model === officerModel
        lastAnswerKind = WakeKind.PLAN
        conversation.add(MindMessage.Assistant(raw.text, raw.toolCalls, raw.reasoningDetails))
        val reply = WakeReply.from(f.draft)
        applyNotes(reply)
        when (reply.kind) {
            WakeReply.Kind.FINISH, WakeReply.Kind.GIVE_UP, WakeReply.Kind.ASK, WakeReply.Kind.NEEDS_FRONTIER, WakeReply.Kind.WHEEL ->
                apply(reply.copy(orders = null, facts = emptyList()), currentStep())
            else -> if (score.plan == null) end(RunEnd.FAILED, "The Flash mind didn't write a plan.")
        }
    }

    // ---- small things ---------------------------------------------------------------------------------------------------

    private fun compact(keepFrom: Int) {
        val r = compactor.compact(conversation, score, keepFrom) ?: return
        score = score.event("compact", "History compacted: about ${r.tokensBefore / 1000}k → ${r.tokensAfter / 1000}k tokens" +
            if (r.byLuna) "" else " (code summary)", clock())
        milestoneStartIndex = conversation.size()
    }

    private fun countReplan() {
        val max = profile.maxReplans ?: return
        if (++replans > max) handover("$replans replans")
    }

    private fun currentStep(): Step? = score.plan?.current()?.second

    private fun milestoneOf(stepId: String): Milestone? = score.plan?.milestones?.firstOrNull { m -> m.steps.any { it.id == stepId } }

    /** A step that was running goes back to the to-do list. */
    private fun retry(step: Step) {
        if (score.plan?.steps?.firstOrNull { it.id == step.id }?.state == StepState.RUNNING) score = score.mark(step.id, StepState.TODO)
    }

    private fun drainCrew() = crew.drain().forEach { (kind, line) ->
        score = if (kind.startsWith("fact:")) score.fact(kind.removePrefix("fact:"), line, "tool", clock())
            .event("fact", "Found ${kind.removePrefix("fact:")}", clock())
        else score.event(kind, line, clock())
    }

    private fun line(s: Step) = s.action.replace('_', ' ') + (s.target?.let { " \"$it\"" }.orEmpty())

    private fun systemPrompt(): String {
        val now = runCatching { situation() }.getOrDefault("")
        return if (profile.difficulty == Difficulty.FLASH) OneMindPrompt.flash(now) else OneMindPrompt.mind(now)
    }

    private fun publish() = runCatching { listener.onScore(score) }

    private fun end(end: RunEnd, summary: String): Nothing {
        score = score.event("end", "${end.name.lowercase()}: ${summary.take(200)}", clock())
        publish()
        checkpoint()
        throw End(ConductorOutcome(end, summary, score, wakes, handedOver))
    }

    companion object {
        /** Triggers that question the plan rather than one step. */
        val REPLAN_TRIGGERS = setOf("surprise", "drift", "divergence", "stuck", "unreadable", "crew_error")
        /** Triggers that count against Flash's surprise budget. */
        val SURPRISE_TRIGGERS = setOf("surprise", "drift", "divergence")
        private val NEEDS_A_PLAN = setOf(WakeReply.Kind.CONTINUE, WakeReply.Kind.ADJUST, WakeReply.Kind.REPLAN, WakeReply.Kind.DETOUR, WakeReply.Kind.SKIP, WakeReply.Kind.RECALL)
        const val MAX_DETOURS = 3
        const val MAX_DEPTH = 8
        const val KEEP_RECENT = 6
        /** Plan 75: how often, and how long at most, a rate limit is waited out once no backup is left. */
        const val MAX_RATE_WAITS = 2
        const val MAX_RATE_WAIT_MS = 5_000L
        /** Plan 75 alpha 148: how long a strong proof waits before it is read again, when no model answered. */
        const val DONE_SETTLE_MS = 400L
        const val GLANCE_POLL_MS = 200L
        const val PAUSE_POLL_MS = 250L

        /**
         * Alpha 143, the finish rule. [code] is the plan's done predicate checked by code, [strong] whether that
         * predicate names what the goal shows, [yes] the DONE question's careful yes (null: nobody answered).
         * - a code miss is never done;
         * - a strong predicate that holds is done unless the DONE question says no;
         * - a weak one that holds ("the app is open") needs an answered yes;
         * - no predicate, or one code can't judge, needs a sure yes.
         */
        fun doneConfirmed(code: Verdict, strong: Boolean, yes: Double?, again: Verdict? = null): Boolean = when (code) {
            Verdict.MISSED -> false
            // Plan 75 alpha 148: a strong proof with no model's answer needs to hold again on a fresh read (see finish).
            Verdict.HELD -> if (strong) (yes?.let { it >= FusedBoard.YES } ?: (again == Verdict.HELD)) else yes != null && yes >= FusedBoard.YES
            Verdict.UNKNOWN -> yes != null && yes >= FusedBoard.SURE
        }

        /** True when [e] names something the goal shows, not just where the phone is. */
        fun strong(e: Expect): Boolean = when (e) {
            is Expect.Shows, is Expect.Control, is Expect.FieldHas, is Expect.Setting, is Expect.HasFact -> true
            is Expect.All -> e.parts.any(::strong)
            is Expect.AppIs, is Expect.Absent, Expect.Focused, Expect.Changed, is Expect.Described -> false
        }
    }
}
