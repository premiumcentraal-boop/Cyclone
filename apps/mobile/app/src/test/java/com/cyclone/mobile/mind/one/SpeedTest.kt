package com.cyclone.mobile.mind.one

import com.cyclone.mobile.decisions.DAnswer
import com.cyclone.mobile.decisions.DReply
import com.cyclone.mobile.mind.MindToolCall
import com.cyclone.mobile.mind.MindToolResult
import com.cyclone.mobile.mind.MindToolSpec
import com.cyclone.mobile.mind.MindToolbox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan 75 alpha 146 (speed without risk): a split on an easy-to-undo move is settled by one narrow follow-up, never for a
 * move that can't be undone; the read after a move starts the next one; a slow provider is set aside; a clean, sure answer
 * is what the early stop accepts.
 */
class SpeedTest {
    private val chat = StepScreen("com.whatsapp", "WhatsApp", "list", "f1",
        listOf(StepControl("e1", "Anna de Vries", "button"), StepControl("e2", "Anna Smit", "button")), listOf("Chats"))
    private val withImage = chat.copy(imageDataUrl = "data:image/png;base64,AAAA")

    private fun reply(next: String, sure: Double = 0.95): DReply = DReply(mapOf(
        "next" to DAnswer.Choice(next, sure, mapOf(next to sure)),
        "fits_plan" to DAnswer.Noul(0.95), "surprise" to DAnswer.Noul(0.05),
        "blocked_by" to DAnswer.Choice("none", 0.9, emptyMap()), "popup" to DAnswer.Choice("none", 0.9, emptyMap()),
        "popup_dismiss" to DAnswer.Choice("none", 0.9, emptyMap()), "divergence" to DAnswer.Score(0.0, 0.9, emptyMap()),
    ))

    private class Hands : MindToolbox {
        val calls = mutableListOf<String>()
        override fun specs(): List<MindToolSpec> = emptyList()
        override fun execute(call: MindToolCall, arguments: JSONObject): MindToolResult {
            calls += call.name
            return MindToolResult("ok", changedScreen = true, screenProof = "another screen is showing")
        }
    }

    private class Eyes(vararg screens: StepScreen) {
        val reads = mutableListOf<Boolean>()
        private val queue = ArrayDeque(screens.toList())
        private var last: StepScreen? = null
        val eyes = StepEyes { image -> reads += image; (queue.removeFirstOrNull() ?: last)?.also { last = it } }
    }

    @Test fun `a split on an easy-to-undo tap is settled by one narrow follow-up with the screenshot`() {
        val step = Step("s", "tap", target = "Anna")
        val hands = Hands()
        val asks = mutableListOf<FusedBoard.Ask>()
        val board = StepBoard { _, ask ->
            asks += ask
            if (asks.size == 1) listOf(FusedBoard.read(reply("e1"), ask), FusedBoard.read(reply("e2"), ask))
            else listOf(FusedBoard.read(reply("e2"), ask), FusedBoard.read(reply("e2"), ask))
        }
        val out = StepEngine(Eyes(chat, withImage, chat.copy(fingerprint = "f2")).eyes, board, hands).run(step, score(step))
        assertTrue("the settled pick acts: $out", out is StepOutcome.Done)
        assertEquals(2, asks.size)
        assertEquals(setOf("e1", "e2"), asks[1].candidates.map { it.ref }.toSet())
        assertEquals("tap", hands.calls.single())
    }

    @Test fun `a split on a move that cannot be undone is never settled, the mind decides as before`() {
        val step = Step("s", "tap", target = "Send")
        val send = StepScreen("com.whatsapp", "WhatsApp", "chat", "f3",
            listOf(StepControl("e1", "Send now", "button"), StepControl("e2", "Send later", "button")), listOf("Anna"), imageDataUrl = "data:image/png;base64,AAAA")
        var calls = 0
        val board = StepBoard { _, ask ->
            calls++
            listOf(FusedBoard.read(reply("e1"), ask), FusedBoard.read(reply("e2"), ask))
        }
        val hands = Hands()
        val out = StepEngine(Eyes(send, send).eyes, board, hands).run(step, score(step))
        assertTrue("$out", out is StepOutcome.NeedsMind && out.trigger == "unsure")
        assertEquals("no follow-up question for a move that can't be undone", 1, calls)
        assertTrue(hands.calls.isEmpty())
    }

    @Test fun `a follow-up that still splits settles nothing`() {
        val step = Step("s", "tap", target = "Anna")
        var calls = 0
        val board = StepBoard { _, ask ->
            calls++
            if (calls == 1) listOf(FusedBoard.read(reply("e1"), ask), FusedBoard.read(reply("e2"), ask))
            else listOf(FusedBoard.read(reply("e1"), ask), FusedBoard.read(reply("e2"), ask))
        }
        val hands = Hands()
        val out = StepEngine(Eyes(chat, withImage).eyes, board, hands).run(step, score(step))
        assertTrue("$out", out is StepOutcome.NeedsMind)
        assertEquals(2, calls)
        assertTrue(hands.calls.isEmpty())
    }

    @Test fun `the read after a move starts the next one, and an older read is read again`() {
        val open = Step("s1", "open_app", args = mapOf("app" to "WhatsApp"), expect = Expect.AppIs("WhatsApp"))
        val next = Step("s2", "open_app", args = mapOf("app" to "Chrome"), expect = Expect.AppIs("Chrome"))
        val wa = StepScreen("com.whatsapp", "WhatsApp", "list", "f1", emptyList(), listOf("Chats"))
        val chrome = StepScreen("com.android.chrome", "Chrome", "home", "c1", emptyList(), listOf("Search"))
        val board = StepBoard { _, _ -> emptyList() }
        var now = 0L
        val eyes = Eyes(wa, wa, chrome, chrome)
        val engine = StepEngine(eyes.eyes, board, Hands(), now = { now })
        engine.run(open, score(open))
        assertEquals("the first run reads the screen, then after its move", 2, eyes.reads.size)
        engine.run(next, score(next))
        assertEquals("the read after the move is reused: one new read only", 3, eyes.reads.size)
        assertEquals(1, engine.stats.reused)

        val late = Eyes(wa, wa, chrome, chrome)
        now = 0L
        val stale = StepEngine(late.eyes, board, Hands(), now = { now })
        stale.run(open, score(open))
        now = 5_000L
        stale.run(next, score(next))
        assertEquals("an old read is read again", 4, late.reads.size)
        assertEquals(0, stale.stats.reused)
    }

    @Test fun `a provider that misses the deadline three moves in a row is set aside and one answer resets it`() {
        val misses = AndroidCrew.LaneMisses()
        assertTrue(misses.waited("jev"))
        repeat(AndroidCrew.SET_ASIDE_MISSES) { misses.record("jev", answered = false) }
        assertFalse(misses.waited("jev"))
        assertTrue("another provider is still waited for", misses.waited("openai"))
        misses.record("jev", answered = true)
        assertTrue(misses.waited("jev"))
    }

    @Test fun `only a clean, sure control that can be taken back is an early answer`() {
        val step = Step("s", "tap", target = "Anna")
        val ask = FusedBoard.Ask("g", "m", step, chat, chat.controls, null, emptyList())
        assertTrue(AndroidCrew.settled(reply("e1", sure = 0.95), ask))
        assertFalse("not sure enough", AndroidCrew.settled(reply("e1", sure = 0.6), ask))
        assertFalse("a verb is not a control", AndroidCrew.settled(reply(FusedBoard.BACK, sure = 0.95), ask))
        assertFalse("no answer", AndroidCrew.settled(null, ask))
        val sendScreen = chat.copy(controls = listOf(StepControl("e1", "Send", "button")))
        assertFalse("a press that can't be taken back waits for both models",
            AndroidCrew.settled(reply("e1", sure = 0.95), FusedBoard.Ask("g", "m", step, sendScreen, sendScreen.controls, null, emptyList())))
    }

    private fun score(vararg steps: Step) = Score.start("r", Difficulty.FLASH, "Tell Anna I'm on my way", 1)
        .copy(plan = Plan(1, listOf(Milestone("m1", "Send", steps.toList()))))
}
