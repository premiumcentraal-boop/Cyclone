package com.cyclone.mobile.ui.overlay

import org.junit.Assert.*
import org.junit.Test

class HostActionYieldTest {
    @Test fun `nested gesture completion does not restore overlay before host verification`() {
        OverlayGesturePassthrough.resetForTests()
        val changes = mutableListOf<Boolean>()
        OverlayGesturePassthrough.bind(changes::add)
        try {
            OverlayGesturePassthrough.withHostPassthrough {
                OverlayGesturePassthrough.withHostPassthrough {
                    assertTrue(OverlayGesturePassthrough.active())
                }
                assertTrue("host result must be read while Ask still yields", OverlayGesturePassthrough.active())
                assertEquals(listOf(true), changes)
            }
            assertFalse(OverlayGesturePassthrough.active())
            assertEquals(listOf(true, false), changes)
        } finally { OverlayGesturePassthrough.resetForTests() }
    }

    @Test fun `failed host verification restores overlay and leaves next action usable`() {
        OverlayGesturePassthrough.resetForTests()
        val changes = mutableListOf<Boolean>()
        OverlayGesturePassthrough.bind(changes::add)
        try {
            runCatching {
                OverlayGesturePassthrough.withHostPassthrough { error("unverified host result") }
            }
            assertFalse(OverlayGesturePassthrough.active())
            OverlayGesturePassthrough.withHostPassthrough { assertTrue(OverlayGesturePassthrough.active()) }
            assertEquals(listOf(true, false, true, false), changes)
        } finally { OverlayGesturePassthrough.resetForTests() }
    }

    @Test fun `a host action steps back but the overlay stays on screen`() {
        OverlayGesturePassthrough.resetForTests()
        val changes = mutableListOf<Boolean>()
        val voice = mutableListOf<Boolean>()
        OverlayGesturePassthrough.bind(changes::add)
        OverlayGesturePassthrough.follow(voice::add)
        try {
            OverlayGesturePassthrough.withHostFocus {
                assertTrue("no touch, no focus, out of the tree", OverlayGesturePassthrough.active())
                assertFalse("still on screen", OverlayGesturePassthrough.hidden())
                assertFalse("the owner's Stop still counts", OverlayGesturePassthrough.injectedRecently())
            }
            assertEquals(listOf(true, false), changes)
            assertFalse(OverlayGesturePassthrough.injectedRecently())
            assertTrue("Drive's windows don't move for a step back", voice.isEmpty())
        } finally { OverlayGesturePassthrough.resetForTests() }
    }

    @Test fun `a stroke hides the overlay only when it lands on the glass and only for that stroke`() {
        OverlayGesturePassthrough.resetForTests()
        val voice = mutableListOf<Boolean>()
        OverlayGesturePassthrough.regions { listOf(intArrayOf(0, 1800, 1080, 2400)) }  // the Ask card at the bottom
        OverlayGesturePassthrough.follow(voice::add)
        try {
            OverlayGesturePassthrough.withHostFocus {
                OverlayGesturePassthrough.withHostStroke(floatArrayOf(500f, 600f, 520f, 620f)) {
                    assertFalse("a tap in the middle of the app leaves the card on screen", OverlayGesturePassthrough.hidden())
                }
                OverlayGesturePassthrough.withHostStroke(floatArrayOf(500f, 1300f, 520f, 1900f)) {
                    assertTrue("a swipe into the card hides it for the stroke", OverlayGesturePassthrough.hidden())
                }
                assertFalse("back on screen while the result settles", OverlayGesturePassthrough.hidden())
                assertTrue(OverlayGesturePassthrough.active())
                OverlayGesturePassthrough.withHostStroke(floatArrayOf(500f, 1730f, 520f, 1760f)) {
                    assertTrue("within the margin counts as on the glass", OverlayGesturePassthrough.hidden())
                }
                OverlayGesturePassthrough.withHostStroke(null) {
                    assertTrue("unknown bounds hide, as before", OverlayGesturePassthrough.hidden())
                }
            }
            assertEquals(listOf(true, false, true, false, true, false), voice)
        } finally { OverlayGesturePassthrough.resetForTests() }
    }

    @Test fun `a window owner that can't say where it is hides the overlay`() {
        OverlayGesturePassthrough.resetForTests()
        OverlayGesturePassthrough.regions { error("not laid out") }
        try {
            assertTrue(OverlayGesturePassthrough.overlaps(floatArrayOf(1f, 1f, 2f, 2f)))
            OverlayGesturePassthrough.resetForTests()
            assertFalse("no Cyclone window on screen", OverlayGesturePassthrough.overlaps(floatArrayOf(1f, 1f, 2f, 2f)))
        } finally { OverlayGesturePassthrough.resetForTests() }
    }
}
