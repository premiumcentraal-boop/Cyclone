package com.cyclone.mobile.mind.one

import com.cyclone.mobile.mind.MindModel
import com.cyclone.mobile.mind.MindModelReply
import com.cyclone.mobile.mind.MindModelRequest
import com.cyclone.mobile.mind.MindUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan 74 (alpha 149): an owner card only for a need code can prove only the owner can answer. A clarification between
 * choices is assumed and said; stuck is never a card (a Flash run hands over once, then the run stops honestly).
 */
class Alpha149Test {
    @Test fun `only a hard need raises an owner card`() {
        assertTrue(NeedGate.raisesCard(NeedKind.CONSENT))
        assertTrue(NeedGate.raisesCard(NeedKind.SECRET))
        assertTrue(NeedGate.raisesCard(NeedKind.HANDS))
        assertTrue(NeedGate.raisesCard(NeedKind.UNKNOWABLE))
        for (soft in listOf(NeedKind.CLARIFY, NeedKind.STUCK, NeedKind.VERIFY, NeedKind.PREFERENCE, NeedKind.INPUT, NeedKind.PLACE)) {
            assertFalse("$soft is settled by Cyclone", NeedGate.raisesCard(soft))
        }
    }

    @Test fun `a question kind comes from what it asks, consent, a choice between two, or a fact`() {
        assertEquals(NeedKind.CONSENT, NeedGate.kindOf(consent = true, choices = emptyList()))
        assertEquals(NeedKind.CLARIFY, NeedGate.kindOf(consent = false, choices = listOf("Anna de Vries", "Anna Smit")))
        assertEquals("a blank choice is not a choice", NeedKind.UNKNOWABLE, NeedGate.kindOf(false, listOf("Anna", " ")))
        assertEquals(NeedKind.UNKNOWABLE, NeedGate.kindOf(consent = false, choices = emptyList()))
    }

    /** Answers from a script, one reply per call. */
    private class Script(vararg replies: String) : MindModel {
        override val id = "test/script"
        override val label = "Test model"
        override val vision = false
        private val queue = ArrayDeque(replies.toList())
        override fun complete(request: MindModelRequest): MindModelReply =
            MindModelReply(queue.removeFirstOrNull() ?: """{"decision":"give_up","summary":"the script ran out"}""", emptyList(),
                usage = MindUsage(1_000, 50, 0.002))
    }

    private class Stub(val script: (Step, Int) -> StepOutcome) : Crew {
        private val seen = HashMap<String, Int>()
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

    private class Owner(var answer: String? = "Anna de Vries") : OwnerLine {
        val questions = mutableListOf<String>()
        override fun ask(question: String, choices: List<String>): String? { questions += question; return answer }
        override fun checkIn(reason: String) = CheckIn.Stop
    }

    private val plan = """{"milestone":"m"}
        {"step":"tap","target":"Anna"}""".trimIndent()

    private fun conductor(model: MindModel, crew: Crew, owner: Owner, profile: Profile = Profile.MIND) =
        Conductor("run-1", "Tell Anna I'm on my way", profile, crew, { _: Effort -> model }, owner, clock = { 1_000L },
            glanceRunner = { it() }, sleep = {})

    @Test fun `a clarification between choices is assumed and said, with no card to the owner`() {
        val ask = """{"decision":"ask","question":"Which Anna?","choices":["Anna de Vries","Anna Smit"],"consent":false}"""
        // plan; the mind asks; asks the same again (so the question is no longer new); then carries on with its assumption.
        val model = Script(plan, ask, ask, """{"decision":"continue"}""", """{"decision":"continue"}""", """{"decision":"continue"}""")
        val crew = Stub { step, n -> if (n == 1) StepOutcome.NeedsMind(step, "unsure", "which Anna?") else StepOutcome.Done(step, Verdict.HELD, 0) }
        val owner = Owner()
        val out = conductor(model, crew, owner).run()
        assertEquals(RunEnd.DONE, out.end)
        assertTrue("the owner was not asked", owner.questions.isEmpty())
        assertTrue(out.score.events.any { it.kind == "assumed" && it.line.contains("Anna de Vries") })
    }

    @Test fun `a question with no choices asks the owner, who answers`() {
        val ask = """{"decision":"ask","question":"What is the code on the card?","consent":false}"""
        val model = Script(plan, ask, ask, """{"decision":"continue"}""", """{"decision":"continue"}""", """{"decision":"continue"}""")
        val crew = Stub { step, n -> if (n == 1) StepOutcome.NeedsMind(step, "unsure", "need a fact") else StepOutcome.Done(step, Verdict.HELD, 0) }
        val owner = Owner(answer = "1234")
        val out = conductor(model, crew, owner).run()
        assertEquals(RunEnd.DONE, out.end)
        assertEquals("the owner was asked the one fact only they have", 1, owner.questions.size)
    }

    @Test fun `a stuck Flash run hands over to the captain once, and a stuck Mind run stops honestly`() {
        val stuck = Stub { step, _ -> StepOutcome.NeedsMind(step, "unsure", "can't tell") }
        // Every answer is the plan: the captain writes one after the hand-over, and the stuck wakes get a plan back each time.
        val flash = object : MindModel {
            override val id = "test/plan"
            override val label = "Test model"
            override val vision = false
            override fun complete(request: MindModelRequest) = MindModelReply(plan, emptyList(), usage = MindUsage(1_000, 50, 0.002))
        }
        val owner = Owner()
        val out = conductor(flash, stuck, owner, profile = Profile.FLASH).run()
        assertTrue("the Flash run handed over once", out.score.events.any { it.kind == "handover" })
        assertTrue("no owner question for being stuck", owner.questions.isEmpty())
        assertEquals(RunEnd.GAVE_UP, out.end)
        assertTrue(out.summary.contains("Couldn't finish") && out.summary.contains("unsure"))
    }
}
