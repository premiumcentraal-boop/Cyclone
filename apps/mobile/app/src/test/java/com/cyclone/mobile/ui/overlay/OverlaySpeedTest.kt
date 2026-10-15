package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.ui.overlay.tracefield.TraceFieldMode
import com.cyclone.mobile.ui.overlay.tracefield.TraceFieldPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Alpha.134 (plan 60): the overlay's constant work is gone, and Lite drops the per-frame work. */
class OverlaySpeedTest {
    private fun fp(state: String, words: String) = NotificationPacer.Fingerprint(state, words)

    @Test fun theNotificationIsRebuiltOnlyWhenItChangesAndAtMostOnceASecond() {
        val pacer = NotificationPacer(minGapMs = 1_000)
        assertEquals(NotificationPacer.Decision.Post, pacer.decide(fp("working", "Opening Instagram"), 0))
        assertEquals("the same words again", NotificationPacer.Decision.Skip, pacer.decide(fp("working", "Opening Instagram"), 100))
        assertEquals(NotificationPacer.Decision.Later(700), pacer.decide(fp("working", "Typing"), 300))
        assertEquals("a new state never waits", NotificationPacer.Decision.Post, pacer.decide(fp("needs-you", "Approve?"), 400))
        assertEquals(NotificationPacer.Decision.Post, pacer.decide(fp("needs-you", "Approve this?"), 1_500))
        pacer.reset()
        assertEquals("a new task posts at once", NotificationPacer.Decision.Post, pacer.decide(fp("needs-you", "Approve this?"), 1_600))
    }

    @Test fun liteHasNoFullScreenTraceFieldAndBatterySaverKeepsTheEdge() {
        assertEquals(TraceFieldMode.OFF, TraceFieldPolicy.resolveMode(TraceFieldMode.FIELD, powerSave = false, lite = true))
        assertEquals(TraceFieldMode.OFF, TraceFieldPolicy.resolveMode(TraceFieldMode.EDGE, powerSave = true, lite = true))
        assertEquals(TraceFieldMode.EDGE, TraceFieldPolicy.resolveMode(TraceFieldMode.FIELD, powerSave = true, lite = false))
        assertEquals("Full keeps the owner's choice", TraceFieldMode.FIELD, TraceFieldPolicy.resolveMode(TraceFieldMode.FIELD, powerSave = false, lite = false))
    }

    @Test fun theStopRingNeedsFramesOnlyWhileHeldOrArmed() {
        val g = ComposerStopGesture()
        assertFalse("idle while the task works: no frames", g.live(0))
        g.press(10)
        assertTrue(g.live(20))
        g.release(30)
        assertTrue("armed for the second tap", g.live(40))
        assertFalse("the arm runs out", g.live(30 + ComposerStopGesture.TAP_RETRACT_MS))
    }
}
