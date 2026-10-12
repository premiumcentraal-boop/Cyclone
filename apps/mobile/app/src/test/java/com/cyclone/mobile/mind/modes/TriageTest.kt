package com.cyclone.mobile.mind.modes

import com.cyclone.mobile.decisions.DAnswer
import com.cyclone.mobile.decisions.DQuestion
import com.cyclone.mobile.decisions.DReply
import com.cyclone.mobile.decisions.DecisionsWire
import com.cyclone.mobile.mind.decide.Decider
import com.cyclone.mobile.mind.decide.Guess
import com.cyclone.mobile.mind.decide.TriageRecord
import com.cyclone.mobile.mind.decide.WatchLog
import com.cyclone.mobile.mind.decide.WatchRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 58 (alpha.123): the Triage board, its combining rules, the watch board and the gate's numbers. */
class TriageTest {
    private val home = GrammarWorld(
        labels = listOf("Pokémon GO", "Instagram", "Camera"),
        apps = listOf("Instagram" to "com.instagram.android", "Camera" to "com.google.android.GoogleCamera", "Gmail" to "com.google.android.gm",
            "WhatsApp" to "com.whatsapp", "Settings" to "com.android.settings"),
    )

    /** A Triage answer: [difficulty] with its confidence, the action, and any flags (probability of yes). */
    private fun reading(
        difficulty: Double?, sure: Double = 0.9, capability: String? = "none", capSure: Double = 0.95,
        target: String? = null, targetSure: Double = 0.95, steps: List<String> = emptyList(), vararg flags: Pair<String, Double>,
    ): Triage.Reading = Triage.read(DReply(buildMap {
        difficulty?.let { put("difficulty", DAnswer.Score(it, sure, emptyMap())) }
        capability?.let { put("capability", DAnswer.Choice(it, capSure, emptyMap())) }
        put("target", DAnswer.Choice(target ?: "none", targetSure, emptyMap()))
        put("is_for_cyclone", DAnswer.Noul(0.97))
        flags.forEach { (k, v) -> put(k, DAnswer.Noul(v)) }
        steps.forEachIndexed { i, s -> put("step_${i + 1}", DAnswer.Choice(s, 0.9, emptyMap())) }
        if (steps.isNotEmpty() && steps.size < Triage.MAX_STEPS) put("step_${steps.size + 1}", DAnswer.Choice("none", 0.9, emptyMap()))
    }))

    private fun route(r: Triage.Reading, text: String) = Triage.route(r, text, home, 0.85)

    @Test fun `the board asks every question in one valid documented request`() {
        val questions = Triage.questions(home, "open camera")
        val body = DecisionsWire.body("openai/gpt-6-luna-decisions", Triage.state("open camera", home), questions)
        val q = body.getJSONObject("questions")
        assertEquals("score", q.getJSONObject("difficulty").getString("type"))
        assertEquals(4, q.getJSONObject("difficulty").getJSONArray("criteria").length())
        assertTrue(q.getJSONObject("capability").getJSONObject("criteria").has("camera"))
        assertTrue(q.getJSONObject("capability").getJSONObject("criteria").has("none"))
        for (flag in Triage.RISKS + listOf("writes_text", "multi_app", "needs_screen", "later", "is_for_cyclone", "answer_only")) {
            assertEquals(flag, "noul", q.getJSONObject(flag).getString("type"))
        }
        for (i in 1..Triage.MAX_STEPS) assertTrue(q.getJSONObject("step_$i").getJSONObject("criteria").has("go_to"))
        assertTrue(questions.size < DecisionsWire.MAX_QUESTIONS)
        assertTrue((questions["target"] as DQuestion.Choice).options.containsKey("Instagram"))
    }

    @Test fun `open camera is instant`() {
        val r = route(reading(0.05, capability = "camera"), "open camera")
        assertEquals(Mode.INSTANT, r.mode)
        assertEquals(InstantIntent.CAMERA, r.command!!.intent)
    }

    @Test fun `open an app needs a sure app that is really installed`() {
        assertEquals("com.instagram.android", route(reading(0.1, capability = "open_app", target = "Instagram"), "pull up insta").command!!.target)
        // An app that isn't installed, or a target it isn't sure of, goes up.
        assertEquals(Mode.FLASH, route(reading(0.1, capability = "open_app", target = "Pokémon GO"), "open pokemon").mode)
        assertEquals(Mode.FLASH, route(reading(0.1, capability = "open_app", target = "Instagram", targetSure = 0.5), "open insta").mode)
    }

    @Test fun `the owners hard example goes to the mind`() {
        val text = "open my gmail, check which account is logged in, then message my grandma from it"
        val r = route(reading(2.9, capability = "none", steps = listOf("open_app", "read", "tap"),
            flags = arrayOf("sends_or_posts" to 0.97, "writes_text" to 0.95, "account" to 0.4)), text)
        assertEquals(Mode.MIND, r.mode)
    }

    @Test fun `a short chain without risk is flash for now`() {
        val r = reading(1.1, capability = "none", steps = listOf("open_app", "go_to", "open_item"))
        assertEquals(Mode.FLASH, route(r, "open instagram, go to my dms and open the first one").mode)
        assertEquals(listOf("open_app", "go_to", "open_item"), r.steps)
    }

    @Test fun `risk never lets a request be instant`() {
        for (risk in Triage.RISKS) {
            val r = route(reading(0.1, capability = "open_app", target = "Instagram", flags = arrayOf(risk to 0.35)), "open instagram")
            assertEquals(risk, Mode.FLASH, r.mode)
        }
    }

    @Test fun `unsure goes up`() {
        // No difficulty at all: Flash. A wide spread counts as harder: 0.3 ± unsure is not Instant.
        assertEquals(Mode.FLASH, route(reading(null, capability = "camera"), "open camera").mode)
        assertEquals(Mode.FLASH, route(reading(0.3, sure = 0.4, capability = "camera"), "open camera").mode)
        // An unsure action is not Instant either.
        assertEquals(Mode.FLASH, route(reading(0.1, capability = "camera", capSure = 0.6), "open camera").mode)
        assertEquals(Mode.MIND, route(reading(2.2, sure = 0.4), "sort my photos by trip").mode)
    }

    @Test fun `writing, later and questions go to the mind and chatter is ignored`() {
        assertEquals(Mode.MIND, route(reading(0.4, flags = arrayOf("writes_text" to 0.8)), "note milk").mode)
        assertEquals(Mode.MIND, route(reading(0.2, capability = "flashlight_on", flags = arrayOf("later" to 0.9)), "flashlight at 9").mode)
        assertEquals(Mode.MIND, route(reading(0.2, flags = arrayOf("answer_only" to 0.9)), "why is the sky blue").mode)
        val chatter = Triage.read(DReply(mapOf("is_for_cyclone" to DAnswer.Noul(0.1))))
        assertEquals(Mode.IGNORE, route(chatter, "haha ok").mode)
    }

    @Test fun `refusals are no answer, never a no`() {
        val r = Triage.read(DReply(mapOf("difficulty" to DAnswer.Refusal, "capability" to DAnswer.Choice("camera", 0.99, emptyMap()))))
        assertEquals(setOf("difficulty"), r.refused)
        assertEquals(Mode.FLASH, route(r, "open camera").mode)
    }

    @Test fun `triage acts only when switched on and the rules still come first`() {
        val asked = mutableListOf<String>()
        val triage = TypedBox { _, q -> asked += q.keys; TypedAnswer(DReply(mapOf(
            "difficulty" to DAnswer.Score(0.05, 0.95, emptyMap()), "capability" to DAnswer.Choice("camera", 0.97, emptyMap()),
            "is_for_cyclone" to DAnswer.Noul(0.99))), 180) }
        val board0 = DecisionBox { BoxReply(mapOf("route" to BoxAnswer("mind", 0.95))) }
        val r = ModeRouter.route("lemme snap something", home, LocalFacts(), Speed.AUTO, board0, 0.85, null, triage)
        assertEquals(Mode.INSTANT, r.mode)
        assertEquals(Decider.DECISIONS, r.by)
        assertEquals(180, r.decideMs)
        // Without the switch, Board 0 decides as before.
        assertEquals(Mode.MIND, ModeRouter.route("lemme snap something", home, LocalFacts(), Speed.AUTO, board0, 0.85).mode)
        // The rules send "pay" to the Mind before Triage is asked.
        asked.clear()
        assertEquals(Mode.MIND, ModeRouter.route("pay the bill in my bank app", home, LocalFacts(), Speed.AUTO, board0, 0.85, null, triage).mode)
        assertTrue(asked.isEmpty())
        // No answer from Triage: one rung up.
        assertEquals(Mode.FLASH, ModeRouter.route("lemme snap something", home, LocalFacts(), Speed.AUTO, board0, 0.85, null,
            TypedBox { _, _ -> null }).mode)
    }

    @Test fun `the watch asks board 0 and triage in one call and records no text`() {
        val text = "lemme snap something"
        val q = WatchBoard.questions(text, home, comparing = true, routedByBoard0 = true, triage = StageSwitch.SHADOW)
        assertTrue(q.keys.containsAll(listOf("route", "intent", "target", "difficulty", "capability", "step_1")))
        assertTrue(WatchBoard.questions(text, home, comparing = false, routedByBoard0 = true, triage = StageSwitch.OFF).isEmpty())
        assertEquals(setOf("route", "intent", "target"),
            WatchBoard.questions(text, home, comparing = true, routedByBoard0 = true, triage = StageSwitch.OFF).keys)
        val route = Route(Mode.FLASH, "board", by = Decider.DECISIONS, decision = Guess("flash", "none", null, 0.9))
        val reply = DReply(mapOf(
            "route" to DAnswer.Choice("instant", 0.9, emptyMap()), "intent" to DAnswer.Choice("camera", 0.9, emptyMap()),
            "target" to DAnswer.Choice("none", 0.9, emptyMap()),
            "difficulty" to DAnswer.Score(0.1, 0.9, emptyMap()), "capability" to DAnswer.Choice("camera", 0.95, emptyMap()),
            "is_for_cyclone" to DAnswer.Noul(0.99)))
        val record = WatchBoard.record(1L, "luna", 320, route, reply, null, text, home, 0.85, q)
        assertEquals("instant", record.board0!!.mode)
        assertEquals("instant", record.triage!!.mode)
        assertFalse(record.toJson().toString().contains("pictures"))
        assertEquals(record, WatchRecord.fromJson(record.toJson()))
        val failed = WatchBoard.record(2L, "luna", 1200, route, null, "timeout", text, home, 0.85, q)
        assertEquals("timeout", failed.outcome)
        assertNull(failed.triage)
    }

    @Test fun `the gate counts triage putting a request below the rules`() {
        val mind = Guess("mind", "none", null, 1.0)
        fun t(mode: String) = TriageRecord(mode, 0.5, "none", 0.9, emptyList(), emptyList(), 0)
        val records = listOf(
            WatchRecord(1, "luna", "ok", 300, Decider.RULES, mind, triage = t("mind")),
            WatchRecord(2, "luna", "ok", 300, Decider.RULES, mind, triage = t("instant")),
            WatchRecord(3, "luna", "ok", 300, Decider.DECISIONS, Guess("flash", "none", null, 0.9), board0 = Guess("flash", "none", null, 0.8), triage = t("flash")),
            WatchRecord(4, "luna", "timeout", 1200, Decider.GRAMMAR, Guess("instant", "camera", null, 1.0)),
        )
        val s = WatchLog.summary(records)
        assertEquals(4, s.getInt("watched"))
        assertEquals(0.75, s.getDouble("answerRate"), 1e-9)
        assertEquals(1, s.getInt("triageBelowRules"))
        assertEquals(1.0, s.getDouble("board0Agreement"), 1e-9)
        assertEquals(2.0 / 3, s.getDouble("triageRungAgreement"), 1e-3)
        assertEquals(records, WatchLog.decode(WatchLog.encode(records)))
    }
}
