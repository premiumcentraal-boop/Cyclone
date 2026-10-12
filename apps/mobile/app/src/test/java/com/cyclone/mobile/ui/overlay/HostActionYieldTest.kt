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
}
