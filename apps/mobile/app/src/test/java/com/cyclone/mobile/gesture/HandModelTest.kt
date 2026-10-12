package com.cyclone.mobile.gesture

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 52 run 2: swipes from intents, with a hand model instead of fixed geometry. */
class HandModelTest {
    private val screen = GestureBounds(0f, 0f, 1080f, 2400f)
    private val feed = GestureBounds(0f, 300f, 1080f, 2100f)

    private fun plan(seed: Long, intent: SwipeIntent, hand: Handedness = Handedness.RIGHT, varied: Boolean = true) =
        HandModel.plan(intent, screen, hand, SeededGestureRng(seed), varied)!!

    @Test fun aThousandSwipesAllDifferAndStayInTheArea() {
        val intent = SwipeIntent(SwipeDirection.UP, SwipeAmount.PAGE, SwipeSpeed.NORMAL, feed)
        val plans = (0L until 1_000L).map { plan(it, intent) }
        assertTrue("starts vary", plans.map { it.start.x.toInt() to it.start.y.toInt() }.toSet().size > 900)
        assertTrue("durations vary", plans.map { it.durationMs }.toSet().size > 100)
        plans.forEach { p ->
            assertTrue(feed.contains(p.start) && feed.contains(p.end))
            assertTrue("the finger moves up", p.end.y < p.start.y)
            // Clear of the gesture-navigation strip and the back edges.
            assertTrue(p.start.y < 2400f * 0.92f && p.start.x > 1080f * 0.07f && p.start.x < 1080f * 0.93f)
        }
    }

    @Test fun amountsAreOrderedAndSpeedsSetTheEnding() {
        fun travel(amount: SwipeAmount) = (0L until 200L).map {
            val p = plan(it, SwipeIntent(SwipeDirection.UP, amount, SwipeSpeed.NORMAL, feed))
            p.start.distanceTo(p.end)
        }.average()
        assertTrue(travel(SwipeAmount.PEEK) < travel(SwipeAmount.HALF))
        assertTrue(travel(SwipeAmount.HALF) < travel(SwipeAmount.PAGE))
        assertTrue(travel(SwipeAmount.PAGE) < travel(SwipeAmount.FAR))
        assertEquals(StrokeEnding.FLICK, plan(1L, SwipeIntent(SwipeDirection.UP, SwipeAmount.PAGE, SwipeSpeed.FLICK, feed)).ending)
        assertEquals(StrokeEnding.GLIDE, plan(1L, SwipeIntent(SwipeDirection.UP, SwipeAmount.PAGE, SwipeSpeed.GENTLE, feed)).ending)
        val flick = plan(1L, SwipeIntent(SwipeDirection.UP, SwipeAmount.PAGE, SwipeSpeed.FLICK, feed)).durationMs
        val gentle = plan(1L, SwipeIntent(SwipeDirection.UP, SwipeAmount.PAGE, SwipeSpeed.GENTLE, feed)).durationMs
        assertTrue(flick < gentle)
    }

    @Test fun theThumbRestsOnTheSideOfTheHoldingHand() {
        val intent = SwipeIntent(SwipeDirection.UP, SwipeAmount.PAGE, SwipeSpeed.NORMAL, feed)
        val right = (0L until 300L).map { plan(it, intent, Handedness.RIGHT).start.x }.average()
        val left = (0L until 300L).map { plan(it, intent, Handedness.LEFT).start.x }.average()
        assertTrue(right > 540.0)
        assertTrue(left < 540.0)
    }

    @Test fun everyDirectionMovesTheRightWay() {
        val box = GestureBounds(100f, 800f, 1000f, 1200f)
        val l = plan(3L, SwipeIntent(SwipeDirection.LEFT, SwipeAmount.HALF, SwipeSpeed.NORMAL, box))
        assertTrue(l.end.x < l.start.x)
        val r = plan(3L, SwipeIntent(SwipeDirection.RIGHT, SwipeAmount.HALF, SwipeSpeed.NORMAL, box))
        assertTrue(r.end.x > r.start.x)
        val d = plan(3L, SwipeIntent(SwipeDirection.DOWN, SwipeAmount.HALF, SwipeSpeed.NORMAL, feed))
        assertTrue(d.end.y > d.start.y)
    }

    @Test fun preciseHandsAreTheSameEveryTime() {
        val intent = SwipeIntent(SwipeDirection.UP, SwipeAmount.PAGE, SwipeSpeed.NORMAL, feed)
        val plans = (0L until 50L).map { plan(it, intent, varied = false) }.toSet()
        assertEquals(1, plans.size)
        assertEquals(350L, plans.single().durationMs)
        assertEquals(plans.single().start.x, plans.single().end.x, 0.01f)
    }

    @Test fun aTinyAreaIsRefused() {
        assertNull(HandModel.plan(SwipeIntent(SwipeDirection.UP, SwipeAmount.PAGE, SwipeSpeed.NORMAL,
            GestureBounds(500f, 500f, 510f, 510f)), screen, Handedness.RIGHT, SeededGestureRng(1L)))
    }

    @Test fun intentsParseFromToolParams() {
        val params = JSONObject().put("direction", "left").put("amount", "peek").put("speed", "flick")
            .put("region", JSONObject().put("left", 0).put("top", 900).put("right", 1080).put("bottom", 1300))
        assertTrue(SwipeIntents.isIntent(params))
        val (planned, error) = SwipeIntents.resolve(params, screen, Handedness.RIGHT, SeededGestureRng(2L), HandsStyle.NATURAL)
        assertNull(error)
        assertNotNull(planned)
        assertTrue(planned!!.end.x < planned.start.x && planned.start.y in 900f..1300f)
        assertEquals(StrokeEnding.FLICK, planned.ending)
        assertEquals("direction must be up, down, left or right",
            SwipeIntents.resolve(JSONObject().put("direction", "sideways"), screen, Handedness.RIGHT, SeededGestureRng(1L)).second)
        assertEquals("amount must be peek, half, page or far",
            SwipeIntents.resolve(JSONObject().put("direction", "up").put("amount", "lots"), screen, Handedness.RIGHT, SeededGestureRng(1L)).second)
        assertTrue("coordinates win", !SwipeIntents.isIntent(JSONObject().put("direction", "up").put("x1", 1)))
    }
}
