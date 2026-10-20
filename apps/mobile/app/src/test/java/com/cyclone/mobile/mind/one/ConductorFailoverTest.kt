package com.cyclone.mobile.mind.one

import com.cyclone.mobile.mind.MindModel
import com.cyclone.mobile.mind.MindModelError
import com.cyclone.mobile.mind.MindModelReply
import com.cyclone.mobile.mind.MindModelRequest
import com.cyclone.mobile.mind.MindUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan 75 (alpha 145): a run doesn't end because one model had a bad minute. The Conductor asks again, moves to the
 * owner's backup model, waits out a rate limit, and keeps to the owner's working time.
 */
class ConductorFailoverTest {
    private val plan = """
        {"milestone":"Open Anna's chat"}
        {"step":"open_app","args":{"app":"WhatsApp"},"expect":{"app":"WhatsApp"}}
    """.trimIndent()

    /** Answers from a script; an exception in the script is thrown instead. */
    private class Model(override val label: String, vararg script: Any) : MindModel {
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

    private class Crew1(val onRun: () -> Unit = {}) : Crew {
        override fun run(step: Step, score: Score): StepOutcome { onRun(); return StepOutcome.Done(step, Verdict.HELD, 0) }
        override fun actAt(barrier: StepOutcome.AtBarrier, score: Score) = StepOutcome.Done(barrier.step, Verdict.HELD, 1)
        override fun look(image: Boolean) = StepScreen("com.whatsapp", "WhatsApp", "list", "f", listOf(StepControl("e1", "Chats")), listOf("Chats"))
        override fun check(expect: Expect, score: Score) = Verdict.UNKNOWN
        override fun done(score: Score, screen: StepScreen?) = 0.95
        override fun undo(moves: Int) {}
    }

    private class Owner(val onAsk: () -> Unit = {}) : OwnerLine {
        override fun ask(question: String, choices: List<String>): String? { onAsk(); return "Anna de Vries" }
        override fun checkIn(reason: String) = CheckIn.Stop
    }

    private fun conductor(primary: MindModel, backup: MindModel?, sleeps: MutableList<Long> = mutableListOf(),
                          clock: () -> Long = { 1_000L }, crew: Crew = Crew1(), owner: OwnerLine = Owner(), workingMs: Long? = null) =
        Conductor("run-1", "Open Anna's chat", Profile.MIND, crew, { primary }, owner, clock = clock, sleep = { sleeps += it },
            backup = backup?.let { b -> { _: Effort -> b } }, workingMs = workingMs)

    @Test fun `a model that refuses the request hands the run to the backup, which keeps it`() {
        val primary = Model("Primary", MindModelError.Fatal("400 bad request"))
        val backup = Model("Backup", plan)
        val c = conductor(primary, backup)
        val out = c.run()
        assertEquals(RunEnd.DONE, out.end)
        assertEquals(1, primary.calls)
        assertEquals(1, backup.calls)
        assertTrue(out.score.events.any { it.kind == "model" && it.line.contains("Backup") })
    }

    @Test fun `a transient failure is asked again on the same model first`() {
        val primary = Model("Primary", MindModelError.Transient("502"), plan)
        val backup = Model("Backup")
        assertEquals(RunEnd.DONE, conductor(primary, backup).run().end)
        assertEquals(2, primary.calls)
        assertEquals(0, backup.calls)
    }

    @Test fun `with no backup a rate limit is waited out, at most five seconds a time and twice`() {
        val sleeps = mutableListOf<Long>()
        val ok = Model("Primary", MindModelError.RateLimited("429", 9_000), MindModelError.RateLimited("429", 200), plan)
        assertEquals(RunEnd.DONE, conductor(ok, null, sleeps).run().end)
        assertEquals(listOf(5_000L, 1_000L), sleeps)
        val stuck = Model("Primary", *Array<Any>(3) { MindModelError.RateLimited("429", 1_000) })
        assertEquals(RunEnd.FAILED, conductor(stuck, null).run().end)
    }

    @Test fun `with nothing left to try the run fails and says which model`() {
        val out = conductor(Model("Primary", MindModelError.Fatal("400")), null).run()
        assertEquals(RunEnd.FAILED, out.end)
        assertTrue(out.summary.contains("Primary"))
        val both = conductor(Model("Primary", MindModelError.Fatal("400")), Model("Backup", MindModelError.Fatal("401"))).run()
        assertEquals(RunEnd.FAILED, both.end)
        assertTrue(both.summary.contains("Backup"))
    }

    @Test fun `the owner's working time ends the run honestly, and waiting on the owner doesn't count`() {
        var now = 0L
        val slow = conductor(Model("Primary", plan + "\n" + """{"step":"tap","target":"Chats"}"""), null, clock = { now },
            crew = Crew1 { now += 70_000L }, workingMs = 60_000L).run()
        assertEquals(RunEnd.GAVE_UP, slow.end)
        assertTrue(slow.summary.contains("working time"))

        now = 0L
        val ask = """{"decision":"ask","question":"Which Anna?","choices":["Anna de Vries","Anna Smit"],"consent":true}"""
        val waited = conductor(Model("Primary", ask, plan), null, clock = { now }, owner = Owner { now += 10 * 60_000L },
            workingMs = 60_000L).run()
        assertEquals(RunEnd.DONE, waited.end)
    }
}
