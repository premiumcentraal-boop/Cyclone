package com.cyclone.mobile.gesture

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** How a stroke ends: what the finger does in its last moments. */
enum class StrokeEnding {
    /** Slows smoothly to a stop before lifting (a controlled scroll or drag). Lists do not fling. */
    GLIDE,

    /** Still speeding up as it lifts, the way a thumb flicks a feed. Lists keep scrolling. */
    FLICK,

    /** Slows, stops, and rests on the glass for a moment before lifting ("put it exactly here"). */
    HOLD,
}

/** The path family a stroke follows. */
enum class StrokeShape {
    STRAIGHT,

    /** One gentle bow to one side, peak position varied. */
    BOW,

    /** A circular arc around the thumb's pivot (below and to the side of the hand holding the phone). */
    THUMB_ARC,

    /** Two opposite bows, for long diagonal or long vertical travel. */
    S_CURVE,

    /** Goes 2–6 % past the end and comes back. Only used with [StrokeEnding.HOLD]. */
    OVERSHOOT,
}

/** A point the finger reaches at [tMs] after touching down. */
data class TimedPoint(val x: Float, val y: Float, val tMs: Long) {
    val point: GesturePoint get() = GesturePoint(x, y)
}

/**
 * A speed-curved stroke: an ordered, strictly time-increasing polyline. Android renders each pair of consecutive
 * points as one chained stroke piece, so speed changes from piece to piece instead of staying constant.
 */
data class MotionPlan(
    val points: List<TimedPoint>,
    val shape: StrokeShape,
    val ending: StrokeEnding,
    val profile: HumanizeProfile,
) {
    init {
        require(points.size >= 2) { "A motion needs at least two points" }
        require(points.first().tMs == 0L) { "A motion starts at time 0" }
        for (index in 1 until points.size) {
            require(points[index].tMs > points[index - 1].tMs) { "Motion times must strictly increase" }
        }
    }

    val durationMs: Long get() = points.last().tMs
    val segments: Int get() = points.size - 1
    val start: GesturePoint get() = points.first().point
    val end: GesturePoint get() = points.last().point
}

/**
 * Plan 52 run 1: human speed curves and stroke shapes.
 *
 * Android plays one stroke at an even speed along its path. Real fingers do not: a controlled stroke follows a
 * smooth bell-shaped speed curve (minimum jerk), a flick is still accelerating when it lifts, and a careful drag
 * slows and rests before lifting. This planner turns a start, an end and a duration into timed points that follow
 * those curves along a natural path, staying inside the viewport. It is pure, seeded and replayable.
 */
object HumanMotion {
    /** Shortest time one chained piece may take; shorter pieces add callback overhead without visible benefit. */
    const val MIN_PIECE_MS = 8L
    const val MAX_PIECES = 14
    private const val MIN_PIECES = 6
    private const val PIECE_TARGET_MS = 24.0
    private const val GEOMETRY_SAMPLES = 48

    /**
     * Fraction of the path covered at normalized time [tau] (0..1).
     *
     * - GLIDE and HOLD: minimum jerk, 10τ³ − 15τ⁴ + 6τ⁵ (zero speed at both ends, peak in the middle).
     * - FLICK: the minimum-jerk curve cut at [flickCut] and rescaled, so speed peaks late and is high at lift-off.
     */
    fun progress(ending: StrokeEnding, tau: Double, flickCut: Double = 0.68): Double {
        val t = tau.coerceIn(0.0, 1.0)
        return when (ending) {
            StrokeEnding.GLIDE, StrokeEnding.HOLD -> minimumJerk(t)
            StrokeEnding.FLICK -> {
                val cut = flickCut.coerceIn(0.55, 0.9)
                minimumJerk(t * cut) / minimumJerk(cut)
            }
        }
    }

    private fun minimumJerk(t: Double): Double = t * t * t * (10.0 - 15.0 * t + 6.0 * t * t)

    fun planStroke(
        start: GesturePoint,
        end: GesturePoint,
        viewport: GestureBounds,
        profile: HumanizeProfile,
        ending: StrokeEnding,
        durationMs: Long,
        handedness: Handedness,
        rng: GestureRng,
    ): MotionPlan {
        require(viewport.width > 2f && viewport.height > 2f) { "Viewport must have positive size" }
        val margin = 1f
        val a = viewport.clamp(start, margin)
        val b = viewport.clamp(end, margin)
        val duration = durationMs.coerceIn(40L, 3_000L)
        val length = a.distanceTo(b)
        if (profile == HumanizeProfile.OFF || length < 4f) {
            return MotionPlan(listOf(TimedPoint(a.x, a.y, 0L), TimedPoint(b.x, b.y, duration)), StrokeShape.STRAIGHT, ending, profile)
        }

        val shape = chooseShape(profile, ending, length, rng)
        val flickCut = 0.62 + rng.nextUnit() * 0.12
        val geometry = geometry(a, b, viewport, margin, profile, shape, handedness, rng)
        val resolvedShape = geometry.second
        val path = geometry.first

        val cumulative = FloatArray(path.size)
        for (index in 1 until path.size) cumulative[index] = cumulative[index - 1] + path[index - 1].distanceTo(path[index])
        val total = cumulative.last().coerceAtLeast(1e-3f)

        val pieces = (duration / PIECE_TARGET_MS).toInt().coerceIn(MIN_PIECES, MAX_PIECES)
            .coerceAtMost((duration / MIN_PIECE_MS).toInt().coerceAtLeast(1))
        val points = ArrayList<TimedPoint>(pieces + 2)
        var lastT = -1L
        for (index in 0..pieces) {
            val tau = index.toDouble() / pieces
            val distance = (progress(ending, tau, flickCut) * total).toFloat()
            val p = when (index) {
                0 -> path.first()
                pieces -> path.last()
                else -> pointAtLength(path, cumulative, distance)
            }
            var t = (tau * duration).roundToLong()
            if (t <= lastT) t = lastT + 1
            points += TimedPoint(p.x, p.y, t)
            lastT = t
        }
        if (ending == StrokeEnding.HOLD) {
            val rest = 40L + (rng.nextUnit() * 80.0).roundToLong()
            val last = points.last()
            val settle = viewport.clamp(GesturePoint(last.x + 0.6f, last.y + 0.4f), margin)
            points += TimedPoint(settle.x, settle.y, last.tMs + rest)
        }
        return MotionPlan(points, resolvedShape, ending, profile)
    }

    /**
     * Where the finger lifts after a tap or press that landed at [down]: a 0–3 px roll of the fingertip, kept inside
     * the target's safe area and the viewport, and far below Android's touch slop so a tap stays a tap.
     */
    fun liftPoint(
        down: GesturePoint,
        target: GestureBounds?,
        viewport: GestureBounds,
        profile: HumanizeProfile,
        maxDriftPx: Float,
        rng: GestureRng,
    ): GesturePoint {
        if (profile == HumanizeProfile.OFF) return down
        val reach = when (profile) {
            HumanizeProfile.LIGHT -> 0.5 + rng.nextUnit() * 0.6
            else -> 0.4 + rng.nextUnit() * 0.6
        } * maxDriftPx
        val angle = rng.nextUnit() * 2.0 * PI
        val candidate = GesturePoint(down.x + (cos(angle) * reach).toFloat(), down.y + (sin(angle) * reach).toFloat())
        val safeTarget = target?.intersect(viewport)?.insetCapped(2f)
        if (safeTarget != null && !safeTarget.contains(candidate)) return down
        if (!viewport.insetCapped(1f).contains(candidate)) return down
        return candidate
    }

    private fun chooseShape(profile: HumanizeProfile, ending: StrokeEnding, length: Float, rng: GestureRng): StrokeShape {
        val roll = rng.nextUnit()
        if (ending == StrokeEnding.HOLD && length > 150f && roll < 0.35) return StrokeShape.OVERSHOOT
        return when (profile) {
            HumanizeProfile.OFF -> StrokeShape.STRAIGHT
            HumanizeProfile.LIGHT -> if (roll < 0.6) StrokeShape.THUMB_ARC else StrokeShape.BOW
            HumanizeProfile.NORMAL -> when {
                length > 400f && roll < 0.45 -> StrokeShape.THUMB_ARC
                length > 400f && roll < 0.75 -> StrokeShape.BOW
                length > 400f -> StrokeShape.S_CURVE
                roll < 0.55 -> StrokeShape.THUMB_ARC
                else -> StrokeShape.BOW
            }
        }
    }

    /** The sampled path for [shape], shrunk until it fits the viewport; falls back to a straight line. */
    private fun geometry(
        a: GesturePoint,
        b: GesturePoint,
        viewport: GestureBounds,
        margin: Float,
        profile: HumanizeProfile,
        shape: StrokeShape,
        handedness: Handedness,
        rng: GestureRng,
    ): Pair<List<GesturePoint>, StrokeShape> {
        val length = a.distanceTo(b)
        val ux = (b.x - a.x) / length
        val uy = (b.y - a.y) / length
        val nx = -uy
        val ny = ux
        val normal = profile == HumanizeProfile.NORMAL
        val randomSign = if (rng.nextUnit() < 0.5) -1f else 1f
        // Draw every random number up front so the stream is the same whatever the fit loop below does.
        val amplitudeRoll = rng.nextUnit()
        val skewRoll = rng.nextUnit()
        val overshootRoll = rng.nextUnit()

        val lateral: (Double) -> Double
        var amplitude: Double
        var overshoot = 0.0
        when (shape) {
            StrokeShape.STRAIGHT -> {
                lateral = { 0.0 }
                amplitude = 0.0
            }
            StrokeShape.BOW, StrokeShape.OVERSHOOT -> {
                val ratio = if (normal && shape == StrokeShape.BOW) 0.015 + amplitudeRoll * 0.03 else 0.006 + amplitudeRoll * 0.01
                amplitude = min(length * ratio, if (normal) 48.0 else 10.0) * randomSign
                // Peak anywhere from 40 % to 60 % of the way: u' = u^k maps the chosen peak to the middle.
                val peak = 0.4 + skewRoll * 0.2
                val k = ln(0.5) / ln(peak)
                lateral = { u -> val w = u.pow(k); 4.0 * w * (1.0 - w) }
                if (shape == StrokeShape.OVERSHOOT) overshoot = 0.02 + overshootRoll * 0.04
            }
            StrokeShape.THUMB_ARC -> {
                val ratio = if (normal) 0.025 + amplitudeRoll * 0.035 else 0.01 + amplitudeRoll * 0.01
                val sagitta = min(length * ratio, if (normal) 70.0 else 14.0)
                // The arc bulges away from the thumb's pivot: below and to the outside of the holding hand.
                val pivotX = if (handedness == Handedness.RIGHT) viewport.right + viewport.width * 0.1f else viewport.left - viewport.width * 0.1f
                val pivotY = viewport.bottom + viewport.height * 0.05f
                val midX = (a.x + b.x) / 2f
                val midY = (a.y + b.y) / 2f
                val towardPivot = nx * (pivotX - midX) + ny * (pivotY - midY)
                val sign = if (towardPivot > 0f) -1.0 else 1.0
                amplitude = sagitta * sign
                lateral = { u -> circularBump(u, length.toDouble(), sagitta) }
            }
            StrokeShape.S_CURVE -> {
                val ratio = 0.008 + amplitudeRoll * 0.012
                amplitude = min(length * ratio, 30.0) * randomSign
                lateral = { u -> sin(2.0 * PI * u) }
            }
        }

        repeat(5) {
            val sampled = sample(a, b, length, ux, uy, nx, ny, amplitude, lateral, overshoot)
            if (sampled.all { viewport.insetCapped(margin).contains(it) }) return sampled to shape
            amplitude *= 0.5
            overshoot *= 0.5
        }
        return listOf(a, b) to StrokeShape.STRAIGHT
    }

    /** Lateral offset of a circular arc of the given sagitta, normalised so its peak is 1. */
    private fun circularBump(u: Double, length: Double, sagitta: Double): Double {
        if (sagitta <= 1e-6) return 0.0
        val radius = (length * length / 4.0 + sagitta * sagitta) / (2.0 * sagitta)
        val x = (u - 0.5) * length
        val y = sqrt(max(0.0, radius * radius - x * x)) - (radius - sagitta)
        return (y / sagitta).coerceIn(0.0, 1.0)
    }

    private fun sample(
        a: GesturePoint,
        b: GesturePoint,
        length: Float,
        ux: Float,
        uy: Float,
        nx: Float,
        ny: Float,
        amplitude: Double,
        lateral: (Double) -> Double,
        overshoot: Double,
    ): List<GesturePoint> {
        val out = ArrayList<GesturePoint>(GEOMETRY_SAMPLES + 8)
        val reach = 1.0 + overshoot
        for (index in 0..GEOMETRY_SAMPLES) {
            val u = index.toDouble() / GEOMETRY_SAMPLES
            val along = u * reach * length
            val side = amplitude * lateral(u)
            out += GesturePoint(
                (a.x + ux * along + nx * side).toFloat(),
                (a.y + uy * along + ny * side).toFloat(),
            )
        }
        if (overshoot > 0.0) {
            // Come back from past the end to the end itself.
            val from = out.last()
            for (index in 1..6) out += GesturePoint.lerp(from, b, index / 6f)
        } else {
            out[out.lastIndex] = b
        }
        out[0] = a
        return out
    }

    private fun pointAtLength(path: List<GesturePoint>, cumulative: FloatArray, distance: Float): GesturePoint {
        if (distance <= 0f) return path.first()
        if (distance >= cumulative.last()) return path.last()
        var low = 0
        var high = cumulative.lastIndex
        while (high - low > 1) {
            val mid = (low + high) ushr 1
            if (cumulative[mid] <= distance) low = mid else high = mid
        }
        val span = cumulative[high] - cumulative[low]
        val fraction = if (span <= 1e-6f) 0f else (distance - cumulative[low]) / span
        return GesturePoint.lerp(path[low], path[high], fraction)
    }

    /** Average speed of piece [index] in px/ms; used by tests and the lab to read the speed curve. */
    fun pieceSpeed(plan: MotionPlan, index: Int): Double {
        val from = plan.points[index]
        val to = plan.points[index + 1]
        return from.point.distanceTo(to.point).toDouble() / (to.tMs - from.tMs).toDouble()
    }

    /** Index of the fastest piece, as a fraction of the motion's pieces (0 = first, 1 = last). */
    fun peakSpeedPosition(plan: MotionPlan): Double {
        var best = 0
        var bestSpeed = -1.0
        val moving = if (plan.ending == StrokeEnding.HOLD) plan.segments - 1 else plan.segments
        for (index in 0 until moving) {
            val speed = pieceSpeed(plan, index)
            if (speed > bestSpeed + 1e-9) {
                bestSpeed = speed
                best = index
            }
        }
        return if (moving <= 1) 0.0 else best.toDouble() / (moving - 1).toDouble()
    }

}
