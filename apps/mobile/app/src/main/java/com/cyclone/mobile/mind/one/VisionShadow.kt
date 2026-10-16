package com.cyclone.mobile.mind.one

import com.cyclone.mobile.decisions.DAnswer
import com.cyclone.mobile.decisions.DPart
import com.cyclone.mobile.decisions.DQuestion
import com.cyclone.mobile.decisions.DReply

/**
 * Plan 69 §4.5: JEV's Box ("Where?") and icons as answer options, **in shadow only**. When a step climbs to sight,
 * JEV is asked where the step's target is (Box + "is it there?" + how many), and the answer is recorded as an event
 * the Lab can compare with what the run really tapped. Nothing here ever taps, until TypeSafe calls image input and
 * Box production-ready. Pure.
 */
object VisionShadow {
    data class Request(val state: List<DPart>, val questions: Map<String, DQuestion>)

    data class Reading(val present: Double, val count: String?, val box: DAnswer.Box?) {
        /** What the run would have done, had the owner allowed it: a tap at the box centre, or nothing. */
        val wouldTap: Boolean get() = present >= 0.9 && count == "one" && box?.plausible == true
    }

    fun request(screen: StepScreen, step: Step): Request? {
        val image = screen.imageDataUrl ?: return null
        if (screen.sensitive) return null
        val target = step.target?.takeIf { it.isNotBlank() } ?: return null
        val state = listOf(DPart.Text("Screenshot of the phone:"), DPart.Image(image))
        return Request(state, linkedMapOf(
            "present" to DQuestion.Noul("Is $target visible in the screenshot?", "visible", "not visible"),
            "count" to DQuestion.choice("How many things in the screenshot match \"$target\"?", listOf("none", "one", "several")),
            "where" to DQuestion.Box("$target in the screenshot"),
        ))
    }

    fun read(reply: DReply): Reading = Reading(
        present = reply.noul("present")?.yes ?: 0.0,
        count = reply.choice("count")?.choice,
        box = reply.answers["where"] as? DAnswer.Box,
    )

    /** One event line for the Score: where it would have tapped, never a tap. */
    fun line(step: Step, r: Reading): String = if (r.wouldTap && r.box != null)
        "Vision shadow: would tap \"${step.target}\" at %.2f, %.2f (sure %.2f) — not tapped".format(r.box.centerX, r.box.centerY, r.box.confidence)
    else "Vision shadow: would not tap \"${step.target}\" (present %.2f, ${r.count ?: "?"})".format(r.present)
}
