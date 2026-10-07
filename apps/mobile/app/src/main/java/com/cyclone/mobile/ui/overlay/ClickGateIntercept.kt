package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.ElementSelector
import com.cyclone.mobile.UiNodeSnapshot
import com.cyclone.mobile.UiWindowSnapshot
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

    /** Tree order inside ONE window: a later sibling's subtree is drawn over an earlier one's; a child over its parent. */
    private val DRAW_ORDER = Comparator<UiNodeSnapshot> { a, b ->
        val pa = a.path.split('/').map { it.removePrefix("w").toIntOrNull() ?: 0 }
        val pb = b.path.split('/').map { it.removePrefix("w").toIntOrNull() ?: 0 }
        val diff = pa.zip(pb).firstOrNull { (x, y) -> x != y }
        if (diff != null) diff.first.compareTo(diff.second) else pa.size.compareTo(pb.size)
    }

    private fun UiNodeSnapshot.under(x: Int, y: Int) =
        visibleToUser && bounds.width > 0 && bounds.height > 0 &&
            x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom

    private fun UiNodeSnapshot.isInside(ancestor: UiNodeSnapshot) =
        path == ancestor.path || path.startsWith(ancestor.path + "/")

    /**
     * The review (approval gate) for a tap by position judges only the action that tap performs on the page it lands
     * on: the one control that receives the touch, its activation target and its own words. Nothing else on screen,
     * no window behind it, and no list row underneath a floating button feeds the gate.
     *
     * Review-side only: this never changes what the model observes. The full screen tree, every window and every
     * label still reach the model unchanged, so it keeps seeing where an action will take it.
     *
     * 1. Page: the window that takes the touch is the one with the highest layer whose bounds hold the point.
     * 2. Control: inside that window, the topmost clickable node under the point (tree order = draw order there).
     * 3. Words: that control, its activation target and its own descendants. With no clickable control, the topmost
     *    node under the point speaks for the tap.
     * If the receiving window is unknown or none of its nodes are under the point, every node is considered (gating
     * more, never less).
     */
    fun labelsAtPoint(
        nodes: List<UiNodeSnapshot>,
        x: Int,
        y: Int,
        windows: List<UiWindowSnapshot> = emptyList(),
    ): List<String> {
        val allUnder = nodes.filter { it.under(x, y) }
        if (allUnder.isEmpty()) return emptyList()
        val page = windows.filter { w ->
            x >= w.bounds.left && x < w.bounds.right && y >= w.bounds.top && y < w.bounds.bottom
        }.maxByOrNull { it.layer }
        val under = page?.let { w -> allUnder.filter { it.windowId == w.id } }?.takeIf { it.isNotEmpty() } ?: allUnder
        val order = if (under.map { it.windowId }.distinct().size <= 1) DRAW_ORDER else {
            // Mixed windows only on the fallback path: window layer first, then tree order inside a window.
            val layer = windows.associate { it.id to it.layer }
            compareBy<UiNodeSnapshot> { layer[it.windowId] ?: Int.MIN_VALUE }.then(DRAW_ORDER)
        }
        val control = under.filter { it.clickable || it.longClickable }.maxWithOrNull(order)
        val labels = mutableListOf<String>()
        fun addAll(values: List<String>) = values.forEach { if (it !in labels) labels += it }
        if (control == null) {
            addAll(labelsFor(under.maxWithOrNull(order)!!))
            return labels
        }
        // The words nearest the finger first, but only when they belong to the control that takes the tap.
        under.filter { it.isInside(control) }.maxWithOrNull(order)?.let { addAll(labelsFor(it)) }
        val activation = com.cyclone.mobile.AccessibilityRoles.resolveActivationTarget(nodes, control)
        addAll(labelsFor(control, activation))
        // A clickable row or button often carries its words on its children: its own children only.
        nodes.filter { it.windowId == control.windowId && it.isInside(control) && it.path != control.path }
            .take(12).forEach { addAll(labelsFor(it)) }
        return labels
    }

    fun overlayClass(gateClass: GateClass): OverlayGateClass = OverlayGateClass.parse(gateClass.jsonKey)

    fun decide(
        action: String,
        labels: List<String>,
        overlayState: OverlayChromeState,
        useRuntimeApproval: Boolean = true,
    ): Decision {
        val classified = GateClassifier.classify(action, labels) ?: return Decision(
            performClick = true,
            enterGate = false,
            gateClass = null,
        )
        val overlay = overlayClass(classified)
        if (useRuntimeApproval && OverlayChromeRuntime.consumeGateApproval(overlay, action, labels)) {
            return Decision(performClick = true, enterGate = false, gateClass = overlay)
        }
        if (useRuntimeApproval) OverlayChromeRuntime.registerGateChallenge(overlay, action, labels)
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
        val decision = decide(action, labels, machine.state(), useRuntimeApproval = false)
        if (decision.enterGate && decision.gateClass != null) {
            machine.enterGate(decision.gateClass, pcAutoApprove)
        }
        return decision.performClick
    }
}
