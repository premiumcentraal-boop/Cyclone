package com.cyclone.mobile.mind.one

import com.cyclone.mobile.mind.MindModel
import com.cyclone.mobile.mind.MindModelError
import com.cyclone.mobile.mind.MindModelReply
import com.cyclone.mobile.mind.MindModelRequest
import com.cyclone.mobile.mind.MindUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan 75 alpha 147: a Flash run's first officer plans, consults and checks barriers; the captain re-plans. Code keeps the
 * officer inside its milestone and the run's done check fixed; a doubt at a barrier goes to the captain.
 */
class FirstOfficerTest {
    private val plan = """
        {"milestone":"Send the message"}
        {"step":"open_app","args":{"app":"WhatsApp"},"expect":{"app":"WhatsApp"}}
        {"step":"tap","target":"Anna de Vries","expect":{"shows":"Anna de Vries"}}
    """.trimIndent()

    /** Answers from a script; an exception in the script is thrown instead. Counts its calls. */
    private class Script(override val label: String, vararg script: Any) : MindModel {
        override val id = "test/$label"
        override val vision = false
        val queue = ArrayDeque(script.toList())
        var calls = 0
        override fun complete(request: MindModelRequest): MindModelReply {
            calls++
            return when (val next = queue.removeFirstOrNull() ?: """{"decision":"give_up","summary":"the script ran out"}""") {
                is Exception -> throw next
                else -> MindModelReply(next.toString(), emptyList(), usage = MindUsage(1_000, 50, 0.002))
            }
        }
    }

    private class Stub(val script: (Step, Int) -> StepOutcome) : Crew {
        private val seen = HashMap<String, Int>()
        /** Counts by what the step does, not its id: a re-plan gives the same move a new id. */
        override fun run(step: Step, score: Score): StepOutcome {
            val key = "${step.action}|${step.target}"
            val n = (seen[key] ?: 0) + 1
            seen[key] = n
            return script(step, n)
        }
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

    private fun conductor(captain: Script, officer: Script?, crew: Crew) =
        Conductor("run-1", "Send Anna a message", Profile.FLASH, crew, { _: Effort -> captain }, Owner(), clock = { 1_000L },
            glanceRunner = { it() }, sleep = {}, firstOfficer = officer?.let { o -> { _: Effort -> o } })

    @Test fun `the officer plans, and its re-plan beyond the current milestone goes to the captain`() {
        val officer = Script("officer", plan, """
            {"milestone":"Somewhere else"}
            {"step":"open_app","args":{"app":"Chrome"},"expect":{"app":"Chrome"}}
        """.trimIndent())
        val captain = Script("captain", """
            {"milestone":"Send it"}
            {"step":"tap","target":"Anna de Vries","expect":{"shows":"Anna de Vries"}}
        """.trimIndent())
        val crew = Stub { step, n -> if (step.action == "tap" && n == 1) StepOutcome.NeedsMind(step, "unsure", "not sure") else StepOutcome.Done(step, Verdict.HELD, 0) }
        val out = conductor(captain, officer, crew).run()
        assertEquals(RunEnd.DONE, out.end)
        assertEquals("the officer planned and consulted", 2, officer.calls)
        assertEquals("the captain decided the re-plan that left the milestone", 1, captain.calls)
        assertTrue(out.score.events.any { it.kind == "officer" && it.line.contains("beyond this milestone") })
    }

    @Test fun `a re-plan can never change the done check the run started with`() {
        val started = plan.trimEnd() + "\n" + """{"done":{"shows":"Paid"}}"""
        val officer = Script("officer", started)
        val captain = Script("captain", """
            {"milestone":"Send the message"}
            {"step":"tap","target":"Anna de Vries","expect":{"shows":"Anna de Vries"}}
            {"done":{"shows":"Sent"}}
        """.trimIndent())
        val crew = Stub { step, n -> if (step.action == "tap" && n == 1) StepOutcome.NeedsMind(step, "drift", "the screen drifted") else StepOutcome.Done(step, Verdict.HELD, 0) }
        val out = conductor(captain, officer, crew).run()
        assertEquals(RunEnd.DONE, out.end)
        assertEquals("the contract holds", Expect.Shows("Paid"), out.score.plan?.done)
        assertEquals(1, captain.calls)
    }

    @Test fun `an officer that can't answer hands the run to the captain at once`() {
        val officer = Script("officer", MindModelError.Fatal("400 bad request"))
        val captain = Script("captain", plan)
        val crew = Stub { step, _ -> StepOutcome.Done(step, Verdict.HELD, 0) }
        val out = conductor(captain, officer, crew).run()
        assertEquals(RunEnd.DONE, out.end)
        assertEquals("the officer was tried once, not retried", 1, officer.calls)
        assertTrue(captain.calls >= 1)
        assertTrue(out.score.events.any { it.kind == "model" && it.line.contains("the captain takes over") })
    }

    @Test fun `a doubt at a barrier goes to the captain, and a yes lets the move go on`() {
        val officer = Script("officer", plan, """{"decision":"give_up","summary":"not sure this is what the goal asked for"}""")
        val captain = Script("captain", """{"decision":"continue","why":"it is the move"}""")
        var barrierRuns = 0
        val crew = Stub { step, _ ->
            if (step.action == "tap") { barrierRuns++; StepOutcome.AtBarrier(step, "e1", "Anna de Vries") } else StepOutcome.Done(step, Verdict.HELD, 0)
        }
        val out = conductor(captain, officer, crew).run()
        assertEquals(RunEnd.DONE, out.end)
        assertEquals("the officer checked the barrier", 2, officer.calls)
        assertEquals("the captain decided the doubt", 1, captain.calls)
        assertTrue("the move was made once the captain said so", barrierRuns >= 1)
    }

    @Test fun `only plans, consults and glances go to the officer, and a re-plan of the whole run stays with the captain`() {
        assertTrue(FirstOfficer.answers(WakeKind.PLAN))
        assertTrue(FirstOfficer.answers(WakeKind.CONSULT))
        assertTrue(FirstOfficer.answers(WakeKind.GLANCE))
        assertFalse(FirstOfficer.answers(WakeKind.REPLAN))
        assertFalse(FirstOfficer.answers(WakeKind.WHEEL))
    }

    @Test fun `a plan keeps to its milestone when only the current milestone's steps change`() {
        val step = Step("s1", "tap", target = "A")
        val old = Plan(1, listOf(Milestone("m1", "One", listOf(step)), Milestone("m2", "Two", listOf(Step("s2", "tap", target = "B")))))
        val onlyCurrent = Plan(2, listOf(Milestone("m1", "One", listOf(Step("s9", "tap", target = "Z"))),
            Milestone("m2", "Two", listOf(Step("s2", "tap", target = "B")))))
        assertTrue(FirstOfficer.keepsMilestones(old, onlyCurrent, "m1"))
        val otherChanged = Plan(2, listOf(Milestone("m1", "One", listOf(step)), Milestone("m2", "Two", listOf(Step("s3", "tap", target = "C")))))
        assertFalse(FirstOfficer.keepsMilestones(old, otherChanged, "m1"))
        val added = Plan(2, old.milestones + Milestone("m3", "Three", emptyList()))
        assertFalse(FirstOfficer.keepsMilestones(old, added, "m1"))
    }
}
