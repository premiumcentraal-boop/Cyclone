package com.cyclone.mobile.mind.one

import com.cyclone.mobile.mind.MindConversation
import com.cyclone.mobile.mind.MindMessage
import com.cyclone.mobile.mind.MindModel
import com.cyclone.mobile.mind.MindModelReply
import com.cyclone.mobile.mind.MindModelRequest
import com.cyclone.mobile.mind.MindToolCall
import com.cyclone.mobile.mind.MindToolSpec
import com.cyclone.mobile.mind.MindUsage
import com.cyclone.mobile.mind.StreamingMindModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 69 checkpoint 2: the minds against a scripted model, a fake crew and a fake owner. */
class OneMindConductorTest {

    // ---- fakes --------------------------------------------------------------------------------------------------------

    private open class ScriptedModel(vararg replies: String) : MindModel {
        override val id = "test/model"
        override val label = "Test model"
        override val vision = true
        val queue = ArrayDeque(replies.toList())
        val requests = mutableListOf<MindModelRequest>()
        val calls = mutableListOf<MindToolCall>()
        @Synchronized override fun complete(request: MindModelRequest): MindModelReply {
            requests += request
            val text = queue.removeFirstOrNull() ?: """{"decision":"give_up","summary":"the script ran out"}"""
            if (text.startsWith("TOOL:")) return MindModelReply("", listOf(MindToolCall("c1", text.removePrefix("TOOL:"), "{}")), usage = MindUsage(100, 10, 0.001))
            return MindModelReply(text, emptyList(), usage = MindUsage(1_000, 50, 0.002))
        }
    }

    private class FakeCrew(private val script: (Step, Int) -> StepOutcome = { s, _ -> StepOutcome.Done(s, Verdict.HELD, 0) }) : Crew {
        val runs = mutableListOf<Step>()
        val acted = mutableListOf<String>()
        var undone = 0
        var doneYes: Double? = 0.95
        var verdict = Verdict.UNKNOWN
        var onRun: (Step) -> Unit = {}
        var texts = listOf("Chats", "Anna de Vries")
        override fun run(step: Step, score: Score): StepOutcome {
            runs += step
            onRun(step)
            return script(step, runs.count { it.target == step.target && it.action == step.action })
        }
        override fun actAt(barrier: StepOutcome.AtBarrier, score: Score): StepOutcome { acted += barrier.step.target.orEmpty(); return StepOutcome.Done(barrier.step, Verdict.HELD, 1) }
        override fun look(image: Boolean) = StepScreen("com.whatsapp", "WhatsApp", "list", "f", listOf(StepControl("e1", "Search")), texts,
            imageDataUrl = if (image) "data:image/png;base64,AAAA" else null)
        override fun check(expect: Expect, score: Score) = verdict
        override fun done(score: Score, screen: StepScreen?) = doneYes
        override fun undo(moves: Int) { undone += moves }
    }

    private class FakeOwner(var checkIn: CheckIn = CheckIn.Stop, var answer: String? = "Anna de Vries") : OwnerLine {
        val questions = mutableListOf<String>()
        val checkIns = mutableListOf<String>()
        val steerQueue = ArrayDeque<String>()
        override fun steers(): List<String> = buildList { while (steerQueue.isNotEmpty()) add(steerQueue.removeFirst()) }
        override fun ask(question: String, choices: List<String>): String? { questions += question; return answer }
        override fun checkIn(reason: String): CheckIn { checkIns += reason; return checkIn }
    }

    private fun conductor(model: MindModel, crew: Crew, owner: OwnerLine = FakeOwner(), profile: Profile = Profile.MIND,
                          wheel: Wheel? = null, compactor: Compactor = Compactor(), runner: (() -> Unit) -> Unit = { it() },
                          efforts: MutableList<Effort> = mutableListOf(), situation: String = "") =
        Conductor("run-1", "Tell Anna I'm on my way", profile, crew, { e -> efforts += e; model }, owner, wheel, compactor,
            glanceRunner = runner, situation = { situation }, clock = { 1_000L })

    private fun lastText(r: MindModelRequest): String {
        val content = r.messages.getJSONObject(r.messages.length() - 1).get("content")
        return if (content is org.json.JSONArray) content.getJSONObject(0).getString("text") else content.toString()
    }

    private val twoSteps = """
        {"milestone":"Open Anna's chat"}
        {"step":"open_app","args":{"app":"WhatsApp"},"expect":{"app":"WhatsApp"}}
        {"step":"tap","target":"Anna de Vries","expect":{"shows":"Anna de Vries"}}
    """.trimIndent()

    // ---- the plan format ------------------------------------------------------------------------------------------------

    @Test fun `plan lines read milestones, steps, orders, facts and the done check, and ignore everything else`() {
        val text = """
            Sure, here is the plan:
            ```json
            {"decision":"replan","why":"wrong app"}
            {"milestone":"Find the invoice","review":true}
            {"step":"open_app","args":{"app":"Gmail"}}
            {"step":"tap","target":"Invoice October","risk":"gated","if":[{"if":"the invoice is already open","then":"skip"}]}
            {"step":"type_text","target":"Amount","args":{"text":"{amount}"},"slots":{"to":"payee"}}
            {"milestone":"Pay it"}
            {"step":"tap","target":"Pay","expect":{"all":[{"shows":"Paid"},{"absent":"Error"}]}}
            {"order":{"kind":"wake_if_over","text":"Wake me if over 400","fact":"amount","over":"€400"}}
            {"order":{"kind":"wake_when","text":"no question"}}
            {"fact":"payee","value":"Mark Jansen"}
            {"done":{"shows":"Paid"}}
            {"step":"BAD ACTION!"}
            not json {"step":
            ```
        """.trimIndent()
        val reply = WakeReply.read(text, "Pay the invoice", "w2.")
        assertEquals(WakeReply.Kind.REPLAN, reply.kind)
        assertEquals("wrong app", reply.why)
        val plan = reply.plan!!
        assertEquals(listOf("w2.m1", "w2.m2"), plan.milestones.map { it.id })
        assertTrue(plan.milestones[0].review)
        assertEquals(listOf("w2.s1", "w2.s2", "w2.s3", "w2.s4"), plan.steps.map { it.id })
        assertEquals(Risk.GATED, plan.steps[1].risk)
        assertEquals(Branch.BranchAction.SKIP, plan.steps[1].branches.single().then)
        assertEquals(mapOf("to" to "payee"), plan.steps[2].slots)
        assertTrue(plan.steps[3].expect is Expect.All)
        assertEquals(Expect.Shows("Paid"), plan.done)
        assertEquals(1, reply.orders!!.size)
        assertEquals(400.0, reply.orders!!.single().over!!, 1e-9)
        assertEquals(listOf("payee" to "Mark Jansen"), reply.facts)
    }

    @Test fun `decisions are typed, and ones missing what they need fall back to continue`() {
        assertEquals(WakeReply.Kind.CONTINUE, WakeReply.read("", "g").kind)
        assertEquals(WakeReply.Kind.REPLAN, WakeReply.read(twoSteps, "g").kind)
        assertEquals(WakeReply.Kind.CONTINUE, WakeReply.read("""{"decision":"ask"}""", "g").kind)
        val ask = WakeReply.read("""{"decision":"ask","question":"Which Anna?","choices":["Anna de Vries","Anna Smit"],"consent":false}""", "g")
        assertEquals(WakeReply.Kind.ASK, ask.kind); assertEquals(2, ask.choices.size)
        assertEquals(WakeReply.Kind.CONTINUE, WakeReply.read("""{"decision":"adjust"}""", "g").kind)
        assertEquals(WakeReply.Kind.NEEDS_FRONTIER, WakeReply.read("""{"decision":"needs_frontier","why":"open-ended"}""", "g").kind)
        val finish = WakeReply.read("""{"decision":"finish","summary":"Sent."}""", "g")
        assertEquals(WakeReply.Kind.FINISH, finish.kind); assertEquals("Sent.", finish.summary)
        assertEquals(WakeReply.Kind.RECALL, WakeReply.read("""{"decision":"recall","what":"the invoice number"}""", "g").kind)
    }

    @Test fun `a streamed plan is cut into lines however the pieces arrive`() {
        val lines = mutableListOf<String>()
        val stream = PlanLines.Stream { lines += it }
        val text = twoSteps + "\n"
        text.chunked(7).forEach(stream::feed)
        stream.finish()
        assertEquals(twoSteps.lines(), lines)
        val draft = PlanLines.Draft("goal")
        draft.line(lines[1])
        assertEquals("m1", draft.plan()!!.milestones.single().id) // a step before any milestone gets one
        assertEquals("goal", draft.plan()!!.milestones.single().title)
    }

    // ---- standing orders ------------------------------------------------------------------------------------------------

    @Test fun `numbers are read by code in every common way`() {
        assertEquals(1234.56, StandingOrders.number("€1.234,56")!!, 1e-9)
        assertEquals(1234.56, StandingOrders.number("$1,234.56")!!, 1e-9)
        assertEquals(482.10, StandingOrders.number("Total: 482.10 EUR")!!, 1e-9)
        assertEquals(482.10, StandingOrders.number("482,10")!!, 1e-9)
        assertEquals(1234567.0, StandingOrders.number("1.234.567")!!, 1e-9)
        assertEquals(400.0, StandingOrders.number("over 400 for 3 items")!!, 1e-9)
        assertEquals(-3.0, StandingOrders.number("-3")!!, 1e-9)
        assertNull(StandingOrders.number("no amount"))
    }

    @Test fun `orders compile to at most 8, fire on facts by code and are spent once fired`() {
        val many = (1..12).map { StandingOrder("", "o$it", StandingOrder.Kind.WAKE_WHEN, question = "Is $it shown?") }
        assertEquals(8, StandingOrders.compile(many).size)
        assertEquals((1..8).map { "o$it" }, StandingOrders.compile(many).map { it.id })
        val over = StandingOrders.compile(listOf(StandingOrder("", "over 400", StandingOrder.Kind.WAKE_IF_OVER, fact = "amount", over = 400.0),
            StandingOrder("", "quiet", StandingOrder.Kind.QUIET_UNTIL, question = "Is checkout open?")))
        var s = Score.start("r", Difficulty.MIND, "g", 1).orders(over)
        assertTrue(StandingOrders.firedByFacts(s).isEmpty())
        assertTrue(StandingOrders.quiet(s))
        s = s.fact("amount", "€ 482,10", "read", 2)
        assertEquals(listOf("o1"), StandingOrders.firedByFacts(s).map { it.id })
        s = StandingOrders.spend(s, "o1")
        assertTrue(StandingOrders.firedByFacts(s).isEmpty())
        assertEquals(listOf("o2"), StandingOrders.questions(s.orders).map { it.id })
    }

    // ---- briefings ------------------------------------------------------------------------------------------------------

    @Test fun `briefings carry what happened, the facts exactly, and an image only when the trigger is visual`() {
        var s = Score.start("r", Difficulty.MIND, "Pay the invoice", 1).copy(plan = WakeReply.read(twoSteps, "g").plan)
        s = s.event("act", "Opened Gmail", 2).event("act", "Tapped \"Invoice\"", 3).fact("amount", "€482.10", "read", 4)
        val screen = StepScreen("com.google.android.gm", "Gmail", "p", "f", listOf(StepControl("e1", "Pay")), listOf("Invoice October"),
            imageDataUrl = "data:image/png;base64,AAAA")
        val surprise = Briefing.build(3, WakeKind.REPLAN, "surprise", "Not what the plan expected", s, sinceSeq = 1, evidence = Briefing.Evidence(screen))
        assertTrue(surprise.text.startsWith("BRIEFING 3 · REPLAN · trigger: surprise"))
        assertFalse(surprise.text.contains("Opened Gmail")) // already seen
        assertTrue(surprise.text.contains("Tapped \"Invoice\""))
        assertTrue(surprise.text.contains("amount = €482.10"))
        assertTrue(surprise.text.contains("e1 control \"Pay\""))
        assertNotNull(surprise.imageDataUrl)
        assertEquals(2, surprise.lastSeq)
        assertNull(Briefing.build(4, WakeKind.CONSULT, "missing_fact", "x", s, 0, Briefing.Evidence(screen)).imageDataUrl)
        val secret = Briefing.build(5, WakeKind.REPLAN, "surprise", "x", s, 0, Briefing.Evidence(screen.copy(sensitive = true)))
        assertNull(secret.imageDataUrl)
        assertFalse(secret.text.contains("Invoice October"))
    }

    // ---- the conductor ----------------------------------------------------------------------------------------------------

    @Test fun `a Mind run plans, the crew runs the steps, and a checked finish is done`() {
        val model = ScriptedModel(twoSteps)
        val crew = FakeCrew()
        val c = conductor(model, crew)
        val out = c.run()
        assertEquals(RunEnd.DONE, out.end)
        assertEquals(listOf("open_app", "tap"), crew.runs.map { it.action })
        assertEquals(1, out.wakes)
        assertEquals(1, model.requests.size)
        assertEquals(1, c.ledger.calls("mind"))
        val conversation = c.conversation.all()
        assertTrue(conversation[0] is MindMessage.System)
        assertTrue((conversation[1] as MindMessage.User).text.startsWith("BRIEFING 1 · PLAN"))
        assertTrue(conversation[2] is MindMessage.Assistant)
        assertEquals(StepState.DONE, out.score.plan!!.steps.last().state)
        assertTrue(out.summary.startsWith("Done"))
    }

    @Test fun `each wake kind runs at its effort, and the decision is applied`() {
        // Step 2 is unsure (a consult, at low effort): skip it. Step 3 misses (a replan, at the owner's effort): adjust.
        val plan = """{"milestone":"m"}
            {"step":"open_app","args":{"app":"WhatsApp"}}
            {"step":"tap","target":"Anna"}
            {"step":"tap","target":"Later"}""".trimIndent()
        val model = ScriptedModel(plan, """{"decision":"skip","why":"not needed"}""",
            """{"decision":"adjust","why":"use the menu"}
               {"step":"tap","target":"Menu"}""".trimIndent())
        val crew = FakeCrew { s, _ ->
            when (s.target) {
                "Anna" -> StepOutcome.NeedsMind(s, "unsure", "two Annas")
                "Later" -> StepOutcome.Missed(s, "nothing happened")
                else -> StepOutcome.Done(s, Verdict.HELD, 0)
            }
        }
        val efforts = mutableListOf<Effort>()
        val out = conductor(model, crew, efforts = efforts).run()
        assertEquals(RunEnd.DONE, out.end)
        assertEquals(listOf("WhatsApp", "Anna", "Later", "Menu"), crew.runs.map { it.target ?: it.args["app"] })
        assertEquals(StepState.SKIPPED, out.score.plan!!.steps.first { it.target == "Anna" }.state)
        assertTrue(Effort.LOW in efforts && Effort.OWNER in efforts)
        assertEquals(3, out.wakes)
    }

    @Test fun `the wheel drives in the same conversation and hands back with a plan`() {
        val model = ScriptedModel("""{"milestone":"m"}
            {"step":"tap","target":"Unknown app thing"}""".trimIndent(), """{"decision":"wheel","why":"this app is new"}""")
        val crew = FakeCrew { s, _ -> if (s.target == "Unknown app thing") StepOutcome.NeedsMind(s, "surprise", "new app") else StepOutcome.Done(s, Verdict.HELD, 0) }
        var drove: MindConversation? = null
        val wheel = object : Wheel {
            override fun specs() = listOf(MindToolSpec("tap", "Tap"))
            override fun drive(conversation: MindConversation, why: String): WheelEnd {
                drove = conversation
                return WheelEnd.HandedBack("""{"milestone":"rest"}
                    {"step":"tap","target":"Next"}""".trimIndent())
            }
        }
        val c = conductor(model, crew, wheel = wheel)
        val out = c.run()
        assertEquals(RunEnd.DONE, out.end)
        assertTrue(drove === c.conversation)
        assertTrue(crew.runs.any { it.target == "Next" })
        assertTrue(out.score.events.any { it.line.contains("handed back") })
    }

    @Test fun `a forced review runs beside the crew, and the crew's moves are taken back when it disagrees`() {
        val plan = """{"milestone":"Write and send"}
            {"step":"tap","target":"Message"}
            {"step":"type_text","target":"Message","args":{"text":"On my way"}}
            {"step":"tap","target":"Send","risk":"gated"}""".trimIndent()
        val glanceAdjust = """{"decision":"adjust","why":"say it warmer"}
            {"step":"type_text","target":"Message","args":{"text":"On my way, see you soon"}}
            {"step":"tap","target":"Send","risk":"gated"}""".trimIndent()
        val model = ScriptedModel(plan, glanceAdjust, """{"decision":"continue"}""")
        var pending: (() -> Unit)? = null
        val crew = FakeCrew { s, _ -> if (s.risk == Risk.GATED) StepOutcome.AtBarrier(s, "e2", "Send") else StepOutcome.Done(s, Verdict.HELD, 0) }
        // The glance answers only after the crew made two easy-to-undo moves.
        crew.onRun = { if (crew.runs.size == 3) pending?.invoke() }
        val out = conductor(model, crew, runner = { pending = it }).run()
        assertEquals(RunEnd.DONE, out.end)
        assertEquals(2, crew.undone)
        assertTrue(out.score.events.any { it.kind == "undo" })
        assertEquals(listOf("Send"), crew.acted)
        assertTrue(crew.runs.any { it.args["text"] == "On my way, see you soon" })
    }

    @Test fun `a barrier in a milestone nobody reviewed waits for a blocking glance`() {
        val plan = """{"milestone":"Send"}
            {"step":"tap","target":"Post"}""".trimIndent()
        val model = ScriptedModel(plan, """{"decision":"continue"}""", """{"decision":"continue"}""")
        val crew = FakeCrew { s, _ -> StepOutcome.AtBarrier(s, "e1", "Post") }
        val out = conductor(model, crew).run()
        assertEquals(RunEnd.DONE, out.end)
        assertEquals(listOf("Post"), crew.acted)
        // The milestone posts: code forced its review before the crew started it, and the barrier waited for it.
        assertTrue(out.score.events.any { it.line.startsWith("Glance") })
    }

    @Test fun `replans keep finished milestones, and a detour returns to the paused milestone`() {
        val plan = """{"milestone":"One"}
            {"step":"open_app","args":{"app":"Bank"}}
            {"milestone":"Two"}
            {"step":"tap","target":"Statements"}""".trimIndent()
        val detour = """{"decision":"detour","why":"log in first"}
            {"milestone":"Log in"}
            {"step":"tap","target":"Log in"}""".trimIndent()
        val model = ScriptedModel(plan, detour)
        val crew = FakeCrew { s, n -> if (s.target == "Statements" && n == 1) StepOutcome.Blocked(s, "login") else StepOutcome.Done(s, Verdict.HELD, 0) }
        val out = conductor(model, crew).run()
        assertEquals(RunEnd.DONE, out.end)
        assertEquals(listOf("Bank", "Statements", "Log in", "Statements"), crew.runs.map { it.target ?: it.args["app"] })
        assertTrue(out.score.events.any { it.line.startsWith("Back from the side trip") })

        var s = Score.start("r", Difficulty.MIND, "g", 1).copy(plan = WakeReply.read(plan, "g").plan)
        s = s.mark("s1", StepState.DONE)
        s = s.replan(WakeReply.read("""{"milestone":"Other way"}
            {"step":"back"}""".trimIndent(), "g", "w2.").plan!!)
        assertEquals(listOf("m1", "w2.m1"), s.plan!!.milestones.map { it.id })
    }

    @Test fun `doubt means not done, with two re-checks and then couldn't confirm`() {
        val model = ScriptedModel(twoSteps, """{"decision":"finish","summary":"Sent it."}""", """{"decision":"finish","summary":"Sent it."}""")
        val crew = FakeCrew().apply { doneYes = 0.2 }
        val out = conductor(model, crew).run()
        assertEquals(out.score.events.joinToString("\n") { it.line }, RunEnd.UNCONFIRMED, out.end)
        assertTrue(out.summary.startsWith("Couldn't confirm: please check."))
        assertEquals(2, out.score.events.count { it.kind == "wake" && it.line.contains("done check") })

        // Code's own predicate missing can never be outvoted by a model.
        val strict = conductor(ScriptedModel(twoSteps + "\n{\"done\":{\"shows\":\"Sent\"}}", """{"decision":"finish"}""", """{"decision":"finish"}"""), FakeCrew().apply { verdict = Verdict.MISSED; doneYes = 1.0 }).run()
        assertEquals(RunEnd.UNCONFIRMED, strict.end)
        // And code holding needs no model.
        assertEquals(RunEnd.DONE, conductor(ScriptedModel(twoSteps + "\n{\"done\":{\"shows\":\"On my way\"}}"), FakeCrew().apply { verdict = Verdict.HELD; doneYes = null }).run().end)
    }

    @Test fun `the same trigger three times asks the owner, who can stop or keep going`() {
        val plan = """{"milestone":"m"}
            {"step":"tap","target":"Stubborn"}""".trimIndent()
        val stuck = FakeCrew { s, _ -> StepOutcome.NeedsMind(s, "unsure", "can't tell") }
        val owner = FakeOwner(checkIn = CheckIn.Stop)
        val out = conductor(ScriptedModel(plan, "{\"decision\":\"continue\"}", "{\"decision\":\"continue\"}"), stuck, owner).run()
        assertEquals(RunEnd.STOPPED, out.end)
        assertEquals(1, owner.checkIns.size)
        assertTrue(owner.checkIns.single().contains("unsure"))

        val keep = FakeOwner(checkIn = CheckIn.KeepGoing)
        val healed = FakeCrew { s, n -> if (n <= 3) StepOutcome.NeedsMind(s, "unsure", "can't tell") else StepOutcome.Done(s, Verdict.HELD, 0) }
        val out2 = conductor(ScriptedModel(plan, "{\"decision\":\"continue\"}", "{\"decision\":\"continue\"}", "{\"decision\":\"continue\"}"), healed, keep).run()
        assertEquals(RunEnd.DONE, out2.end)
        assertEquals(1, keep.checkIns.size)
    }

    @Test fun `four wakes in a milestone tell the mind to change approach`() {
        val plan = """{"milestone":"m"}
            {"step":"tap","target":"A"}""".trimIndent()
        val triggers = listOf("unsure", "missing_fact", "tripwire", "popup")
        val crew = FakeCrew { s, n -> if (n <= 4) StepOutcome.NeedsMind(s, triggers[n - 1], "t$n") else StepOutcome.Done(s, Verdict.HELD, 0) }
        val model = ScriptedModel(plan, *Array(4) { "{\"decision\":\"continue\"}" })
        conductor(model, crew).run()
        val fourth = lastText(model.requests[4])
        assertTrue(fourth.contains("4th wake in this milestone"))
    }

    @Test fun `a question is tried once by the mind, then reaches the owner, and consent goes straight there`() {
        val plan = """{"milestone":"m"}
            {"step":"tap","target":"Anna"}""".trimIndent()
        val ask = """{"decision":"ask","question":"Which Anna?"}"""
        val owner = FakeOwner(answer = "Anna de Vries")
        val crew = FakeCrew { s, n -> if (n == 1) StepOutcome.NeedsMind(s, "unsure", "two Annas") else StepOutcome.Done(s, Verdict.HELD, 0) }
        val model = ScriptedModel(plan, ask, ask, """{"decision":"continue"}""")
        val out = conductor(model, crew, owner).run()
        assertEquals(RunEnd.DONE, out.end)
        assertEquals(listOf("Which Anna?"), owner.questions)
        val hinted = lastText(model.requests[2])
        assertTrue(hinted.contains("Before this reaches the owner"))

        val consentOwner = FakeOwner(answer = "yes")
        val consent = ScriptedModel(plan, """{"decision":"ask","question":"May I share your location with Anna?","consent":true}""", """{"decision":"continue"}""")
        conductor(consent, FakeCrew { s, n -> if (n == 1) StepOutcome.NeedsMind(s, "unsure", "x") else StepOutcome.Done(s, Verdict.HELD, 0) }, consentOwner).run()
        assertEquals(1, consentOwner.questions.size)
    }

    @Test fun `a steer is a new goal version and the plan follows it`() {
        val owner = FakeOwner()
        val model = ScriptedModel(twoSteps, """{"decision":"replan","why":"new goal"}
            {"milestone":"Call instead"}
            {"step":"tap","target":"Call"}""".trimIndent())
        val crew = FakeCrew()
        crew.onRun = { if (crew.runs.size == 1) owner.steerQueue += "Call Anna instead" }
        val out = conductor(model, crew, owner).run()
        assertEquals(2, out.score.goal.version)
        assertEquals("Call Anna instead", out.score.goal.text)
        assertTrue(crew.runs.any { it.target == "Call" })
        assertFalse(crew.runs.any { it.target == "Anna de Vries" })
    }

    @Test fun `Flash hands over to OneMind once, keeping the Score, with a summary as the first briefing`() {
        val model = ScriptedModel("""{"decision":"needs_frontier","why":"this is open-ended"}""", twoSteps)
        val crew = FakeCrew()
        val c = conductor(model, crew, profile = Profile.FLASH, compactor = Compactor(story = StoryWriter { _, _ -> null }))
        val out = c.run()
        assertEquals(RunEnd.DONE, out.end)
        assertTrue(out.handedOver)
        assertEquals(Difficulty.MIND, out.score.difficulty)
        val system = (c.conversation.all()[0] as MindMessage.System).text
        assertTrue(system.startsWith("You are OneMind"))
        val first = (c.conversation.all()[1] as MindMessage.User).text
        assertTrue(first.contains("HANDOVER from the Flash mind: this is open-ended"))

        // The step budget hands over too.
        val long = (1..13).joinToString("\n") { """{"step":"tap","target":"t$it"}""" }
        val budget = conductor(ScriptedModel(long, """{"milestone":"rest"}
            {"step":"tap","target":"last"}""".trimIndent()), FakeCrew(), profile = Profile.FLASH).run()
        assertTrue(budget.handedOver)
        assertTrue(budget.score.events.any { it.line.contains("passed 12 steps") })
    }

    @Test fun `a streamed Flash plan starts acting on its first step while the rest is written`() {
        val firstStepRan = CountDownLatch(1)
        var actedEarly = false
        val model = object : ScriptedModel(), StreamingMindModel {
            override fun stream(request: MindModelRequest, onText: (String) -> Unit): MindModelReply {
                val first = "{\"step\":\"open_app\",\"args\":{\"app\":\"Settings\"}}\n"
                onText(first)
                actedEarly = firstStepRan.await(3, TimeUnit.SECONDS)
                val second = "{\"step\":\"tap\",\"target\":\"Dark mode\"}\n"
                onText(second)
                return MindModelReply(first + second, emptyList(), usage = MindUsage(500, 40, 0.001))
            }
        }
        val crew = FakeCrew()
        crew.onRun = { firstStepRan.countDown() }
        val out = conductor(model, crew, profile = Profile.FLASH).run()
        assertTrue(actedEarly)
        assertEquals(RunEnd.DONE, out.end)
        assertEquals(listOf("open_app", "tap"), crew.runs.map { it.action })
        assertTrue(out.score.plan!!.steps.all { it.state == StepState.DONE })
    }

    @Test fun `Flash finishes at once when the goal already holds`() {
        val crew = FakeCrew().apply { verdict = Verdict.HELD }
        val out = conductor(ScriptedModel("""{"decision":"finish","summary":"Already on"}
            {"done":{"setting":"dark_mode","value":"on"}}""".trimIndent()), crew, profile = Profile.FLASH).run()
        assertEquals(RunEnd.DONE, out.end)
        assertEquals("Already on", out.summary)
        assertTrue(crew.runs.isEmpty())
    }

    @Test fun `a mind reaching for tools in a briefing is taken as the wheel`() {
        val model = ScriptedModel("TOOL:tap")
        val out = conductor(model, FakeCrew()).run()
        // No wheel in this run: it is told to write a plan, the script runs out, and the run ends honestly.
        assertEquals(RunEnd.GAVE_UP, out.end)
        val tool = model.requests[1].messages.let { m -> (0 until m.length()).map { m.getJSONObject(it) }.first { it.optString("role") == "tool" } }
        assertTrue(tool.getString("content").startsWith("NOT RUN"))
    }

    // ---- compaction -------------------------------------------------------------------------------------------------------

    @Test fun `compaction waits for the limit and a boundary, keeps facts and the mind's words, and recall reopens`() {
        val c = MindConversation(listOf(MindMessage.System("system")))
        repeat(20) { i ->
            c.add(MindMessage.User("BRIEFING $i · REPLAN · trigger: surprise\n" + "screen text about invoice INV-$i ".repeat(250), origin = MindMessage.User.Origin.HARNESS))
            c.add(MindMessage.Assistant("I will open invoice $i next.", emptyList()))
            c.add(MindMessage.Tool("t$i", "read_screen", "full ".repeat(500), "read the screen"))
        }
        val score = Score.start("r", Difficulty.MIND, "g", 1).fact("amount", "€482.10", "read", 2)
        val below = Compactor(CompactAt.K200)
        assertFalse(below.due(c, atBoundary = true))
        val compactor = Compactor(CompactAt.K50, StoryWriter { _, _ -> null })
        val tokens = compactor.tokens(c)
        assertTrue(tokens in 50_001..59_999) // over the limit, not 20% over
        assertFalse(compactor.due(c, atBoundary = false))
        assertTrue(compactor.due(c, atBoundary = true))
        val r = compactor.compact(c, score, keepFrom = c.size() - 3)!!
        assertTrue(r.tokensAfter < r.tokensBefore / 4)
        assertFalse(r.byLuna)
        val all = c.all()
        assertTrue(all.filterIsInstance<MindMessage.Assistant>().all { it.text.startsWith("I will open invoice") })
        val story = (all.last() as MindMessage.User).text
        assertTrue(story.startsWith("STORY SO FAR"))
        assertTrue(story.contains("amount = €482.10"))
        assertTrue(compactor.recall("INV-7").contains("INV-7"))
        assertFalse(Compactor(CompactAt.OFF).due(c, atBoundary = true))

        val luna = Compactor(CompactAt.K50, StoryWriter { _, _ -> "Learned: the invoice list is under Billing." })
        val c2 = MindConversation(listOf(MindMessage.System("system"), MindMessage.User("BRIEFING 1 · PLAN\n" + "x".repeat(260_000))))
        assertTrue(luna.compact(c2, score, c2.size())!!.byLuna)
        assertTrue((c2.all().last() as MindMessage.User).text.contains("under Billing"))
    }

    @Test fun `a Mind run compacts at a milestone boundary only past the limit, and Flash never does`() {
        val plan = """{"milestone":"One"}
            {"step":"open_app","args":{"app":"Bank"}}
            {"milestone":"Two"}
            {"step":"tap","target":"Statements"}""".trimIndent()
        val big = "screen ".repeat(30_000)
        val crew = FakeCrew().apply { texts = listOf(big.take(120)) }
        val small = conductor(ScriptedModel(plan), crew, compactor = Compactor(CompactAt.K50, StoryWriter { _, _ -> null })).run()
        assertFalse(small.score.events.any { it.kind == "compact" })
        // A long conversation (here: a big plan reply) is compacted at the first milestone boundary.
        val longPlan = plan + "\n" + "{\"say\":\"" + "x".repeat(230_000) + "\"}"
        val huge = conductor(ScriptedModel(longPlan), FakeCrew(), compactor = Compactor(CompactAt.K50, StoryWriter { _, _ -> null })).run()
        assertTrue(huge.score.events.any { it.kind == "compact" })
        val flash = conductor(ScriptedModel(longPlan), FakeCrew(), profile = Profile.FLASH, compactor = Compactor(CompactAt.K50)).run()
        assertFalse(flash.score.events.any { it.kind == "compact" })
    }

    // ---- checkpoint 3: recipes, resume, the baton, the card --------------------------------------------------------------

    private fun finished(): Score {
        val plan = WakeReply.read("""{"milestone":"Open the chat"}
            {"step":"open_app","args":{"app":"WhatsApp"}}
            {"step":"tap","target":"Anna","if":[{"if":"Anna's chat is already open","then":"skip"}]}
            {"step":"tap","target":"Archived"}
            {"milestone":"Write"}
            {"step":"type_text","target":"Message","args":{"text":"On my way, code 482913"}}""".trimIndent(), "g").plan!!
        var s = Score.start("r", Difficulty.FLASH, "Tell Anna I'm on my way", 1).copy(plan = plan)
        s = s.mark("s1", StepState.DONE).mark("s2", StepState.SKIPPED).mark("s3", StepState.SKIPPED).mark("s4", StepState.DONE)
        return s
    }

    @Test fun `a verified run leaves a recipe of what ran, and a miss on a recipe drops it`() {
        val r = Recipes.compile(finished(), RunEnd.DONE, 5, null)!!
        assertEquals(Recipes.key("tell anna i'm on my way!"), r.key)
        // The step a branch skipped stays (its condition decides next time); the plain skip goes.
        assertEquals(listOf("open_app", "tap", "type_text"), r.plan.steps.map { it.action })
        assertTrue(r.plan.steps.all { it.state == StepState.TODO })
        assertFalse(r.plan.steps.last().args["text"]!!.contains("482913"))
        assertNull(Recipes.compile(finished(), RunEnd.UNCONFIRMED, 5, null))
        assertNull(Recipes.compile(finished().event("wheel", "took the wheel", 2), RunEnd.DONE, 5, null))

        var book = Recipes.after(emptyMap(), finished(), RunEnd.DONE, 5, usedRecipe = false)
        assertEquals(1, book.size)
        assertEquals(book, Recipes.decode(Recipes.encode(book)))
        book = Recipes.after(book, finished(), RunEnd.DONE, 6, usedRecipe = true)
        assertEquals(2, book.values.single().runs)
        assertTrue(Recipes.after(book, finished(), RunEnd.FAILED, 7, usedRecipe = true).isEmpty())
        // A steered run says nothing about the words it started from.
        assertEquals(book, Recipes.after(book, finished().steer("Call Anna", 3), RunEnd.FAILED, 7, usedRecipe = true))
    }

    @Test fun `a recipe starts the crew with no planning call`() {
        val recipe = Recipes.compile(finished(), RunEnd.DONE, 5, null)!!.plan
        val model = ScriptedModel()
        val crew = FakeCrew()
        val out = Conductor("run-2", "Tell Anna I'm on my way", Profile.FLASH, crew, { model }, FakeOwner(), recipe = recipe,
            glanceRunner = { it() }, clock = { 1L }).run()
        assertEquals(RunEnd.DONE, out.end)
        assertEquals(0, model.requests.size)
        assertEquals(3, crew.runs.size)
        assertTrue(out.score.events.any { it.kind == "recipe" })
    }

    @Test fun `a resumed run keeps its Score and conversation and asks the mind where to go on`() {
        val saved = finished().mark("s4", StepState.TODO).copy(difficulty = Difficulty.MIND)
        val conversation = MindConversation(listOf(MindMessage.System("You are OneMind"), MindMessage.User("BRIEFING 1 · PLAN"),
            MindMessage.Assistant("{\"milestone\":\"Open the chat\"}", emptyList())))
        val model = ScriptedModel("""{"decision":"continue"}""")
        val crew = FakeCrew()
        val out = Conductor("run-3", saved.goal.text, Profile.MIND, crew, { model }, FakeOwner(), start = saved, resumed = conversation,
            glanceRunner = { it() }, clock = { 1L }).run()
        assertEquals(RunEnd.DONE, out.end)
        val first = lastText(model.requests.first())
        assertTrue(first.contains("trigger: resumed"))
        assertEquals("You are OneMind", model.requests.first().messages.getJSONObject(0).getString("content"))
        assertEquals(listOf("type_text"), crew.runs.map { it.action }) // only the step that hadn't run
    }

    @Test fun `the Instant baton is in the Score, and the opening notes reach the first briefing`() {
        val start = Score.start("run-4", Difficulty.FLASH, "turn on dark mode and lower the brightness", 1)
            .event("instant", "Instant turned on dark mode; brightness needs a slider", 1)
        val model = ScriptedModel("""{"step":"open_settings","args":{"page":"display"}}""")
        val out = Conductor("run-4", start.goal.text, Profile.FLASH, FakeCrew(), { model }, FakeOwner(), start = start,
            opening = listOf("What Cyclone remembers: the owner likes 30% brightness"), glanceRunner = { it() }, clock = { 1L }).run()
        assertEquals(RunEnd.DONE, out.end)
        val brief = lastText(model.requests.first())
        assertTrue(brief.contains("Instant turned on dark mode"))
        assertTrue(brief.contains("30% brightness"))
        assertEquals(Difficulty.FLASH, out.score.difficulty)
    }

    @Test fun `owner messages wake the mind, and a pause holds the run`() {
        var paused = true
        var slept = 0
        val said = ArrayDeque(listOf("use the work account"))
        val owner = object : OwnerLine {
            override fun messages(): List<String> = buildList { while (said.isNotEmpty()) add(said.removeFirst()) }
            override fun paused(): Boolean = paused
            override fun ask(question: String, choices: List<String>): String? = null
            override fun checkIn(reason: String): CheckIn = CheckIn.Stop
        }
        val model = ScriptedModel(twoSteps, """{"decision":"continue","why":"noted"}""")
        val out = Conductor("run-5", "Tell Anna I'm on my way", Profile.MIND, FakeCrew(), { model }, owner,
            glanceRunner = { it() }, clock = { 1L }, sleep = { slept++; if (slept == 3) paused = false }).run()
        assertEquals(RunEnd.DONE, out.end)
        assertEquals(3, slept)
        assertTrue(out.score.events.any { it.line == "Paused by the owner" })
        assertTrue(lastText(model.requests[1]).contains("use the work account"))
    }

    @Test fun `the card shows milestones, the step, wakes and cost, and the PC gets counts and lines only`() {
        val s = finished().mark("s4", StepState.TODO).event("wake", "Consult: two Annas", 3)
        val ledger = Ledger().apply { add(LedgerEntry(1, "flash", "m", 100, 0, 10, 0.0042)) }
        assertEquals(listOf("✓ Open the chat", "Write"), ScoreCard.milestones(s))
        assertEquals(1, ScoreCard.index(s))
        assertEquals("Typing into \"Message\"", ScoreCard.stepLine(s))
        assertEquals("Thinking: two Annas · 1 wake · <$0.01", ScoreCard.line(s, ledger))
        val summary = ScoreCard.summary(s, ledger)
        assertEquals(setOf("difficulty", "goalVersion", "planVersion", "milestones", "step", "wakes", "wakeLines", "calls", "costUsd"),
            summary.keys().asSequence().toSet())
        assertEquals("done", summary.getJSONArray("milestones").getJSONObject(0).getString("state"))
        assertEquals("Couldn't confirm: please check. Sent.", ScoreCard.report(s, RunEnd.UNCONFIRMED, "Sent."))
    }
}
