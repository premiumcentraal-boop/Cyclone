package com.cyclone.mobile.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Plan 52 run 6: drag, pinch, double tap and drawing are planned inside their targets, with human timing. */
class HandGesturesTest {
    private val screen = GestureBounds(0f, 0f, 1080f, 2400f)
    private val button = GestureBounds(400f, 1000f, 680f, 1140f)

    @Test fun `a double tap is two short taps 90 to 180 ms apart, both inside the target`() {
        val safe = HandPlacement.safeArea(button, screen)!!
        repeat(300) { seed ->
            val gesture = HandGestures.doubleTap(button, screen, HandsStyle.NATURAL, Handedness.RIGHT, SeededGestureRng(seed.toLong()))!!
            assertEquals(2, gesture.strokes.size)
            val (first, second) = gesture.strokes
            val gap = second.startMs - first.endMs
            assertTrue("gap $gap", gap in HandGestures.DOUBLE_TAP_GAP_MIN_MS..HandGestures.DOUBLE_TAP_GAP_MAX_MS)
            assertTrue(first.motion.durationMs in 45L..140L && second.motion.durationMs in 45L..140L)
            gesture.strokes.flatMap { it.motion.points }.forEach { assertTrue(safe.insetCapped(0f).contains(it.point) || button.contains(it.point)) }
            // The second tap lands within a few pixels of the first (Android's double-tap slop is far larger).
            assertTrue(first.motion.start.distanceTo(second.motion.start) <= 6f)
        }
    }

    @Test fun `precise hands double tap the centre the same way every time`() {
        val a = HandGestures.doubleTap(button, screen, HandsStyle.PRECISE, Handedness.RIGHT, SeededGestureRng(1))!!
        val b = HandGestures.doubleTap(button, screen, HandsStyle.PRECISE, Handedness.RIGHT, SeededGestureRng(99))!!
        assertEquals(a, b)
        assertEquals(button.center, a.strokes[0].motion.start)
    }

    @Test fun `a drag holds still long enough to pick up, then carries, slows and rests before letting go`() {
        val item = GestureBounds(100f, 600f, 980f, 760f)
        val slot = GestureBounds(100f, 1400f, 980f, 1560f)
        repeat(200) { seed ->
            val gesture = HandGestures.drag(item, slot.center, slot, screen, HandsStyle.NATURAL, Handedness.RIGHT, SeededGestureRng(seed.toLong()))!!
            val motion = gesture.strokes.single().motion
            val down = motion.points[0]
            val held = motion.points[1]
            assertTrue("pickup ${held.tMs}", held.tMs >= HandGestures.DRAG_PICKUP_MIN_MS)
            // Still far inside the touch slop while held, so the long press is not cancelled.
            assertTrue(down.point.distanceTo(held.point) < 2f)
            assertTrue(item.contains(down.point))
            assertTrue("drop inside the slot", slot.contains(motion.end))
            val last = motion.points.last()
            val beforeLast = motion.points[motion.points.size - 2]
            assertTrue("rests before release", last.tMs - beforeLast.tMs >= 100L)
            motion.points.forEach { assertTrue(screen.contains(it.point)) }
            // Slowing into the drop: the last moving piece is slower than the fastest one.
            val moving = (1 until motion.points.size - 2).map { HumanMotion.pieceSpeed(motion, it) }
            assertTrue(moving.last() < moving.max())
        }
    }

    @Test fun `a drag by direction ends on screen, and a hold can be asked for`() {
        val end = HandGestures.dragEnd(button, SwipeDirection.UP, SwipeAmount.HALF, screen)!!
        assertTrue(end.y < button.center.y - 400f)
        val gesture = HandGestures.drag(button, end, null, screen, HandsStyle.NATURAL, Handedness.RIGHT, SeededGestureRng(3), holdMs = 1_000L)!!
        assertEquals(1_000L, gesture.strokes.single().motion.points[1].tMs)
        assertNull("too short to be a drag", HandGestures.drag(button, button.center, null, screen, HandsStyle.NATURAL, Handedness.RIGHT, SeededGestureRng(3)))
    }

    @Test fun `a pinch keeps two fingers apart, inside the area, and zooms the way asked`() {
        val map = GestureBounds(0f, 300f, 1080f, 2000f)
        repeat(200) { seed ->
            for (scale in listOf(2.0, 0.5, 3.5, 0.3)) {
                val gesture = HandGestures.pinch(map, screen, scale, null, HandsStyle.NATURAL, Handedness.RIGHT, SeededGestureRng(seed.toLong()))!!
                assertTrue(gesture.simultaneous)
                assertEquals(2, gesture.strokes.size)
                val points = gesture.strokes[0].motion.points.size
                for (i in 0 until points) {
                    assertTrue(HandGestures.fingerGap(gesture, i) >= HandGestures.MIN_FINGER_GAP_PX * 0.95f)
                    gesture.strokes.forEach { assertTrue(map.contains(it.motion.points[i].point)) }
                }
                val start = HandGestures.fingerGap(gesture, 0)
                val end = HandGestures.fingerGap(gesture, points - 1)
                if (scale > 1) assertTrue("zoom in spreads", end > start * 1.3f) else assertTrue("zoom out pinches", end < start * 0.75f)
                val achieved = gesture.facts["achievedScale"] as Double
                assertTrue(if (scale > 1) achieved > 1.0 else achieved < 1.0)
            }
        }
    }

    @Test fun `the two fingers of a pinch move at different speeds and turn a little`() {
        val gesture = HandGestures.pinch(GestureBounds(0f, 300f, 1080f, 2000f), screen, 2.5, null, HandsStyle.NATURAL, Handedness.RIGHT, SeededGestureRng(11))!!
        val index = gesture.strokes[0].motion
        val thumb = gesture.strokes[1].motion
        val indexTravel = index.start.distanceTo(index.end)
        val thumbTravel = thumb.start.distanceTo(thumb.end)
        assertTrue("the thumb moves less", thumbTravel < indexTravel * 0.95f)
        assertTrue(abs(gesture.facts["rotationDeg"] as Double) >= 1.0)
    }

    @Test fun `pinch refuses scales out of range and areas too small`() {
        val area = GestureBounds(0f, 300f, 1080f, 2000f)
        listOf(1.05, 0.1, 9.0, Double.NaN).forEach {
            assertNull(HandGestures.pinch(area, screen, it, null, HandsStyle.NATURAL, Handedness.RIGHT, SeededGestureRng(1)))
        }
        assertNull(HandGestures.pinch(GestureBounds(500f, 1000f, 580f, 1060f), screen, 2.0, null, HandsStyle.NATURAL, Handedness.RIGHT, SeededGestureRng(1)))
    }

    @Test fun `drawing stays inside the canvas for every shape and lifts the pen between strokes`() {
        val canvas = GestureBounds(60f, 1200f, 1020f, 1700f)
        for (shape in DrawShape.entries) {
            repeat(60) { seed ->
                val gesture = HandGestures.draw(canvas, screen, shape, null, HandsStyle.NATURAL, SeededGestureRng(seed.toLong()))!!
                assertTrue(!gesture.simultaneous)
                gesture.strokes.flatMap { it.motion.points }.forEach { assertTrue("$shape left the canvas", canvas.contains(it.point)) }
                for (i in 1 until gesture.strokes.size) assertTrue(gesture.strokes[i].startMs - gesture.strokes[i - 1].endMs >= 100L)
                gesture.strokes.forEach { stroke ->
                    // A pen's speed curve: slow at both ends, faster in the middle.
                    val m = stroke.motion
                    if (m.points.size > 8) {
                        val speeds = (0 until m.segments).map { HumanMotion.pieceSpeed(m, it) }
                        assertTrue(speeds.first() < speeds.max() && speeds.last() < speeds.max())
                    }
                }
            }
        }
        assertEquals(2, HandGestures.draw(canvas, screen, DrawShape.SIGNATURE, null, HandsStyle.NATURAL, SeededGestureRng(1))!!.strokes.size)
    }

    @Test fun `caller strokes are limited to four of 64 normalised points`() {
        val canvas = GestureBounds(60f, 1200f, 1020f, 1700f)
        val ok = listOf(listOf(GesturePoint(0.1f, 0.1f), GesturePoint(0.9f, 0.9f)))
        assertNotNull(HandGestures.draw(canvas, screen, null, ok, HandsStyle.NATURAL, SeededGestureRng(1)))
        assertNull(HandGestures.draw(canvas, screen, null, List(5) { ok[0] }, HandsStyle.NATURAL, SeededGestureRng(1)))
        assertNull(HandGestures.draw(canvas, screen, null, listOf(List(65) { GesturePoint(0.5f, it / 65f) }), HandsStyle.NATURAL, SeededGestureRng(1)))
        assertNull(HandGestures.draw(canvas, screen, null, listOf(listOf(GesturePoint(0.1f, 0.1f), GesturePoint(1.4f, 0.5f))), HandsStyle.NATURAL, SeededGestureRng(1)))
        assertNull(HandGestures.draw(canvas, screen, null, null, HandsStyle.NATURAL, SeededGestureRng(1)))
    }

    @Test fun `shapes and swipe styles parse from plain words`() {
        assertEquals(DrawShape.SIGNATURE, DrawShape.parse("signature-style"))
        assertEquals(DrawShape.SIGNATURE, DrawShape.parse("signature"))
        assertEquals(DrawShape.CHECK, DrawShape.parse("Check"))
        assertNull(DrawShape.parse("star"))
        assertEquals(SwipeStyle.ARC, SwipeStyle.parse("arc"))
        assertEquals(SwipeStyle.STRAIGHTISH, SwipeStyle.parse("straight_ish"))
        assertEquals(SwipeStyle.S_CURVE, SwipeStyle.parse("s-curve"))
        assertNull(SwipeStyle.parse("loop"))
    }

    @Test fun `a swipe style picks the path family`() {
        val plan = HumanMotion.planStroke(GesturePoint(540f, 1800f), GesturePoint(560f, 700f), screen, HumanizeProfile.NORMAL,
            StrokeEnding.FLICK, 300L, Handedness.RIGHT, SeededGestureRng(5), preferredShape = StrokeShape.S_CURVE)
        assertEquals(StrokeShape.S_CURVE, plan.shape)
    }

    @Test fun `the same seed plans the same gesture`() {
        val area = GestureBounds(0f, 300f, 1080f, 2000f)
        assertEquals(
            HandGestures.pinch(area, screen, 2.0, null, HandsStyle.NATURAL, Handedness.LEFT, SeededGestureRng(42)),
            HandGestures.pinch(area, screen, 2.0, null, HandsStyle.NATURAL, Handedness.LEFT, SeededGestureRng(42)),
        )
    }
}
