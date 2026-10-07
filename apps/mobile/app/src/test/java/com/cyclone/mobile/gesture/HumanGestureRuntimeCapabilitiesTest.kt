package com.cyclone.mobile.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanGestureRuntimeCapabilitiesTest {
    @Test
    fun `foreground layer2 and named vd all report cubic accessibility dispatch`() {
        val runtime = HumanGestureRuntimeCapabilities.toJson(accessibilityConnected = true)
        assertTrue(runtime.getBoolean("runtimeAvailable"))
        assertEquals("cyclone.human_gesture.control.v1", runtime.getString("controlVersion"))
        assertEquals("cyclone.human_gesture.trace.v1", runtime.getString("traceVersion"))
        assertEquals("cyclone.human_gesture.trace.v1", runtime.getString("traceSchema"))

        val planes = runtime.getJSONObject("executionPlanes")
        assertTrue(planes.getJSONObject("foregroundDisplay0").getBoolean("cubicPath"))
        assertTrue(planes.getJSONObject("namedVirtualDisplay").getBoolean("cubicPath"))
        assertTrue(planes.getJSONObject("namedVirtualDisplay").getBoolean("humanGesture"))
        assertEquals(
            "accessibility_dispatch_gesture",
            planes.getJSONObject("namedVirtualDisplay").getString("backend"),
        )
        assertEquals(
            "gesture_description_set_display_id",
            planes.getJSONObject("namedVirtualDisplay").getString("displayTarget"),
        )
        assertFalse(planes.getJSONObject("namedVirtualDisplay").has("compatibility"))
        assertTrue(planes.getJSONObject("layer2").getBoolean("cubicPath"))
        assertEquals(
            "layer2_display0_mutation_lease",
            planes.getJSONObject("layer2").getString("ownership"),
        )
    }

    @Test
    fun `runtime availability follows the accessibility execution dependency`() {
        assertFalse(HumanGestureRuntimeCapabilities.toJson(accessibilityConnected = false).getBoolean("runtimeAvailable"))
        assertTrue(HumanGestureRuntimeCapabilities.toJson(accessibilityConnected = true).getBoolean("runtimeAvailable"))
    }

    @Test
    fun `action matrix publishes semantic first scroll and bounded auto profiles`() {
        val actions = HumanGestureRuntimeCapabilities.toJson(true).getJSONObject("actions")
        assertEquals("light", actions.getJSONObject("tap").getString("autoProfile"))
        assertEquals("light", actions.getJSONObject("longPressFallback").getString("autoProfile"))
        assertEquals("normal", actions.getJSONObject("swipe").getString("autoProfile"))
        assertEquals("normal", actions.getJSONObject("scroll").getString("autoProfile"))
        assertEquals(
            "semantic_first_then_safe_grounded_fallback",
            actions.getJSONObject("scroll").getString("foregroundMode"),
        )
    }

    @Test
    fun `run 6 gestures are reported per display, pinch stays off background screens, and none takes raw points`() {
        val actions = HumanGestureRuntimeCapabilities.toJson(true).getJSONObject("actions")
        for (name in listOf("doubleTap", "drag", "pinch", "draw")) {
            val gesture = actions.getJSONObject(name)
            assertFalse(gesture.getBoolean("rawPoints"))
            assertEquals("synthesized_touch", gesture.getString("foregroundMode"))
        }
        assertTrue(actions.getJSONObject("doubleTap").getBoolean("backgroundDisplays"))
        assertFalse(actions.getJSONObject("pinch").getBoolean("backgroundDisplays"))
        assertTrue(actions.getJSONObject("draw").getBoolean("groundedCanvasOnly"))
        val off = HumanGestureRuntimeCapabilities.toJson(false).getJSONObject("actions")
        assertFalse(off.getJSONObject("pinch").getBoolean("supported"))
        val hands = HumanGestureRuntimeCapabilities.toJson(true).getJSONObject("hands")
        assertEquals(4, hands.getJSONArray("gestures").length())
    }
}
