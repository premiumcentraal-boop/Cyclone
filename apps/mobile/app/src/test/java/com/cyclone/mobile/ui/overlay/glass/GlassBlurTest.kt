package com.cyclone.mobile.ui.overlay.glass

import android.os.PowerManager
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassBlurTest {
    @Test fun blurOnlyWhenAndroidAllowsItAndThePhoneIsCool() {
        assertTrue(GlassBlur.decide(31, allowed = true, thermalStatus = PowerManager.THERMAL_STATUS_NONE, failed = false))
        assertTrue(GlassBlur.decide(35, allowed = true, thermalStatus = PowerManager.THERMAL_STATUS_LIGHT, failed = false))
        // Android 11, a phone that cannot blur, battery saver or blurs switched off (allowed = false): smoke.
        assertFalse(GlassBlur.decide(30, allowed = true, thermalStatus = PowerManager.THERMAL_STATUS_NONE, failed = false))
        assertFalse(GlassBlur.decide(34, allowed = false, thermalStatus = PowerManager.THERMAL_STATUS_NONE, failed = false))
        // Warm or worse: smoke until it cools.
        assertFalse(GlassBlur.decide(34, allowed = true, thermalStatus = PowerManager.THERMAL_STATUS_MODERATE, failed = false))
        assertFalse(GlassBlur.decide(34, allowed = true, thermalStatus = PowerManager.THERMAL_STATUS_SEVERE, failed = false))
        // A blur window that failed to open: smoke for the session.
        assertFalse(GlassBlur.decide(34, allowed = true, thermalStatus = PowerManager.THERMAL_STATUS_NONE, failed = true))
    }

    @Test fun aMovingPanelHasNoBlurUntilItSettles() {
        var changes = 0
        val later = ArrayList<() -> Unit>()
        val host = BlurHost(slots = 2, schedule = { _, block -> later += block }) { changes++ }
        val a = Any(); val b = Any(); val c = Any()
        host.place(a, BlurRegion(Rect(0f, 0f, 100f, 60f), 20f))
        host.place(b, BlurRegion(Rect(0f, 80f, 100f, 140f), 30f))
        host.place(c, BlurRegion(Rect(0f, 160f, 100f, 200f), 18f))
        // Just appeared: in motion, so no blur yet.
        assertEquals(listOf(null, null), host.shown())
        assertFalse(host.isBlurred(a))
        // Only the last scheduled settle counts; then the first two panels (the slots) get their blur.
        for (k in 0 until later.size - 1) later[k].invoke()
        assertFalse(host.isBlurred(a))
        later[later.size - 1].invoke()
        assertTrue(host.isBlurred(a))
        assertTrue(host.isBlurred(b))
        assertFalse(host.isBlurred(c))
        assertEquals(2, host.shown().filterNotNull().size)
        // The same place again is not a move; a real move steps the blur aside at once.
        val before = changes
        host.place(a, BlurRegion(Rect(0f, 0f, 100f, 60f), 20f))
        assertEquals(before, changes)
        host.place(a, BlurRegion(Rect(0f, 10f, 100f, 70f), 20f))
        assertEquals(null, host.shown()[0])
        assertFalse(host.isBlurred(a))
        // A panel that goes frees its slot.
        later[later.size - 1].invoke()
        host.remove(a)
        assertTrue(host.isBlurred(c))
        // The whole window moved: everything steps aside.
        host.jolt()
        assertEquals(listOf(null, null), host.shown())
    }
}
