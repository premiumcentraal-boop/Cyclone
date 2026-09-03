package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.ElementSelector
import com.cyclone.mobile.UiNodeSnapshot
import com.cyclone.mobile.policy.GateClass
import com.cyclone.mobile.policy.GateClassifier

/**
 * Intercept host clicks that classify as GATE before Accessibility ACTION_CLICK.
 * Files "Move to bin" must enter overlay GATE from IDLE or LIVE and must not be performed.
 */
class GateBlockedException(
    val gateClass: OverlayGateClass?,
    message: String = "GATE ${gateClass?.wire ?: "required"} requires confirmation",
) : RuntimeException(message)

object ClickGateIntercept {
    data class Decision(
        val performClick: Boolean,
        val enterGate: Boolean,
        val gateClass: OverlayGateClass?,
    )

    fun labelsFor(
        chosen: UiNodeSnapshot,
        activation: UiNodeSnapshot = chosen,
        selector: ElementSelector? = null,
    ): List<String> {
        val labels = mutableListOf<String>()
        fun add(value: String?) {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isNotEmpty() && trimmed !in labels) labels += trimmed
        }
        add(chosen.text)
        add(chosen.contentDescription)
        add(activation.text)
        add(activation.contentDescription)
        add(selector?.text)
        add(selector?.textContains)
        add(selector?.contentDescription)
        add(selector?.contentDescriptionContains)
        add(selector?.fuzzyText)
        add(selector?.descendantText)
        return labels
    }

    fun overlayClass(gateClass: GateClass): OverlayGateClass = OverlayGateClass.parse(gateClass.jsonKey)

    fun decide(
        action: String,
        labels: List<String>,
        overlayState: OverlayChromeState,
    ): Decision {
        val classified = GateClassifier.classify(action, labels) ?: return Decision(
            performClick = true,
            enterGate = false,
            gateClass = null,
        )
        val overlay = overlayClass(classified)
        return Decision(
            performClick = false,
            enterGate = overlayState != OverlayChromeState.GATE,
            gateClass = overlay,
        )
    }

    fun apply(
        machine: OverlayChromeMachine,
        action: String,
        labels: List<String>,
        pcAutoApprove: Boolean = false,
    ): Boolean {
        val decision = decide(action, labels, machine.state())
        if (decision.enterGate && decision.gateClass != null) {
            machine.enterGate(decision.gateClass, pcAutoApprove)
        }
        return decision.performClick
    }
}
