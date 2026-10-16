package com.cyclone.mobile.mind.modes

import com.cyclone.mobile.decisions.DAnswer
import com.cyclone.mobile.decisions.DReply
import com.cyclone.mobile.mind.decide.Decider
import com.cyclone.mobile.mind.decide.EngineLog
import com.cyclone.mobile.mind.decide.EngineRecord
import com.cyclone.mobile.mind.lab.GoldenSet
import com.cyclone.mobile.mind.lab.GoldenSetOracle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Plan 63 (alpha.136): the two decision engines, proven without a phone. The race's timing with fake models, the
 * combining rules one by one, and seeded property tests over the golden set: whatever the two models answer, an engine
 * never acts on something one model alone wasn't allowed to, and never ends lower than the more careful model.
 */
class EnsembleTest {
    private val world = GoldenSet.WORLD

    private fun reply(difficulty: Double, capability: String = "none", target: String = "none", sure: Double = 0.95,
                      vararg flags: Pair<String, Double>): DReply = DReply(buildMap {
        put("difficulty", DAnswer.Score(difficulty, 0.9, emptyMap()))
        put("capability", DAnswer.Choice(capability, sure, emptyMap()))
        put("target", DAnswer.Choice(target, sure, emptyMap()))
        put("is_for_cyclone", DAnswer.Noul(0.98))
        flags.forEach { (k, v) -> put(k, DAnswer.Noul(v)) }
    })

    private fun op(lane: String, reply: DReply, text: String = "x", ms: Long = 200, variant: Int = 0) =
        Ensemble.opinion(Arrival("$lane#$variant", TypedAnswer(reply, ms), ms, lane, variant), text, world, 0.85)!!

    private val camera = reply(0.05, "camera")
    private val volume = reply(0.05, "volume_up")
    private val insta = reply(0.1, "open_app", "Instagram")
    private val tapFollow = reply(0.1, "tap", "Follow")
    private val flash = reply(1.1)
    private val mind = reply(2.9, flags = arrayOf("writes_text" to 0.9))

    // ---- the combining rules -------------------------------------------------------------------------------------------

    @Test fun `agreement acts and disagreement goes up`() {
        assertEquals(Mode.INSTANT, Ensemble.combine(listOf(op("jev", camera), op("openai", camera))).mode)
        assertEquals(Mode.FLASH, Ensemble.combine(listOf(op("jev", camera), op("openai", volume))).mode)
        assertEquals(Mode.FLASH, Ensemble.combine(listOf(op("jev", camera), op("openai", flash))).mode)
        assertEquals(Mode.MIND, Ensemble.combine(listOf(op("jev", flash), op("openai", mind))).mode)
        assertEquals(Mode.MIND, Ensemble.combine(listOf(op("jev", camera), op("openai", mind))).mode)
    }

    @Test fun `one answer is enough only for an easy-to-undo action`() {
        assertEquals(Mode.INSTANT, Ensemble.combine(listOf(op("jev", camera))).mode)
        assertEquals(Mode.INSTANT, Ensemble.combine(listOf(op("openai", insta))).mode)
        // A tap isn't easy to undo (it could be anything): one model's word isn't enough.
        assertEquals(Mode.FLASH, Ensemble.combine(listOf(op("jev", tapFollow))).mode)
        assertEquals(Mode.INSTANT, Ensemble.combine(listOf(op("jev", tapFollow), op("openai", tapFollow))).mode)
        assertEquals(Mode.FLASH, Ensemble.combine(emptyList()).mode)
        assertEquals(Mode.MIND, Ensemble.combine(listOf(op("jev", mind))).mode)
    }

    @Test fun `ignoring a request needs both`() {
        val chatter = DReply(mapOf("is_for_cyclone" to DAnswer.Noul(0.05), "difficulty" to DAnswer.Score(0.1, 0.9, emptyMap())))
        assertEquals(Mode.IGNORE, Ensemble.combine(listOf(op("jev", chatter), op("openai", chatter))).mode)
        assertEquals(Mode.FLASH, Ensemble.combine(listOf(op("jev", chatter))).mode)
        assertEquals(Mode.FLASH, Ensemble.combine(listOf(op("jev", chatter), op("openai", flash))).mode)
        assertEquals(Mode.FLASH, Ensemble.combine(listOf(op("jev", chatter), op("openai", camera))).mode)
        assertEquals(Mode.FLASH, Ensemble.combine(listOf(op("jev", chatter), op("openai", tapFollow))).mode)
    }

    @Test fun `sprint acts alone only when sure of an easy-to-undo action with no risk`() {
        assertTrue(Ensemble.sprintMayAct(op("jev", camera)))
        assertFalse(Ensemble.sprintMayAct(op("jev", tapFollow)))
        assertFalse(Ensemble.sprintMayAct(op("jev", reply(0.05, "camera", sure = 0.88))))
        assertFalse(Ensemble.sprintMayAct(op("jev", reply(0.05, "open_app", "Instagram", flags = arrayOf("sends_or_posts" to 0.4)))))
        assertFalse(Ensemble.sprintMayAct(op("jev", flash)))
    }

    @Test fun `an unreadable answer is no opinion`() {
        assertNull(Ensemble.opinion(Arrival("jev", null, 100), "x", world, 0.85))
        assertNull(Ensemble.opinion(Arrival("jev", TypedAnswer(DReply(emptyMap()), 100), 100), "x", world, 0.85))
    }

    // ---- the race ------------------------------------------------------------------------------------------------------

    private fun lane(name: String, delayMs: Long, reply: DReply?, fail: Boolean = false, variant: Int = 0) = Lane("$name#$variant", TypedBox { _, _ ->
        Thread.sleep(delayMs)
        if (fail) throw IllegalStateException("down")
        reply?.let { TypedAnswer(it, delayMs) }
    }, name, variant)

    private fun engine(kind: DecisionEngine, vararg lanes: Lane, deadline: Long = 800, records: MutableList<EngineRecord> = CopyOnWriteArrayList(),
                       done: CountDownLatch? = null) =
        RaceEngine(kind, DecisionRace(lanes.toList(), deadline, graceMs = 600), record = { records += it; done?.countDown() })

    @Test fun `consensus waits for both, sprint acts on the first two sure easy-to-undo answers`() {
        val c = engine(DecisionEngine.CONSENSUS, lane("jev", 50, camera), lane("openai", 400, camera))
        val started = System.nanoTime()
        val route = c.decide("open camera", world, 0.85)
        val took = (System.nanoTime() - started) / 1_000_000
        assertEquals(Mode.INSTANT, route.mode)
        assertEquals(Decider.DECISIONS, route.by)
        assertTrue("consensus waited for the second model ($took ms)", took >= 350)

        val records = CopyOnWriteArrayList<EngineRecord>()
        val settled = CountDownLatch(1)
        val s = engine(DecisionEngine.SPRINT, lane("jev", 50, camera), lane("jev", 80, camera, variant = 1),
            lane("openai", 400, camera), lane("openai", 450, camera, variant = 1), records = records, done = settled)
        val t0 = System.nanoTime()
        assertEquals(Mode.INSTANT, s.decide("open camera", world, 0.85).mode)
        assertTrue("sprint didn't wait", (System.nanoTime() - t0) / 1_000_000 < 300)
        // The late answers still reach the numbers, each with its phrasing.
        assertTrue(settled.await(2, TimeUnit.SECONDS))
        assertEquals("jev", records.single().early)
        assertEquals(true, records.single().agreed)
        assertEquals(4, records.single().lanes.count { it.answered })
        assertEquals(setOf(0, 1), records.single().lanes.filter { it.lane == "openai" }.map { it.copy }.toSet())
    }

    @Test fun `sprint acts on the first answer when each model answers once, needs two when each answers twice, never against dissent`() {
        // The default race (one answer per model): the first sure easy-to-undo answer acts.
        val pair = engine(DecisionEngine.SPRINT, lane("jev", 30, camera), lane("openai", 350, camera))
        val p0 = System.nanoTime()
        assertEquals(Mode.INSTANT, pair.decide("open camera", world, 0.85).mode)
        assertTrue("sprint didn't wait for the second model", (System.nanoTime() - p0) / 1_000_000 < 250)
        // The four-answer race: one sure answer waits for a second.
        val one = engine(DecisionEngine.SPRINT, lane("jev", 30, camera), lane("jev", 350, camera, variant = 1),
            lane("openai", 380, camera), lane("openai", 400, camera, variant = 1))
        val t0 = System.nanoTime()
        assertEquals(Mode.INSTANT, one.decide("open camera", world, 0.85).mode)
        assertTrue("sprint waited for the second answer", (System.nanoTime() - t0) / 1_000_000 >= 300)
        // A dissent that is already in stops the early act.
        assertNull(Ensemble.sprintEarly(listOf(op("jev", camera), op("openai", volume)), 1))
        // A fast dissent stops the early act, and the model that answered two ways can't act.
        val split = engine(DecisionEngine.SPRINT, lane("jev", 20, camera), lane("jev", 40, volume, variant = 1),
            lane("openai", 60, camera), lane("openai", 80, camera, variant = 1))
        assertEquals(Mode.FLASH, split.decide("open camera", world, 0.85).mode)
    }

    @Test fun `once every model has answered, the rest get a short grace, not the whole deadline`() {
        val records = CopyOnWriteArrayList<EngineRecord>()
        val settled = CountDownLatch(1)
        val e = RaceEngine(DecisionEngine.CONSENSUS, DecisionRace(listOf(lane("jev", 30, camera), lane("jev", 40, camera, variant = 1),
            lane("openai", 60, camera), lane("openai", 2_000, camera, variant = 1)), 1_500, graceMs = 2_500),
            record = { records += it; settled.countDown() })
        val t0 = System.nanoTime()
        assertEquals(Mode.INSTANT, e.decide("open camera", world, 0.85).mode)
        val took = (System.nanoTime() - t0) / 1_000_000
        assertTrue("quorum grace, not the deadline ($took ms)", took in 300..900)
        // The straggler still reaches the numbers.
        assertTrue(settled.await(4, TimeUnit.SECONDS))
        assertEquals(4, records.single().lanes.count { it.answered })
    }

    @Test fun `one model's two phrasings merge into one opinion`() {
        assertEquals(Mode.INSTANT, Ensemble.merge(listOf(op("jev", camera), op("jev", camera, variant = 1)))!!.route.mode)
        // Two different actions from one model: it isn't sure, so no action.
        assertEquals(Mode.FLASH, Ensemble.merge(listOf(op("jev", camera), op("jev", volume, variant = 1)))!!.route.mode)
        // Different rungs: the more careful one.
        assertEquals(Mode.MIND, Ensemble.merge(listOf(op("jev", camera), op("jev", mind, variant = 1)))!!.route.mode)
        assertEquals(2, Ensemble.perModel(listOf(op("jev", camera), op("jev", camera, variant = 1), op("openai", camera))).size)
        // Four answers: an action only when all four say the same one.
        assertEquals(Mode.INSTANT, Ensemble.combine(Ensemble.perModel(listOf(op("jev", camera), op("jev", camera, variant = 1),
            op("openai", camera), op("openai", camera, variant = 1)))).mode)
        assertEquals(Mode.FLASH, Ensemble.combine(Ensemble.perModel(listOf(op("jev", camera), op("jev", camera, variant = 1),
            op("openai", camera), op("openai", volume, variant = 1)))).mode)
    }

    @Test fun `the second phrasing asks the same question in a different order`() {
        val words = "open instagram"
        val plain = Triage.questions(world, words, 0)
        val turned = Triage.questions(world, words, 1)
        assertEquals(plain.keys, turned.keys)
        for (name in listOf("capability", "target")) {
            val a = (plain.getValue(name) as com.cyclone.mobile.decisions.DQuestion.Choice).options.keys.toList()
            val b = (turned.getValue(name) as com.cyclone.mobile.decisions.DQuestion.Choice).options.keys.toList()
            assertEquals(a.toSet(), b.toSet())
            assertEquals("none", b.last())
            assertEquals(a.dropLast(1).reversed(), b.dropLast(1))
        }
        assertNotEquals(Triage.state(words, world, 0), Triage.state(words, world, 1))
    }

    @Test fun `sprint never acts alone on a tap and waits for the other model`() {
        val s = engine(DecisionEngine.SPRINT, lane("jev", 30, tapFollow), lane("openai", 200, flash))
        assertEquals(Mode.FLASH, s.decide("tap follow", world, 0.85).mode)
    }

    @Test fun `a slow or broken model doesn't stall the decision`() {
        val slow = engine(DecisionEngine.CONSENSUS, lane("jev", 40, camera), lane("openai", 3_000, camera), deadline = 500)
        val t0 = System.nanoTime()
        val route = slow.decide("open camera", world, 0.85)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 900)
        assertEquals("only one answered: an easy-to-undo action goes ahead", Mode.INSTANT, route.mode)

        val broken = engine(DecisionEngine.CONSENSUS, lane("jev", 10, null, fail = true), lane("openai", 30, tapFollow))
        assertEquals(Mode.FLASH, broken.decide("tap follow", world, 0.85).mode)

        val none = engine(DecisionEngine.CONSENSUS, lane("jev", 10, null), lane("openai", 10, null))
        assertEquals(Mode.FLASH, none.decide("open camera", world, 0.85).mode)
    }

    @Test fun `the router asks the engine after the grammar, the rules and the phone model`() {
        var asked = 0
        val e = EngineDecider { _, _, _ -> asked++; Route(Mode.INSTANT, "engine", InstantCommand(InstantIntent.CAMERA, "x")) }
        assertEquals(Mode.INSTANT, ModeRouter.route("pull up the thing i take pictures with", world, LocalFacts(), Speed.AUTO, null, 0.85, engine = e).mode)
        assertEquals(1, asked)
        // The rules send "pay" to the Mind before the engine is asked.
        assertEquals(Mode.MIND, ModeRouter.route("pay the electricity bill", world, LocalFacts(), Speed.AUTO, null, 0.85, engine = e).mode)
        assertEquals(1, asked)
        // A failing engine is no answer: one rung up.
        val failing = EngineDecider { _, _, _ -> throw IllegalStateException() }
        assertEquals(Mode.FLASH, ModeRouter.route("pull up the thing i take pictures with", world, LocalFacts(), Speed.AUTO, null, 0.85, engine = failing).mode)
    }

    // ---- properties over the golden set ---------------------------------------------------------------------------------

    private val golden by lazy {
        val file = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .flatMap { dir -> sequenceOf(File(dir, "src/main/assets/${GoldenSet.ASSET}"), File(dir, "apps/mobile/app/src/main/assets/${GoldenSet.ASSET}")) }
            .first { it.isFile }
        GoldenSet.parse(file.readText())
    }

    private val capabilities = ModeRouter.INTENTS.keys.toList()

    /** A random but well-formed Triage answer: what a confused or wrong model might say. */
    private fun noise(rnd: Random): DReply {
        val cap = capabilities[rnd.nextInt(capabilities.size)]
        val target = (world.labels + world.apps.map { it.first } + "none")[rnd.nextInt(world.labels.size + world.apps.size + 1)]
        return DReply(buildMap {
            put("difficulty", DAnswer.Score(rnd.nextDouble(0.0, 3.0), rnd.nextDouble(0.3, 1.0), emptyMap()))
            put("capability", DAnswer.Choice(cap, rnd.nextDouble(0.5, 1.0), emptyMap()))
            put("target", DAnswer.Choice(target, rnd.nextDouble(0.5, 1.0), emptyMap()))
            for (f in listOf("is_for_cyclone", "answer_only", "writes_text", "multi_app", "later") + Triage.RISKS) {
                put(f, DAnswer.Noul(if (rnd.nextDouble() < 0.7) rnd.nextDouble(0.0, 0.25) else rnd.nextDouble()))
            }
        })
    }

    @Test fun `over the golden set an engine never acts where one model alone wasn't allowed to`() {
        val rnd = Random(58_63)
        var instant = 0
        repeat(20) {
            for (r in golden) {
                // Half the time the two models agree (the same answer), half the time they don't.
                val first = noise(rnd)
                val a = op("jev", first, r.text)
                val b = op("openai", if (rnd.nextBoolean()) first else noise(rnd), r.text)
                val both = Ensemble.combine(listOf(a, b))
                // Instant needs both to say the same Instant action.
                if (both.mode == Mode.INSTANT) {
                    instant++
                    assertTrue(a.route.mode == Mode.INSTANT && b.route.mode == Mode.INSTANT)
                    assertEquals(Ensemble.actionOf(a.route), Ensemble.actionOf(b.route))
                }
                // Never lower than the more careful of the two (ignore aside).
                if (both.mode != Mode.IGNORE) {
                    val careful = maxOf(Ensemble.rank(a.route.mode), Ensemble.rank(b.route.mode))
                    assertTrue("${r.id}: ${both.mode} below $careful", Ensemble.rank(both.mode) >= careful)
                }
                // Ignore needs both.
                if (both.mode == Mode.IGNORE) assertTrue(a.route.mode == Mode.IGNORE && b.route.mode == Mode.IGNORE)
                // One answer: never Instant for something that isn't easy to undo.
                val one = Ensemble.combine(listOf(a))
                if (one.mode == Mode.INSTANT) assertTrue(Ensemble.undoable(one))
                if (one.mode == Mode.IGNORE) error("one model may never ignore a request")
                // Sprint's early act is always easy to undo and never risky.
                if (Ensemble.sprintMayAct(a)) assertTrue(Ensemble.undoable(a.route) && !a.reading.risky)
            }
        }
        assertTrue("the noise reached Instant sometimes ($instant)", instant > 0)
    }

    @Test fun `over the golden set four answers act only when all four agree`() {
        val rnd = Random(65_4)
        var instant = 0
        repeat(10) {
            for (r in golden) {
                val first = noise(rnd)
                val answers = listOf("jev" to 0, "jev" to 1, "openai" to 0, "openai" to 1).map { (m, v) ->
                    op(m, if (rnd.nextDouble() < 0.75) first else noise(rnd), r.text, variant = v)
                }
                val route = Ensemble.combine(Ensemble.perModel(answers))
                if (route.mode == Mode.INSTANT) {
                    instant++
                    assertTrue(answers.all { it.route.mode == Mode.INSTANT })
                    assertEquals(1, answers.map { Ensemble.actionOf(it.route) }.distinct().size)
                }
                if (route.mode == Mode.IGNORE) assertTrue(answers.all { it.route.mode == Mode.IGNORE })
                else assertTrue(Ensemble.rank(route.mode) >= answers.filter { it.route.mode != Mode.IGNORE }.maxOf { Ensemble.rank(it.route.mode) })
                // Sprint's early act: two sure answers of one easy-to-undo action, and nothing else said.
                Ensemble.sprintEarly(answers, 2)?.let { early ->
                    assertTrue(Ensemble.undoable(early.route) && !early.reading.risky)
                    assertTrue(answers.all { Ensemble.keyOf(it.route) == Ensemble.keyOf(early.route) })
                }
            }
        }
        assertTrue("the noise reached Instant sometimes ($instant)", instant > 0)
    }

    @Test fun `two perfect models reproduce the golden labels`() {
        // The same perfect answers GoldenSetTest builds, given to both lanes: the engine must keep every label.
        val scored = golden.filter { it.tag !in GoldenSet.ON_PHONE_TAGS && it.rung != "answer" }
        val score = GoldenSet.score(scored) { r ->
            val perfect = GoldenSetOracle.answers(r)
            Ensemble.combine(listOf(op("jev", perfect, r.text), op("openai", perfect, r.text))).mode.name.lowercase()
        }
        assertEquals(emptyList<String>(), score.under)
        assertEquals(scored.size, score.count(com.cyclone.mobile.mind.lab.GoldenVerdict.RIGHT))
        // And one perfect model with the other silent: never lower, sometimes more careful (taps need both).
        val alone = GoldenSet.score(scored) { r ->
            Ensemble.combine(listOf(op("jev", GoldenSetOracle.answers(r), r.text))).mode.name.lowercase()
        }
        assertEquals(emptyList<String>(), alone.under)
        assertNotEquals(0, alone.count(com.cyclone.mobile.mind.lab.GoldenVerdict.RIGHT))
    }

    @Test fun `the engine log counts agreement, early acts and each model's speed`() {
        val records = listOf(
            EngineRecord(1, "sprint", listOf(com.cyclone.mobile.mind.decide.LaneRecord("jev", 200, true, "instant", "camera:"),
                com.cyclone.mobile.mind.decide.LaneRecord("openai", 400, true, "instant", "camera:")), "instant", true, "jev"),
            EngineRecord(2, "sprint", listOf(com.cyclone.mobile.mind.decide.LaneRecord("jev", 300, true, "flash"),
                com.cyclone.mobile.mind.decide.LaneRecord("openai", 1500, false)), "flash", null),
        )
        val s = EngineLog.summary(records)
        val sprint = s.getJSONObject("engines").getJSONObject("sprint")
        assertEquals(2, sprint.getInt("decisions"))
        assertEquals(0.5, sprint.getDouble("bothAnswered"), 1e-9)
        assertEquals(1.0, sprint.getDouble("agreement"), 1e-9)
        assertEquals(1, sprint.getInt("earlyActs"))
        assertTrue(s.getJSONObject("lanes").getJSONObject("jev").isNull("selfAgreement"))
        val jev = s.getJSONObject("lanes").getJSONObject("jev")
        assertEquals(1.0, jev.getDouble("answerRate"), 1e-9)
        assertEquals(1.0, jev.getDouble("firstShare"), 1e-9)
        assertEquals(records, EngineLog.decode(EngineLog.encode(records)))
    }
}
