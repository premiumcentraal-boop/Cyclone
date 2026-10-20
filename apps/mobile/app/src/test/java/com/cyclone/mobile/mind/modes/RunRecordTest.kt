package com.cyclone.mobile.mind.modes

import com.cyclone.mobile.mind.one.Ledger
import com.cyclone.mobile.mind.one.LedgerEntry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 75 (alpha 145): one record per request of where its time and money went, numbers under fixed names only. */
class RunRecordTest {
    @After fun clear() = AskLedger.clear()

    private val calls = listOf(
        RunRecord.Call("step", "~typesafe/jev-latest", 310, 4_000, 0.0002, true),
        RunRecord.Call("step", "openai/gpt-6-luna-decisions", 1_900, 6_000, 0.0008, true),
        RunRecord.Call("step", "openai/gpt-6-luna-decisions", 1_800, 0, 0.0, false),
        RunRecord.Call("plan", "test/model", 4_200, 9_000, 0.003, true),
    )

    @Test fun `calls are summed by rung with their median and slowest time`() {
        val json = RunRecord.calls(calls)
        assertEquals(4, json.getInt("count"))
        assertEquals(0.004, json.getDouble("costUsd"), 1e-9)
        val step = json.getJSONObject("byRung").getJSONObject("step")
        assertEquals(3, step.getInt("calls")); assertEquals(1, step.getInt("failed"))
        assertEquals(1_800L, step.getLong("p50Ms")); assertEquals(1_900L, step.getLong("maxMs"))
        assertEquals(10_000, step.getInt("inputTokens"))
    }

    @Test fun `the record holds numbers and fixed names, never words from the run`() {
        val record = RunRecord.build(12_000, listOf("route" to 300L, "read" to 200L, "read" to 150L), calls,
            RunRecord.Moves(byCode = 2, asked = 3, splits = 1), mapOf("plan" to 1, "consult" to 2), recipe = false, asks = 0, end = "done")
        assertEquals(350L, record.getJSONObject("stages").getLong("read"))
        assertEquals(1, record.getJSONObject("moves").getInt("splits"))
        assertEquals(2, record.getJSONObject("wakes").getInt("consult"))
        // Every string in it is a key or the end word: no model ids, no request, no screen text.
        fun strings(node: Any?): List<String> = when (node) {
            is JSONObject -> node.keys().asSequence().flatMap { strings(node.get(it)) }.toList()
            is String -> listOf(node)
            else -> emptyList()
        }
        assertEquals(listOf("done"), strings(record))
    }

    @Test fun `wake kinds are read from the score's wake lines, and ledger rows keep their rung`() {
        assertEquals(mapOf("plan" to 1, "consult" to 2), RunRecord.wakes(listOf("Plan: A new task", "Consult: unsure", "Consult: the owner sent a message", "Oops: x")))
        val ledger = Ledger(listOf(LedgerEntry(1, "decisions", "~typesafe/jev-latest", ms = 300, stage = "step"), LedgerEntry(2, "mind", "m", ms = 9)))
        val back = Ledger.decode(ledger.encode()).entries()
        assertEquals(listOf("step", "mind"), back.map { RunRecord.Call.of(it).rung })
    }

    @Test fun `a handed-up request's record joins the mission's when the mission ends`() {
        val id = AskLedger.begin("open my profile")
        AskLedger.routed("open my profile", "instant", "grammar", "a clear command", 2)
        val instant = RunRecord.build(6_000, listOf("route" to 300L, "instant" to 5_400L),
            listOf(RunRecord.Call("race", "~typesafe/jev-latest", 300, 0, 0.0001, true)), end = "handed_up")
        AskLedger.handed("open my profile", "flash", "the tap didn't change the screen", "mission-1", record = instant)
        val mission = RunRecord.build(20_000, listOf("think" to 9_000L), calls, RunRecord.Moves(asked = 3), mapOf("plan" to 1), end = "done")
        AskLedger.missionEnded("mission-1", ok = true, say = "Done.", record = mission)
        val record = AskLedger.get(id)!!.record
        assertNotNull(record)
        assertEquals(26_000L, record!!.getLong("totalMs"))
        assertEquals(5, record.getJSONObject("calls").getInt("count"))
        assertTrue(record.getJSONObject("calls").getJSONObject("byRung").has("race"))
        assertEquals(300L, record.getJSONObject("stages").getLong("route"))
        assertEquals("done", record.getString("end"))
    }

    @Test fun `a tally counts only while it is open`() {
        val tally = CallTally()
        tally.add(calls[0])
        assertEquals(1, tally.close().size)
        tally.add(calls[1])
        assertFalse(tally.open)
        assertEquals(1, tally.close().size)
    }
}
