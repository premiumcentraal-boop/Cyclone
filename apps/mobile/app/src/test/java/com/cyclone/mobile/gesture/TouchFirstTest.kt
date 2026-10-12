package com.cyclone.mobile.gesture

import com.cyclone.mobile.UiBounds
import com.cyclone.mobile.UiNodeSnapshot
import com.cyclone.mobile.UiSnapshot
import com.cyclone.mobile.UiWindowSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 52 run 2: a finger instead of ACTION_CLICK / ACTION_SCROLL, only when it is as safe. */
class TouchFirstTest {
    private fun node(
        path: String,
        bounds: UiBounds,
        depth: Int = path.count { it == '/' },
        window: Int = 1,
        clickable: Boolean = false,
        scrollable: Boolean = false,
        visible: Boolean = true,
    ) = UiNodeSnapshot(
        id = path, path = path, parentId = null, childIds = emptyList(), depth = depth, windowId = window,
        className = "android.view.View", role = "", text = "", contentDescription = "", resourceId = "",
        bounds = bounds, clickable = clickable, longClickable = false, editable = false, scrollable = scrollable,
        enabled = true, selected = false, checked = false, checkable = false, focused = false, focusable = false,
        visibleToUser = visible,
    )

    private val appWindow = UiWindowSnapshot(1, "App", 1, 0, true, true, UiBounds(0, 0, 1080, 2400))
    private val root = node("w1/0", UiBounds(0, 0, 1080, 2400))
    private val button = node("w1/0/3", UiBounds(100, 1000, 500, 1120), clickable = true)

    private fun snapshot(vararg nodes: UiNodeSnapshot, windows: List<UiWindowSnapshot> = listOf(appWindow)) =
        UiSnapshot("com.example", null, 1080, 2400, 0L, "fp", "agent", windows, listOf(root) + nodes)

    @Test fun aPlainVisibleButtonIsPressedWithAFinger() {
        assertEquals(TouchFirst.Verdict.TOUCH, TouchFirst.decide(snapshot(button), button, HandsStyle.NATURAL, HumanizePreference.AUTO))
    }

    @Test fun preciseHandsAndHumanizeOffKeepTheSemanticClick() {
        assertEquals(TouchFirst.Verdict.STYLE_PRECISE, TouchFirst.decide(snapshot(button), button, HandsStyle.PRECISE, HumanizePreference.AUTO))
        assertEquals(TouchFirst.Verdict.CALLER_OFF, TouchFirst.decide(snapshot(button), button, HandsStyle.NATURAL, HumanizePreference.OFF))
    }

    @Test fun smallHiddenOffScreenOrUnclickableTargetsKeepTheSemanticClick() {
        val small = node("w1/0/4", UiBounds(10, 10, 30, 30), clickable = true)
        assertEquals(TouchFirst.Verdict.TOO_SMALL, TouchFirst.decide(snapshot(small), small, HandsStyle.NATURAL, HumanizePreference.AUTO))
        val hidden = node("w1/0/5", UiBounds(100, 100, 400, 200), clickable = true, visible = false)
        assertEquals(TouchFirst.Verdict.NOT_VISIBLE, TouchFirst.decide(snapshot(hidden), hidden, HandsStyle.NATURAL, HumanizePreference.AUTO))
        val below = node("w1/0/6", UiBounds(100, 2350, 400, 2500), clickable = true)
        assertEquals(TouchFirst.Verdict.OFF_SCREEN, TouchFirst.decide(snapshot(below), below, HandsStyle.NATURAL, HumanizePreference.AUTO))
        val label = node("w1/0/7", UiBounds(100, 600, 500, 700))
        assertEquals(TouchFirst.Verdict.NOT_CLICKABLE, TouchFirst.decide(snapshot(label), label, HandsStyle.NATURAL, HumanizePreference.AUTO))
    }

    @Test fun somethingOnTopKeepsTheSemanticClick() {
        val badge = node("w1/0/9", UiBounds(250, 1040, 350, 1100), depth = 5)
        assertEquals(TouchFirst.Verdict.COVERED_BY_APP, TouchFirst.decide(snapshot(button, badge), button, HandsStyle.NATURAL, HumanizePreference.AUTO))
        val keyboard = UiWindowSnapshot(2, "Keyboard", 2, 5, false, false, UiBounds(0, 1050, 1080, 2400))
        assertEquals(TouchFirst.Verdict.COVERED_BY_WINDOW,
            TouchFirst.decide(snapshot(button, windows = listOf(appWindow, keyboard)), button, HandsStyle.NATURAL, HumanizePreference.AUTO))
        // Cyclone's own overlay (type 4) never counts: touches pass through it.
        val overlay = UiWindowSnapshot(3, "Cyclone", 4, 9, false, false, UiBounds(0, 0, 1080, 2400))
        assertEquals(TouchFirst.Verdict.TOUCH,
            TouchFirst.decide(snapshot(button, windows = listOf(appWindow, overlay)), button, HandsStyle.NATURAL, HumanizePreference.AUTO))
    }

    @Test fun aRowWithItsOwnButtonInsideKeepsTheSemanticClick() {
        val row = node("w1/0/2", UiBounds(0, 1300, 1080, 1500), clickable = true)
        val inner = node("w1/0/2/1", UiBounds(400, 1320, 700, 1480), clickable = true)
        assertEquals(TouchFirst.Verdict.CHILD_CONTROL, TouchFirst.decide(snapshot(row, inner), row, HandsStyle.NATURAL, HumanizePreference.AUTO))
        val text = node("w1/0/2/1", UiBounds(400, 1320, 700, 1480))
        assertEquals(TouchFirst.Verdict.TOUCH, TouchFirst.decide(snapshot(row, text), row, HandsStyle.NATURAL, HumanizePreference.AUTO))
    }

    @Test fun aLargeListIsScrolledByThumbButNotFromANestedCarousel() {
        val list = node("w1/0/1", UiBounds(0, 200, 1080, 2200), scrollable = true)
        val screen = GestureBounds(0f, 0f, 1080f, 2400f)
        val planned = NaturalScroll.plan(snapshot(list), list, true, HandsStyle.NATURAL, HumanizePreference.AUTO, screen,
            Handedness.RIGHT, SeededGestureRng(4L))
        assertNotNull(planned)
        assertTrue("forward means the finger moves up", planned!!.end.y < planned.start.y)
        assertNull(NaturalScroll.plan(snapshot(list), list, true, HandsStyle.PRECISE, HumanizePreference.AUTO, screen,
            Handedness.RIGHT, SeededGestureRng(4L)))
        // A carousel across the whole lower part of the list: starts there are refused, so a start must land above it.
        val carousel = node("w1/0/1/5", UiBounds(0, 900, 1080, 2200), scrollable = true)
        repeat(50) { seed ->
            NaturalScroll.plan(snapshot(list, carousel), list, true, HandsStyle.NATURAL, HumanizePreference.AUTO, screen,
                Handedness.RIGHT, SeededGestureRng(seed.toLong()))?.let { assertTrue(it.start.y < 900f) }
        }
        val sideways = node("w1/0/8", UiBounds(0, 1000, 1080, 1300), scrollable = true)
        assertNull("a short wide strip is a carousel: semantic", NaturalScroll.plan(snapshot(sideways), sideways, true,
            HandsStyle.NATURAL, HumanizePreference.AUTO, screen, Handedness.RIGHT, SeededGestureRng(1L)))
    }
}
