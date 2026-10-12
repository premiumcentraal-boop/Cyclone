package com.cyclone.mobile.gesture

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

/** Plan 52 run 6: the gestures beyond tap, press and swipe. */
enum class TouchGestureKind(val wire: String) {
    DOUBLE_TAP("double_tap"),
    DRAG("drag"),
    PINCH("pinch"),
    DRAW("draw"),
}

/** One finger's stroke: its motion (times from its own touch-down) and when it touches down in the gesture. */
data class FingerStroke(val startMs: Long, val motion: MotionPlan) {
    init {
        require(startMs >= 0L) { "A stroke cannot start before the gesture" }
    }

    val endMs: Long get() = startMs + motion.durationMs
}

/**
 * A planned multi-stroke gesture. [simultaneous] strokes are fingers on the glass together (a pinch) and share one
 * time grid; otherwise the strokes follow one another (the two taps of a double tap, the strokes of a drawing).
 */
data class TouchGesture(
    val kind: TouchGestureKind,
    val strokes: List<FingerStroke>,
    val simultaneous: Boolean,
    /** Bounded facts for the result's evidence: shapes, counts, durations and ratios, never coordinates or text. */
    val facts: Map<String, Any> = emptyMap(),
) {
    init {
        require(strokes.isNotEmpty()) { "A gesture needs a stroke" }
        if (simultaneous) {
            val grid = strokes.first().motion.points.map { it.tMs }
            require(strokes.all { stroke -> stroke.startMs == 0L && stroke.motion.points.map { it.tMs } == grid }) {
                "Fingers on the glass together share one time grid"
            }
        } else {
            for (index in 1 until strokes.size) {
                require(strokes[index].startMs > strokes[index - 1].endMs) { "Strokes that follow one another do not overlap" }
            }
        }
    }

    val durationMs: Long get() = strokes.maxOf { it.endMs }
}

/** Plan 52 run 6: which gestures background displays take; pinch waits for a device run. */
object HandGesturesSupport {
    const val PINCH_ON_BACKGROUND = false
}

/** The named shapes `phone.draw` can make inside a grounded canvas. */
enum class DrawShape(val wire: String) {
    CIRCLE("circle"),
    CHECK("check"),
    UNDERLINE("underline"),
    ZIGZAG("zigzag"),
    SCRIBBLE("scribble"),
    /** A generic handwritten-looking flourish; never anyone's real signature, and always approved by the owner. */
    SIGNATURE("signature-style");

    companion object {
        fun parse(raw: String?): DrawShape? {
            val token = raw?.trim()?.lowercase()?.replace('_', '-') ?: return null
            return entries.firstOrNull { it.wire == token || (it == SIGNATURE && token == "signature") }
        }
    }
}

/** Swipe path families the caller may ask for in plain words (plan 52 run 6). */
enum class SwipeStyle(val wire: String, val shape: StrokeShape) {
    ARC("arc", StrokeShape.THUMB_ARC),
    STRAIGHTISH("straight-ish", StrokeShape.BOW),
    S_CURVE("s-curve", StrokeShape.S_CURVE);

    companion object {
        fun parse(raw: String?): SwipeStyle? {
            val token = raw?.trim()?.lowercase()?.replace('_', '-') ?: return null
            return when (token) {
                "arc", "thumb-arc", "curved" -> ARC
                "straight-ish", "straightish", "straight" -> STRAIGHTISH
                "s-curve", "scurve", "s" -> S_CURVE
                else -> null
            }
        }
    }
}

/**
 * Plan 52 run 6: drag, pinch, double tap and drawing, planned on the phone from typed intents.
 *
 * Callers name controls (and, for drawing, a shape or a few normalised strokes inside a grounded canvas). The planner
 * picks every point and every millisecond. Precise hands give fixed, centred geometry; Natural and Relaxed hands vary
 * where fingers land, how they move and how long they rest, inside the same safety limits. Pure, seeded, replayable.
 */
object HandGestures {
    /** Android's double-tap window is 40–300 ms between the first lift and the second touch. */
    const val DOUBLE_TAP_GAP_MIN_MS = 90L
    const val DOUBLE_TAP_GAP_MAX_MS = 180L

    /** Longer than any Android long-press timeout (400–500 ms), so the item is picked up before it moves. */
    const val DRAG_PICKUP_MIN_MS = 550L
    const val DRAG_PICKUP_MAX_MS = 1_500L

    const val PINCH_SCALE_MIN = 0.25
    const val PINCH_SCALE_MAX = 4.0

    /** Normalised strokes a caller may send for drawing. */
    const val DRAW_MAX_STROKES = 4
    const val DRAW_MAX_POINTS = 64

    /** Fractions of the screen kept clear of system gestures (status bar, gesture navigation). */
    private const val EDGE_X = 0.05f
    private const val EDGE_TOP = 0.05f
    private const val EDGE_BOTTOM = 0.07f

    /** Two fingertips closer than this read as one touch; pinches keep them apart. */
    const val MIN_FINGER_GAP_PX = 90f

    private fun rngFor(style: HandsStyle, rng: GestureRng): GestureRng = if (style.natural) rng else FixedGestureRng

    private fun profileFor(style: HandsStyle): HumanizeProfile = if (style.natural) HumanizeProfile.NORMAL else HumanizeProfile.OFF

    private fun safeScreen(viewport: GestureBounds): GestureBounds = GestureBounds(
        viewport.left + viewport.width * EDGE_X,
        viewport.top + viewport.height * EDGE_TOP,
        viewport.right - viewport.width * EDGE_X,
        viewport.bottom - viewport.height * EDGE_BOTTOM,
    )

    // ---- double tap ------------------------------------------------------------------------------------------------

    /**
     * Two taps on [target], 90–180 ms apart (lift to touch), the second a few pixels from the first and a touch
     * shorter, both inside the target's safe area.
     */
    fun doubleTap(
        target: GestureBounds,
        viewport: GestureBounds,
        style: HandsStyle,
        handedness: Handedness,
        rng: GestureRng,
    ): TouchGesture? {
        val r = rngFor(style, rng)
        val natural = style.natural
        val safe = HandPlacement.safeArea(target, viewport) ?: return null
        val first = HandPlacement.tapPoint(target, viewport, handedness, r, natural)
        val press1 = HandPlacement.pressMs(target, r, natural).coerceAtMost(130L)
        val gap = if (natural) DOUBLE_TAP_GAP_MIN_MS + (r.nextUnit() * (DOUBLE_TAP_GAP_MAX_MS - DOUBLE_TAP_GAP_MIN_MS)).roundToLong()
            else 120L
        val second = if (natural) {
            val angle = r.nextUnit() * 2.0 * PI
            val reach = 1.5 + r.nextUnit() * 4.0
            val moved = GesturePoint(first.x + (cos(angle) * reach).toFloat(), first.y + (sin(angle) * reach).toFloat())
            if (safe.contains(moved)) moved else first
        } else first
        val press2 = if (natural) (press1 * (0.82 + r.nextUnit() * 0.16)).roundToLong().coerceAtLeast(45L) else 80L
        val profile = if (natural) HumanizeProfile.LIGHT else HumanizeProfile.OFF
        val tap1 = press(first, press1, target, viewport, profile, r)
        val tap2 = press(second, press2, target, viewport, profile, r)
        return TouchGesture(
            TouchGestureKind.DOUBLE_TAP,
            listOf(FingerStroke(0L, tap1), FingerStroke(press1 + gap, tap2)),
            simultaneous = false,
            facts = mapOf("gapMs" to gap, "pressMs" to listOf(press1, press2)),
        )
    }

    private fun press(at: GesturePoint, durationMs: Long, target: GestureBounds, viewport: GestureBounds,
                      profile: HumanizeProfile, rng: GestureRng): MotionPlan {
        val lift = HumanMotion.liftPoint(at, target, viewport, profile, 1.6f, rng)
        return MotionPlan(listOf(TimedPoint(at.x, at.y, 0L), TimedPoint(lift.x, lift.y, durationMs)), StrokeShape.STRAIGHT,
            StrokeEnding.HOLD, profile)
    }

    // ---- drag --------------------------------------------------------------------------------------------------------

    /** Where a drag by direction ends: [amount] of the screen's height or width from the start, kept on screen. */
    fun dragEnd(
        from: GestureBounds,
        direction: SwipeDirection,
        amount: SwipeAmount,
        viewport: GestureBounds,
    ): GesturePoint? {
        val start = from.intersect(viewport)?.center ?: return null
        val along = if (direction.vertical) viewport.height else viewport.width
        val fraction = when (amount) {
            SwipeAmount.PEEK -> 0.12f
            SwipeAmount.HALF -> 0.25f
            SwipeAmount.PAGE -> 0.40f
            SwipeAmount.FAR -> 0.60f
        }
        val travel = along * fraction
        val end = when (direction) {
            SwipeDirection.UP -> GesturePoint(start.x, start.y - travel)
            SwipeDirection.DOWN -> GesturePoint(start.x, start.y + travel)
            SwipeDirection.LEFT -> GesturePoint(start.x - travel, start.y)
            SwipeDirection.RIGHT -> GesturePoint(start.x + travel, start.y)
        }
        val safe = safeScreen(viewport)
        val clamped = safe.clamp(end)
        return if (start.distanceTo(clamped) < 24f) null else clamped
    }

    /**
     * Press and hold [from] until it is picked up, carry it to [to] (inside [toTarget] when dropping on a control),
     * slowing into the drop, rest a moment, then let go. One unbroken stroke.
     *
     * @param holdMs how long to hold before moving; at least [DRAG_PICKUP_MIN_MS].
     */
    fun drag(
        from: GestureBounds,
        to: GesturePoint,
        toTarget: GestureBounds?,
        viewport: GestureBounds,
        style: HandsStyle,
        handedness: Handedness,
        rng: GestureRng,
        holdMs: Long? = null,
    ): TouchGesture? {
        val r = rngFor(style, rng)
        val natural = style.natural
        val start = HandPlacement.tapPoint(from, viewport, handedness, r, natural)
        val end = if (toTarget != null) {
            HandPlacement.safeArea(toTarget, viewport) ?: return null
            HandPlacement.tapPoint(toTarget, viewport, handedness, r, natural)
        } else {
            viewport.insetCapped(2f).clamp(to)
        }
        val distance = start.distanceTo(end)
        if (distance < 24f) return null
        val pickup = (holdMs ?: if (natural) 580L + (r.nextUnit() * 200.0).roundToLong() else 650L)
            .coerceIn(DRAG_PICKUP_MIN_MS, DRAG_PICKUP_MAX_MS)
        // A finger held still still rolls a pixel or so, far below the touch slop that would cancel the long press.
        val settled = if (natural) {
            val angle = r.nextUnit() * 2.0 * PI
            viewport.insetCapped(1f).clamp(GesturePoint(start.x + cos(angle).toFloat() * 0.9f, start.y + sin(angle).toFloat() * 0.9f))
        } else start
        // Drags are deliberate: slower than a swipe of the same length, and slower still for long carries.
        val moveMs = ((330.0 + distance * 0.55) * (if (natural) 0.9 + r.nextUnit() * 0.25 else 1.0)).roundToLong().coerceIn(320L, 2_400L)
        val carried = HumanMotion.planStroke(settled, end, viewport, profileFor(style), StrokeEnding.HOLD, moveMs, handedness, r)
        val dropRest = if (natural) 120L + (r.nextUnit() * 140.0).roundToLong() else 100L
        val points = ArrayList<TimedPoint>()
        points += TimedPoint(start.x, start.y, 0L)
        points += TimedPoint(settled.x, settled.y, pickup)
        for (index in 1 until carried.points.size) {
            val p = carried.points[index]
            points += TimedPoint(p.x, p.y, pickup + p.tMs)
        }
        val last = points.last()
        points += TimedPoint(last.x, last.y, last.tMs + dropRest)
        val motion = MotionPlan(points, carried.shape, StrokeEnding.HOLD, profileFor(style))
        return TouchGesture(
            TouchGestureKind.DRAG,
            listOf(FingerStroke(0L, motion)),
            simultaneous = false,
            facts = mapOf("pickupMs" to pickup, "moveMs" to carried.durationMs, "dropRestMs" to dropRest,
                "shape" to carried.shape.name.lowercase(), "distancePx" to distance.toInt()),
        )
    }

    // ---- pinch -------------------------------------------------------------------------------------------------------

    /**
     * Two fingers on [area]: apart for [scale] > 1 (zoom in), together for [scale] < 1 (zoom out). The fingers lie on
     * a slanted axis like a thumb and index finger, move at slightly different speeds (the thumb moves less and a
     * beat later), turn the axis by a few degrees, and rest before lifting. The scale actually planned is reported as
     * `achievedScale` (the area may be too small for the full request).
     *
     * @param focus optional point (0..1 in the area) to zoom around; default its middle.
     */
    fun pinch(
        area: GestureBounds,
        viewport: GestureBounds,
        scale: Double,
        focus: GesturePoint?,
        style: HandsStyle,
        handedness: Handedness,
        rng: GestureRng,
    ): TouchGesture? {
        if (!scale.isFinite() || scale !in PINCH_SCALE_MIN..PINCH_SCALE_MAX || abs(scale - 1.0) < 0.1) return null
        val r = rngFor(style, rng)
        val natural = style.natural
        val region = area.intersect(safeScreen(viewport))?.insetCapped(6f) ?: return null
        val zoomIn = scale > 1.0
        val fx = (focus?.x ?: 0.5f).coerceIn(0.1f, 0.9f) + if (natural) (r.nextSignedUnit() * 0.05).toFloat() else 0f
        val fy = (focus?.y ?: 0.5f).coerceIn(0.1f, 0.9f) + if (natural) (r.nextSignedUnit() * 0.05).toFloat() else 0f
        val center = region.clamp(GesturePoint(region.left + region.width * fx, region.top + region.height * fy))
        // Thumb below-left and index above-right for a right hand (mirrored for a left hand).
        val axisDeg = if (natural) 50.0 + r.nextUnit() * 22.0 else 55.0
        val axis = Math.toRadians(if (handedness == Handedness.RIGHT) -axisDeg else -(180.0 - axisDeg))
        val rotationDeg = if (natural) (2.0 + r.nextUnit() * 4.0) * (if (r.nextUnit() < 0.5) -1.0 else 1.0) else 0.0
        val thumbShare = if (natural) 0.34 + r.nextUnit() * 0.14 else 0.5
        val thumbLag = if (natural) 0.03 + r.nextUnit() * 0.06 else 0.0
        val durationMs = ((if (natural) 380.0 + r.nextUnit() * 220.0 else 450.0)).roundToLong()
        val restMs = if (natural) 60L + (r.nextUnit() * 70.0).roundToLong() else 60L

        val reach = maxReach(center, axis, region)
        val minRadius = MIN_FINGER_GAP_PX / 2f
        if (reach < minRadius * 1.25f) return null
        // Separations at the start and end (fingertip to fingertip).
        var startGap: Double
        var endGap: Double
        val maxGap = 2.0 * reach
        val minGap = 2.0 * minRadius
        if (zoomIn) {
            startGap = (minGap * (if (natural) 1.0 + r.nextUnit() * 0.5 else 1.2)).coerceAtMost(maxGap / 1.15)
            endGap = (startGap * scale).coerceAtMost(maxGap)
        } else {
            startGap = maxGap * (if (natural) 0.8 + r.nextUnit() * 0.15 else 0.9)
            endGap = (startGap * scale).coerceAtLeast(minGap)
        }
        if (abs(endGap - startGap) < 20.0) return null

        val pieces = 12
        repeat(4) { attempt ->
            val rotation = Math.toRadians(rotationDeg) * (1.0 - attempt * 0.34)
            val index = ArrayList<TimedPoint>(pieces + 2)
            val thumb = ArrayList<TimedPoint>(pieces + 2)
            var fits = true
            for (step in 0..pieces) {
                val tau = step.toDouble() / pieces
                val t = (tau * durationMs).roundToLong()
                val pIndex = HumanMotion.progress(StrokeEnding.GLIDE, tau)
                val pThumb = HumanMotion.progress(StrokeEnding.GLIDE, ((tau - thumbLag) / (1.0 - thumbLag)).coerceIn(0.0, 1.0))
                val change = endGap - startGap
                val indexRadius = startGap / 2.0 + change * (1.0 - thumbShare) * pIndex
                val thumbRadius = startGap / 2.0 + change * thumbShare * pThumb
                val angle = axis + rotation * pIndex
                val ip = GesturePoint((center.x + cos(angle) * indexRadius).toFloat(), (center.y + sin(angle) * indexRadius).toFloat())
                val tp = GesturePoint((center.x - cos(angle) * thumbRadius).toFloat(), (center.y - sin(angle) * thumbRadius).toFloat())
                if (!region.contains(ip) || !region.contains(tp) || ip.distanceTo(tp) < MIN_FINGER_GAP_PX * 0.95f) fits = false
                index += TimedPoint(ip.x, ip.y, t)
                thumb += TimedPoint(tp.x, tp.y, t)
            }
            if (!fits) {
                endGap = startGap + (endGap - startGap) * 0.85
                return@repeat
            }
            index += index.last().copy(tMs = durationMs + restMs)
            thumb += thumb.last().copy(tMs = durationMs + restMs)
            val profile = profileFor(style)
            val achieved = endGap / startGap
            return TouchGesture(
                TouchGestureKind.PINCH,
                listOf(
                    FingerStroke(0L, MotionPlan(index, StrokeShape.STRAIGHT, StrokeEnding.HOLD, profile)),
                    FingerStroke(0L, MotionPlan(thumb, StrokeShape.STRAIGHT, StrokeEnding.HOLD, profile)),
                ),
                simultaneous = true,
                facts = mapOf(
                    "direction" to if (zoomIn) "in" else "out",
                    "requestedScale" to round2(scale),
                    "achievedScale" to round2(achieved),
                    "rotationDeg" to round2(Math.toDegrees(rotation)),
                    "durationMs" to durationMs,
                ),
            )
        }
        return null
    }

    /** How far from [center] along both directions of [axis] a finger can go and stay in [region]. */
    private fun maxReach(center: GesturePoint, axis: Double, region: GestureBounds): Float {
        val dx = cos(axis)
        val dy = sin(axis)
        fun along(sign: Double): Double {
            var limit = Double.MAX_VALUE
            val vx = dx * sign
            val vy = dy * sign
            if (vx > 1e-9) limit = min(limit, (region.right - center.x) / vx)
            if (vx < -1e-9) limit = min(limit, (center.x - region.left) / -vx)
            if (vy > 1e-9) limit = min(limit, (region.bottom - center.y) / vy)
            if (vy < -1e-9) limit = min(limit, (center.y - region.top) / -vy)
            return limit
        }
        // The axis turns a little during the pinch: keep a small margin for it.
        return (min(along(1.0), along(-1.0)) * 0.94).toFloat().coerceAtLeast(0f)
    }

    // ---- drawing -----------------------------------------------------------------------------------------------------

    /**
     * Draw [shape], or the caller's normalised [strokes] (0..1 across the canvas, at most [DRAW_MAX_STROKES] of at
     * most [DRAW_MAX_POINTS] points), inside [canvas] only. Each stroke is timed with a pen's speed curve; the pen
     * lifts briefly between strokes. Every point stays inside the canvas.
     */
    fun draw(
        canvas: GestureBounds,
        viewport: GestureBounds,
        shape: DrawShape?,
        strokes: List<List<GesturePoint>>?,
        style: HandsStyle,
        rng: GestureRng,
    ): TouchGesture? {
        val r = rngFor(style, rng)
        val natural = style.natural
        val box = canvas.intersect(viewport)?.let { it.insetCapped(min(it.width, it.height) * 0.06f) } ?: return null
        if (box.width < 40f || box.height < 24f) return null
        val normalised: List<List<GesturePoint>> = when {
            shape != null -> shapeStrokes(shape, natural, r)
            strokes != null -> {
                if (strokes.isEmpty() || strokes.size > DRAW_MAX_STROKES) return null
                if (strokes.any { it.size < 2 || it.size > DRAW_MAX_POINTS }) return null
                if (strokes.flatten().any { it.x !in 0f..1f || it.y !in 0f..1f }) return null
                strokes
            }
            else -> return null
        }
        // A person's copy of a shape is never exact: a little smaller or larger, a little turned.
        val sizeFactor = if (natural) 0.9f + r.nextUnit().toFloat() * 0.1f else 1f
        val turn = if (natural) Math.toRadians(r.nextSignedUnit() * 3.0) else 0.0
        val placed = normalised.map { stroke ->
            val mapped = stroke.map { p ->
                val cx = (p.x - 0.5f) * sizeFactor
                val cy = (p.y - 0.5f) * sizeFactor
                val rx = (cx * cos(turn) - cy * sin(turn)).toFloat()
                val ry = (cx * sin(turn) + cy * cos(turn)).toFloat()
                box.clamp(GesturePoint(box.left + box.width * (0.5f + rx), box.top + box.height * (0.5f + ry)))
            }
            if (natural) tremor(mapped, box, r) else mapped
        }
        val profile = profileFor(style)
        val out = ArrayList<FingerStroke>(placed.size)
        var at = 0L
        for ((index, path) in placed.withIndex()) {
            val clean = path.zipWithNext().filter { (a, b) -> a.distanceTo(b) > 0.01f }.map { it.second }
                .let { listOf(path.first()) + it }
            if (clean.size < 2) return null
            val length = clean.zipWithNext().sumOf { (a, b) -> a.distanceTo(b).toDouble() }
            // About 0.7–1.2 px/ms for a fingertip drawing carefully.
            val pace = if (natural) 0.85 + r.nextUnit() * 0.35 else 1.0
            val duration = ((180.0 + length * 0.95) * pace).roundToLong().coerceIn(160L, 3_000L)
            val motion = HumanMotion.timePath(clean, duration, StrokeEnding.GLIDE, profile)
            out += FingerStroke(at, motion)
            val lift = if (natural) 140L + (r.nextUnit() * 140.0).roundToLong() else 150L
            at += motion.durationMs + if (index < placed.lastIndex) lift else 0L
        }
        return TouchGesture(
            TouchGestureKind.DRAW,
            out,
            simultaneous = false,
            facts = mapOf("shape" to (shape?.wire ?: "strokes"), "strokes" to out.size,
                "points" to out.sumOf { it.motion.points.size }),
        )
    }

    /** A fingertip wobbles a little: smooth, sub-percent sideways noise along the stroke, kept inside the canvas. */
    private fun tremor(path: List<GesturePoint>, box: GestureBounds, rng: GestureRng): List<GesturePoint> {
        if (path.size < 3) return path
        val amplitude = min(box.width, box.height) * 0.006f
        val phase = rng.nextUnit() * 2.0 * PI
        val cycles = 2.0 + rng.nextUnit() * 3.0
        return path.mapIndexed { index, p ->
            if (index == 0 || index == path.lastIndex) return@mapIndexed p
            val prev = path[index - 1]
            val next = path[index + 1]
            val dx = next.x - prev.x
            val dy = next.y - prev.y
            val len = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1e-3f)
            val wobble = (sin(phase + cycles * 2.0 * PI * index / path.lastIndex) * amplitude).toFloat()
            box.clamp(GesturePoint(p.x - dy / len * wobble, p.y + dx / len * wobble))
        }
    }

    /** The named shapes as normalised strokes (0..1 across the canvas). */
    fun shapeStrokes(shape: DrawShape, natural: Boolean, rng: GestureRng): List<List<GesturePoint>> {
        val r = if (natural) rng else FixedGestureRng
        fun pt(x: Double, y: Double) = GesturePoint(x.coerceIn(0.0, 1.0).toFloat(), y.coerceIn(0.0, 1.0).toFloat())
        return when (shape) {
            DrawShape.CIRCLE -> {
                // Right-handed people mostly draw circles counter-clockwise from the top, and overlap the start a bit.
                val start = -PI / 2 + r.nextSignedUnit() * 0.35
                val turns = 1.0 + r.nextUnit() * 0.08
                val rx = 0.42 * (0.94 + r.nextUnit() * 0.08)
                val ry = 0.42 * (0.94 + r.nextUnit() * 0.08)
                listOf((0..48).map { i ->
                    val a = start - 2.0 * PI * turns * i / 48
                    pt(0.5 + cos(a) * rx, 0.5 + sin(a) * ry)
                })
            }
            DrawShape.CHECK -> {
                val dip = 0.78 + r.nextSignedUnit() * 0.04
                listOf(listOf(pt(0.12, 0.52), pt(0.24, 0.64), pt(0.36, dip), pt(0.42, dip - 0.04), pt(0.6, 0.48), pt(0.76, 0.3), pt(0.9, 0.16)))
            }
            DrawShape.UNDERLINE -> {
                val slant = r.nextSignedUnit() * 0.04
                val bow = r.nextSignedUnit() * 0.03
                listOf((0..16).map { i ->
                    val u = i / 16.0
                    pt(0.06 + 0.88 * u, 0.6 + slant * (u - 0.5) + bow * 4 * u * (1 - u))
                })
            }
            DrawShape.ZIGZAG -> {
                val peaks = 5 + (r.nextUnit() * 3).toInt()
                listOf((0..peaks * 2).map { i ->
                    val u = i / (peaks * 2.0)
                    pt(0.06 + 0.88 * u, if (i % 2 == 0) 0.68 + r.nextSignedUnit() * 0.03 else 0.32 + r.nextSignedUnit() * 0.03)
                })
            }
            DrawShape.SCRIBBLE -> {
                val loops = 4 + (r.nextUnit() * 3).toInt()
                listOf((0..56).map { i ->
                    val u = i / 56.0
                    val a = 2.0 * PI * loops * u
                    pt(0.12 + 0.76 * u + 0.07 * sin(a), 0.5 + 0.22 * cos(a) * (0.8 + 0.2 * sin(3 * a)))
                })
            }
            DrawShape.SIGNATURE -> {
                // A looping line that reads as handwriting, then a quick underline: generic, never a real person's.
                val loops = 5 + (r.nextUnit() * 3).toInt()
                val main = (0..60).map { i ->
                    val u = i / 60.0
                    val a = 2.0 * PI * loops * u
                    pt(0.08 + 0.8 * u - 0.035 * sin(a), 0.48 - 0.16 * sin(a * 0.5 + 0.3) * (1.0 - 0.4 * u) + 0.09 * cos(a))
                }
                val flourish = (0..10).map { i ->
                    val u = i / 10.0
                    pt(0.2 + 0.68 * u, 0.78 - 0.05 * u + 0.02 * sin(PI * u))
                }
                listOf(main, flourish)
            }
        }
    }

    private fun round2(value: Double): Double = (value * 100.0).roundToLong() / 100.0

    /** Heading of a stroke in degrees, used by tests and the lab. */
    fun headingDeg(from: GesturePoint, to: GesturePoint): Double = Math.toDegrees(atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble()))

    /** The longest gap between consecutive fingertips of a simultaneous gesture, used by tests. */
    fun fingerGap(gesture: TouchGesture, pointIndex: Int): Float {
        require(gesture.simultaneous && gesture.strokes.size == 2)
        val a = gesture.strokes[0].motion.points[pointIndex].point
        val b = gesture.strokes[1].motion.points[pointIndex].point
        return a.distanceTo(b)
    }
}
