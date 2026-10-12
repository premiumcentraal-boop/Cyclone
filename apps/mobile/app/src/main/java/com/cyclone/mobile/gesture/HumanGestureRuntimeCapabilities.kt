package com.cyclone.mobile.gesture

import com.cyclone.mobile.gesture.diagnostics.HumanGestureTraceAdapter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone-authoritative Human Gesture capability facts exposed through phone.capabilities.
 * This describes what each Android execution backend can actually represent; it is not a planner promise.
 */
object HumanGestureRuntimeCapabilities {
    const val CONTROL_VERSION = "cyclone.human_gesture.control.v1"

    fun toJson(accessibilityConnected: Boolean): JSONObject = JSONObject()
        .put("runtimeAvailable", accessibilityConnected)
        .put("controlVersion", CONTROL_VERSION)
        .put("traceVersion", HumanGestureTraceAdapter.SCHEMA)
        .put("traceSchema", HumanGestureTraceAdapter.SCHEMA)
        .put("synthesisVersion", HumanGestureTraceAdapter.ENGINE_VERSION)
        .put("supportedProfiles", JSONArray(listOf("auto", "off", "light", "normal")))
        .put("actions", JSONObject()
            .put("clickFallback", action("light", "semantic_first_then_synthesized_touch"))
            .put("tap", action("light", "synthesized_touch"))
            .put("longPressFallback", action("light", "semantic_first_then_synthesized_touch"))
            .put("swipe", action("normal", "synthesized_touch"))
            .put("scroll", action("normal", "semantic_first_then_safe_grounded_fallback"))
            // Plan 52 run 6: planned on the phone from typed intents; never raw points from a caller.
            .put("doubleTap", gesture(accessibilityConnected, background = true))
            .put("drag", gesture(accessibilityConnected && Hands.segmentedStrokesSupported, background = true))
            .put("pinch", gesture(accessibilityConnected, background = HandGesturesSupport.PINCH_ON_BACKGROUND))
            .put("draw", gesture(accessibilityConnected, background = true).put("groundedCanvasOnly", true)))
        // Plan 52: Human Hands. What the owner's Hands style makes Cyclone do on this phone right now.
        .put("hands", JSONObject()
            .put("style", Hands.style.name.lowercase())
            .put("handedness", Hands.handedness.name.lowercase())
            .put("speedCurves", Hands.style.natural && Hands.segmentedStrokesSupported)
            .put("swipeIntents", true)
            .put("touchFirstClicks", Hands.style.natural)
            .put("keystrokeTyping", Hands.style.natural && Hands.keystrokesSupported)
            .put("pacing", Hands.style.natural)
            .put("naturalPlacement", Hands.style.natural)
            .put("swipeStyles", true)
            .put("gestures", JSONArray(listOf("double_tap", "drag", "pinch", "draw"))))
        .put("executionPlanes", JSONObject()
            .put("foregroundDisplay0", JSONObject()
                .put("backend", "accessibility_dispatch_gesture")
                .put("cubicPath", true)
                .put("humanGesture", true))
            .put("namedVirtualDisplay", JSONObject()
                .put("backend", "accessibility_dispatch_gesture")
                .put("cubicPath", true)
                .put("humanGesture", true)
                .put("displayTarget", "gesture_description_set_display_id"))
            .put("layer2", JSONObject()
                .put("backend", "accessibility_dispatch_gesture")
                .put("cubicPath", true)
                .put("humanGesture", true)
                .put("ownership", "layer2_display0_mutation_lease")))

    /**
     * A plan 52 run 6 gesture: supported on the main screen when Accessibility is connected (drag also needs the
     * chained strokes this phone may have dropped), and on background screens only where [background] says so.
     */
    private fun gesture(supported: Boolean, background: Boolean): JSONObject = JSONObject()
        .put("supported", supported)
        .put("humanizeAccepted", true)
        .put("foregroundMode", if (supported) "synthesized_touch" else "unsupported")
        .put("backgroundDisplays", supported && background)
        .put("rawPoints", false)

    private fun action(autoProfile: String, mode: String): JSONObject = JSONObject()
        .put("humanizeAccepted", true)
        .put("autoProfile", autoProfile)
        .put("foregroundMode", mode)
        .put("offMode", "legacy_straight")
}
