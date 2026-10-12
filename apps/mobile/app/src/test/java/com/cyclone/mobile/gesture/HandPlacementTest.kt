package com.cyclone.mobile.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Plan 52 (final): taps land like a thumb's, and the pause before an action depends on what comes next. */
class HandPlacementTest {
    private val screen = GestureBounds(0f, 0f, 1080f, 2400f)

    @Test fun `natural taps cluster near the centre, always inside the safe area`() {
        val button = GestureBounds(300f, 1000f, 780f, 1132f)
        val safe = HandPlacement.safeArea(button, screen)!!
        val points = (0 until 2_000).map { HandPlacement.tapPoint(button, screen, Handedness.RIGHT, SeededGestureRng(it.toLong())) }
        points.forEach { assertTrue(safe.contains(it)) }
        val meanX = points.map { it.x }.average()
        val meanY = points.map { it.y }.average()
        // Slightly low and toward the right thumb, never far from the centre.
        assertTrue(meanX - button.center.x in 0.5..6.0)
        assertTrue(meanY - button.center.y in 0.5..7.0)
        // Spread is capped: a wide control is not pressed at its far ends.
        assertTrue(points.all { abs(it.x - button.center.x) < 70f })
        assertTrue(points.map { it.x }.distinct().size > 1_000)
    }

    @Test fun `a long row is pressed near its middle, not anywhere along it`() {
        val row = GestureBounds(0f, 800f, 1080f, 950f)
        val points = (0 until 1_000).map { HandPlacement.tapPoint(row, screen, Handedness.RIGHT, SeededGestureRng(it.toLong())) }
        assertTrue(points.all { abs(it.x - row.center.x) < 60f })
    }

    @Test fun `precise taps hit the exact centre, tiny targets stay inside`() {
        val button = GestureBounds(300f, 1000f, 780f, 1132f)
        assertEquals(button.center, HandPlacement.tapPoint(button, screen, Handedness.RIGHT, SeededGestureRng(1), natural = false))
        val tiny = GestureBounds(500f, 500f, 504f, 503f)
        repeat(100) { assertTrue(tiny.contains(HandPlacement.tapPoint(tiny, screen, Handedness.LEFT, SeededGestureRng(it.toLong())))) }
    }

    @Test fun `press length varies like a person's and stays bounded`() {
        val presses = (0 until 2_000).map { HandPlacement.pressMs(GestureBounds(0f, 0f, 120f, 120f), SeededGestureRng(it.toLong())) }
        assertTrue(presses.all { it in 52L..170L })
        assertTrue(presses.average() in 75.0..110.0)
        assertTrue(presses.distinct().size > 40)
        assertEquals(80L, HandPlacement.pressMs(null, SeededGestureRng(1), natural = false))
    }

    @Test fun `far and small targets take longer to reach (Fitts)`() {
        assertTrue(HandPlacement.reachMs(900f, 40f) > HandPlacement.reachMs(200f, 40f))
        assertTrue(HandPlacement.reachMs(400f, 30f) > HandPlacement.reachMs(400f, 200f))
    }

    @Test fun `acting again on the same page is quicker than reading a new one`() {
        repeat(200) { seed ->
            val fresh = Pacing.pauseMs(HandsStyle.NATURAL, PaceStep(800, 0L, newPage = true), SeededGestureRng(seed.toLong()))
            val again = Pacing.pauseMs(HandsStyle.NATURAL, PaceStep(800, 0L, newPage = false), SeededGestureRng(seed.toLong()))
            assertTrue(again < fresh)
            val type = Pacing.pauseMs(HandsStyle.NATURAL, PaceStep(800, 0L, newPage = false, kind = PaceKind.TYPE), SeededGestureRng(seed.toLong()))
            assertTrue(type <= again)
            val confirm = Pacing.pauseMs(HandsStyle.NATURAL, PaceStep(800, 0L, newPage = false, kind = PaceKind.CONFIRM), SeededGestureRng(seed.toLong()))
            assertTrue(confirm > again)
            assertTrue(fresh <= Pacing.NATURAL_MAX_MS && confirm <= Pacing.NATURAL_MAX_MS)
        }
    }

    @Test fun `a far target adds reach time, precise hands and a slow model add nothing`() {
        repeat(100) { seed ->
            val near = Pacing.pauseMs(HandsStyle.RELAXED, PaceStep(0, 0L, newPage = false, reachPx = 50f, targetSizePx = 120f), SeededGestureRng(seed.toLong()))
            val far = Pacing.pauseMs(HandsStyle.RELAXED, PaceStep(0, 0L, newPage = false, reachPx = 1_600f, targetSizePx = 40f), SeededGestureRng(seed.toLong()))
            assertTrue(far > near)
            assertEquals(0L, Pacing.pauseMs(HandsStyle.PRECISE, PaceStep(800, 0L), SeededGestureRng(seed.toLong())))
            assertEquals(0L, Pacing.pauseMs(HandsStyle.NATURAL, PaceStep(800, 10_000L), SeededGestureRng(seed.toLong())))
        }
    }

    @Test fun `hand memory knows how far the thumb has to travel, and forgets`() {
        HandMemory.forget()
        assertEquals(null, HandMemory.reachTo(GesturePoint(0f, 0f), nowMs = 1_000L))
        HandMemory.touched(GesturePoint(0f, 0f), nowMs = 1_000L)
        assertEquals(500f, HandMemory.reachTo(GesturePoint(300f, 400f), nowMs = 2_000L)!!, 0.01f)
        assertEquals(null, HandMemory.reachTo(GesturePoint(300f, 400f), nowMs = 200_000L))
        HandMemory.forget()
    }
}
