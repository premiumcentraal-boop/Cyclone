package com.cyclone.mobile.mind.one

import com.cyclone.mobile.mind.MindModel
import com.cyclone.mobile.mind.MindModelReply
import com.cyclone.mobile.mind.MindModelRequest
import com.cyclone.mobile.mind.MindUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan 75 alpha 150: the captain checks a Flash run's milestones in the background, as Mind's glances do; the first officer
 * still plans and checks barriers, and it never answers a glance.
 */
class Alpha150Test {
    private class Counting(name: String, vararg replies: String) : MindModel {
        override val id = "test/$name"
        override val label = name
        override val vision = false
        var calls = 0
        private val queue = ArrayDeque(replies.toList())
        override fun complete(request: MindModelRequest): MindModelReply {
            calls++
            return MindModelReply(queue.removeFirstOrNull() ?: """{"decision":"continue"}""", emptyList(), usage = MindUsage(1_000, 50, 0.002))
        }
    }

    private class Stub : Crew {
        override fun run(step: Step, score: Score): StepOutcome = StepOutcome.Done(step, Verdict.HELD, 0)
        override fun actAt(barrier: StepOutcome.AtBarrier, score: Score) = StepOutcome.Done(barrier.step, Verdict.HELD, 1)
        override fun look(image: Boolean) = StepScreen("com.whatsapp", "WhatsApp", "list", "f1", listOf(StepControl("e1", "Anna de Vries")), listOf("Chats"))
        override fun check(expect: Expect, score: Score) = Verdict.UNKNOWN
        override fun done(score: Score, screen: StepScreen?) = 0.95
        override fun undo(moves: Int) {}
    }

    private class Owner : OwnerLine {
        override fun ask(question: String, choices: List<String>): String? = null
        override fun checkIn(reason: String) = CheckIn.Stop
    }

    @Test fun `the captain glances at a Flash milestone that is marked for review, and the officer does not`() {
        val officer = Counting("officer", """{"milestone":"Send","review":true}
            {"step":"tap","target":"Anna de Vries","expect":{"shows":"Anna de Vries"}}""".trimIndent())
        val captain = Counting("captain")
        val conductor = Conductor("run-1", "Send Anna a message", Profile.FLASH, Stub(), { _: Effort -> captain }, Owner(), clock = { 1_000L },
            glanceRunner = { it() }, sleep = {}, firstOfficer = { _: Effort -> officer })
        val out = conductor.run()
        assertEquals(RunEnd.DONE, out.end)
        assertEquals("the officer planned, and never answered the glance", 1, officer.calls)
        assertTrue("the captain glanced at the finished milestone", captain.calls >= 1)
        assertTrue(conductor.ledger.entries().any { it.stage == "glance" })
        assertTrue(conductor.ledger.entries().none { it.stage == "officer_glance" })
    }

    @Test fun `a Mind run's glances are unchanged, and the Flash profile glances only with its own flag`() {
        assertTrue(Profile.FLASH.glances)
        assertTrue(!Profile.MIND.glances && Profile.MIND.reviews)
    }
}
