package com.cyclone.mobile.mind.pilot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 41: the Step Pilot. The fast model picks moves and decides when to hand back; the harness keeps the boundaries. */
class PilotTest {
    private val chats = PilotScreen("WhatsApp", "Chats", listOf("Chats"), listOf(
        PilotControl("e1", "Search", "button"), PilotControl("e4", "Lou", "list item"), PilotControl("e5", "Mam", "list item")))
    private val chat = PilotScreen("WhatsApp", "Lou", listOf("Lou", "online"), listOf(
        PilotControl("e9", "Message", "text field", editable = true), PilotControl("e10", "Send", "button")))

    /** A phone that moves between screens on taps, and records what it was asked to do. */
    private class FakePhone(var screen: PilotScreen, val next: Map<String, PilotScreen> = emptyMap()) : PilotHands {
        val did = mutableListOf<String>()
        var stop = false
        override fun look(withImage: Boolean) = PilotLook(screen, if (withImage) "data:image/png;base64,AA" else null)
        override fun tap(ref: String): PilotMove {
            did += "tap $ref"
            val to = next[ref] ?: return PilotMove(true, false, "Tapped. The screen did not visibly change.")
            screen = to
            return PilotMove(true, true, "Tapped. The screen changed.")
        }
        override fun type(ref: String, text: String): PilotMove { did += "type $ref $text"; return PilotMove(true, true, "Typed.") }
        override fun scroll(down: Boolean): PilotMove { did += "scroll ${if (down) "down" else "up"}"; return PilotMove(true, true, "Scrolled.") }
        override fun back(): PilotMove { did += "back"; return PilotMove(true, true, "Back.") }
        override fun stopped() = stop
    }

    private fun script(vararg answers: PilotAnswer?): Pair<PilotDecider, MutableList<PilotQuestion>> {
        val asked = mutableListOf<PilotQuestion>()
        val queue = ArrayDeque(answers.toList())
        return PilotDecider { q -> asked += q; queue.removeFirstOrNull() } to asked
    }

    private val sure = PilotSettings(sureness = 0.85, images = false)
    private val steps = listOf(PilotStep("Open the chat with Lou", "the chat with Lou is open"), PilotStep("Type the ETA", text = "On my way, 10 min"))

    @Test fun routineStepsRunFastAndTheRecordSaysWhatHappened() {
        val phone = FakePhone(chats, mapOf("e4" to chat))
        val (decider, asked) = script(PilotAnswer("e4", 0.95), PilotAnswer("step_done", 0.93), PilotAnswer("e9", 0.91), PilotAnswer("step_done", 0.9))
        val outcome = Pilot.run("Send Lou my ETA", steps, phone, decider, sure)
        assertNull(outcome.handBack)
        assertEquals(2, outcome.stepsDone)
        assertEquals(listOf("tap e4", "type e9 On my way, 10 min"), phone.did)
        // Every question carries the goal and the step, never the screen alone.
        assertTrue(asked.all { Pilot.context(it).contains("Goal: Send Lou my ETA") && Pilot.context(it).contains("Step ") })
        val report = Pilot.report("fast", sure, steps, outcome)
        assertTrue(report, report.contains("tapped e4 \"Lou\"") && report.contains("All 2 steps done"))
    }

    @Test fun theFastModelDecidesToHandBackAndTheMindIsToldWhy() {
        val phone = FakePhone(chats)
        val (decider, _) = script(PilotAnswer("hand_back", 0.97, "unexpected_screen"))
        val outcome = Pilot.run("Send Lou my ETA", steps, phone, decider, sure)
        assertEquals(PilotHandBack.FAST_MODEL, outcome.handBack!!.by)
        assertEquals("unexpected_screen", outcome.handBack!!.reason)
        assertTrue(phone.did.isEmpty())
        val report = Pilot.report("fast", sure, steps, outcome)
        assertTrue(report, report.contains("The fast model handed step 1 back to you: unexpected screen"))
        assertTrue(report, report.contains("Not done yet: \"Open the chat with Lou\"; \"Type the ETA\""))
    }

    @Test fun anUnsureAnswerIsHandedBackBeforeAnyMove() {
        val phone = FakePhone(chats, mapOf("e4" to chat))
        val (decider, _) = script(PilotAnswer("e4", 0.6))
        val outcome = Pilot.run("Send Lou my ETA", steps, phone, decider, sure)
        assertEquals("unsure", outcome.handBack!!.reason)
        assertEquals(PilotHandBack.HARNESS, outcome.handBack!!.by)
        assertTrue(phone.did.isEmpty())
    }

    @Test fun noAnswerOrAnAnswerOutsideTheChoicesHandsBack() {
        assertEquals("no_answer", Pilot.run("g", steps, FakePhone(chats), script(null).first, sure).handBack!!.reason)
        assertEquals("invalid", Pilot.run("g", steps, FakePhone(chats), script(PilotAnswer("e77", 0.99)).first, sure).handBack!!.reason)
    }

    @Test fun aMoveThatChangedNothingIsNotRepeated() {
        val phone = FakePhone(chats) // e5 changes nothing
        val (decider, _) = script(PilotAnswer("e5", 0.9), PilotAnswer("e5", 0.9))
        val outcome = Pilot.run("g", steps, phone, decider, sure)
        assertEquals("repeat", outcome.handBack!!.reason)
        assertEquals(listOf("tap e5"), phone.did)
    }

    @Test fun aStepHasAMoveBudget() {
        val phone = FakePhone(chats)
        val (decider, _) = script(*Array(10) { PilotAnswer("scroll_down", 0.9) })
        val outcome = Pilot.run("g", steps, phone, decider, sure)
        assertEquals("too_many_moves", outcome.handBack!!.reason)
        assertEquals(Pilot.MAX_MOVES_PER_STEP, phone.did.size)
    }

    @Test fun sensitiveScreensAndKeptOffAppsAreNeverTouched() {
        val login = chats.copy(sensitive = true)
        val (decider, asked) = script(PilotAnswer("e4", 0.99))
        val outcome = Pilot.run("g", steps, FakePhone(login), decider, sure)
        assertEquals("sensitive", outcome.handBack!!.reason)
        assertTrue(asked.isEmpty())
        assertTrue(Pilot.keepOff("com.ing.mobile", "ING Bankieren"))
        assertTrue(Pilot.keepOff("com.google.android.apps.walletnfcrel", "Google Wallet"))
        assertTrue(Pilot.keepOff("com.revolut.revolut", "Revolut"))
        assertFalse(Pilot.keepOff("com.whatsapp", "WhatsApp"))
        assertFalse(Pilot.keepOff("com.google.android.apps.photos", "Photos"))
    }

    @Test fun secretFieldsAreNeverChoicesAndSecretTextIsNeverTyped() {
        val form = PilotScreen("Shop", null, emptyList(), listOf(PilotControl("e1", "Password", "password field", editable = true, secret = true),
            PilotControl("e2", "Note", "text field", editable = true)))
        val q = Pilot.question("g", PilotStep("fill the note", text = "x"), 0, 1, emptyList(), form, null)
        assertFalse(q.options.containsKey("e1"))
        val (decider, _) = script(PilotAnswer("e2", 0.99))
        val outcome = Pilot.run("g", listOf(PilotStep("fill the note", text = "my password: Zomer2024")), FakePhone(form), decider, sure)
        assertEquals("secret_text", outcome.handBack!!.reason)
    }

    @Test fun theShortlistKeepsTheStepsControlsOnALongScreen() {
        val many = (1..40).map { PilotControl("e$it", "Item $it", "list item") } + PilotControl("e41", "Lou", "list item")
        val list = Pilot.shortlist(PilotStep("open the chat with Lou"), many)
        assertEquals(Pilot.MAX_CHOICES, list.size)
        assertTrue(list.any { it.ref == "e41" })
    }

    @Test fun imagesAreAskedForOnWeakScreensOrAfterAMoveChangedNothing() {
        var images = 0
        val phone = object : PilotHands by FakePhone(chats) {
            override fun look(withImage: Boolean): PilotLook { if (withImage) images++; return PilotLook(chats, if (withImage) "img" else null) }
        }
        val (decider, asked) = script(PilotAnswer("e5", 0.9), PilotAnswer("hand_back", 0.9, "not_on_screen"))
        Pilot.run("g", steps, phone, decider, PilotSettings(0.85, images = true))
        assertEquals(1, images)
        assertNull(asked[0].image)
        assertEquals("img", asked[1].image)
    }

    @Test fun theOwnerCanStopThePilot() {
        val phone = FakePhone(chats).apply { stop = true }
        assertEquals("stopped", Pilot.run("g", steps, phone, script().first, sure).handBack!!.reason)
    }

    @Test fun stepsAreReadFromTheToolArguments() {
        val args = JSONObject("""{"steps":[{"do":"Open Lou","expect":"chat open"},"Tap Message",{"do":" "},{"do":"Type","text":"hi"}]}""")
        assertEquals(listOf(PilotStep("Open Lou", "chat open"), PilotStep("Tap Message"), PilotStep("Type", text = "hi")), Pilot.steps(args))
    }

    @Test fun wireFormatsAreReadTolerantly() {
        val q = Pilot.question("g", steps[0], 0, 2, emptyList(), chats, null)
        val body = PilotWire.choiceBody("google/x", q)
        assertTrue(body.toString().contains("\"enum\":[\"e1\",\"e4\",\"e5\",\"step_done\",\"scroll_down\",\"scroll_up\",\"back\",\"hand_back\"]"))
        val chat = """{"choices":[{"message":{"content":"{\"choice\":\"e4\",\"confidence\":0.92,\"reason\":\"other\"}"}}]}"""
        assertEquals(PilotAnswer("e4", 0.92, "other"), PilotWire.parseChoice(chat))
        assertNull(PilotWire.parseChoice("""{"choices":[{"message":{"content":"sure, tap Lou"}}]}"""))
        assertEquals("hand_back", PilotWire.parseDecisions("""{"answers":{"next":{"choice":"hand_back","confidence":0.8},"reason":"step_unclear"}}""")!!.choice)
        assertEquals(PilotAnswer("e4", 0.7, null), PilotWire.parseDecisions("""{"decisions":{"next":{"value":"e4","probability":0.7}}}"""))
        assertEquals("e4", PilotWire.parseDecisions("""{"next":"e4"}""")!!.choice)
        assertNull(PilotWire.parseDecisions("""{"nothing":1}"""))
        val decisions = PilotWire.decisionsBody("~typesafe/jev-latest", q)
        assertEquals("choice", decisions.getJSONObject("questions").getJSONObject("next").getString("type"))
    }
}
