package com.cyclone.mobile.mind.one

import com.cyclone.mobile.mind.MindToolCall
import com.cyclone.mobile.mind.MindToolResult
import com.cyclone.mobile.mind.MindToolbox
import com.cyclone.mobile.mind.modes.InstantGrammar
import org.json.JSONObject

/** The phone as the crew reads it. */
fun interface StepEyes { fun look(image: Boolean): StepScreen? }

/** The fused call, answered by every usable decision provider (one reading each). Empty: no answer. */
fun interface StepBoard { fun ask(request: FusedBoard.Request, ask: FusedBoard.Ask): List<FusedBoard.Reading> }

/** What happened to a step, for the run's conductor to act on. */
sealed interface StepOutcome {
    /** The step ran. [verdict] is code's check of its postcondition (UNKNOWN: the next fused call verifies it). */
    data class Done(val step: Step, val verdict: Verdict, val rung: Int, val divergence: Int = 0) : StepOutcome
    /** A branch condition held: skip this step, end the plan, or move to the next milestone. */
    data class Branched(val step: Step, val action: Branch.BranchAction, val condition: String) : StepOutcome
    /** The step ran but its postcondition clearly didn't hold. */
    data class Missed(val step: Step, val reason: String) : StepOutcome
    /** The previous step's postcondition turned out false (seen by VERIFY on this screen). */
    data class PreviousMissed(val step: Step, val expect: Expect) : StepOutcome
    /** Login, permission, error, paywall or a dialog that won't go. */
    data class Blocked(val step: Step, val kind: String) : StepOutcome
    /** A mind is needed: [trigger] is a stable word for the breaker, [reason] the line for the briefing. */
    data class NeedsMind(val step: Step, val trigger: String, val reason: String) : StepOutcome
    /** A standing order fired: wake the mind before going on. */
    data class OrderFired(val step: Step, val orderId: String) : StepOutcome
    /** The next move isn't easy to undo: the conductor decides whether to review first, then calls [StepEngine.actAt]. */
    data class AtBarrier(val step: Step, val ref: String?, val label: String?) : StepOutcome
    /** The breaker stopped the run or asks the owner. */
    data class Halt(val step: Step, val call: Breaker.Call) : StepOutcome
}

/**
 * Plan 69 §4.1: one step on the ladder.
 *
 * - **Rung 0, code:** direct actions (open an app, a link, settings, back, home, wait, typed tools) and targets that
 *   match exactly one control by label. No model call.
 * - **Rung 1, the fused call** (JEV ∥ OpenAI Decisions), with the careful-side rules of [FusedBoard.combine].
 * - **Alpha 143 (plan 72): two that decide, one that proves.** The screenshot goes into the first call (OpenAI
 *   Decisions sees it, JEV reads the text), so there is no second "now with the image" round: a movement makes at most
 *   two deciding calls, and its proof rides in the next movement's call (or the DONE check at the end).
 * - Anything still unsure goes to the run's mind ([StepOutcome.NeedsMind]).
 * - Every proven tap is remembered for its app ([memory]), and the run's last moves ride in each call.
 *
 * Every action goes through [hands] (the Mind's own toolbox), so every gate, approval and the vault stay exactly where
 * they are. Code decides risk ([com.cyclone.mobile.mind.pilot.Pilot.irreversible]); a decision model can only raise
 * care. Pure apart from what [eyes], [board] and [hands] do.
 */
class StepEngine(
    private val eyes: StepEyes,
    private val board: StepBoard,
    private val hands: MindToolbox,
    private val breaker: Breaker = Breaker(),
    private val settings: SettingReader? = null,
    private val record: (kind: String, line: String) -> Unit = { _, _ -> },
    private val memory: MoveMemory? = null,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Plan 75 (alpha 145): how this run's moves were decided and where their time went, for the run record. */
    @Volatile var stats = com.cyclone.mobile.mind.modes.RunRecord.Moves()
        private set

    private fun <T> read(block: () -> T): T {
        val from = now()
        try { return block() } finally { val ms = now() - from; stats = stats.copy(reads = stats.reads + 1, readMs = stats.readMs + ms) }
    }

    /** The run's last moves and whether each was proven, for the fused call (plan 72). */
    private val moves = ArrayDeque<String>()

    /** How many deciding calls (fused-board asks) this engine has made; each ask is one call per model. */
    var asks = 0
        private set
    /** The last step's postcondition when code couldn't settle it; the next fused call asks it as VERIFY. */
    var pendingVerify: Pair<Step, Expect>? = null
        private set
    private var calls = 0

    fun run(step: Step, score: Score): StepOutcome = run(step, score, popups = 0)

    private fun run(step: Step, score: Score, popups: Int): StepOutcome {
        val screen = takeObserved() ?: read { eyes.look(false) } ?: return StepOutcome.NeedsMind(step, "unreadable", "The screen could not be read")
        if (screen.sensitive) return StepOutcome.NeedsMind(step, "sensitive", "A sensitive screen (password, code or a banking app): the crew stays off it")

        // Rung 0: direct actions need no screen decision.
        ToolSteps.direct(step, score)?.let { call ->
            return when (call) {
                is ToolSteps.Call.Missing -> StepOutcome.NeedsMind(step, "missing_fact", "The step needs ${call.what}, which hasn't been found")
                is ToolSteps.Call.Ready ->
                    if (call.name in ToolSteps.BARRIER_TOOLS || step.risk != Risk.UNDOABLE) StepOutcome.AtBarrier(step, null, call.name)
                    else act(step, call.name, call.args, screen, score, rung = 0, label = null)
            }
        }

        // Rung 0: a target that matches exactly one control (typing always goes past the tripwire questions).
        codeTarget(step, screen)?.let { control ->
            val risk = riskOf(step, control.label)
            if (pendingVerify == null && step.branches.isEmpty() && step.action !in TRIPWIRE_ACTIONS) {
                return if (risk == Risk.UNDOABLE) actOnRef(step, control, screen, score, rung = 0)
                else StepOutcome.AtBarrier(step, control.ref, control.label)
            }
        }

        // Rung 1: one fused call per model, with the screenshot for the model that sees (plan 72). The screen is read
        // again with the image, and the decision is made on that reading, so refs and image match.
        val tripwire = step.risk != Risk.UNDOABLE || step.action in TRIPWIRE_ACTIONS
        val fresh = runCatching { read { eyes.look(true) } }.getOrNull()
        if (fresh?.sensitive == true) return StepOutcome.NeedsMind(step, "sensitive", "A sensitive screen (password, code or a banking app): the crew stays off it")
        val seen = fresh ?: screen
        val image = seen.imageDataUrl != null
        val reading = ask(step, seen, score, tripwire, image = image)
        val r = reading ?: return StepOutcome.NeedsMind(step, "no_answer", "The decision models gave no usable answer")
        return decide(step, r, seen, score, if (image) 2 else 1, tripwire, popups)
    }

    /** After a review at a barrier: carry out the move the step stopped at. */
    fun actAt(barrier: StepOutcome.AtBarrier, score: Score): StepOutcome {
        val screen = eyes.look(false) ?: return StepOutcome.NeedsMind(barrier.step, "unreadable", "The screen could not be read")
        if (barrier.ref == null) {
            return when (val call = ToolSteps.direct(barrier.step, score)) {
                is ToolSteps.Call.Ready -> act(barrier.step, call.name, call.args, screen, score, rung = 0, label = null)
                is ToolSteps.Call.Missing -> StepOutcome.NeedsMind(barrier.step, "missing_fact", "The step needs ${call.what}, which hasn't been found")
                null -> StepOutcome.NeedsMind(barrier.step, "stale", "The step has nothing to act on")
            }
        }
        val control = barrier.ref.let { ref -> screen.controls.firstOrNull { it.ref == ref } }
            ?: return StepOutcome.NeedsMind(barrier.step, "stale", "The control the step stopped at is gone")
        return actOnRef(barrier.step, control, screen, score, rung = 1, gated = true)
    }

    // ---- deciding ---------------------------------------------------------------------------------------------------

    private fun decide(step: Step, r: FusedBoard.Reading, screen: StepScreen, score: Score, rung: Int, tripwire: Boolean, popups: Int): StepOutcome {
        // What the last step should have done comes first: a false postcondition is a surprise for the mind.
        pendingVerify?.let { (prev, expect) ->
            val held = r.verified
            if (held != null) {
                pendingVerify = null
                if (held < FusedBoard.YES) return StepOutcome.PreviousMissed(prev, expect)
            }
        }
        r.blockedBy?.takeIf { it != "none" }?.let { return StepOutcome.Blocked(step, it) }
        r.popup?.takeIf { it != "none" }?.let { kind ->
            val dismiss = r.popupDismiss
            val allowed = kind in AUTO_DISMISS || score.orders.any { it.kind == StandingOrder.Kind.DISMISS_POPUPS }
            if (dismiss != null && allowed && popups < MAX_POPUPS_PER_STEP) {
                screen.controls.firstOrNull { it.ref == dismiss }?.let { c ->
                    if (!com.cyclone.mobile.mind.pilot.Pilot.irreversible(c.label)) {
                        record("popup", "Closed a ${kind.replace('_', ' ')} (\"${c.label}\")")
                        hands.execute(MindToolCall(id(), "tap", JSONObject().put("ref", c.ref).toString()), JSONObject().put("ref", c.ref))
                        return run(step, score, popups + 1)
                    }
                }
            }
            return StepOutcome.NeedsMind(step, "popup", "An interruption (${kind.replace('_', ' ')}) is covering the app")
        }
        r.ordersFired.firstOrNull()?.let { return StepOutcome.OrderFired(step, it) }
        r.branchesFired.minOrNull()?.let { i -> step.branches.getOrNull(i)?.let { b -> return StepOutcome.Branched(step, b.then, b.condition) } }
        if ((r.surprise ?: 0.0) >= FusedBoard.SURE) return StepOutcome.NeedsMind(step, "surprise", "The screen is not what the plan expected")
        if ((r.fitsPlan ?: 1.0) < 1 - FusedBoard.SURE) return StepOutcome.NeedsMind(step, "drift", "The screen doesn't fit the step")
        if ((r.divergence ?: 0) >= 2) return StepOutcome.NeedsMind(step, "divergence", "The run looks ${FusedBoard.DIVERGENCE[r.divergence!!].replace('_', ' ')}")

        return when (val next = r.next) {
            null, FusedBoard.NEEDS_MIND -> StepOutcome.NeedsMind(step, "unsure", "The decision models weren't sure which control carries out the step" +
                (r.doubt?.let { ": $it" }.orEmpty()))
            FusedBoard.STEP_DONE -> {
                val verdict = step.expect?.let { ExpectCheck.check(it, screen, null, score.facts, settings) } ?: Verdict.UNKNOWN
                if (verdict == Verdict.MISSED) StepOutcome.NeedsMind(step, "unsure", "Said done, but the step's check doesn't hold")
                else StepOutcome.Done(step, verdict, rung, r.divergence ?: 0)
            }
            FusedBoard.SCROLL_DOWN, FusedBoard.SCROLL_UP ->
                act(step, "scroll", JSONObject().put("direction", if (next == FusedBoard.SCROLL_DOWN) "down" else "up"), screen, score, rung, null, retryStep = true)
            FusedBoard.BACK -> act(step, "back", JSONObject(), screen, score, rung, null, retryStep = true)
            FusedBoard.WAIT -> act(step, "wait", JSONObject().put("seconds", 2), screen, score, rung, null, retryStep = true)
            else -> {
                val control = screen.controls.firstOrNull { it.ref == next }
                    ?: return StepOutcome.NeedsMind(step, "unsure", "The pick isn't on the screen any more")
                if (r.nextSure < FusedBoard.ACT_SURE) return StepOutcome.NeedsMind(step, "unsure", "Not sure enough which control to use")
                val unplanned = r.risks.filterValues { it >= FusedBoard.RISK_BAR }.keys
                val risk = riskOf(step, control.label)
                when {
                    risk == Risk.UNDOABLE && unplanned.isNotEmpty() ->
                        StepOutcome.NeedsMind(step, "tripwire", "This move may ${unplanned.joinToString(", ") { it.replace('_', ' ') }}, which the plan didn't expect")
                    risk != Risk.UNDOABLE && tripwire && (r.matchesRequest ?: 0.0) < FusedBoard.YES ->
                        StepOutcome.NeedsMind(step, "tripwire", "This move may not be what the goal asked for")
                    risk != Risk.UNDOABLE -> StepOutcome.AtBarrier(step, control.ref, control.label)
                    else -> actOnRef(step, control, screen, score, rung)
                }
            }
        }
    }

    // ---- acting -----------------------------------------------------------------------------------------------------

    private fun actOnRef(step: Step, control: StepControl, screen: StepScreen, score: Score, rung: Int, gated: Boolean = false): StepOutcome {
        val args = JSONObject().put("ref", control.ref)
        val name = when (step.action) {
            "type_text" -> {
                val text = ToolSteps.text(step, score) ?: return StepOutcome.NeedsMind(step, "missing_fact", "The step needs a value that hasn't been found")
                args.put("text", text)
                if (step.args["press_enter"] == "true") args.put("press_enter", true)
                "type_text"
            }
            "long_press", "double_tap", "press_enter" -> step.action
            "scroll" -> { args.put("direction", step.args["direction"] ?: "down"); "scroll" }
            else -> "tap"
        }
        if (!gated && riskOf(step, control.label) != Risk.UNDOABLE) return StepOutcome.AtBarrier(step, control.ref, control.label)
        return act(step, name, args, screen, score, rung, control.label)
    }

    private fun act(step: Step, name: String, args: JSONObject, before: StepScreen, score: Score, rung: Int, label: String?,
                    retryStep: Boolean = false): StepOutcome {
        val acting = now()
        val result: MindToolResult = runCatching { hands.execute(MindToolCall(id(), name, args.toString()), args) }
            .getOrElse { MindToolResult.error(it.message ?: "the action failed") }
        stats = stats.copy(actMs = stats.actMs + (now() - acting), byCode = stats.byCode + if (rung == 0) 1 else 0)
        record("act", "${describe(name, label, args)} (rung $rung)" + if (result.ok) "" else " — failed: ${result.brief}")
        // A tool step can keep its result as a fact (plan 69 §4.6: "as" names it); the value is the tool's own text.
        if (result.ok) step.args["as"]?.takeIf { FACT_NAME.matches(it) }?.let { record("fact:$it", result.text.take(Score.MAX_FACT)) }
        val after = read { eyes.look(false) }
        observed = after?.let { it to now() }
        val key = "$name:${label ?: args.optString("app", args.optString("url", ""))}"
        if (!result.ok) {
            return when (val call = breaker.action(key, before.pageKey, progressed = false)) {
                is Breaker.Call.Stop, is Breaker.Call.CheckIn -> StepOutcome.Halt(step, call)
                else -> StepOutcome.Missed(step, result.brief)
            }
        }
        if (retryStep) {
            // A scroll, Back or wait is a move towards the step, not the step: decide again on the new screen.
            return when (val call = breaker.action(key, before.pageKey, progressed = after?.fingerprint != before.fingerprint)) {
                is Breaker.Call.Stop, is Breaker.Call.CheckIn -> StepOutcome.Halt(step, call)
                Breaker.Call.Climb -> StepOutcome.NeedsMind(step, "stuck", "Moving around didn't reach the step's control")
                else -> run(step, score)
            }
        }
        // Alpha 143: a step the plan gave no check still has to show it happened: an opened app is the one in front, a
        // tap changed the screen. "The tool said ok" is not proof.
        val expect = step.expect ?: implied(name, args)
        val verdict = when {
            after == null -> Verdict.UNKNOWN
            // Alpha 144: "a tap is the kind of tool that changes screens" is not proof; what the phone showed is.
            expect == Expect.Changed && step.expect == null && result.screenProof != null -> Verdict.HELD
            expect != null -> ExpectCheck.check(expect, after, before, score.facts, settings)
            result.changedScreen -> Verdict.HELD
            else -> Verdict.UNKNOWN
        }
        when (val call = breaker.action(key, before.pageKey, progressed = verdict == Verdict.HELD || (expect == null && result.changedScreen))) {
            is Breaker.Call.Stop, is Breaker.Call.CheckIn -> return StepOutcome.Halt(step, call)
            else -> Unit
        }
        remember(describe(name, label, args), verdict, after)
        // Plan 72: a proven tap teaches this app's memory; a tap that missed counts against what memory said.
        if (label != null && name in LEARNED_ACTIONS && after != null) runCatching {
            when (verdict) {
                Verdict.HELD -> memory?.proven(before, label, after)
                Verdict.MISSED -> memory?.failed(before, label)
                Verdict.UNKNOWN -> Unit
            }
        }
        return when (verdict) {
            Verdict.MISSED -> StepOutcome.Missed(step, "Expected ${ExpectCheck.describe(expect!!)}, and it isn't so")
            Verdict.UNKNOWN -> { pendingVerify = expect?.let { step to it }; breaker.stepDone(); StepOutcome.Done(step, verdict, rung) }
            Verdict.HELD -> { breaker.stepDone(); StepOutcome.Done(step, verdict, rung) }
        }
    }

    /**
     * Plan 75 alpha 146: the screen read right after the last move is the screen the next move starts from, so it is used
     * once instead of being read again (a read is a few hundred ms). Only while it is fresh: a move waits at most
     * [OBSERVED_MAX_AGE_MS] for the decision, and anything older is read again.
     */
    private var observed: Pair<StepScreen, Long>? = null

    private fun takeObserved(): StepScreen? {
        val (screen, at) = observed ?: return null
        observed = null
        if (now() - at > OBSERVED_MAX_AGE_MS) return null
        stats = stats.copy(reused = stats.reused + 1)
        return screen
    }

    /**
     * Plan 75 alpha 146 (R1.5): the models split on an easy-to-undo move. One short follow-up asks only the disputed
     * controls, with the same screenshot, and its pick is used only when it is clean and sure. The other answers of the
     * first call (orders, plan fit, risks) are kept; only the pick changes. Anything else is no settle: the mind decides.
     */
    private fun settle(combined: FusedBoard.Reading, ask: FusedBoard.Ask, readings: List<FusedBoard.Reading>): FusedBoard.Reading? {
        val picks = readings.mapNotNull { it.next }.filter { !FusedBoard.isVerb(it) && it != FusedBoard.NEEDS_MIND }.distinct()
        val disputed = ask.screen.controls.filter { it.ref in picks }
        if (picks.size < 2 || disputed.size < 2) return null
        val narrow = ask.copy(candidates = disputed)
        stats = stats.copy(settles = stats.settles + 1)
        val follow = FusedBoard.combine(runCatching { board.ask(FusedBoard.request(narrow), narrow) }.getOrDefault(emptyList()), undoable = true)
            ?: return null
        val pick = follow.next ?: return null
        if (FusedBoard.isVerb(pick) || pick == FusedBoard.NEEDS_MIND) return null
        if (follow.nextSure < SETTLE_SURE || follow.doubt != null || disputed.none { it.ref == pick }) return null
        stats = stats.copy(settled = stats.settled + 1)
        return combined.copy(next = pick, nextSure = follow.nextSure, doubt = null)
    }

    private fun ask(step: Step, screen: StepScreen, score: Score, tripwire: Boolean, image: Boolean): FusedBoard.Reading? {
        val milestone = score.plan?.milestones?.firstOrNull { m -> m.steps.any { it.id == step.id } }?.title.orEmpty()
        val known = runCatching { memory?.known(screen) }.getOrNull().orEmpty()
        val ask = FusedBoard.Ask(score.goal.text, milestone, step, screen, FusedBoard.shortlist(screen, step), pendingVerify?.second,
            score.orders, tripwire, image, history = moves.toList(), known = known)
        asks++
        val deciding = now()
        val readings = runCatching { board.ask(FusedBoard.request(ask), ask) }.getOrDefault(emptyList())
        val pickRisk = readings.firstNotNullOfOrNull { r -> screen.controls.firstOrNull { it.ref == r.next } }?.let { riskOf(step, it.label) } ?: step.risk
        val undoable = pickRisk == Risk.UNDOABLE
        val combined = FusedBoard.combine(readings, undoable = undoable)
        val split = readings.size > 1 && readings.map { it.next }.distinct().size > 1
        stats = stats.copy(asked = stats.asked + 1, decideMs = stats.decideMs + (now() - deciding),
            noAnswer = stats.noAnswer + if (readings.isEmpty()) 1 else 0,
            splits = stats.splits + if (split) 1 else 0,
            doubts = stats.doubts + if (readings.any { it.doubt != null }) 1 else 0)
        // Only an easy-to-undo move without a tripwire may be settled by a follow-up; the strict rule stays for the rest.
        val reading = combined ?: return null
        if (split && reading.next == FusedBoard.NEEDS_MIND && undoable && !tripwire) settle(reading, ask, readings)?.let { return it }
        return reading
    }

    // ---- code decisions ---------------------------------------------------------------------------------------------

    /** One line per move for the next calls: what was done and whether the phone proved it. */
    private fun remember(what: String, verdict: Verdict, after: StepScreen?) {
        val proof = when (verdict) {
            Verdict.HELD -> "proven"
            Verdict.MISSED -> "didn't happen"
            Verdict.UNKNOWN -> "not proven yet"
        }
        moves.addLast("$what → $proof" + (after?.let { " (now: ${it.appLabel}${it.pageKey.takeIf(String::isNotBlank)?.let { k -> ", page $k" }.orEmpty()})" }.orEmpty()))
        while (moves.size > FusedBoard.MAX_HISTORY) moves.removeFirst()
    }

    /** Alpha 143: the check a step implies when the plan wrote none. Null when the tool's own result is the proof. */
    private fun implied(name: String, args: JSONObject): Expect? = when (name) {
        "open_app" -> args.optString("app").trim().takeIf { it.isNotBlank() && it != "null" }?.let { Expect.AppIs(it) }
        "tap", "long_press", "double_tap" -> Expect.Changed
        else -> null
    }

    /** The one control whose label is the step's target, or contains it when no other control does. */
    private fun codeTarget(step: Step, screen: StepScreen): StepControl? {
        val target = step.target?.let(InstantGrammar::normalize)?.takeIf { it.isNotBlank() } ?: return null
        val exact = screen.controls.filter { InstantGrammar.normalize(it.label) == target }
        if (exact.size == 1) return exact.single()
        if (exact.size > 1) return null
        return screen.controls.filter { InstantGrammar.normalize(it.label).contains(target) }.singleOrNull()
    }

    /** The plan's risk, raised by code when the control's own words are irreversible. Never lowered. */
    private fun riskOf(step: Step, label: String): Risk =
        if (com.cyclone.mobile.mind.pilot.Pilot.irreversible(label)) step.risk.atLeast(Risk.GATED) else step.risk

    private fun describe(name: String, label: String?, args: JSONObject): String = when (name) {
        "tap" -> "Tapped \"${label.orEmpty()}\""
        "type_text" -> "Typed into \"${label.orEmpty()}\""
        "scroll" -> "Scrolled ${args.optString("direction", "down")}"
        "back" -> "Pressed Back"
        "home" -> "Went Home"
        "wait" -> "Waited"
        "open_app" -> "Opened ${args.optString("app")}"
        "open_link" -> "Opened a link"
        "open_settings" -> "Opened Settings ${args.optString("page")}"
        else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() } + (label?.let { " \"$it\"" } ?: "")
    }

    private fun id() = "one-${++calls}"

    companion object {
        /** Interruptions the crew may close by itself; anything else asks the mind. */
        val AUTO_DISMISS = setOf("cookie_banner", "rating_prompt", "update_prompt", "ad")
        /** Actions that always carry the risk questions, whatever the plan says. */
        val TRIPWIRE_ACTIONS = setOf("type_text", "press_enter")
        /** Moves the app memory learns from: taps on a control, never typing. */
        val LEARNED_ACTIONS = setOf("tap", "long_press", "double_tap")
        const val MAX_POPUPS_PER_STEP = 2
        /** Plan 75 alpha 146: how sure the follow-up must be to settle a split on an easy-to-undo move. */
        const val SETTLE_SURE = 0.8
        /** Plan 75 alpha 146: how old a read after a move may be and still start the next move. */
        const val OBSERVED_MAX_AGE_MS = 2_000L
        private val FACT_NAME = Regex("^[a-z][a-z0-9_]{0,40}$")
    }
}
