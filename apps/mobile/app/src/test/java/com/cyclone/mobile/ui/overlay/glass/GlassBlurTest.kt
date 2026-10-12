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

    @Test fun onlyPanelsWithABlurWindowGetLighter() {
        var changes = 0
        val host = BlurHost(slots = 2) { changes++ }
        val a = Any(); val b = Any(); val c = Any()
        host.place(a, BlurRegion(Rect(0f, 0f, 100f, 60f), 20f))
        host.place(b, BlurRegion(Rect(0f, 80f, 100f, 140f), 30f))
        host.place(c, BlurRegion(Rect(0f, 160f, 100f, 200f), 18f))
        host.lift = GlassBlur.VEIL_LIFT
        assertEquals(2, host.shown().size)
        assertEquals(GlassBlur.VEIL_LIFT, host.liftFor(a), 0f)
        assertEquals(GlassBlur.VEIL_LIFT, host.liftFor(b), 0f)
        assertEquals(0f, host.liftFor(c), 0f)
        // The same place again is not a change; a panel that goes frees its slot.
        val before = changes
        host.place(a, BlurRegion(Rect(0f, 0f, 100f, 60f), 20f))
        assertEquals(before, changes)
        host.remove(a)
        assertEquals(GlassBlur.VEIL_LIFT, host.liftFor(c), 0f)
        assertEquals(0f, host.liftFor(a), 0f)
    }
}
