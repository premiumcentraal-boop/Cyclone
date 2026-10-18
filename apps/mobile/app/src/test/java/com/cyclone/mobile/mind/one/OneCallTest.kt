package com.cyclone.mobile.mind.one

import com.cyclone.mobile.decisions.DAnswer
import com.cyclone.mobile.decisions.DPart
import com.cyclone.mobile.decisions.DReply
import com.cyclone.mobile.mind.MindToolCall
import com.cyclone.mobile.mind.MindToolResult
import com.cyclone.mobile.mind.MindToolSpec
import com.cyclone.mobile.mind.MindToolbox
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan 72 (alpha 143): two that decide, one that proves. The cross-checks live inside each model's one call, a step
 * never asks a second round, the done check is one call with sight, and proven taps teach the app's memory.
 */
class OneCallTest {
    private val feed = StepScreen("com.instagram.android", "Instagram", "feed", "f1",
        listOf(StepControl("e1", "Home", "tab"), StepControl("e2", "Profile", "tab"), StepControl("e3", "Profile picture", "button"), StepControl("e4", "Reels", "tab")),
        listOf("For you"), imageDataUrl = "data:image/png;base64,AAAA")
    private val profile = StepScreen("com.instagram.android", "Instagram", "profile", "p1",
        listOf(StepControl("e1", "Home", "tab"), StepControl("e5", "Edit profile", "button"), StepControl("e6", "Share profile", "button")),
        listOf("anna.devries"))
    private val step = Step("s1", "tap", target = "Profile")
    private fun score(vararg s: Step) = Score.start("r", Difficulty.FLASH, "open my profile", 1).copy(plan = Plan(1, listOf(Milestone("m1", "Profile", s.toList()))))
    private fun ask(screen: StepScreen = feed) = FusedBoard.Ask("open my profile", "Profile", step, screen, screen.controls)

    private fun reply(next: String, rev: String? = next, confirms: Map<String, Double> = emptyMap(), sure: Double = 0.95): DReply = DReply(buildMap {
        put("next", DAnswer.Choice(next, sure, mapOf(next to sure)))
        rev?.let { put("next_rev", DAnswer.Choice(it, sure, mapOf(it to sure))) }
        confirms.forEach { (ref, yes) -> put("is_$ref", DAnswer.Noul(yes)) }
        put("fits_plan", DAnswer.Noul(0.95)); put("surprise", DAnswer.Noul(0.05))
        put("blocked_by", DAnswer.Choice("none", 0.9, emptyMap())); put("popup", DAnswer.Choice("none", 0.9, emptyMap()))
        put("popup_dismiss", DAnswer.Choice("none", 0.9, emptyMap())); put("divergence", DAnswer.Score(0.0, 0.9, emptyMap()))
    })

    @Test fun `one call asks the pick twice, the second time reversed, and judges code's finalists on their own`() {
        val r = FusedBoard.request(ask().copy(history = listOf("Opened Instagram → proven"), known = listOf("\"Profile\" led to a page with Edit profile (3×)")))
        val rev = r.questions["next_rev"] as com.cyclone.mobile.decisions.DQuestion.Choice
        val fwd = r.questions["next"] as com.cyclone.mobile.decisions.DQuestion.Choice
        assertEquals(fwd.options.keys.filter { it.startsWith("e") }.reversed(), rev.options.keys.filter { it.startsWith("e") })
        // "Profile" and "Profile picture" share the target's words: both are judged.
        assertEquals(setOf("is_e2", "is_e3"), r.questions.keys.filter { it.startsWith("is_") }.toSet())
        val text = r.state.filterIsInstance<DPart.Text>().joinToString("\n") { it.text }
        assertTrue(text.contains("Moves so far"))
        assertTrue(text.contains("Learned in this app before"))
    }

    @Test fun `a pick stands only when the cross-checks inside the call agree`() {
        val a = ask()
        assertEquals("e2", FusedBoard.read(reply("e2", confirms = mapOf("e2" to 0.9, "e3" to 0.1)), a).next)
        // Reversed order changed the answer: position bias, not a decision.
        val flipped = FusedBoard.read(reply("e2", rev = "e3", confirms = mapOf("e2" to 0.9, "e3" to 0.1)), a)
        assertEquals(FusedBoard.NEEDS_MIND, flipped.next)
        assertTrue(flipped.doubt!!.contains("reversed"))
        // Not sure on its own, or a rival finalist also looks like the step.
        assertEquals(FusedBoard.NEEDS_MIND, FusedBoard.read(reply("e2", confirms = mapOf("e2" to 0.6, "e3" to 0.1)), a).next)
        assertEquals(FusedBoard.NEEDS_MIND, FusedBoard.read(reply("e2", confirms = mapOf("e2" to 0.9, "e3" to 0.6)), a).next)
        // A verb (scroll, back) isn't judged as a control; a model that skipped the cross-checks isn't blocked by them.
        assertEquals(FusedBoard.SCROLL_DOWN, FusedBoard.read(reply(FusedBoard.SCROLL_DOWN, confirms = mapOf("e2" to 0.9)), a).next)
        assertEquals("e2", FusedBoard.read(reply("e2", rev = null), a).next)
    }

    @Test fun `the state stays under JEV's fast-mode size, the screen text giving way first`() {
        val huge = feed.copy(texts = List(40) { "x".repeat(2_000) })
        val r = FusedBoard.request(ask(huge))
        val chars = r.state.filterIsInstance<DPart.Text>().sumOf { it.text.length }
        assertTrue("$chars", chars <= FusedBoard.MAX_STATE_CHARS)
        assertTrue(r.state.filterIsInstance<DPart.Text>().any { it.text.startsWith("Controls:") && it.text.contains("Profile picture") })
        // The model that reads text only gets no image.
        val withImage = FusedBoard.request(ask().copy(withImage = true))
        assertTrue(withImage.state.any { it is DPart.Image })
        assertFalse(FusedBoard.textOnly(withImage.state).any { it is DPart.Image })
    }

    private class Hands(val changes: Boolean = true) : MindToolbox {
        val calls = mutableListOf<String>()
        override fun specs(): List<MindToolSpec> = emptyList()
        // [changes]: the phone showed the move happen (alpha 144: its proof, not the tool's kind, is the change).
        override fun execute(call: MindToolCall, arguments: JSONObject): MindToolResult { calls += call.name
            return MindToolResult("ok", changedScreen = changes, screenProof = if (changes) "another screen is showing" else null) }
    }

    private class Seq(vararg screens: StepScreen) {
        private val q = ArrayDeque(screens.toList())
        private var last: StepScreen? = null
        var looks = 0
        var images = 0
        val eyes = StepEyes { image -> looks++; if (image) images++; (q.removeFirstOrNull() ?: last)?.also { last = it } }
    }

    @Test fun `a step decides in one round, with the screenshot in that round, and never asks again`() {
        val unsure = feed.copy(controls = listOf(StepControl("e2", "My profile"), StepControl("e3", "Profile picture")))
        var asked = 0
        var sawImage = false
        val seq = Seq(unsure, unsure)
        val out = StepEngine(seq.eyes, { _, a -> asked++; sawImage = a.withImage; listOf(FusedBoard.read(reply("e2", rev = "e3"), a)) }, Hands())
            .run(step, score(step))
        assertTrue(out is StepOutcome.NeedsMind)
        assertEquals("one deciding round, however unsure", 1, asked)
        assertTrue(sawImage)
        assertTrue((out as StepOutcome.NeedsMind).reason.contains("reversed"))
    }

    @Test fun `the run's last moves and their proof ride in the next call`() {
        val seq = Seq(feed, feed, profile, profile, profile, profile)
        val seen = mutableListOf<List<String>>()
        val engine = StepEngine(seq.eyes, { _, a -> seen += a.history; listOf(FusedBoard.read(reply(a.candidates.first().ref), a)) }, Hands())
        assertTrue(engine.run(step, score(step)) is StepOutcome.Done) // rung 0: exact "Profile"
        val next = Step("s2", "tap", target = "Options")
        engine.run(next, score(step, next))
        assertTrue(seen.last().single().contains("Tapped \"Profile\" → proven"))
    }

    @Test fun `proven taps teach the app's memory, a known move that fails twice is forgotten, and secrets never enter`() {
        val dir = Files.createTempDirectory("moves").toFile()
        val memory = FileMoveMemory(dir) { 1_000L }
        val engine = StepEngine(Seq(feed, profile).eyes, { _, _ -> emptyList() }, Hands(), memory = memory)
        assertTrue(engine.run(step, score(step)) is StepOutcome.Done)
        val known = memory.known(feed)
        assertEquals(1, known.size)
        assertTrue(known.single(), known.single().startsWith("\"Profile\" led to a page with Edit profile, Share profile"))
        // A new instance reads it back from the phone.
        assertEquals(known, FileMoveMemory(dir).known(feed))
        // Proven again counts up.
        memory.proven(feed, "Profile", profile)
        assertTrue(memory.known(feed).single().endsWith("(2×)"))
        // Two misses forget it.
        memory.failed(feed, "Profile"); memory.failed(feed, "Profile")
        assertTrue(memory.known(feed).isEmpty())
        // Never from a sensitive screen, another app, the same page, or a label that looks like a number or address.
        memory.proven(feed.copy(sensitive = true), "Profile", profile)
        memory.proven(feed, "Profile", profile.copy(packageName = "com.whatsapp"))
        memory.proven(feed, "Profile", feed)
        memory.proven(feed, "Call 0612345678", profile)
        memory.proven(feed, "anna@example.com", profile)
        assertTrue(memory.known(feed).isEmpty())
        assertTrue(memory.known(feed.copy(sensitive = true)).isEmpty())
        assertFalse(FileMoveMemory.keepable("€ 49,95"))
        assertTrue(FileMoveMemory.keepable("Edit profile"))
    }

    @Test fun `a tap that changed nothing counts against what memory said`() {
        val dir = Files.createTempDirectory("moves").toFile()
        val memory = FileMoveMemory(dir)
        memory.proven(feed, "Profile", profile)
        StepEngine(Seq(feed, feed).eyes, { _, _ -> emptyList() }, Hands(changes = false), memory = memory).run(step, score(step))
        StepEngine(Seq(feed, feed).eyes, { _, _ -> emptyList() }, Hands(changes = false), memory = memory).run(step, score(step))
        assertTrue(memory.known(feed).isEmpty())
    }

    @Test fun `the one proving call sees the page`() {
        val seq = Seq(profile.copy(imageDataUrl = "data:image/png;base64,BBBB"))
        var request: FusedBoard.Request? = null
        val crew = EngineCrew(seq.eyes, { _, _ -> emptyList() }, { r -> request = r; listOf(DReply(mapOf("done" to DAnswer.Noul(0.9)))) }, Hands(), Breaker())
        assertEquals(0.9, crew.done(score(step), null)!!, 1e-9)
        assertNotNull(request)
        assertTrue(request!!.state.any { it is DPart.Image })
        assertEquals(1, seq.images)
        // Never a screenshot of a sensitive screen.
        var secretRequest: FusedBoard.Request? = null
        EngineCrew(Seq(profile.copy(sensitive = true, imageDataUrl = "data:x")).eyes, { _, _ -> emptyList() },
            { r -> secretRequest = r; emptyList() }, Hands(), Breaker()).done(score(step), null)
        assertNull(secretRequest!!.state.firstOrNull { it is DPart.Image })
    }
}
