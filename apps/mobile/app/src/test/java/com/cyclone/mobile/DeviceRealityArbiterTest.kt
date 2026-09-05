package com.cyclone.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRealityArbiterTest {
    @Test
    fun activeCycloneOverlayCannotShadowChromeTaskWindow() {
        val reality = DeviceRealityArbiter.select(
            listOf(
                window(10, "com.android.chrome", DeviceRealitySurfaceKind.APPLICATION, layer = 1, active = false, focused = false),
                window(20, DeviceRealityArbiter.CYCLONE_PACKAGE, DeviceRealitySurfaceKind.ACCESSIBILITY_OVERLAY, layer = 8, active = true, focused = true),
            ),
        )
        assertEquals(10, reality.taskWindowId)
        assertEquals("com.android.chrome", reality.taskPackage)
        assertEquals(20, reality.agentOverlayWindowId)
        assertTrue(reality.groundingConflict)
        assertEquals("cyclone_overlay_excluded_from_task_reality", reality.reason)
    }

    @Test
    fun activeCycloneOverlayCannotShadowGmailTaskWindow() {
        val reality = DeviceRealityArbiter.select(
            listOf(
                window(31, "com.google.android.gm", DeviceRealitySurfaceKind.APPLICATION, layer = 2, active = false, focused = false),
                window(32, DeviceRealityArbiter.CYCLONE_PACKAGE, DeviceRealitySurfaceKind.ACCESSIBILITY_OVERLAY, layer = 12, active = true, focused = true),
            ),
        )
        assertEquals("com.google.android.gm", reality.taskPackage)
        assertEquals(31, reality.taskWindowId)
    }

    @Test
    fun focusedExternalApplicationBeatsHigherLayerUnfocusedApplication() {
        val reality = DeviceRealityArbiter.select(
            listOf(
                window(1, "com.example.background", DeviceRealitySurfaceKind.APPLICATION, layer = 9, active = false, focused = false),
                window(2, "com.android.chrome", DeviceRealitySurfaceKind.APPLICATION, layer = 2, active = true, focused = true),
            ),
        )
        assertEquals(2, reality.taskWindowId)
        assertFalse(reality.groundingConflict)
    }

    @Test
    fun cycloneApplicationCanBeTaskWhenNoExternalApplicationExists() {
        val reality = DeviceRealityArbiter.select(
            listOf(
                window(4, DeviceRealityArbiter.CYCLONE_PACKAGE, DeviceRealitySurfaceKind.APPLICATION, layer = 1, active = true, focused = true),
                window(5, DeviceRealityArbiter.CYCLONE_PACKAGE, DeviceRealitySurfaceKind.ACCESSIBILITY_OVERLAY, layer = 4, active = false, focused = false),
            ),
        )
        assertEquals(DeviceRealityArbiter.CYCLONE_PACKAGE, reality.taskPackage)
        assertEquals("cyclone_application_is_task", reality.reason)
    }

    @Test
    fun systemUiNeverBecomesTaskReality() {
        val reality = DeviceRealityArbiter.select(
            listOf(
                window(6, DeviceRealityArbiter.SYSTEM_UI_PACKAGE, DeviceRealitySurfaceKind.SYSTEM, layer = 20, active = true, focused = true),
                window(7, "com.android.chrome", DeviceRealitySurfaceKind.APPLICATION, layer = 1, active = false, focused = false),
            ),
        )
        assertEquals("com.android.chrome", reality.taskPackage)
    }

    @Test
    fun noApplicationProducesExplicitUngroundedReality() {
        val reality = DeviceRealityArbiter.select(
            listOf(window(8, DeviceRealityArbiter.CYCLONE_PACKAGE, DeviceRealitySurfaceKind.ACCESSIBILITY_OVERLAY, layer = 4, active = true, focused = true)),
        )
        assertNull(reality.taskWindowId)
        assertNull(reality.taskPackage)
        assertEquals("no_application_window", reality.reason)
    }

    private fun window(
        id: Int,
        pkg: String,
        kind: DeviceRealitySurfaceKind,
        layer: Int,
        active: Boolean,
        focused: Boolean,
    ) = DeviceRealityWindowCandidate(
        id = id,
        packageName = pkg,
        kind = kind,
        layer = layer,
        active = active,
        focused = focused,
    )
}
