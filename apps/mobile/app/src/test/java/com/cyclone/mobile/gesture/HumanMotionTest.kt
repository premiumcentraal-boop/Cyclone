package com.cyclone.mobile.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 52 run 1: speed curves, shapes and finger roll. */
class HumanMotionTest {
    private val screen = GestureBounds(0f, 0f, 1080f, 2400f)

    private fun stroke(
        seed: Long,
        ending: StrokeEnding = StrokeEnding.FLICK,
        profile: HumanizeProfile = HumanizeProfile.NORMAL,
        start: GesturePoint = GesturePoint(700f, 1800f),
        end: GesturePoint = GesturePoint(690f, 700f),
        durationMs: Long = 320L,
        hand: Handedness = Handedness.RIGHT,
    ) = HumanMotion.planStroke(start, end, screen, profile, ending, durationMs, hand, SeededGestureRng(seed))

    @Test fun progressCurvesStartAtZeroEndAtOneAndNeverGoBack() {
        for (ending in StrokeEnding.entries) {
            assertEquals(0.0, HumanMotion.progress(ending, 0.0), 1e-9)
            assertEquals(1.0, HumanMotion.progress(ending, 1.0), 1e-9)
            var last = 0.0
            for (i in 1..200) {
                val p = HumanMotion.progress(ending, i / 200.0)
                assertTrue("$ending goes backwards at $i", p >= last - 1e-12)
                last = p
            }
        }
    }

    @Test fun aGlideSlowsToAStopAndAFlickLiftsWhileFast() {
        val glide = stroke(1L, StrokeEnding.GLIDE)
        val flick = stroke(1L, StrokeEnding.FLICK)
        val glideLast = HumanMotion.pieceSpeed(glide, glide.segments - 1)
        val glideMax = (0 until glide.segments).maxOf { HumanMotion.pieceSpeed(glide, it) }
        assertTrue("a glide ends slow", glideLast < glideMax * 0.35)
        val flickLast = HumanMotion.pieceSpeed(flick, flick.segments - 1)
        val flickMax = (0 until flick.segments).maxOf { HumanMotion.pieceSpeed(flick, it) }
        assertTrue("a flick lifts near its top speed", flickLast > flickMax * 0.6)
        assertTrue("a flick peaks late", HumanMotion.peakSpeedPosition(flick) >= 0.6)
        val mid = HumanMotion.peakSpeedPosition(glide)
        assertTrue("a glide peaks mid-stroke ($mid)", mid in 0.3..0.7)
    }

    @Test fun speedIsNotConstant() {
        val plan = stroke(7L, StrokeEnding.GLIDE)
        val speeds = (0 until plan.segments).map { HumanMotion.pieceSpeed(plan, it) }
        assertTrue(speeds.max() > speeds.min() * 3)
    }

    @Test fun aHoldRestsBeforeLifting() {
        val plan = stroke(3L, StrokeEnding.HOLD, durationMs = 400L)
        val rest = plan.points.last().tMs - plan.points[plan.points.size - 2].tMs
        assertTrue("rests 40-120 ms, got $rest", rest in 40L..120L)
        assertTrue(plan.durationMs > 400L)
    }

    @Test fun everyPlanStartsAndEndsWhereAskedStaysOnScreenAndIsTimedInOrder() {
        for (seed in 0L until 3_000L) {
            val rng = SeededGestureRng(seed * 31 + 7)
            val start = GesturePoint((rng.nextUnit() * 1080).toFloat(), (rng.nextUnit() * 2400).toFloat())
            val end = GesturePoint((rng.nextUnit() * 1080).toFloat(), (rng.nextUnit() * 2400).toFloat())
            val ending = StrokeEnding.entries[(seed % 3).toInt()]
            val profile = if (seed % 2 == 0L) HumanizeProfile.NORMAL else HumanizeProfile.LIGHT
            val hand = if (seed % 5 == 0L) Handedness.LEFT else Handedness.RIGHT
            val plan = HumanMotion.planStroke(start, end, screen, profile, ending, 70L + seed % 900, hand, rng)
            val safe = screen.clamp(start, 1f)
            assertEquals(safe.x, plan.start.x, 0.01f)
            assertEquals(safe.y, plan.start.y, 0.01f)
            val stop = if (ending == StrokeEnding.HOLD && plan.shape != StrokeShape.STRAIGHT) plan.points[plan.points.size - 2] else plan.points.last()
            val safeEnd = screen.clamp(end, 1f)
            assertTrue("seed $seed ends at the target", stop.point.distanceTo(safeEnd) < 1.5f)
            plan.points.forEach { assertTrue("seed $seed leaves the screen at $it", screen.contains(it.point)) }
            for (i in 1 until plan.points.size) {
                val piece = plan.points[i].tMs - plan.points[i - 1].tMs
                assertTrue("seed $seed piece $i is $piece ms", piece >= 1L)
            }
            assertTrue(plan.segments <= HumanMotion.MAX_PIECES + 1)
        }
    }

    @Test fun shapesVaryAcrossSwipes() {
        val shapes = (0L until 400L).map { stroke(it, durationMs = 300L).shape }.toSet()
        assertTrue(StrokeShape.THUMB_ARC in shapes)
        assertTrue(StrokeShape.BOW in shapes)
        assertTrue(StrokeShape.S_CURVE in shapes)
        val holds = (0L until 400L).map { stroke(it, StrokeEnding.HOLD).shape }.toSet()
        assertTrue(StrokeShape.OVERSHOOT in holds)
    }

    @Test fun aRightThumbArcBulgesAwayFromTheHand() {
        // An upward swipe on the right side: the right thumb pivots at the bottom right, so the path bows left.
        val arcs = (0L until 300L).map { stroke(it, StrokeEnding.GLIDE, end = GesturePoint(700f, 700f)) }
            .filter { it.shape == StrokeShape.THUMB_ARC }
        assertTrue(arcs.isNotEmpty())
        arcs.forEach { plan ->
            val middle = plan.points[plan.points.size / 2]
            assertTrue("bows left: ${middle.x}", middle.x < 700f)
        }
        val left = (0L until 300L).map { stroke(it, StrokeEnding.GLIDE, end = GesturePoint(700f, 700f), hand = Handedness.LEFT) }
            .filter { it.shape == StrokeShape.THUMB_ARC }
        left.forEach { plan -> assertTrue(plan.points[plan.points.size / 2].x > 700f) }
    }

    @Test fun anOvershootGoesPastTheEndAndComesBack() {
        val plan = (0L until 400L).map { stroke(it, StrokeEnding.HOLD, durationMs = 500L) }.first { it.shape == StrokeShape.OVERSHOOT }
        // Upward swipe: past the end means above it.
        assertTrue(plan.points.any { it.y < 700f - 5f })
    }

    @Test fun theSameSeedGivesTheSameStroke() {
        assertEquals(stroke(42L), stroke(42L))
    }

    @Test fun offIsOneStraightEvenStroke() {
        val plan = stroke(1L, profile = HumanizeProfile.OFF)
        assertEquals(1, plan.segments)
        assertEquals(StrokeShape.STRAIGHT, plan.shape)
    }

    @Test fun aFingerRollsAFewPixelsInsideTheTarget() {
        val target = GestureBounds(100f, 100f, 300f, 200f)
        repeat(2_000) { seed ->
            val down = GesturePoint(200f, 150f)
            val lift = HumanMotion.liftPoint(down, target, screen, HumanizeProfile.NORMAL, 2.5f, SeededGestureRng(seed.toLong()))
            assertTrue(lift.distanceTo(down) <= 2.5f + 1e-3f)
            assertTrue(target.contains(lift))
        }
        val edge = GesturePoint(101f, 101f)
        val tiny = HumanMotion.liftPoint(edge, target, screen, HumanizeProfile.NORMAL, 2.5f, SeededGestureRng(9L))
        assertTrue("never rolls out of the target", target.insetCapped(2f).contains(tiny) || tiny == edge)
        assertEquals(edge, HumanMotion.liftPoint(edge, target, screen, HumanizeProfile.OFF, 2.5f, SeededGestureRng(9L)))
    }
}
