package com.cyclone.mobile.gesture

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Plan 52 (final): where a finger lands on a control and how long it stays down.
 *
 * People do not tap the exact centre, nor anywhere at random. Touches cluster around the centre with a spread that
 * grows with the control but stops growing on big ones (a long list row is pressed near the middle, not at its far
 * end), and a thumb lands a little low and a little toward the side of the hand that holds the phone. The landing
 * point always stays inside the control's safe area and its inner 30–70 % (the part touch-first checked is uncovered),
 * so variation never makes a tap miss.
 */
object HandPlacement {
    /** The part of a control a finger may land on: the control on screen, kept a few pixels off its edges. */
    fun safeArea(target: GestureBounds, viewport: GestureBounds): GestureBounds? {
        val clipped = target.intersect(viewport) ?: return null
        val minDimension = min(clipped.width, clipped.height)
        return clipped.insetCapped(min(6f, minDimension * 0.2f))
    }

    /**
     * Where the finger lands. Precise hands (or [natural] = false) press the exact centre. Natural hands aim at the
     * centre with a Gaussian spread of about 14 % of the control's size, capped at 14 px across and 10 px down, plus
     * the thumb's offset; a point that would leave the safe area is drawn again, then clamped.
     */
    fun tapPoint(
        target: GestureBounds,
        viewport: GestureBounds,
        handedness: Handedness,
        rng: GestureRng,
        natural: Boolean = true,
    ): GesturePoint {
        val area = safeArea(target, viewport) ?: return viewport.clamp(target.center)
        if (!natural) return area.center
        // Touch-first checks that nothing covers the inner 30–70 % of a control; a finger never lands outside it.
        val clipped = target.intersect(viewport) ?: return area.center
        val inner = GestureBounds(
            clipped.left + clipped.width * 0.3f, clipped.top + clipped.height * 0.3f,
            clipped.right - clipped.width * 0.3f, clipped.bottom - clipped.height * 0.3f,
        )
        val safe = area.intersect(inner)?.takeIf { it.width > 0.5f && it.height > 0.5f } ?: return area.center
        val sigmaX = (area.width * 0.14f).coerceIn(0.8f, 14f)
        val sigmaY = (area.height * 0.14f).coerceIn(0.8f, 10f)
        // A thumb lands slightly low and slightly toward the holding hand.
        val biasX = min(area.width * 0.04f, 3f) * if (handedness == Handedness.RIGHT) 1f else -1f
        val biasY = min(area.height * 0.08f, 4f)
        val aim = GesturePoint(safe.center.x + biasX, safe.center.y + biasY)
        repeat(4) {
            val (gx, gy) = gaussianPair(rng)
            val candidate = GesturePoint(aim.x + (gx * sigmaX).toFloat(), aim.y + (gy * sigmaY).toFloat())
            if (safe.contains(candidate)) return candidate
        }
        return safe.clamp(aim)
    }

    /**
     * How long a tap stays on the glass. People press for roughly 60–140 ms (a log-normal spread around ~90 ms);
     * small targets take a little longer. Precise hands keep a fixed 80 ms.
     */
    fun pressMs(target: GestureBounds?, rng: GestureRng, natural: Boolean = true): Long {
        if (!natural) return 80L
        val minDimension = target?.let { min(it.width, it.height) } ?: 48f
        val base = 86.0 + 10.0 * ln(1.0 + 48.0 / minDimension.coerceAtLeast(8f).toDouble())
        val (g, _) = gaussianPair(rng)
        return (base * exp(0.2 * g)).roundToLong().coerceIn(52L, 170L)
    }

    /** Two independent standard normal values (Box–Muller), from two draws of [rng]. */
    fun gaussianPair(rng: GestureRng): Pair<Double, Double> {
        val u1 = rng.nextUnit().coerceIn(1e-9, 1.0)
        val u2 = rng.nextUnit()
        val radius = sqrt(-2.0 * ln(u1))
        return radius * cos(2.0 * PI * u2) to radius * sin(2.0 * PI * u2)
    }

    /**
     * Fitts's law for a thumb: the time to move to a target of size [targetSize] at [distance], in ms. Used by the
     * pause before an action (a far or small target takes longer to find and reach).
     */
    fun reachMs(distance: Float, targetSize: Float): Double {
        val width = max(targetSize, 8f).toDouble()
        val id = log2(distance.coerceAtLeast(0f).toDouble() / width + 1.0)
        return 60.0 + 95.0 * id
    }
}
