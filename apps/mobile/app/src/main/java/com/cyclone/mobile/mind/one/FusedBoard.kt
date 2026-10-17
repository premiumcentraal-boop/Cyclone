package com.cyclone.mobile.mind.one

import com.cyclone.mobile.decisions.DPart
import com.cyclone.mobile.decisions.DQuestion
import com.cyclone.mobile.decisions.DReply

/**
 * Plan 69 §4.2: one Decisions call per step. The Decisions API answers up to 200 questions in parallel against one
 * state, so everything a step needs is asked at once: which control carries out the step, whether the screen fits the
 * plan, whether the last step did what it should, popups, blockers, how far off course the run looks, the mind's
 * standing orders, the plan's branch conditions and, before anything not easy to undo, the risk questions.
 *
 * Decision models only pick and judge: they never count, compare numbers or write words (plan 66 §10.6). The state is
 * kept small (≤ 25 controls, ≤ 40 text lines) so JEV stays in its fast mode. Pure.
 */
object FusedBoard {
    const val MAX_CONTROLS = 25
    const val MAX_TEXT_LINES = 40
    const val MAX_DONE_CONTROLS = 30

    /** Verbs a step can answer besides a control. */
    const val STEP_DONE = "step_done"
    const val SCROLL_DOWN = "scroll_down"
    const val SCROLL_UP = "scroll_up"
    const val BACK = "back"
    const val WAIT = "wait"
    const val NEEDS_MIND = "needs_mind"
    private val VERBS = listOf(STEP_DONE, SCROLL_DOWN, SCROLL_UP, BACK, WAIT, NEEDS_MIND)

    val BLOCKERS = listOf("none", "login", "permission", "error", "paywall", "dialog")
    val POPUPS = listOf("none", "cookie_banner", "rating_prompt", "permission_prompt", "update_prompt", "ad", "other")
    val DIVERGENCE = listOf("on_course", "minor_doubt", "likely_off", "off_course")
    val RISKS = listOf("sends_or_posts", "money", "destroys", "shares_personal", "account")

    data class Ask(
        val goal: String,
        val milestone: String,
        val step: Step,
        val screen: StepScreen,
        /** Code's shortlist of controls worth choosing from (screen order kept). */
        val candidates: List<StepControl>,
        /** The previous step's postcondition, when code couldn't settle it. */
        val verify: Expect? = null,
        val orders: List<StandingOrder> = emptyList(),
        /** True before a move that isn't easy to undo: the risk questions ride along. */
        val tripwire: Boolean = false,
        val withImage: Boolean = false,
    )

    data class Request(val state: List<DPart>, val questions: Map<String, DQuestion>, val refs: Map<String, String>)

    /** One provider's reading of the step. Nullable parts were not asked or not answered. */
    data class Reading(
        val next: String?,
        val nextSure: Double,
        val fitsPlan: Double?,
        val surprise: Double?,
        val verified: Double?,
        val blockedBy: String?,
        val popup: String?,
        val popupDismiss: String?,
        val divergence: Int?,
        val ordersFired: Set<String>,
        val branchesFired: Set<Int>,
        val risks: Map<String, Double>,
        val matchesRequest: Double?,
    )

    // ---- building ---------------------------------------------------------------------------------------------------

    fun request(ask: Ask): Request {
        val controls = ask.candidates.take(MAX_CONTROLS)
        val refs = controls.associate { it.ref to it.ref }
        val stepLine = "${ask.step.action}" + (ask.step.target?.let { " · $it" } ?: "")
        val state = buildList {
            add(DPart.Text("Goal: ${ask.goal}"))
            add(DPart.Text("Milestone: ${ask.milestone}\nThis step: $stepLine"))
            add(DPart.Text("Screen: ${ask.screen.appLabel}\n" + ask.screen.texts.take(MAX_TEXT_LINES).joinToString("\n")))
            add(DPart.Text("Controls:\n" + controls.joinToString("\n") { c ->
                "${c.ref}: ${c.role.ifBlank { "control" }} \"${c.label}\"" + (if (c.focused) " (focused)" else "") + (c.value?.let { " = \"${it.take(40)}\"" } ?: "")
            }))
            if (ask.withImage) ask.screen.imageDataUrl?.let { add(DPart.Image(it)) }
        }
        val questions = linkedMapOf<String, DQuestion>()
        questions["next"] = DQuestion.Choice(
            "Which one carries out this step on this screen? Pick the control, or: $STEP_DONE if the step is already done, " +
                "$SCROLL_DOWN / $SCROLL_UP if the control is off screen, $BACK if this is the wrong screen, $WAIT if it is loading, " +
                "$NEEDS_MIND if it needs judgement.",
            controls.associate { c -> c.ref to "${c.role} \"${c.label}\"" } + VERBS.associateWith { "" },
        )
        questions["fits_plan"] = DQuestion.Noul("Does this screen fit the step the plan is on?", "it fits", "it doesn't fit")
        questions["surprise"] = DQuestion.Noul("Is this screen something the plan did not expect at this point?", "unexpected", "as expected")
        ask.verify?.let { e ->
            questions["verify"] = DQuestion.Noul("After the last step, is this true now: ${ExpectCheck.describe(e)}?", "true", "not true")
        }
        questions["blocked_by"] = DQuestion.choice("Is something blocking the work on this screen?", BLOCKERS)
        questions["popup"] = DQuestion.choice("Is an interruption covering the app (a banner, prompt or ad)?", POPUPS)
        questions["popup_dismiss"] = DQuestion.Choice("If there is an interruption, which control closes it without accepting anything?",
            controls.associate { c -> c.ref to "${c.role} \"${c.label}\"" } + ("none" to ""))
        questions["divergence"] = DQuestion.Score("How is the run doing against this milestone?", DIVERGENCE)
        StandingOrders.questions(ask.orders).forEach { o ->
            questions["order_${o.id}"] = DQuestion.Noul(o.question!!, "yes", "no")
        }
        ask.step.branches.forEachIndexed { i, b -> questions["branch_$i"] = DQuestion.Noul("Is this true now: ${b.condition}?", "true", "not true") }
        if (ask.tripwire) {
            questions["sends_or_posts"] = DQuestion.Noul("Would the chosen control send, post or share something to other people?", "yes", "no")
            questions["money"] = DQuestion.Noul("Would it pay, buy, subscribe or move money?", "yes", "no")
            questions["destroys"] = DQuestion.Noul("Would it delete, remove or overwrite something?", "yes", "no")
            questions["shares_personal"] = DQuestion.Noul("Would it share personal data (address, phone, ID, health, location)?", "yes", "no")
            questions["account"] = DQuestion.Noul("Would it change an account, a password or a permission?", "yes", "no")
            questions["matches_request"] = DQuestion.Noul("Is this exactly what the goal asked for?", "yes", "no")
        }
        return Request(state, questions, refs)
    }

    // ---- reading ----------------------------------------------------------------------------------------------------

    fun read(reply: DReply, ask: Ask): Reading {
        val next = reply.choice("next")
        return Reading(
            next = next?.choice,
            nextSure = next?.confidence ?: 0.0,
            fitsPlan = reply.noul("fits_plan")?.yes,
            surprise = reply.noul("surprise")?.yes,
            verified = if (ask.verify != null) reply.noul("verify")?.yes else null,
            blockedBy = reply.choice("blocked_by")?.takeIf { it.confidence >= 0.5 }?.choice,
            popup = reply.choice("popup")?.takeIf { it.confidence >= 0.5 }?.choice,
            popupDismiss = reply.choice("popup_dismiss")?.takeIf { it.confidence >= 0.7 }?.choice?.takeIf { it != "none" },
            divergence = reply.score("divergence")?.score?.let { Math.round(it).toInt().coerceIn(0, DIVERGENCE.lastIndex) },
            ordersFired = ask.orders.filter { (reply.noul("order_${it.id}")?.yes ?: 0.0) >= YES }.map { it.id }.toSet(),
            branchesFired = ask.step.branches.indices.filter { (reply.noul("branch_$it")?.yes ?: 0.0) >= SURE }.toSet(),
            risks = if (ask.tripwire) RISKS.associateWith { reply.noul(it)?.yes ?: 0.0 } else emptyMap(),
            matchesRequest = if (ask.tripwire) reply.noul("matches_request")?.yes else null,
        )
    }

    /**
     * Two (or more) providers' readings as one, always on the careful side: an action needs every reader to pick it
     * (one reader is enough only for an easy-to-undo move); a check passes only if every reader passes it; a warning,
     * an order or a risk counts if any reader raises it; a branch (which skips work) needs every reader.
     */
    fun combine(readings: List<Reading>, undoable: Boolean): Reading? {
        if (readings.isEmpty()) return null
        if (readings.size == 1) {
            val r = readings.single()
            return if (undoable) r else r.copy(next = r.next?.takeIf { it in VERBS && it != STEP_DONE } ?: NEEDS_MIND.takeIf { r.next != null }, branchesFired = emptySet())
        }
        val picks = readings.map { it.next }
        val agreed = picks.distinct().singleOrNull()
        fun minOf(f: (Reading) -> Double?) = readings.mapNotNull(f).minOrNull()
        fun maxOf(f: (Reading) -> Double?) = readings.mapNotNull(f).maxOrNull()
        return Reading(
            next = agreed ?: NEEDS_MIND,
            nextSure = if (agreed != null) readings.minOf { it.nextSure } else 0.0,
            fitsPlan = minOf { it.fitsPlan },
            surprise = maxOf { it.surprise },
            verified = minOf { it.verified },
            blockedBy = readings.mapNotNull { it.blockedBy }.firstOrNull { it != "none" } ?: readings.firstNotNullOfOrNull { it.blockedBy },
            popup = readings.mapNotNull { it.popup }.firstOrNull { it != "none" } ?: readings.firstNotNullOfOrNull { it.popup },
            popupDismiss = readings.map { it.popupDismiss }.distinct().singleOrNull(),
            divergence = readings.mapNotNull { it.divergence }.maxOrNull(),
            ordersFired = readings.flatMap { it.ordersFired }.toSet(),
            branchesFired = readings.map { it.branchesFired }.reduce { a, b -> a intersect b },
            risks = RISKS.associateWith { k -> readings.maxOf { it.risks[k] ?: 0.0 } }.filterValues { it > 0.0 },
            matchesRequest = minOf { it.matchesRequest },
        )
    }

    // ---- the done check --------------------------------------------------------------------------------------------

    /**
     * Plan 69 §4.4 DONE: a separate call whenever a finish is claimed. It only judges what the screen and the facts
     * show; code's own done predicate is checked first and a model can never turn a code miss into done.
     */
    fun doneRequest(goal: String, finished: List<String>, facts: List<Fact>, screen: StepScreen?): Request {
        val state = buildList {
            add(DPart.Text("Goal: $goal"))
            if (finished.isNotEmpty()) add(DPart.Text("Milestones finished:\n" + finished.joinToString("\n") { "- $it" }))
            if (facts.isNotEmpty()) add(DPart.Text("Facts found:\n" + facts.takeLast(20).joinToString("\n") { "- ${it.name} = ${it.value.take(80)}" }))
            // Alpha 143: the controls too ("Edit profile", a selected tab): a page is often told apart by them, not its text.
            screen?.let { s ->
                val controls = s.controls.map { it.label }.filter { it.isNotBlank() }.distinct().take(MAX_DONE_CONTROLS)
                add(DPart.Text("Screen now: ${s.appLabel}\n" + s.texts.take(MAX_TEXT_LINES).joinToString("\n") +
                    if (controls.isEmpty()) "" else "\nControls: " + controls.joinToString(", ")))
            }
        }
        val questions = linkedMapOf<String, DQuestion>(
            "done" to DQuestion.Noul("Has every part of the goal been achieved, as far as the screen and the facts show? An app " +
                "that is open on the wrong page (its home page when the goal names a page in it) is not achieved.", "fully achieved",
                "not (fully) achieved"),
        )
        return Request(state, questions, emptyMap())
    }

    /** The careful reading of the done check across providers: the lowest yes. Null when nobody answered. */
    fun doneRead(replies: List<DReply>): Double? = replies.mapNotNull { it.noul("done")?.yes }.minOrNull()

    /** A yes counts from here; a branch or a skip needs more. */
    const val YES = 0.5
    const val SURE = 0.85
    /** A risk flag at or above this stops an action for the mind (only ever towards care). */
    const val RISK_BAR = 0.3
    /** An action pick is acted on only this sure. */
    const val ACT_SURE = 0.7

    /**
     * Code's shortlist: controls whose label shares words with the step's target first, then the rest in screen order;
     * editable fields stay in for typing steps. Never more than [MAX_CONTROLS].
     */
    fun shortlist(screen: StepScreen, step: Step): List<StepControl> {
        val words = com.cyclone.mobile.mind.modes.InstantGrammar.normalize(step.target.orEmpty()).split(' ').filter { it.length > 2 }.toSet()
        fun score(c: StepControl): Int {
            val label = com.cyclone.mobile.mind.modes.InstantGrammar.normalize(c.label)
            return words.count { label.contains(it) } * 10 + (if (c.editable && step.action == "type_text") 5 else 0)
        }
        val ranked = screen.controls.withIndex().sortedWith(compareByDescending<IndexedValue<StepControl>> { score(it.value) }.thenBy { it.index })
        return ranked.take(MAX_CONTROLS).sortedBy { it.index }.map { it.value }
    }
}
