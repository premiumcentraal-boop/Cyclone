package com.cyclone.mobile

import com.cyclone.mobile.gesture.DrawCanvas
import com.cyclone.mobile.gesture.GestureBounds
import com.cyclone.mobile.gesture.GestureRng
import com.cyclone.mobile.gesture.HandGestureRequest
import com.cyclone.mobile.gesture.HandGestures
import com.cyclone.mobile.gesture.Handedness
import com.cyclone.mobile.gesture.HandsStyle
import com.cyclone.mobile.gesture.TouchGesture
import com.cyclone.mobile.ui.overlay.ClickGateIntercept
import org.json.JSONObject

/**
 * Plan 52 run 6: turns a `phone.double_tap`, `phone.drag`, `phone.pinch` or `phone.draw` request into a planned
 * gesture on the current screen, plus the words its approval check judges. It plans only; the caller (the
 * executor, inside its ownership, freshness and GATE checks) dispatches.
 */
internal object HandGestureTools {
    val TOOLS: Set<String> = HandGestureRequest.TOOLS.keys

    /** Pinch is not offered on background screens until a device run proves two-finger gestures land there. */
    val PINCH_ON_BACKGROUND: Boolean get() = com.cyclone.mobile.gesture.HandGesturesSupport.PINCH_ON_BACKGROUND

    data class Prepared(
        val gesture: TouchGesture,
        /** What the approval check judges: the touched controls' own words (and a signature's consent). */
        val gateLabels: List<String>,
        /** The control the approval is bound to. */
        val gateNodeId: String,
        /** Where the gesture touches first, for the trace overlay. */
        val firstTouch: com.cyclone.mobile.gesture.GesturePoint,
    )

    sealed class Plan {
        data class Ready(val prepared: Prepared) : Plan()
        data class Refused(val code: PhoneToolErrorCode, val message: String) : Plan()
    }

    fun plan(
        tool: String,
        params: JSONObject,
        snapshot: UiSnapshot,
        viewport: GestureBounds,
        style: HandsStyle,
        handedness: Handedness,
        rng: GestureRng,
        selector: ElementSelector? = selectorOf(params),
        resolveTo: (ElementSelector) -> UiNodeSnapshot? = { SelectorEngine.resolve(snapshot, it, 1).firstOrNull()?.node },
    ): Plan {
        val (request, problem) = HandGestureRequest.parse(tool, params)
        if (request == null) return Plan.Refused(PhoneToolErrorCode.INVALID_REQUEST, problem ?: "invalid gesture")
        fun refuse(message: String) = Plan.Refused(PhoneToolErrorCode.INVALID_REQUEST, message)
        fun unsupported(message: String) = Plan.Refused(PhoneToolErrorCode.CAPABILITY_UNAVAILABLE, message)

        val node = selector?.let { resolveTo(it) }
        if (selector != null && node == null) {
            return Plan.Refused(PhoneToolErrorCode.STALE_ELEMENT, "STALE_OBSERVATION: the target is no longer on the screen. Observe again.")
        }
        if (node != null && !node.visibleToUser) return refuse("the target is not visible")
        val bounds = node?.bounds?.toGesture()

        val planned: TouchGesture?
        val labels = ArrayList<String>()
        node?.let { labels += ClickGateIntercept.labelsFor(it, it, selector, packageName = snapshot.packageName) }
        when (request) {
            HandGestureRequest.DoubleTap -> {
                bounds ?: return refuse("double_tap needs a control (elementId)")
                planned = HandGestures.doubleTap(bounds, viewport, style, handedness, rng)
            }
            is HandGestureRequest.Drag -> {
                bounds ?: return refuse("drag needs the control to pick up (elementId)")
                if (request.direction != null) {
                    val end = HandGestures.dragEnd(bounds, request.direction, request.amount, viewport)
                        ?: return refuse("there is no room to drag that way")
                    planned = HandGestures.drag(bounds, end, null, viewport, style, handedness, rng, request.holdMs)
                } else {
                    val toSelector = request.toSelector?.let { ElementSelector.fromJson(it) }
                        ?: ElementSelector.fromJson(JSONObject().put("elementId", request.toElementId))
                    if (toSelector.isEmpty()) return Plan.Refused(PhoneToolErrorCode.STALE_ELEMENT, "STALE_OBSERVATION: the drop target is not on the screen")
                    val to = resolveTo(toSelector)
                        ?: return Plan.Refused(PhoneToolErrorCode.STALE_ELEMENT, "STALE_OBSERVATION: the drop target is no longer on the screen")
                    if (!to.visibleToUser) return refuse("the drop target is not visible")
                    val toBounds = to.bounds.toGesture()
                    // Dropping on "Trash" or "Bin" is deleting: the approval check reads it as a move to that place.
                    val toWords = ClickGateIntercept.labelsFor(to, to, toSelector, packageName = snapshot.packageName)
                    toWords.firstOrNull()?.let { labels += "move to $it" }
                    labels += toWords
                    planned = HandGestures.drag(bounds, toBounds.center, toBounds, viewport, style, handedness, rng, request.holdMs)
                }
            }
            is HandGestureRequest.Pinch -> {
                val area = bounds ?: viewport
                planned = HandGestures.pinch(area, viewport, request.scale, request.focus, style, handedness, rng)
                    ?: return unsupported("that area is too small to pinch in")
            }
            is HandGestureRequest.Draw -> {
                node ?: return refuse("draw needs the drawing canvas (elementId)")
                DrawCanvas.refusal(node, snapshot.nodes)?.let { return unsupported("Not drawn: $it") }
                // A signature is consent: the owner approves every one, like granting access.
                if (DrawCanvas.signs(node, request.shape)) labels += "grant consent by signature"
                planned = HandGestures.draw(bounds!!, viewport, request.shape, request.strokes, style, rng)
            }
        }
        planned ?: return refuse("that gesture does not fit on the screen")
        val gateNodeId = node?.id ?: "screen"
        return Plan.Ready(Prepared(planned, labels, gateNodeId, planned.strokes.first().motion.start))
    }

    /** The control a gesture request names, or null (only a pinch may name none: then it works on the screen). */
    fun selectorOf(params: JSONObject): ElementSelector? {
        val selector = ElementSelector.fromJson(params.optJSONObject("selector") ?: params)
        return selector.takeUnless { it.isEmpty() }
    }

    /** Bounded result evidence for a gesture: kind, counts, durations, ratios; never coordinates or typed text. */
    fun facts(trace: HumanGestureDispatchTrace?): JSONObject {
        val out = JSONObject()
        trace?.gesture?.let { out.put("gesture", it) }
        trace?.facts?.forEach { (key, value) ->
            when (value) {
                is Number, is String, is Boolean -> out.put(key, value)
                is List<*> -> out.put(key, org.json.JSONArray(value.filterIsInstance<Number>()))
            }
        }
        return out
    }

    private fun UiBounds.toGesture(): GestureBounds = GestureBounds(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}
