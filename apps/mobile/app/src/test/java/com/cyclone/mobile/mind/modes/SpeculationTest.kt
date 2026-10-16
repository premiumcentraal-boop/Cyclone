package com.cyclone.mobile.mind.modes

import com.cyclone.mobile.mind.decide.EngineRecord
import com.cyclone.mobile.mind.lab.GoldenSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Plan 65 R1: a decision made while the owner spoke is used only for the same words, screen and bar. */
class SpeculationTest {
    private val world = GoldenSet.WORLD
    private val camera = Route(Mode.INSTANT, "engine", InstantCommand(InstantIntent.CAMERA, "x"))
    private fun record(engine: String = "consensus") = EngineRecord(1, engine, emptyList(), "instant", true)

    @Test fun `the same words on the same screen reuse the early decision`() {
        val s = Speculation()
        val races = AtomicInteger()
        assertTrue(s.prime("pull up the picture thing", world, 0.85) { races.incrementAndGet(); Thread.sleep(80); camera })
        assertFalse("one race per words", s.prime("Pull up the picture thing!", world, 0.85) { races.incrementAndGet(); camera })
        val route = s.take("pull up the picture thing", world, 0.85, 1_000)
        assertEquals(Mode.INSTANT, route?.mode)
        assertEquals(1, races.get())
        assertNull("used once", s.take("pull up the picture thing", world, 0.85, 50))
    }

    @Test fun `other words, another screen or another bar decide afresh`() {
        val s = Speculation()
        s.prime("pull up the picture", world, 0.85) { camera }
        assertNull(s.take("pull up the picture thing", world, 0.85, 200))
        s.prime("pull up the picture thing", world, 0.85) { camera }
        assertNull(s.take("pull up the picture thing", world.copy(labels = world.labels + "New"), 0.85, 200))
        s.prime("pull up the picture thing", world, 0.85) { camera }
        assertNull(s.take("pull up the picture thing", world, 0.9, 200))
    }

    @Test fun `an old or failed early decision is no decision`() {
        var now = 0L
        val s = Speculation(clock = { now })
        s.prime("pull up the picture thing", world, 0.85) { camera }
        now = Speculation.TTL_MS + 1
        assertNull(s.take("pull up the picture thing", world, 0.85, 200))
        val f = Speculation()
        f.prime("pull up the picture thing", world, 0.85) { throw IllegalStateException("down") }
        assertNull(f.take("pull up the picture thing", world, 0.85, 200))
    }

    @Test fun `the engine record is saved only when the early decision is used`() {
        val s = Speculation()
        s.prime("pull up the picture thing", world, 0.85) { t -> t.recorded(record()); camera }
        s.prime("turn it up a bit", world, 0.85) { t -> t.recorded(record("sprint")); camera }
        val saved = CopyOnWriteArrayList<EngineRecord>()
        assertNotNull(s.take("pull up the picture thing", world, 0.85, 1_000) { saved += it })
        Thread.sleep(50)
        assertEquals(listOf("consensus"), saved.map { it.engine })
    }

    @Test fun `the router's engine takes the early decision and says so, else races`() {
        val s = Speculation()
        var raced = 0
        val engine = SpeculativeEngine({ _, _, _ -> raced++; Route(Mode.FLASH, "raced") }, s, 1_000)
        s.prime("pull up the picture thing", world, 0.85) { camera }
        val early = engine.decide("pull up the picture thing", world, 0.85)!!
        assertEquals(Mode.INSTANT, early.mode)
        assertTrue(early.why.startsWith("decided while you spoke"))
        assertEquals(0, raced)
        assertEquals(Mode.FLASH, engine.decide("something else entirely", world, 0.85)!!.mode)
        assertEquals(1, raced)
    }

    @Test fun `early decisions never skip the rules`() {
        // The engine is only reached after the grammar and the rules: a primed answer for "pay the bill" is never asked for.
        val s = Speculation()
        s.prime("pay the electricity bill", world, 0.85) { camera }
        val engine = SpeculativeEngine({ _, _, _ -> null }, s, 1_000)
        assertEquals(Mode.MIND, ModeRouter.route("pay the electricity bill", world, LocalFacts(), Speed.AUTO, null, 0.85, engine = engine).mode)
    }
}
