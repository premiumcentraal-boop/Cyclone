package com.cyclone.mobile.ui.overlay.glass

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Plan 27: the optics of Cyclone's tilt-lit glass, kept pure so they can be tested without a device.
 *
 * The light comes from the direction the phone leans. Rim sections facing the light catch a sharp shine, the opposite
 * rim a weaker one (as real glass refracts). Fingerprint dots hug the edges: crisp where the light falls, soft and
 * faint on the far side.
 */
object GlassOptics {
    /** Where the light rests when the phone is held still in the usual way: from the upper left. */
    const val REST_X = -0.45f
    const val REST_Y = -0.70f

    /** Light direction angle (radians, screen coordinates: x right, y down). */
    fun angle(x: Float, y: Float): Float = atan2(y, x)

    /** How strong the shine is, from how far the light is tilted; never fully off so the glass always reads. */
    fun power(x: Float, y: Float): Float = hypot(x, y).coerceIn(0.45f, 1f)

    /**
     * Light from gravity. [gx] and [gy] are the accelerometer's x and y (m/s²); [baseX]/[baseY] the reading when the
     * owner started holding the phone, so only how they tilt it moves the light, not how they happen to hold it.
     */
    fun lightFromGravity(gx: Float, gy: Float, baseX: Float, baseY: Float): Pair<Float, Float> {
        val x = (REST_X - (gx - baseX) / 3.2f).coerceIn(-1f, 1f)
        val y = (REST_Y + (gy - baseY) / 3.2f).coerceIn(-1f, 1f)
        return x to y
    }

    /**
     * Shine on a rim point whose outward normal is [normal] (radians). [thin] is for small pills: a wider, gentler
     * arc; panels get a narrower, sharper one.
     */
    fun rimIntensity(normal: Float, theta: Float, thin: Boolean): Float {
        val face = cos(normal - theta)
        return if (thin) max(0f, face).pow(3.2f) + 0.7f * max(0f, -face).pow(4.5f)
        else max(0f, face).pow(5.5f) + 0.7f * max(0f, -face).pow(7.5f)
    }

    /** The brightest a dot gets before [DOT_OPACITY][com.cyclone.mobile.ui.overlay.glass.DOT_OPACITY]. */
    const val DOT_ALPHA_CAP = 0.85f

    /**
     * One dot of the living halftone, written into [out] as (alpha, radius in dp); no allocation, drawn per frame.
     *
     * - [phi]: the dot's place around the panel (radians); [fall]: how deep it sits in the band (0 inner edge, 1 rim).
     * - Light: a highlight centred on the light's direction [theta] slides round the rim as the phone tilts; the dots
     *   under it are larger and brighter, a faint reflection answers on the opposite side, and the rest stay visible.
     * - Breath: a slow wave travels round the panel (about 11 s a lap) and drifts inward, so the glass is never still.
     * - Ripple: when the phone moves ([energy] 0..1), rings run out from the highlight and settle within a second.
     */
    fun liveDot(phi: Float, fall: Float, theta: Float, power: Float, t: Float, energy: Float, out: FloatArray) {
        val ad = angularDistance(phi, theta)
        val spec = exp(-ad * ad / (2f * 0.55f * 0.55f))
        val back = (PI.toFloat() - ad).let { exp(-it * it / (2f * 0.7f * 0.7f)) }
        val breath = sin(3f * phi - 0.55f * t + 2.4f * fall)
        val ripple = if (energy > 0.005f) energy * sin(7f * ad - 8f * t) * exp(-0.9f * ad) else 0f
        val depth = fall.pow(0.8f)
        val alpha = (0.21f + 0.42f * spec * power + 0.08f * back) * depth * (0.9f + 0.1f * breath + 0.35f * ripple)
        out[0] = alpha.coerceIn(0f, DOT_ALPHA_CAP)
        val radius = dotRadiusAt(fall) * (0.82f + 0.38f * spec) * (0.94f + 0.08f * breath + 0.22f * ripple)
        out[1] = max(0f, radius)
    }

    /** The radius scale before the light: what the band's depth alone gives. */
    fun dotRadiusAt(fall: Float): Float = 0.25f + 1.05f * fall.pow(1.4f)

    /** Distance between two angles, 0..π. */
    fun angularDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % (2f * PI.toFloat())
        return if (d > PI.toFloat()) 2f * PI.toFloat() - d else d
    }

    /** Motion energy left [dt] seconds after a kick of [kick]: it dies away with a 0.8 s time constant. */
    fun energyAfter(kick: Float, dt: Float): Float = if (dt <= 0f) kick else kick * exp(-dt / 0.8f)

    /**
     * How much the light's [speed] (light units a second) excites the dots: tremor does nothing, a real tilt gives a
     * gentle ripple, never a full-strength one.
     */
    fun motionEnergy(speed: Float): Float = ((speed - 0.25f) / 2.5f).coerceIn(0f, 0.6f)

    /** The share of the way a value moves in [dt] seconds towards its target with time constant [tau]. */
    fun smoothing(dt: Float, tau: Float): Float = 1f - exp(-dt / tau)

    /** Gravity's low-pass (seconds): shakes average out, tilts come through. */
    const val TILT_SMOOTHING_S = 0.32f

    /** How quickly the light then follows the smoothed tilt (seconds). */
    const val LIGHT_EASE_S = 0.16f

    /** Signed distance from a point to a rounded rectangle's edge (negative inside). */
    fun roundedRectDistance(px: Float, py: Float, w: Float, h: Float, r: Float): Float {
        val qx = abs(px - w / 2f) - (w / 2f - r)
        val qy = abs(py - h / 2f) - (h / 2f - r)
        return hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - r
    }

    /** Sweep-gradient rotation (degrees) that puts a button rim's brightest point towards the light. */
    fun rimRotationDegrees(x: Float, y: Float): Float = Math.toDegrees(angle(x, y).toDouble()).toFloat()
}
