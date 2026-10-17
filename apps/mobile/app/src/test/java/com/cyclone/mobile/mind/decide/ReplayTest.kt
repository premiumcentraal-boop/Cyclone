package com.cyclone.mobile.mind.decide

import com.cyclone.mobile.mind.lab.GoldenSet
import com.cyclone.mobile.mind.modes.EngineDecider
import com.cyclone.mobile.mind.modes.LocalFacts
import com.cyclone.mobile.mind.modes.Mode
import com.cyclone.mobile.mind.modes.ModeRouter
import com.cyclone.mobile.mind.modes.PhoneDecider
import com.cyclone.mobile.mind.modes.Route
import com.cyclone.mobile.mind.modes.Speed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 65: verified repeats replay with no model call; anything doubtful forgets them. */
class ReplayTest {
    private val camera = Guess("instant", "camera", null, 0.9)
    private val volume = Guess("instant", "volume_up", null, 0.9)
    private val tap = Guess("instant", "tap", "Follow", 0.95)
    private val insta = Guess("instant", "open_app", "Instagram", 0.9)

    private fun lesson(request: String, decision: Guess, outcome: Outcome = Outcome.VERIFIED, at: Long = 0) =
        Lesson(at, request, emptyList(), Decider.DECISIONS, decision, 300, null, outcome, "jev")

    private val words = "get the picture thing up"

    @Test fun `two verified identical runs replay, one doesn't`() {
        assertNull(Replay.build(listOf(lesson(words, camera)))[Replay.keyOf(words)])
        val replay = Replay.build(listOf(lesson(words, camera), lesson("Get the  picture-thing UP!", camera)))
        assertEquals("camera", replay[Replay.keyOf(words)]?.intent)
    }

    @Test fun `a failure, a hand-up or a cancel forgets it`() {
        for (bad in listOf(Outcome.FAILED, Outcome.PROMOTED, Outcome.CANCELLED, Outcome.HANDED)) {
            val replay = Replay.build(listOf(lesson(words, camera), lesson(words, camera), lesson(words, camera, bad)))
            assertNull("$bad", replay[Replay.keyOf(words)])
        }
        // Earning it back takes a new streak.
        val back = Replay.build(listOf(lesson(words, camera), lesson(words, camera), lesson(words, camera, Outcome.FAILED),
            lesson(words, camera)))
        assertNull(back[Replay.keyOf(words)])
    }

    @Test fun `a different verified answer starts a new streak`() {
        val replay = Replay.build(listOf(lesson(words, camera), lesson(words, camera), lesson(words, volume)))
        assertNull(replay[Replay.keyOf(words)])
        val again = Replay.build(listOf(lesson(words, camera), lesson(words, camera), lesson(words, volume), lesson(words, volume)))
        assertEquals("volume_up", again[Replay.keyOf(words)]?.intent)
    }

    @Test fun `only easy-to-undo actions replay`() {
        assertNull(Replay.build(listOf(lesson("follow her", tap), lesson("follow her", tap)))[Replay.keyOf("follow her")])
        assertNull(Replay.build(listOf(lesson("x", Guess("flash", "none", null, 0.9)), lesson("x", Guess("flash", "none", null, 0.9))))["x"])
    }

    // ---- the router step -------------------------------------------------------------------------------------------------

    private val world = GoldenSet.WORLD
    private fun phone(replay: Map<String, Guess>, mayAct: Boolean = true, audit: Boolean = false) =
        PhoneDecider(PhoneModel.train(emptyList(), 0.85), emptySet(), mayAct, audit, replay)

    @Test fun `a replay is decided on the phone with no model asked`() {
        var asked = 0
        val engine = EngineDecider { _, _, _ -> asked++; Route(Mode.FLASH, "engine") }
        val replay = Replay.build(listOf(lesson(words, camera), lesson(words, camera)))
        val route = ModeRouter.route(words, world, LocalFacts(), Speed.AUTO, null, 0.85, phone = phone(replay), engine = engine)
        assertEquals(Mode.INSTANT, route.mode)
        assertEquals(Decider.PHONE, route.by)
        assertEquals("you asked this before and it worked", route.why)
        assertEquals(0, asked)
        // An audit asks the models anyway, and "learn only" never acts.
        ModeRouter.route(words, world, LocalFacts(), Speed.AUTO, null, 0.85, phone = phone(replay, audit = true), engine = engine)
        ModeRouter.route(words, world, LocalFacts(), Speed.AUTO, null, 0.85, phone = phone(replay, mayAct = false), engine = engine)
        assertEquals(2, asked)
    }

    @Test fun `a replayed app must still be on this phone, and the rules still come first`() {
        val app = "time for some insta"
        val replay = Replay.build(listOf(lesson(app, insta), lesson(app, insta), lesson("pay the electricity bill", camera),
            lesson("pay the electricity bill", camera)))
        val gone = world.copy(apps = world.apps.filter { it.first != "Instagram" })
        val engine = EngineDecider { _, _, _ -> Route(Mode.FLASH, "engine") }
        // The app is gone: no replay (the phone says it doesn't see it, or the engine decides).
        assertTrue(ModeRouter.route(app, gone, LocalFacts(), Speed.AUTO, null, 0.85, phone = phone(replay), engine = engine).by != Decider.PHONE)
        assertTrue(world.apps.any { it.first == "Instagram" })
        assertEquals(Mode.INSTANT, ModeRouter.route(app, world, LocalFacts(), Speed.AUTO, null, 0.85, phone = phone(replay), engine = engine).mode)
        assertEquals(Mode.MIND, ModeRouter.route("pay the electricity bill", world, LocalFacts(), Speed.AUTO, null, 0.85,
            phone = phone(replay), engine = engine).mode)
    }
}
