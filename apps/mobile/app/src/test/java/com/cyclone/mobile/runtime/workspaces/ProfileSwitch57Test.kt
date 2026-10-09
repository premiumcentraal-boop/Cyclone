package com.cyclone.mobile.runtime.workspaces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 57 P1 (alpha.119): a switch that ends where asked or comes back by itself, and says why. */
class ProfileSwitch57Test {
    private val nonce = "0123456789abcdef0123456789abcdef"

    @Test fun `the wait is quick first, then patient, and bounded`() {
        val schedule = ProfileSwitch.waitSchedule()
        assertEquals(150L, schedule.first())
        assertEquals(1_000L, schedule.last())
        assertTrue(schedule.sum() in 20_000L..30_000L)
        assertTrue(schedule.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun `the dead-man return is built from numbers and a hex nonce only`() {
        val command = ProfileSwitch.returnCommand(0, 11, nonce)
        assertEquals("setsid sh -c 'sleep 45; [ \"\$(/system/bin/am get-current-user)\" = \"11\" ] && " +
            "[ ! -f /data/user_de/11/com.cyclone.mobile/files/switch-hello-$nonce ] && /system/bin/am switch-user 0' " +
            "</dev/null >/dev/null 2>&1 &", command)
        // It only ever switches back to where the owner was, only while the target is still in front.
        assertTrue(command.contains("= \"11\" ]") && command.endsWith("switch-user 0' </dev/null >/dev/null 2>&1 &"))
        assertThrows(IllegalArgumentException::class.java) { ProfileSwitch.returnCommand(0, 11, "x'; rm -rf /; '") }
        assertThrows(IllegalArgumentException::class.java) { ProfileSwitch.returnCommand(11, 11, nonce) }
        assertThrows(IllegalArgumentException::class.java) { ProfileSwitch.returnCommand(0, 11, nonce, seconds = 5) }
        assertEquals("/data/user_de/0/com.cyclone.mobile/files/switch-hello-$nonce", ProfileSwitch.helloPath(0, nonce))
        assertFalse(ProfileSwitch.validNonce("0123456789ABCDEF0123456789ABCDEF"))
    }

    @Test fun `each root manager gets its own one step, named for the profile`() {
        assertTrue(ProfileSwitch.rootGuidance(ProfileSetupPlan.RootManager.KERNELSU, "Profile C").contains("KernelSU → Superuser"))
        assertTrue(ProfileSwitch.rootGuidance(ProfileSetupPlan.RootManager.APATCH, "Profile C").contains("APatch → Superuser"))
        assertTrue(ProfileSwitch.rootGuidance(ProfileSetupPlan.RootManager.MAGISK, "Profile C").contains("User-independent"))
        ProfileSetupPlan.RootManager.values().forEach { assertTrue(ProfileSwitch.rootGuidance(it, "Profile C").contains("Profile C")) }
        assertTrue(ProfileSwitch.rootGuidance(null, "Profile C").contains("Profile C"))
    }

    private fun record(id: String, label: String, user: Int?) = CycloneProfileRecord(id, label, user, 0, true, emptySet(), "READY", true)
    private val b = record("Cyclone_aaaaaaaaaaaaaaaa", "Profile B", 10)
    private val c = record("Cyclone_bbbbbbbbbbbbbbbb", "Profile C", 11)
    private val d = record("Cyclone_cccccccccccccccc", "Profile D", 12)

    @Test fun `the main profile's list is the authority, others only add what is new`() {
        // From Main: replaced (a rename and a removal there are taken over).
        val renamed = b.copy(label = "Work")
        assertEquals(listOf(renamed, d), ProfileSwitch.mergeRegistry(listOf(b, c), listOf(renamed, d), fromMain = true))
        // From another profile: only new ones are added; nothing removed or renamed.
        assertEquals(listOf(b, c, d), ProfileSwitch.mergeRegistry(listOf(b, c), listOf(renamed, d), fromMain = false))
        assertEquals(listOf(b), ProfileSwitch.mergeRegistry(listOf(b), emptyList(), fromMain = false))
    }

    @Test fun `a switch record keeps every stage and reads back`() {
        var now = 1_000L
        val tracker = ProfileSwitch.Tracker { now }
        tracker.stage(ProfileSwitch.Stage.PREFLIGHT) { now += 40; 11 }
        tracker.note(ProfileSwitch.Stage.CARRY, false, "carry didn't arrive")
        assertThrows(IllegalStateException::class.java) {
            tracker.stage(ProfileSwitch.Stage.SWITCH) { now += 27_000; error("Android hasn't completed switching profiles yet.") }
        }
        assertEquals(listOf(true, false, false), tracker.results.map { it.ok })
        assertEquals(40L, tracker.results.first().ms)
        val record = ProfileSwitch.Record(5L, 0, 11, "Profile C", tracker.results.toList(), ProfileSwitch.Outcome.FAILED, "timed out")
        assertEquals(listOf(record), ProfileSwitch.decode(ProfileSwitch.encode(listOf(record))))
        assertEquals(emptyList<ProfileSwitch.Record>(), ProfileSwitch.decode("not json"))
    }

    @Test fun `Android's own user switcher is read truthfully`() {
        assertEquals(true, ProfileSwitch.userSwitcherOn("1\n"))
        assertEquals(true, ProfileSwitch.userSwitcherOn("null"))
        assertEquals(false, ProfileSwitch.userSwitcherOn("0"))
        assertNull(ProfileSwitch.userSwitcherOn("garbage"))
        assertEquals("/system/bin/settings put global user_switcher_enabled 1", ProfileSetupPlan.shell(ProfileSetupPlan.enableUserSwitcher()))
        assertEquals("/system/bin/settings get global user_switcher_enabled", ProfileSetupPlan.shell(ProfileSetupPlan.readUserSwitcher()))
    }

    @Test fun `the debug file carries the switches, redacted`() {
        val switch = ProfileSwitch.Record(1L, 0, 11, "Profile C",
            listOf(ProfileSwitch.StageResult(ProfileSwitch.Stage.PREPARE, false, 9, "token=abc123secret")), ProfileSwitch.Outcome.FAILED,
            "password: hunter2")
        val facts = ProfileDebugReport.Facts(1L, mapOf("versionName" to "x"), null, null, null, null, emptyList(), emptyMap(), emptyList(),
            emptyList(), null, null, switches = listOf(switch), userSwitcherOn = false)
        val text = ProfileDebugReport.json(facts).toString()
        assertTrue(text.contains("\"switches\"") && text.contains("PREPARE"))
        assertFalse(text.contains("hunter2") || text.contains("abc123secret"))
        val summary = ProfileDebugReport.summary(facts)
        assertTrue(summary.contains("Last switches") && summary.contains("Android's user switcher: off"))
    }
}
