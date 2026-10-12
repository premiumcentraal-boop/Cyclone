package com.cyclone.mobile.runtime.workspaces

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 57 (alpha.122): a switch from the PC gets the same safety as one on the phone: way back armed, confirmed, journaled. */
class ProfilePcSwitch122Test {
    private val context = object : ContextWrapper(null) {}
    private val b = CycloneProfileRecord("Cyclone_aaaaaaaaaaaaaaaa", "Profile B", 10, 0, true, setOf("com.example.social"), "READY", true)
    private val main = ProfileUserRecord(id = 0, name = "Owner", profile = false, managed = false, parentId = null, running = true, partial = false)
    private val userB = ProfileUserRecord(id = 10, name = b.id, profile = false, managed = false, parentId = null, running = true, partial = false)
    private val saved = ProfileApps.run
    private val savedUsers = ProfileApps.users
    private val savedRecords = ProfileApps.records
    private val savedPrepare = ProfileApps.prepare
    private val savedGated = ProfileApps.gated
    private val savedBusy = ProfileApps.busy
    private val savedSleep = ProfileApps.sleep
    private val savedArm = ProfileApps.armWayBack
    private val savedHello = ProfileApps.helloArrived
    private val savedRemember = ProfileApps.remember

    @After fun restore() {
        ProfileApps.run = saved; ProfileApps.users = savedUsers; ProfileApps.records = savedRecords; ProfileApps.prepare = savedPrepare
        ProfileApps.gated = savedGated; ProfileApps.busy = savedBusy; ProfileApps.sleep = savedSleep
        ProfileApps.armWayBack = savedArm; ProfileApps.helloArrived = savedHello; ProfileApps.remember = savedRemember
    }

    private fun phone(switches: Boolean, hello: Boolean = true, arms: Boolean = true): Pair<MutableList<String>, MutableList<ProfileSwitch.Record>> {
        var front = 0
        val log = mutableListOf<String>()
        val journal = mutableListOf<ProfileSwitch.Record>()
        ProfileApps.users = { listOf(main, userB) }
        ProfileApps.records = { listOf(b) }
        ProfileApps.gated = { false }
        ProfileApps.busy = { false }
        ProfileApps.sleep = {}
        ProfileApps.prepare = { _, user, _ -> log += "prepare $user" }
        ProfileApps.run = { command ->
            val shell = ProfileSetupPlan.shell(command)
            if (shell.contains("switch-user")) { log += "switch"; if (switches) front = 10 }
            if (shell.contains("get-current-user")) "$front" else ""
        }
        ProfileApps.armWayBack = { from, to, _ -> if (!arms) error("not listening"); log += "arm $from->$to" }
        ProfileApps.helloArrived = { _, _ -> hello }
        ProfileApps.remember = { journal += it }
        return log to journal
    }

    @Test fun `the way back is armed before the switch, and the switch is journaled`() {
        val (log, journal) = phone(switches = true)
        assertEquals(10, ProfileApps.switchTo(context, b.id))
        assertEquals(listOf("prepare 10", "arm 0->10", "switch"), log)
        val record = journal.single()
        assertEquals(ProfileSwitch.Outcome.DONE, record.outcome)
        assertEquals(listOf(ProfileSwitch.Stage.PREPARE, ProfileSwitch.Stage.ARM_RETURN, ProfileSwitch.Stage.SWITCH, ProfileSwitch.Stage.CONFIRM),
            record.stages.map { it.stage })
        assertTrue(record.label.contains("from the PC"))
    }

    @Test fun `a late hello stays armed, a failed switch is journaled as failed`() {
        phone(switches = true, hello = false).second.let { journal ->
            ProfileApps.switchTo(context, b.id)
            assertEquals(ProfileSwitch.Outcome.CONFIRM_LATE, journal.single().outcome)
        }
        phone(switches = false).second.let { journal ->
            assertThrows(ProfileApps.Refused::class.java) { ProfileApps.switchTo(context, b.id) }
            assertEquals(ProfileSwitch.Outcome.FAILED, journal.single().outcome)
        }
        // A target that isn't listening gets no way back armed, but the owner's switch still happens.
        phone(switches = true, arms = false).let { (log, journal) ->
            ProfileApps.switchTo(context, b.id)
            assertTrue("switch" in log)
            assertEquals(false, journal.single().stages.single { it.stage == ProfileSwitch.Stage.ARM_RETURN }.ok)
        }
    }
}
