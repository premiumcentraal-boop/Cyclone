package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.runtime.background.SemanticStepState
import com.cyclone.mobile.runtime.background.TaskPresentationMilestone
import com.cyclone.mobile.runtime.plane.PlaneUi
import com.cyclone.mobile.ui.v32.CycloneTaskVisualState

/** Plan 27: the words on the redesigned overlay, kept apart from the composables so they are unit-testable. */
object OverlayGlassCopy {
    fun chip(state: CycloneTaskVisualState): String = when (state) {
        CycloneTaskVisualState.WORKING -> "Working"
        CycloneTaskVisualState.ACTION_NEEDED -> "Needs you"
        CycloneTaskVisualState.DONE -> "Done"
        CycloneTaskVisualState.FAILED -> "Couldn't finish"
    }

    /** The plane pill says where Cyclone works, in one word. */
    fun planeWord(look: PlanePillLook): String = when (look) {
        PlanePillLook.SCREEN, PlanePillLook.UNAVAILABLE -> "Screen"
        PlanePillLook.BACKGROUND -> "Background"
        PlanePillLook.SWITCHING -> "Moving"
        PlanePillLook.WAITING -> "Waiting"
    }

    /**
     * An approval puts what will be sent first. When the request quotes the text ("Send “…” to Sam"), the quote is the
     * message and the rest is the line above it; otherwise the whole request is the message under the moment's title.
     */
    fun approval(text: String, title: String): Pair<String, String> {
        val quote = QUOTE.find(text)
        val body = quote?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.trim()
        if (quote != null && body != null && body.length >= 4) {
            val context = text.removeRange(quote.range).replace(Regex("\\s{2,}"), " ").replace(" ?", "?").trim()
                .trimEnd(':').trim()
            return (context.ifBlank { title }) to body
        }
        return title to text.trim()
    }

    /** The steps the card shows: the last few finished ones and the current one, oldest first. */
    fun steps(milestones: List<TaskPresentationMilestone>, max: Int = 3): List<TaskPresentationMilestone> {
        val active = milestones.indexOfLast { it.state == SemanticStepState.ACTIVE || it.state == SemanticStepState.ACTION_NEEDED }
        val end = if (active >= 0) active + 1 else milestones.indexOfLast { it.state != SemanticStepState.PENDING } + 1
        return milestones.subList(0, end.coerceIn(0, milestones.size)).takeLast(max)
    }

    /** "STEP 3 OF 8 · CALENDAR → WHATSAPP"; parts are left out when unknown. */
    fun meta(completed: Int, total: Int?, apps: List<String>): String = listOfNotNull(
        total?.takeIf { it > 0 }?.let { "Step ${(completed + 1).coerceAtMost(it)} of $it" },
        apps.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(" → "),
    ).joinToString(" · ").uppercase()

    /**
     * The island's two lines: what Cyclone does now, and the task with its progress. Plan 70: a title that only names
     * Cyclone ("Task in Cyclone Mind") says nothing, so the second line is then just the progress.
     */
    fun island(current: String?, title: String, completed: Int, total: Int?): Pair<String, String> {
        val generic = title.isBlank() || GENERIC_TITLE in title
        val now = current?.let(::label)?.takeIf { it.isNotEmpty() } ?: if (generic) "Working" else title
        val count = total?.takeIf { it > 0 }?.let { "${(completed + 1).coerceAtMost(it)} of $it" }
        return now to when {
            !generic -> title + (count?.let { " · $it" }.orEmpty())
            count != null -> count
            else -> "Cyclone"
        }
    }

    private const val GENERIC_TITLE = "Cyclone Mind"

    /** A step's words for the glass: without the marks a planner adds ("✓ ", "✗ ", "↳ "), which the row already draws. */
    fun label(text: String): String {
        var t = text.trim()
        while (t.isNotEmpty() && t[0] in MARKS) t = t.drop(1).trim()
        return t
    }

    private const val MARKS = "✓✗↳•·"


    /** The outcome line beside the pill when a move did not happen (never silent). */
    fun outcome(ui: PlaneUi): String? = ui.outcome?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The confirmation gate's title: what Cyclone stopped before, and where. [app] is the app's name (null for an
     * action outside any app). The owner's control words and the model's reason are shown apart.
     */
    fun gate(gateClass: OverlayGateClass, app: String?): GateWords {
        val where = app?.let { " in $it" }.orEmpty()
        return GateWords(when (gateClass) {
            OverlayGateClass.SEND -> "Send a message$where"
            OverlayGateClass.PAY -> "Make a payment$where"
            OverlayGateClass.DELETE -> "Delete something$where"
            OverlayGateClass.GRANT -> if (app != null) "Give $app access" else "Give access"
        })
    }

    data class GateWords(val title: String)

    private val QUOTE = Regex("[“\"]([^”\"]+)[”\"]")
}
