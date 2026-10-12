package com.cyclone.mobile.ui.overlay.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class GlassOpticsTest {
    @Test fun theRimFacingTheLightShinesMostAndTheOppositeRimLess() {
        val theta = GlassOptics.angle(-1f, 0f)                       // light from the left
        val facing = GlassOptics.rimIntensity(PI.toFloat(), theta, thin = false)
        val opposite = GlassOptics.rimIntensity(0f, theta, thin = false)
        val side = GlassOptics.rimIntensity((PI / 2).toFloat(), theta, thin = false)
        assertEquals(1f, facing, 0.001f)
        assertEquals(0.7f, opposite, 0.001f)
        assertTrue(side < 0.01f)
    }

    @Test fun panelsGetANarrowerShineThanThinPills() {
        val theta = 0f
        val offAxis = (PI / 5).toFloat()
        assertTrue(GlassOptics.rimIntensity(offAxis, theta, thin = true) > GlassOptics.rimIntensity(offAxis, theta, thin = false))
    }

    private fun dot(phi: Float, fall: Float = 1f, theta: Float = 0f, t: Float = 0f, energy: Float = 0f): FloatArray =
        FloatArray(2).also { GlassOptics.liveDot(phi, fall, theta, 1f, t, energy, it) }

    @Test fun theHighlightFollowsTheLightAndTheRestStaysVisible() {
        val lit = dot(0f)
        val side = dot((PI / 2).toFloat())
        val far = dot(PI.toFloat())
        assertTrue(lit[0] > side[0] * 1.6f)
        assertTrue(lit[1] > side[1])
        // No gaps: the side and the far side keep their dots.
        assertTrue(side[0] > 0.1f)
        assertTrue(far[0] > 0.1f)
        // Tilting moves the highlight: lit from the left, the left dots are the bright ones.
        assertTrue(dot(PI.toFloat(), theta = PI.toFloat())[0] > dot(0f, theta = PI.toFloat())[0] * 1.6f)
    }

    @Test fun dotsFadeTowardsTheInnerEdgeOfTheBand() {
        assertTrue(dot(0f, fall = 1f)[0] > dot(0f, fall = 0.2f)[0])
        assertTrue(dot(0f, fall = 1f)[1] > dot(0f, fall = 0.2f)[1])
    }

    @Test fun theGlassBreathesGently() {
        val a = dot(1f, t = 0f)
        val b = dot(1f, t = 3f)
        assertTrue(abs(a[0] - b[0]) > 0.001f)
        // Gentle: the breath alone never changes a dot by more than about a tenth.
        assertTrue(abs(a[0] - b[0]) < a[0] * 0.25f)
    }

    @Test fun movementRipplesAndThenSettles() {
        val calm = dot(0.4f, t = 0.1f)
        val moved = dot(0.4f, t = 0.1f, energy = 1f)
        assertTrue(abs(moved[1] - calm[1]) > 0.01f)
        assertEquals(1f, GlassOptics.energyAfter(1f, 0f), 0.0001f)
        assertTrue(GlassOptics.energyAfter(1f, 3f) < 0.03f)
        assertEquals(0f, GlassOptics.motionKick(0.001f, 0f), 0.0001f)
        assertTrue(GlassOptics.motionKick(0.05f, 0f) > 0.2f)
    }

    @Test fun angularDistanceWrapsRoundTheCircle() {
        assertEquals(0.2f, GlassOptics.angularDistance(PI.toFloat() - 0.1f, -PI.toFloat() + 0.1f), 0.001f)
        assertEquals(PI.toFloat(), GlassOptics.angularDistance(0f, PI.toFloat()), 0.0001f)
    }

    @Test fun holdingStillRestsTheLightAndTiltingMovesIt() {
        val (x, y) = GlassOptics.lightFromGravity(1f, 7f, 1f, 7f)
        assertEquals(GlassOptics.REST_X, x, 0.0001f)
        assertEquals(GlassOptics.REST_Y, y, 0.0001f)
        val (tx, _) = GlassOptics.lightFromGravity(-2f, 7f, 1f, 7f)
        assertTrue(tx > x)
        val (cx, cy) = GlassOptics.lightFromGravity(40f, -40f, 0f, 0f)
        assertEquals(-1f, cx, 0f)
        assertEquals(-1f, cy, 0f)
    }

    @Test fun theShineNeverSwitchesOffCompletely() {
        assertEquals(0.45f, GlassOptics.power(0f, 0f), 0f)
        assertEquals(1f, GlassOptics.power(3f, 4f), 0f)
    }

    @Test fun roundedRectDistanceIsNegativeInsideAndZeroOnTheEdge() {
        assertTrue(GlassOptics.roundedRectDistance(50f, 20f, 100f, 40f, 20f) < 0f)
        assertEquals(0f, GlassOptics.roundedRectDistance(50f, 0f, 100f, 40f, 20f), 0.001f)
        assertTrue(GlassOptics.roundedRectDistance(-5f, 20f, 100f, 40f, 20f) > 0f)
    }

    @Test fun rimSegmentsGoAllTheWayRound() {
        val segments = rimSegments(100f, 40f, 20f, 40)
        assertEquals(200, segments.size)
        val normals = (0 until 40).map { segments[it * 5 + 4] }
        assertTrue(normals.any { it < -1.5f } && normals.any { it > 1.5f })     // top and bottom edges
    }
}
