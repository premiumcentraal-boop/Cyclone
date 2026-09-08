package com.cyclone.mobile.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanGestureEngineFuzzTest {
    @Test
    fun manySeedsStayDeterministicFiniteAndScreenSafe() {
        val viewports = listOf(
            GestureBounds(0f, 0f, 1f, 1f),
            GestureBounds(0f, 0f, 8f, 8f),
            GestureBounds(0f, 0f, 360f, 800f),
            GestureBounds(0f, 0f, 1080f, 2400f),
            GestureBounds(-5000f, -3000f, 5000f, 3000f),
        )

        repeat(5_000) { index ->
            val seed = index.toLong() * 0x9E3779B9L
            val viewport = viewports[index % viewports.size]
            val spanX = viewport.width * 1.4f
            val spanY = viewport.height * 1.4f
            val rng = SeededGestureRng(seed xor 0x5A5A5A5AL)
            fun x() = viewport.left - viewport.width * 0.2f + (rng.nextUnit() * spanX).toFloat()
            fun y() = viewport.top - viewport.height * 0.2f + (rng.nextUnit() * spanY).toFloat()
            val start = GesturePoint(x(), y())
            val end = GesturePoint(x(), y())
            val profile = HumanizeProfile.values()[index % HumanizeProfile.values().size]

            val first = HumanGestureEngine.planSwipe(start, end, viewport, profile, seed)
            val replay = HumanGestureEngine.planSwipe(start, end, viewport, profile, seed)
            assertEquals(first, replay)
            assertTrue(first.durationMs in 70L..1_200L)
            for (point in first.sample(20)) {
                assertTrue(point.x.isFinite() && point.y.isFinite())
                assertTrue(viewport.contains(point))
            }

            val targetCenter = viewport.clamp(GesturePoint(x(), y()))
            val halfWidth = maxOf(0.0001f, viewport.width * (0.001f + rng.nextUnit().toFloat() * 0.25f))
            val halfHeight = maxOf(0.0001f, viewport.height * (0.001f + rng.nextUnit().toFloat() * 0.25f))
            val target = GestureBounds(
                targetCenter.x - halfWidth,
                targetCenter.y - halfHeight,
                targetCenter.x + halfWidth,
                targetCenter.y + halfHeight,
            )
            val tap = HumanGestureEngine.planTap(target, viewport, profile, seed)
            assertTrue(viewport.contains(tap.point))
            assertTrue(target.contains(tap.point))
            assertTrue(tap.point.x.isFinite() && tap.point.y.isFinite())
        }
    }
}
