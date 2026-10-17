package com.cyclone.mobile.runtime.safe

import org.junit.Assert.*
import org.junit.Test

class SafePlansTest {
    @Test fun rootPathsAreFixed() {
        assertEquals("safe-17-12.cyc", SafeRootPlan.name(17, 12))
        assertTrue(SafeRootPlan.write(17, 12).contains("/data/adb/cyclone-safe/u17/safe-17-12.cyc.part"))
        assertFalse(SafeRootPlan.write(17, 12).contains("rm "))
        assertFalse(SafeRootPlan.createRootKey().contains("safe.key.part"))
        assertTrue(SafeRootPlan.createRootKey().contains("set -C"))
    }
    @Test(expected = IllegalArgumentException::class) fun negativeUserRejected() { SafeRootPlan.list(-1) }
    @Test(expected = IllegalArgumentException::class) fun negativeSequenceRejected() { SafeRootPlan.name(0, -1) }
    @Test(expected = IllegalArgumentException::class) fun excessiveUserRejected() { SafeRootPlan.name(Int.MAX_VALUE, 1) }
    @Test fun restoreOrderIsDatabasesFilesPrefs() {
        val input = listOf("prefs/ce/a.xml", "sealed/provider", "files/a", "sealed/mind", "databases/a.db")
            .map { SafeSnapshot.Entry(it, byteArrayOf()) }
        val written = mutableListOf<String>()
        SafeRestore.apply(input, { true }) { written += it.path }
        assertEquals(listOf("databases/a.db", "files/a", "sealed/mind", "prefs/ce/a.xml", "sealed/provider"), written)
    }
    @Test fun unknownRestoreEntryStopsBeforeFirstWrite() {
        var writes = 0
        try {
            SafeRestore.apply(listOf(SafeSnapshot.Entry("files/a", byteArrayOf()), SafeSnapshot.Entry("files/b", byteArrayOf())), { it != "files/b" }) { writes++ }
            fail("reject")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, writes)
    }
    @Test fun sourcePriorityAndNewestPerUser() {
        val copies = listOf(SafeRestore.Candidate(SafeRestore.Source.FOLDER, 17, 9, 100, 1), SafeRestore.Candidate(SafeRestore.Source.ROOT, 17, 2, 100, 1),
            SafeRestore.Candidate(SafeRestore.Source.ROOT, 17, 3, 100, 1), SafeRestore.Candidate(SafeRestore.Source.ROOT, 19, 4, 100, 1))
        assertEquals(9L, SafeRestore.find(copies, setOf(17)).single().seq)
    }
    @Test fun wrongUserRestoreIsRejectedBeforeAnyWrite() {
        val key = SafeKey.fresh()
        val archive = java.io.ByteArrayOutputStream().also {
            SafeSnapshot.write(it, key, SafeSnapshot.Identity(17, "profile", "phone_a", 1, 1), emptyList(), org.json.JSONObject())
        }
        var writes = 0
        try {
            SafeRestore.from(archive.toByteArray().inputStream(), key, 0, { true }) { writes++ }
            fail("wrong Android user")
        } catch (_: IllegalStateException) { }
        assertEquals(0, writes)
    }
    @Test fun manifestClassificationsAreUniqueWithReasons() {
        assertEquals(SafeManifest.stores.size, SafeManifest.stores.map { it.location to it.name }.distinct().size)
        assertTrue(SafeManifest.stores.all { it.reason.isNotBlank() })
        assertEquals(SafeManifest.Kind.SAFE_SEALED, SafeManifest.file("Cyclone Brain/Mind memory.json")!!.kind)
        assertEquals(SafeManifest.Kind.NEVER, SafeManifest.file("Cyclone Brain/Missions/queue.json")!!.kind)
    }
    @Test fun deleteOrderAndStopOnEachFailure() {
        SafeDelete.Step.entries.forEach { failed ->
            val seen = mutableListOf<SafeDelete.Step>()
            val result = SafeDelete.run(SafeDelete.Confirmation.afterScreenLock(1000), 1001) { seen += it; if (it == failed) error("fixture") }
            assertEquals(SafeDelete.Step.entries.take(failed.ordinal + 1), seen)
            assertEquals(failed, result.stoppedAt)
            assertEquals("Nothing more was deleted", result.message)
        }
    }
    @Test fun deleteConfirmationIsOneShot() {
        val confirmation = SafeDelete.Confirmation.afterScreenLock(1000)
        assertNull(SafeDelete.run(confirmation, 1001) {}.stoppedAt)
        try { SafeDelete.run(confirmation, 1002) {}; fail("replay") } catch (_: IllegalStateException) { }
    }
    @Test fun expiredConfirmationNeverStartsDeleting() {
        var touched = false
        try { SafeDelete.run(SafeDelete.Confirmation.afterScreenLock(1000), 61001) { touched = true }; fail("expired") } catch (_: IllegalStateException) { }
        assertFalse(touched)
    }
}
