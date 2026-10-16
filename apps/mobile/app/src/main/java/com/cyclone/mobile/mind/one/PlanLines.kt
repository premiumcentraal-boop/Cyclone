package com.cyclone.mobile.mind.one

import org.json.JSONObject

/**
 * Plan 69 §14 checkpoint 2: a mind writes plans and wake replies as **one JSON object per line**, so a plan can stream
 * and the crew can start on its first step while the rest is still being written.
 *
 * ```
 * {"decision":"replan","why":"the app opened on the wrong account"}
 * {"milestone":"Open Anna's chat","review":true}
 * {"step":"open_app","args":{"app":"WhatsApp"},"expect":{"app":"WhatsApp"}}
 * {"step":"tap","target":"Anna de Vries","expect":{"shows":"Anna de Vries"},"if":[{"if":"Anna's chat is already open","then":"skip"}]}
 * {"step":"type_text","target":"Message","args":{"text":"{eta}"},"risk":"undoable"}
 * {"order":{"kind":"wake_if_over","text":"Wake me if the amount is over €400","fact":"amount","over":400}}
 * {"fact":"eta","value":"15 minutes"}
 * {"done":{"shows":"On my way"}}
 * ```
 *
 * Anything that isn't one of these lines (prose, code fences, broken JSON) is ignored, never a crash. Pure.
 */
object PlanLines {
    sealed interface Item {
        data class Decision(val reply: WakeReply.Kind, val json: JSONObject) : Item
        data class MilestoneLine(val title: String, val id: String?, val review: Boolean) : Item
        data class StepLine(val step: Step) : Item
        data class OrderLine(val order: StandingOrder) : Item
        data class FactLine(val name: String, val value: String) : Item
        data class DoneLine(val expect: Expect) : Item
    }

    /** One line, or null when it isn't a plan line. [nextStepId] names a step that came without an id. */
    fun parse(line: String, nextStepId: () -> String): Item? {
        val text = line.trim().removePrefix("-").trim().trimEnd(',')
        if (!text.startsWith("{") || !text.endsWith("}")) return null
        val j = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return when {
            j.has("decision") -> WakeReply.Kind.of(j.optString("decision"))?.let { Item.Decision(it, j) }
            j.has("milestone") -> j.optString("milestone").takeIf { it.isNotBlank() }
                ?.let { Item.MilestoneLine(it.take(160), j.optString("id").takeIf(String::isNotBlank), j.optBoolean("review")) }
            j.has("step") -> step(j, nextStepId)?.let(Item::StepLine)
            j.has("order") -> j.optJSONObject("order")?.let(StandingOrders::parse)?.let(Item::OrderLine)
            j.has("fact") -> {
                val name = j.optString("fact").trim()
                val value = j.optString("value")
                if (FACT_NAME.matches(name) && value.isNotBlank()) Item.FactLine(name, value) else null
            }
            j.has("done") -> j.optJSONObject("done")?.let(ExpectCodec::decode)?.let(Item::DoneLine)
            else -> null
        }
    }

    private fun step(j: JSONObject, nextStepId: () -> String): Step? {
        val action = j.optString("step").trim().lowercase()
        if (!ACTION.matches(action)) return null
        val args = j.optJSONObject("args")?.let { a -> a.keys().asSequence().associateWith { a.optString(it) } }.orEmpty()
        val slots = j.optJSONObject("slots")?.let { a -> a.keys().asSequence().associateWith { a.optString(it) } }.orEmpty()
        val branches = j.optJSONArray("if")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val b = a.optJSONObject(i) ?: return@mapNotNull null
                val then = when (b.optString("then").lowercase()) {
                    "skip" -> Branch.BranchAction.SKIP
                    "done" -> Branch.BranchAction.DONE
                    "next_milestone", "next" -> Branch.BranchAction.NEXT_MILESTONE
                    else -> return@mapNotNull null
                }
                b.optString("if").takeIf { it.isNotBlank() }?.let { Branch(it.take(200), then) }
            }
        }.orEmpty()
        val risk = when (j.optString("risk").lowercase()) {
            "gated" -> Risk.GATED
            "irreversible" -> Risk.IRREVERSIBLE
            else -> Risk.UNDOABLE
        }
        return Step(
            id = j.optString("id").takeIf { STEP_ID.matches(it) } ?: nextStepId(),
            action = action,
            target = j.optString("target").takeIf { it.isNotBlank() }?.take(160),
            args = args,
            expect = j.optJSONObject("expect")?.let(ExpectCodec::decode),
            risk = risk,
            slots = slots,
            branches = branches.take(MAX_BRANCHES),
            note = j.optString("note").takeIf { it.isNotBlank() }?.take(200),
        )
    }

    /**
     * Builds a plan line by line, so the crew can take steps while the mind still writes. Steps before any milestone
     * line go into a first milestone named after the goal.
     */
    class Draft(private val firstTitle: String, private val idPrefix: String = "") {
        private val milestones = mutableListOf<Milestone>()
        private var steps = 0
        val orders = mutableListOf<StandingOrder>()
        val facts = mutableListOf<Pair<String, String>>()
        var done: Expect? = null
            private set
        var decision: Item.Decision? = null
            private set

        fun nextStepId(): String = "${idPrefix}s${++steps}"

        /** Feeds one line; returns its item (null when it wasn't a plan line). */
        fun line(text: String): Item? = parse(text, ::nextStepId)?.also(::add)

        fun add(item: Item) {
            when (item) {
                is Item.Decision -> if (decision == null) decision = item
                is Item.MilestoneLine -> milestones += Milestone(item.id?.takeIf { id -> milestones.none { it.id == id } }
                    ?: "${idPrefix}m${milestones.size + 1}", item.title, emptyList(), review = item.review)
                is Item.StepLine -> {
                    if (milestones.isEmpty()) milestones += Milestone("${idPrefix}m1", firstTitle.take(160), emptyList())
                    val last = milestones.last()
                    if (last.steps.size < MAX_STEPS_PER_MILESTONE) milestones[milestones.size - 1] = last.copy(steps = last.steps + item.step)
                }
                is Item.OrderLine -> if (orders.size < Score.MAX_ORDERS) orders += item.order
                is Item.FactLine -> facts += item.name to item.value
                is Item.DoneLine -> done = item.expect
            }
        }

        val hasSteps: Boolean get() = milestones.any { it.steps.isNotEmpty() }

        /** The plan so far: milestones without steps are dropped. Null when there is no step yet. */
        fun plan(): Plan? = milestones.filter { it.steps.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { Plan(1, it, done) }
    }

    /**
     * Cuts streamed text into lines: [feed] takes each piece as it arrives and hands complete lines to [onLine];
     * [finish] hands over the last one.
     */
    class Stream(private val onLine: (String) -> Unit) {
        private val buffer = StringBuilder()

        @Synchronized fun feed(piece: String) {
            buffer.append(piece)
            while (true) {
                val end = buffer.indexOf("\n")
                if (end < 0) break
                val line = buffer.substring(0, end)
                buffer.delete(0, end + 1)
                if (line.isNotBlank()) onLine(line)
            }
        }

        @Synchronized fun finish() {
            val rest = buffer.toString()
            buffer.clear()
            if (rest.isNotBlank()) onLine(rest)
        }
    }

    /** Reads a whole reply at once. */
    fun read(text: String, firstTitle: String, idPrefix: String = ""): Draft = Draft(firstTitle, idPrefix).also { d ->
        text.lineSequence().forEach { d.line(it) }
    }

    private val ACTION = Regex("^[a-z][a-z0-9_]{1,40}$")
    private val STEP_ID = Regex("^[a-z0-9_.-]{1,24}$")
    private val FACT_NAME = Regex("^[a-z][a-z0-9_]{0,40}$")
    const val MAX_STEPS_PER_MILESTONE = 30
    const val MAX_BRANCHES = 4
}

/** What a mind decided at a wake (plan 69 §14 checkpoint 2: every wake reply is a typed decision). */
data class WakeReply(
    val kind: Kind,
    val why: String = "",
    /** New plan lines, for adjust, replan and detour. */
    val plan: Plan? = null,
    val orders: List<StandingOrder>? = null,
    val facts: List<Pair<String, String>> = emptyList(),
    /** For ask: the question, its choices, and whether it asks for consent (consent always reaches the owner). */
    val question: String? = null,
    val choices: List<String> = emptyList(),
    val consent: Boolean = false,
    /** For finish and give up: the mind's words for the owner. */
    val summary: String? = null,
    /** For recall: what to reopen from the folded history. */
    val recall: String? = null,
    /** The milestones the mind wants to look in on when they end. */
    val reviews: Set<String> = emptySet(),
) {
    enum class Kind {
        /** Go on as planned (standing orders may change). */
        CONTINUE,
        /** Change the rest of the current milestone. */
        ADJUST,
        /** Replace the remaining milestones (finished ones stay finished). */
        REPLAN,
        /** A side trip (a code, a login, a lookup) that returns to the paused milestone. */
        DETOUR,
        /** Skip the current step. */
        SKIP,
        /** The mind acts turn by turn itself. */
        WHEEL,
        ASK,
        FINISH,
        GIVE_UP,
        /** Flash only: this needs OneMind. */
        NEEDS_FRONTIER,
        /** Reopen something compaction folded. */
        RECALL;

        companion object {
            fun of(word: String): Kind? = when (word.trim().lowercase().replace('-', '_').replace(' ', '_')) {
                "continue", "go", "ok" -> CONTINUE
                "adjust" -> ADJUST
                "replan", "plan" -> REPLAN
                "detour" -> DETOUR
                "skip" -> SKIP
                "wheel", "take_the_wheel", "drive" -> WHEEL
                "ask", "ask_owner", "ask_the_owner" -> ASK
                "finish", "done" -> FINISH
                "give_up" -> GIVE_UP
                "needs_frontier", "hand_over", "handover" -> NEEDS_FRONTIER
                "recall" -> RECALL
                else -> null
            }
        }
    }

    companion object {
        /**
         * The typed decision in a reply. Without a decision line, plan lines mean a replan and nothing means continue.
         * An ask without a question, or a recall without a subject, reads as continue.
         */
        fun read(text: String, firstTitle: String, idPrefix: String = ""): WakeReply = from(PlanLines.read(text, firstTitle, idPrefix))

        fun from(draft: PlanLines.Draft): WakeReply {
            val d = draft.decision
            val j = d?.json ?: JSONObject()
            var kind = d?.reply ?: if (draft.hasSteps) Kind.REPLAN else Kind.CONTINUE
            val question = j.optString("question").takeIf { it.isNotBlank() }?.take(400)
            val recall = j.optString("what").ifBlank { j.optString("recall") }.takeIf { it.isNotBlank() }?.take(120)
            if (kind == Kind.ASK && question == null) kind = Kind.CONTINUE
            if (kind == Kind.RECALL && recall == null) kind = Kind.CONTINUE
            if (kind in setOf(Kind.ADJUST, Kind.REPLAN, Kind.DETOUR) && !draft.hasSteps) kind = Kind.CONTINUE
            val choices = j.optJSONArray("choices")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter(String::isNotBlank) }.orEmpty()
            val reviews = j.optJSONArray("review")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter(String::isNotBlank).toSet() }.orEmpty()
            return WakeReply(
                kind = kind,
                why = j.optString("why").take(300),
                plan = draft.plan(),
                orders = draft.orders.takeIf { it.isNotEmpty() }?.toList(),
                facts = draft.facts.toList(),
                question = question,
                choices = choices.take(6),
                consent = j.optBoolean("consent"),
                summary = j.optString("summary").takeIf { it.isNotBlank() }?.take(1_200),
                recall = recall,
                reviews = reviews,
            )
        }
    }
}
