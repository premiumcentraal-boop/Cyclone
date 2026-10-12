package com.cyclone.mobile.gateway

import com.cyclone.mobile.runtime.workspaces.RootStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetRootSignalsTest {
    private fun detect(files: Set<String>, path: String? = null, tags: String? = "release-keys", probe: RootStatus = RootStatus.UNKNOWN) =
        FleetRootSignals.detect({ it in files }, path, tags, probe)

    @Test
    fun aStockPhoneIsNotRooted() {
        val result = detect(emptySet())
        assertFalse(result.rooted)
        assertFalse(result.verified)
        assertEquals(emptyList<String>(), result.signals)
    }

    @Test
    fun magiskAndAnSuBinaryReadAsRooted() {
        val result = detect(setOf("/data/adb/magisk", "/system/xbin/su"))
        assertTrue(result.rooted)
        assertEquals(listOf("su", "magisk"), result.signals)
    }

    @Test
    fun suOnThePathCounts() {
        assertTrue(detect(setOf("/debug_ramdisk/su"), path = "/product/bin:/debug_ramdisk:/system/bin").rooted)
    }

    @Test
    fun kernelSuAndApatchCount() {
        assertEquals(listOf("kernelsu"), detect(setOf("/data/adb/ksud")).signals)
        assertEquals(listOf("apatch"), detect(setOf("/data/adb/ap")).signals)
    }

    @Test
    fun testKeysAloneIsReportedButNotRoot() {
        val result = detect(emptySet(), tags = "test-keys")
        assertFalse(result.rooted)
        assertEquals(listOf("test-keys"), result.signals)
    }

    @Test
    fun theOwnersVerifiedRootCheckWins() {
        val result = detect(emptySet(), probe = RootStatus.ROOTED)
        assertTrue(result.rooted)
        assertTrue(result.verified)
    }
}
