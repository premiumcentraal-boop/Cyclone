package com.cyclone.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeviceRealityWiringTest {
    @Test
    fun productionObservationUsesTaskWindowArbitrationInsteadOfBlindActiveRoot() {
        val source = source("CycloneAccessibilityService.kt")
        assertTrue(source.contains("DeviceRealityArbiter.select(candidates, applicationContext.packageName)"))
        assertTrue(source.contains("reality.taskWindowId"))
        assertTrue(source.contains("AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> DeviceRealitySurfaceKind.ACCESSIBILITY_OVERLAY"))
        assertTrue(source.contains("GATE remains observable through OverlayChromeObservation"))
        assertFalse(source.contains("val root = preferredForegroundRoot() ?: rootInActiveWindow"))
    }

    @Test
    fun canonicalSnapshotClassAndPackageComeFromSelectedTaskRoot() {
        val source = source("CycloneAccessibilityService.kt")
        assertTrue(source.contains("val packageName = root?.packageName"))
        assertTrue(source.contains("val className = root?.className"))
        assertTrue(source.contains("DeviceState.currentPackage = it"))
    }

    private fun source(name: String): String {
        val relative = "src/main/java/com/cyclone/mobile/$name"
        return sequenceOf(
            File(relative),
            File("apps/mobile/app/$relative"),
            File(System.getProperty("user.dir"), relative),
            File(System.getProperty("user.dir"), "apps/mobile/app/$relative"),
        ).first { it.isFile }.readText()
    }
}
