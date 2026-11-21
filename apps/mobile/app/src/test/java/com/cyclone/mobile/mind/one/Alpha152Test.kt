package com.cyclone.mobile.mind.one

import com.cyclone.mobile.mind.MindModel
import com.cyclone.mobile.mind.MindModelReply
import com.cyclone.mobile.mind.MindModelRequest
import com.cyclone.mobile.mind.MindUsage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Alpha 152: the answer the owner was given is the run's summary when the plan runs out. Before this, the run reported the
 * milestone titles ("Done: m.") and the owner never saw the answer.
 */
class Alpha152Test {
    private val plan = """{"milestone":"m"}
        {"step":"tap","target":"Anna"}""".trimIndent()

    @Test fun `a run that ends when its plan runs out reports the owner's answer, not the milestone titles`() {
        val model = Script(plan, """{"decision":"continue"}""", """{"decision":"continue"}""")
        val crew = Stub(answer = "The square root of 7569 is 87.")
        val out = conductor(model, crew).run()
        assertEquals(RunEnd.DONE, out.end)
        assertEquals("The square root of 7569 is 87.", out.summary)
    }

    @Test fun `without an answer the run keeps the milestone report`() {
        val model = Script(plan, """{"decision":"continue"}""", """{"decision":"continue"}""")
        val out = conductor(model, Stub(answer = null)).run()
        assertEquals(RunEnd.DONE, out.end)
        assertEquals("Done: m.", out.summary)
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

    private class Stub(private val answer: String?) : Crew {
        override fun run(step: Step, score: Score): StepOutcome = StepOutcome.Done(step, Verdict.HELD, 0)
        override fun actAt(barrier: StepOutcome.AtBarrier, score: Score) = StepOutcome.Done(barrier.step, Verdict.HELD, 1)
        override fun look(image: Boolean) = StepScreen("com.google.android.calculator", "Calculator", "main", "f1",
            listOf(StepControl("e1", "7")), listOf("87"))
        override fun check(expect: Expect, score: Score) = Verdict.UNKNOWN
        override fun done(score: Score, screen: StepScreen?) = 0.95
        override fun undo(moves: Int) {}
        override fun finalAnswer(): String? = answer
    }

    private class Owner : OwnerLine {
        override fun ask(question: String, choices: List<String>): String? = null
        override fun checkIn(reason: String) = CheckIn.Stop
    }

    private fun conductor(model: MindModel, crew: Crew) =
        Conductor("run-152", "What is the square root of 7569?", Profile.MIND, crew, { _: Effort -> model }, Owner(),
            clock = { 1_000L }, glanceRunner = { it() }, sleep = {})
}
