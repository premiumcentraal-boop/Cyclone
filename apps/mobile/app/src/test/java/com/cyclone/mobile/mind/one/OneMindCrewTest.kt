package com.cyclone.mobile.mind.one

import com.cyclone.mobile.decisions.DAnswer
import com.cyclone.mobile.decisions.DPart
import com.cyclone.mobile.decisions.DQuestion
import com.cyclone.mobile.decisions.DReply
import com.cyclone.mobile.decisions.DecisionsWire
import com.cyclone.mobile.mind.MindToolCall
import com.cyclone.mobile.mind.MindToolResult
import com.cyclone.mobile.mind.MindToolSpec
import com.cyclone.mobile.mind.MindToolbox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 69 checkpoint 1: the Score, the ledger, the breaker, postconditions, tool steps, the fused board and the step engine. */
class OneMindCrewTest {

    // ---- the Score ---------------------------------------------------------------------------------------------------

    private fun plan() = Plan(1, listOf(
        Milestone("m1", "Open Anna's chat", listOf(
            Step("s1", "open_app", args = mapOf("app" to "WhatsApp"), expect = Expect.AppIs("WhatsApp")),
            Step("s2", "tap", target = "Search", expect = Expect.Focused),
            Step("s3", "type_text", target = "Search", args = mapOf("text" to "{contact}"), branches = listOf(Branch("Anna's chat is already open", Branch.BranchAction.SKIP))),
        ), review = true),
        Milestone("m2", "Send", listOf(Step("s4", "tap", target = "Send", risk = Risk.GATED))),
    ), done = Expect.All(listOf(Expect.AppIs("WhatsApp"), Expect.Shows("on my way"))))

    @Test fun `the score round-trips through the journal`() {
        val s = Score.start("r1", Difficulty.MIND, "Tell Anna I'm on my way", 1).copy(plan = plan())
            .fact("contact", "Anna de Vries", "contact_find", 2).event("act", "Opened WhatsApp", 3, "s1")
            .orders(listOf(StandingOrder("o1", "Wake me when the chat is open", StandingOrder.Kind.WAKE_WHEN, question = "Is a chat open?"),
                StandingOrder("o2", "Wake me over €400", StandingOrder.Kind.WAKE_IF_OVER, fact = "amount", over = 400.0)))
            .barriers(listOf(Barrier("s4", "send")))
        val back = ScoreCodec.decode(JSONObject(ScoreCodec.encode(s).toString()))
        assertEquals(s, back)
    }

    @Test fun `only a mind replans, done milestones stay, the owner steers`() {
        var s = Score.start("r1", Difficulty.MIND, "goal", 1).copy(plan = plan())
        s = s.mark("s1", StepState.DONE).mark("s2", StepState.DONE).mark("s3", StepState.SKIPPED)
        assertEquals(MilestoneState.DONE, s.plan!!.milestones[0].state)
        s = s.replan(Plan(1, listOf(Milestone("m9", "Another way", listOf(Step("x", "back"))))))
        assertEquals(listOf("m1", "m9"), s.plan!!.milestones.map { it.id })
        assertEquals(2, s.plan!!.version)
        s = s.steer("Tell Anna I'm late", 5)
        assertEquals(2, s.goal.version)
        assertEquals("Tell Anna I'm late", s.goal.text)
    }

    // ---- postconditions ------------------------------------------------------------------------------------------------

    private val chat = StepScreen("com.whatsapp", "WhatsApp", "chat", "f2",
        listOf(StepControl("e1", "Message", "edit", editable = true, focused = true, value = "on my way"), StepControl("e2", "Send", "button")),
        listOf("Anna de Vries", "online"))
    private val list = StepScreen("com.whatsapp", "WhatsApp", "list", "f1", listOf(StepControl("e1", "Search", "button")), listOf("Chats"))

    @Test fun `postconditions are checked by code where code can tell`() {
        assertEquals(Verdict.HELD, ExpectCheck.check(Expect.AppIs("whatsapp"), chat, null, emptyList()))
        assertEquals(Verdict.MISSED, ExpectCheck.check(Expect.AppIs("Instagram"), chat, null, emptyList()))
        assertEquals(Verdict.HELD, ExpectCheck.check(Expect.Shows("anna de vries"), chat, null, emptyList()))
        assertEquals(Verdict.HELD, ExpectCheck.check(Expect.FieldHas("On my way"), chat, null, emptyList()))
        assertEquals(Verdict.HELD, ExpectCheck.check(Expect.Focused, chat, null, emptyList()))
        assertEquals(Verdict.HELD, ExpectCheck.check(Expect.Changed, chat, list, emptyList()))
        assertEquals(Verdict.MISSED, ExpectCheck.check(Expect.Changed, list, list, emptyList()))
        assertEquals(Verdict.UNKNOWN, ExpectCheck.check(Expect.Described("the chat with Anna is open"), chat, list, emptyList()))
        assertEquals(Verdict.HELD, ExpectCheck.check(Expect.Setting("dark_mode", "on"), chat, null, emptyList()) { "on" })
        assertEquals(Verdict.MISSED, ExpectCheck.check(Expect.All(listOf(Expect.AppIs("WhatsApp"), Expect.Shows("nope"))), chat, null, emptyList()))
        assertEquals(Verdict.UNKNOWN, ExpectCheck.check(Expect.Shows("nope"), chat.copy(weak = true), null, emptyList()))
    }

    // ---- the breaker -------------------------------------------------------------------------------------------------

    @Test fun `the breaker climbs, changes approach, checks in and stops`() {
        val b = Breaker()
        assertEquals(Breaker.Call.Go, b.action("tap:Search", "p", progressed = false))
        assertEquals(Breaker.Call.Climb, b.action("tap:Search", "p", progressed = false))
        val stop = Breaker()
        var last: Breaker.Call = Breaker.Call.Go
        repeat(10) { last = stop.action("tap:$it", "p$it", progressed = false) }
        assertTrue(last is Breaker.Call.Stop)

        val w = Breaker()
        val calls = (1..8).map { w.wake("t$it") }
        assertEquals(Breaker.Call.ChangeApproach, calls[3])
        assertTrue(calls[7] is Breaker.Call.CheckIn)
        val same = Breaker()
        same.wake("surprise"); same.wake("surprise")
        assertTrue(same.wake("surprise") is Breaker.Call.CheckIn)
        same.milestoneDone()
        assertEquals(Breaker.Call.Go, same.wake("surprise"))

        val d = Breaker()
        assertTrue(d.doneDoubt()); assertTrue(d.doneDoubt()); assertFalse(d.doneDoubt())
        assertTrue(d.handover()); assertFalse(d.handover())
    }

    @Test fun `the ledger counts every call`() {
        val l = Ledger()
        l.add(LedgerEntry(1, "decisions", "~typesafe/jev-latest", 900, costUsd = 0.00004, ms = 300))
        l.add(LedgerEntry(2, "mind", "anthropic/claude-fable-5.1", 8_000, 6_000, 400, 0.012, 4_000))
        assertEquals(2, l.calls())
        assertEquals(0.01204, l.summary().getDouble("cost"), 1e-9)
        assertEquals(l.entries(), Ledger.decode(l.encode()).entries())
    }

    // ---- tool steps ----------------------------------------------------------------------------------------------------

    @Test fun `tool steps fill facts and never guess a missing one`() {
        val s = Score.start("r", Difficulty.MIND, "g", 1).fact("amount", "€482.10", "pdf", 2)
        val ok = ToolSteps.direct(Step("t", "calendar_add", args = mapOf("title" to "Pay {amount}", "duration_minutes" to "30")), s) as ToolSteps.Call.Ready
        assertEquals("Pay €482.10", ok.args.getString("title"))
        assertEquals(30, ok.args.getInt("duration_minutes"))
        assertTrue(ToolSteps.direct(Step("t", "calendar_add", args = mapOf("title" to "Pay {invoice}")), s) is ToolSteps.Call.Missing)
        assertNull(ToolSteps.direct(Step("t", "tap", target = "Send"), s))
        assertEquals("€482.10", ToolSteps.text(Step("t", "type_text", slots = mapOf("text" to "amount")), s))
    }

    // ---- the fused board ----------------------------------------------------------------------------------------------

    private fun ask(step: Step, screen: StepScreen = chat, verify: Expect? = null, tripwire: Boolean = false) =
        FusedBoard.Ask("Tell Anna I'm on my way", "Send", step, screen, screen.controls, verify,
            listOf(StandingOrder("o1", "wake when sent", StandingOrder.Kind.WAKE_WHEN, question = "Was the message sent?")), tripwire)

    @Test fun `one fused call asks everything a step needs`() {
        val a = ask(Step("s", "tap", target = "Send", branches = listOf(Branch("already sent", Branch.BranchAction.DONE))), verify = Expect.Focused, tripwire = true)
        val r = FusedBoard.request(a)
        val names = r.questions.keys
        for (q in listOf("next", "fits_plan", "surprise", "verify", "blocked_by", "popup", "popup_dismiss", "divergence", "order_o1", "branch_0",
            "sends_or_posts", "money", "destroys", "shares_personal", "account", "matches_request")) assertTrue(q, q in names)
        val body = DecisionsWire.body("m", r.state, r.questions)
        assertFalse(DecisionsWire.carriesImageOrBox(body))
        assertTrue((r.questions["next"] as DQuestion.Choice).options.keys.containsAll(listOf("e1", "e2", FusedBoard.STEP_DONE, FusedBoard.NEEDS_MIND)))
    }

    private fun reply(next: String, sure: Double = 0.95, verify: Double? = null, surprise: Double = 0.05, popup: String = "none",
                      dismiss: String = "none", branch: Double = 0.0, order: Double = 0.0, sends: Double = 0.0, matches: Double = 0.9): DReply =
        DReply(buildMap {
            put("next", DAnswer.Choice(next, sure, mapOf(next to sure)))
            put("fits_plan", DAnswer.Noul(0.95)); put("surprise", DAnswer.Noul(surprise))
            verify?.let { put("verify", DAnswer.Noul(it)) }
            put("blocked_by", DAnswer.Choice("none", 0.9, emptyMap()))
            put("popup", DAnswer.Choice(popup, 0.9, emptyMap())); put("popup_dismiss", DAnswer.Choice(dismiss, 0.9, emptyMap()))
            put("divergence", DAnswer.Score(0.0, 0.9, emptyMap())); put("order_o1", DAnswer.Noul(order)); put("branch_0", DAnswer.Noul(branch))
            put("sends_or_posts", DAnswer.Noul(sends)); put("matches_request", DAnswer.Noul(matches))
            for (k in listOf("money", "destroys", "shares_personal", "account")) put(k, DAnswer.Noul(0.0))
        })

    @Test fun `combining stays on the careful side`() {
        val a = ask(Step("s", "tap", target = "x", branches = listOf(Branch("b", Branch.BranchAction.SKIP))), tripwire = true)
        val agree = FusedBoard.combine(listOf(FusedBoard.read(reply("e2"), a), FusedBoard.read(reply("e2", 0.8), a)), undoable = false)!!
        assertEquals("e2", agree.next); assertEquals(0.8, agree.nextSure, 1e-9)
        val split = FusedBoard.combine(listOf(FusedBoard.read(reply("e1"), a), FusedBoard.read(reply("e2"), a)), undoable = true)!!
        assertEquals(FusedBoard.NEEDS_MIND, split.next)
        // One reader alone may act only on an easy-to-undo move.
        assertEquals("e2", FusedBoard.combine(listOf(FusedBoard.read(reply("e2"), a)), undoable = true)!!.next)
        assertEquals(FusedBoard.NEEDS_MIND, FusedBoard.combine(listOf(FusedBoard.read(reply("e2"), a)), undoable = false)!!.next)
        // Orders and risks count if any reader raises them; a branch needs every reader.
        val mixed = FusedBoard.combine(listOf(FusedBoard.read(reply("e2", order = 0.9, sends = 0.6, branch = 0.95), a),
            FusedBoard.read(reply("e2", branch = 0.2), a)), undoable = true)!!
        assertEquals(setOf("o1"), mixed.ordersFired)
        assertEquals(0.6, mixed.risks["sends_or_posts"]!!, 1e-9)
        assertTrue(mixed.branchesFired.isEmpty())
    }

    // ---- the step engine ----------------------------------------------------------------------------------------------

    private class FakeHands : MindToolbox {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        var fail = false
        override fun specs(): List<MindToolSpec> = emptyList()
        override fun execute(call: MindToolCall, arguments: JSONObject): MindToolResult {
            calls += call.name to arguments
            return if (fail) MindToolResult.error("nope") else MindToolResult("ok", changedScreen = call.name != "type_text")
        }
    }

    private class Screens(vararg screens: StepScreen) {
        private val queue = ArrayDeque(screens.toList())
        private var last: StepScreen? = null
        val eyes = StepEyes { _ -> (queue.removeFirstOrNull() ?: last)?.also { last = it } }
    }

    private fun score(vararg steps: Step) = Score.start("r", Difficulty.FLASH, "Tell Anna I'm on my way", 1)
        .copy(plan = Plan(1, listOf(Milestone("m1", "Send", steps.toList()))))

    @Test fun `rung 0 needs no model for direct actions and exact targets`() {
        val hands = FakeHands()
        var asked = 0
        val board = StepBoard { _, _ -> asked++; emptyList() }
        val open = Step("s1", "open_app", args = mapOf("app" to "WhatsApp"), expect = Expect.AppIs("WhatsApp"))
        val engine = StepEngine(Screens(list, chat).eyes, board, hands)
        val out = engine.run(open, score(open))
        assertTrue(out is StepOutcome.Done && out.verdict == Verdict.HELD && out.rung == 0)
        val tap = Step("s2", "tap", target = "search")
        val out2 = StepEngine(Screens(list, chat).eyes, board, hands).run(tap, score(tap))
        assertTrue(out2 is StepOutcome.Done)
        assertEquals(listOf("open_app", "tap"), hands.calls.map { it.first })
        assertEquals("e1", hands.calls[1].second.getString("ref"))
        assertEquals(0, asked)
    }

    @Test fun `the board picks when code can't, and disagreement goes to the mind`() {
        val two = StepScreen("com.whatsapp", "WhatsApp", "list", "f1", listOf(StepControl("e1", "Anna de Vries"), StepControl("e2", "Anna Smit")))
        val step = Step("s", "tap", target = "Anna")
        val hands = FakeHands()
        val agree = StepEngine(Screens(two, chat).eyes, { _, a -> listOf(FusedBoard.read(reply("e1"), a), FusedBoard.read(reply("e1"), a)) }, hands)
        assertTrue(agree.run(step, score(step)) is StepOutcome.Done)
        assertEquals("e1", hands.calls.single().second.getString("ref"))
        val split = StepEngine(Screens(two).eyes, { _, a -> listOf(FusedBoard.read(reply("e1"), a), FusedBoard.read(reply("e2"), a)) }, FakeHands())
        val out = split.run(step, score(step))
        assertTrue(out is StepOutcome.NeedsMind && out.trigger == "unsure")
    }

    @Test fun `code raises risk, and a gated move stops at a barrier until the conductor says go`() {
        val step = Step("s", "tap", target = "Send")
        val hands = FakeHands()
        val engine = StepEngine(Screens(chat, chat, chat).eyes, { _, _ -> emptyList() }, hands)
        val out = engine.run(step, score(step))
        assertTrue(out is StepOutcome.AtBarrier && out.ref == "e2")
        assertTrue(hands.calls.isEmpty())
        assertTrue(engine.actAt(out as StepOutcome.AtBarrier, score(step)) is StepOutcome.Done)
        assertEquals("tap", hands.calls.single().first)

        val port = Step("p", "port_send", args = mapOf("port" to "log.out", "text" to "done"))
        val portOut = StepEngine(Screens(chat).eyes, { _, _ -> emptyList() }, FakeHands()).run(port, score(port))
        assertTrue(portOut is StepOutcome.AtBarrier && portOut.ref == null)
    }

    @Test fun `a missed postcondition, and a previous step found false, go to the mind`() {
        val step = Step("s", "open_app", args = mapOf("app" to "Instagram"), expect = Expect.AppIs("Instagram"))
        val out = StepEngine(Screens(list, chat).eyes, { _, _ -> emptyList() }, FakeHands()).run(step, score(step))
        assertTrue(out is StepOutcome.Missed)

        val unsure = Step("s1", "open_app", args = mapOf("app" to "WhatsApp"), expect = Expect.Described("the chat list is shown"))
        val next = Step("s2", "tap", target = "Anna")
        val two = StepScreen("com.whatsapp", "WhatsApp", "list", "f1", listOf(StepControl("e1", "Anna de Vries"), StepControl("e2", "Anna Smit")))
        val engine = StepEngine(Screens(list, two, two).eyes, { _, a -> listOf(FusedBoard.read(reply("e1", verify = 0.1), a)) }, FakeHands())
        assertTrue(engine.run(unsure, score(unsure, next)) is StepOutcome.Done)
        assertNotNull(engine.pendingVerify)
        val then = engine.run(next, score(unsure, next))
        assertTrue(then is StepOutcome.PreviousMissed && then.step.id == "s1")
    }

    @Test fun `a step the plan gave no check must still show it happened`() {
        val launcher = StepScreen("com.google.android.apps.nexuslauncher", "Pixel Launcher", "home", "h1", listOf(StepControl("e1", "Instagram")))
        val insta = StepScreen("com.instagram.android", "Instagram", "feed", "i1", listOf(StepControl("e1", "Profile")), listOf("For you"))
        // open_app without an expect: the app must be the one in front, not just "the tool said ok".
        val open = Step("s1", "open_app", args = mapOf("app" to "Instagram"))
        assertTrue(StepEngine(Screens(launcher, launcher).eyes, { _, _ -> emptyList() }, FakeHands()).run(open, score(open)) is StepOutcome.Missed)
        val opened = StepEngine(Screens(launcher, insta).eyes, { _, _ -> emptyList() }, FakeHands()).run(open, score(open))
        assertTrue(opened is StepOutcome.Done && opened.verdict == Verdict.HELD)
        // A tap without an expect that changed nothing missed.
        val still = object : MindToolbox {
            override fun specs(): List<MindToolSpec> = emptyList()
            override fun execute(call: MindToolCall, arguments: JSONObject) = MindToolResult("ok", changedScreen = false)
        }
        val tap = Step("s2", "tap", target = "Profile")
        assertTrue(StepEngine(Screens(insta, insta).eyes, { _, _ -> emptyList() }, still).run(tap, score(tap)) is StepOutcome.Missed)
        val profile = insta.copy(pageKey = "profile", fingerprint = "p1", texts = listOf("Edit profile"))
        assertTrue(StepEngine(Screens(insta, profile).eyes, { _, _ -> emptyList() }, still).run(tap, score(tap)) is StepOutcome.Done)
    }

    @Test fun `popups close by themselves, branches skip, risky surprises stop`() {
        val banner = StepScreen("com.shop", "Shop", "home", "f1", listOf(StepControl("e1", "Accept all"), StepControl("e2", "Reject"), StepControl("e3", "Cart")))
        val clean = banner.copy(fingerprint = "f2", controls = listOf(StepControl("e3", "Cart"), StepControl("e4", "Menu")))
        val step = Step("s", "tap", target = "basket")
        var round = 0
        val hands = FakeHands()
        val engine = StepEngine(Screens(banner, clean, clean, clean).eyes, { _, a ->
            round++
            if (round == 1) listOf(FusedBoard.read(reply("e3", popup = "cookie_banner", dismiss = "e2"), a))
            else listOf(FusedBoard.read(reply("e3"), a))
        }, hands)
        assertTrue(engine.run(step, score(step)) is StepOutcome.Done)
        assertEquals(listOf("e2", "e3"), hands.calls.map { it.second.getString("ref") })

        val branchy = Step("b", "tap", target = "x", branches = listOf(Branch("dark mode is already on", Branch.BranchAction.SKIP)))
        val b = StepEngine(Screens(list).eyes, { _, a -> listOf(FusedBoard.read(reply("e1", branch = 0.97), a), FusedBoard.read(reply("e1", branch = 0.9), a)) }, FakeHands())
        assertTrue(b.run(branchy, score(branchy)) is StepOutcome.Branched)

        val risky = StepEngine(Screens(two()).eyes, { _, a -> listOf(FusedBoard.read(reply("e1", sends = 0.8), a)) }, FakeHands())
        val typing = Step("t", "type_text", target = "note", args = mapOf("text" to "hi"))
        val out = risky.run(typing, score(typing))
        assertTrue(out is StepOutcome.NeedsMind && out.trigger == "tripwire")

        val secret = list.copy(sensitive = true)
        assertTrue(StepEngine(Screens(secret).eyes, { _, _ -> emptyList() }, FakeHands()).run(step, score(step)) is StepOutcome.NeedsMind)
    }

    private fun two() = StepScreen("com.notes", "Notes", "n", "f1", listOf(StepControl("e1", "Note body", "edit", editable = true), StepControl("e2", "Title", "edit", editable = true)))

    // ---- the wire and the vision shadow ---------------------------------------------------------------------------------

    @Test fun `box questions go on the wire and come back as edges, never as a tap`() {
        val q = mapOf("where" to DQuestion.Box("the shutter"), "present" to DQuestion.Noul("is it there", "yes", "no"))
        val img = "data:image/png;base64,iVBORw0KGgo="
        val body = DecisionsWire.body("~typesafe/jev-latest", listOf(DPart.Text("screen"), DPart.Image(img)), q)
        assertEquals("box", body.getJSONObject("questions").getJSONObject("where").getString("type"))
        assertTrue(DecisionsWire.carriesImageOrBox(body))
        val reply = DecisionsWire.parse("""{"answers":{"where":{"type":"box","box":{"top":0.4,"bottom":0.6,"left":0.45,"right":0.55},"confidence":0.8},
            "present":{"type":"noul","noul":0.95}}}""", q)!!
        val box = reply.answers["where"] as DAnswer.Box
        assertEquals(0.5, box.centerX, 1e-9); assertTrue(box.plausible)
        assertNull(DecisionsWire.parse("""{"answers":{"where":{"type":"box","box":{"top":1.4,"bottom":0.6,"left":0.45,"right":0.55}}}}""", q)!!.answers["where"])

        val shot = chat.copy(imageDataUrl = img)
        val req = VisionShadow.request(shot, Step("s", "tap", target = "the shutter"))!!
        assertTrue("where" in req.questions)
        assertNull(VisionShadow.request(shot.copy(sensitive = true), Step("s", "tap", target = "x")))
        val reading = VisionShadow.Reading(0.95, "one", box)
        assertTrue(reading.wouldTap)
        assertTrue(VisionShadow.line(Step("s", "tap", target = "the shutter"), reading).contains("not tapped"))
    }
}
