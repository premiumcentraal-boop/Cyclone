package com.cyclone.mobile.gesture

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Which way the finger travels. A finger moving UP scrolls content forward (shows what is below). */
enum class SwipeDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT;

    val vertical: Boolean get() = this == UP || this == DOWN

    companion object {
        fun parse(raw: String?): SwipeDirection? = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}

/** How far the finger travels, relative to the area it swipes in. */
enum class SwipeAmount {
    PEEK,
    HALF,
    PAGE,
    FAR;

    companion object {
        fun parse(raw: String?): SwipeAmount? = when (raw?.trim()?.lowercase()) {
            null, "" -> null
            "short", "peek", "little", "small" -> PEEK
            "half", "medium" -> HALF
            "page", "long", "normal" -> PAGE
            "far", "max", "full" -> FAR
            else -> null
        }
    }
}

/** How the finger moves: a slow controlled glide, an ordinary swipe, or a quick flick. */
enum class SwipeSpeed {
    GENTLE,
    NORMAL,
    FLICK;

    companion object {
        fun parse(raw: String?): SwipeSpeed? = when (raw?.trim()?.lowercase()) {
            null, "" -> null
            "gentle", "slow", "careful" -> GENTLE
            "normal" -> NORMAL
            "flick", "fast", "quick" -> FLICK
            else -> null
        }
    }
}

data class SwipeIntent(
    val direction: SwipeDirection,
    val amount: SwipeAmount = SwipeAmount.PAGE,
    val speed: SwipeSpeed = SwipeSpeed.NORMAL,
    /** The area to swipe in (a grounded element's bounds, or the screen). */
    val region: GestureBounds,
)

data class PlannedSwipe(
    val start: GesturePoint,
    val end: GesturePoint,
    val durationMs: Long,
    val ending: StrokeEnding,
)

/**
 * Plan 52 run 2: a model of the hand holding the phone. Callers ask for an intent ("swipe up a page in the feed");
 * the hand model picks where the thumb lands, how far it travels, how long it takes and how it lifts, so no two
 * swipes start at the same spot or take the same time. The motion planner then shapes and times the path.
 *
 * Swipes keep clear of the system gesture edges: the bottom navigation strip, the status bar and the side edges
 * that mean "back" on gesture navigation.
 */
object HandModel {
    /** Fractions of the viewport kept clear of system gestures. */
    private const val EDGE_X = 0.07f
    private const val EDGE_TOP = 0.05f
    private const val EDGE_BOTTOM = 0.08f
    private const val MIN_TRAVEL_PX = 24f

    /**
     * [varied] = false (Precise hands) gives the fixed V0.3 geometry: centred, the middle of the amount's range and
     * 350 ms, the same every time.
     */
    fun plan(
        intent: SwipeIntent,
        viewport: GestureBounds,
        handedness: Handedness,
        rng: GestureRng,
        varied: Boolean = true,
    ): PlannedSwipe? {
        @Suppress("NAME_SHADOWING")
        val rng: GestureRng = if (varied) rng else FixedGestureRng
        val safeScreen = GestureBounds(
            viewport.left + viewport.width * EDGE_X,
            viewport.top + viewport.height * EDGE_TOP,
            viewport.right - viewport.width * EDGE_X,
            viewport.bottom - viewport.height * EDGE_BOTTOM,
        )
        val area = intent.region.intersect(safeScreen)?.insetCapped(min(intent.region.width, intent.region.height) * 0.06f)
            ?: return null
        if (area.width < 8f || area.height < 8f) return null

        val along = if (intent.direction.vertical) area.height else area.width
        val (low, high) = when (intent.amount) {
            SwipeAmount.PEEK -> 0.22 to 0.34
            SwipeAmount.HALF -> 0.38 to 0.50
            SwipeAmount.PAGE -> 0.55 to 0.70
            SwipeAmount.FAR -> 0.70 to 0.84
        }
        val travel = (along * (low + rng.nextUnit() * (high - low))).toFloat().coerceAtMost(along * 0.92f)
        if (travel < MIN_TRAVEL_PX) return null

        // Across the swipe: where the thumb naturally rests in this area for the holding hand.
        val crossRoll = rng.nextUnit()
        val crossFraction = if (!varied) {
            0.5
        } else if (intent.direction.vertical) {
            if (handedness == Handedness.RIGHT) 0.52 + crossRoll * 0.22 else 0.26 + crossRoll * 0.22
        } else {
            0.42 + crossRoll * 0.24
        }
        // Along the swipe: the start sits so the travel fits, with some slack used at random.
        val slack = (along - travel).coerceAtLeast(0f)
        val startOffset = slack * (0.25f + rng.nextUnit().toFloat() * 0.6f)
        // A thumb does not travel perfectly straight: the end drifts sideways a little.
        val drift = if (varied) (rng.nextSignedUnit() * travel * 0.05).toFloat() else 0f

        val start: GesturePoint
        val end: GesturePoint
        when (intent.direction) {
            SwipeDirection.UP -> {
                val x = area.left + area.width * crossFraction.toFloat()
                val y0 = area.bottom - startOffset
                start = GesturePoint(x, y0)
                end = GesturePoint(x + drift, y0 - travel)
            }
            SwipeDirection.DOWN -> {
                val x = area.left + area.width * crossFraction.toFloat()
                val y0 = area.top + startOffset
                start = GesturePoint(x, y0)
                end = GesturePoint(x + drift, y0 + travel)
            }
            SwipeDirection.LEFT -> {
                val y = area.top + area.height * crossFraction.toFloat()
                val x0 = area.right - startOffset
                start = GesturePoint(x0, y)
                end = GesturePoint(x0 - travel, y + drift)
            }
            SwipeDirection.RIGHT -> {
                val y = area.top + area.height * crossFraction.toFloat()
                val x0 = area.left + startOffset
                start = GesturePoint(x0, y)
                end = GesturePoint(x0 + travel, y + drift)
            }
        }
        val clampedStart = area.clamp(start)
        val clampedEnd = area.clamp(end)

        val distance = clampedStart.distanceTo(clampedEnd)
        val durationRoll = rng.nextUnit()
        if (!varied) {
            return PlannedSwipe(clampedStart, clampedEnd, 350L, StrokeEnding.FLICK)
        }
        val duration = when (intent.speed) {
            SwipeSpeed.GENTLE -> 420.0 + durationRoll * 330.0 + distance * 0.15
            SwipeSpeed.NORMAL -> 230.0 + durationRoll * 190.0 + distance * 0.10
            SwipeSpeed.FLICK -> 110.0 + durationRoll * 90.0 + distance * 0.05
        }.roundToLong().coerceIn(90L, 1_400L)
        val ending = when (intent.speed) {
            SwipeSpeed.GENTLE -> StrokeEnding.GLIDE
            SwipeSpeed.FLICK -> StrokeEnding.FLICK
            SwipeSpeed.NORMAL -> if (intent.amount == SwipeAmount.PEEK || intent.amount == SwipeAmount.HALF) StrokeEnding.GLIDE else StrokeEnding.FLICK
        }
        return PlannedSwipe(clampedStart, clampedEnd, max(duration, 90L), ending)
    }
}

/**
 * Plan 52: `phone.swipe` may carry an intent instead of coordinates:
 * `{direction: up|down|left|right, amount?: peek|half|page|far, speed?: gentle|normal|flick, region?: {left,top,right,bottom}}`.
 * The direction is the way the finger travels. The phone resolves it with [HandModel]; the old x1..y2 form still works.
 */
object SwipeIntents {
    fun isIntent(params: org.json.JSONObject): Boolean = params.has("direction") && !params.has("x1")

    /** Null with a reason when the intent is malformed; the planned swipe otherwise (or null when the area is too small). */
    fun resolve(
        params: org.json.JSONObject,
        viewport: GestureBounds,
        handedness: Handedness,
        rng: GestureRng,
        style: HandsStyle = Hands.style,
    ): Pair<PlannedSwipe?, String?> {
        val direction = SwipeDirection.parse(params.optString("direction"))
            ?: return null to "direction must be up, down, left or right"
        val amount = if (params.has("amount")) SwipeAmount.parse(params.optString("amount")) ?: return null to "amount must be peek, half, page or far"
            else SwipeAmount.PAGE
        val speed = if (params.has("speed")) SwipeSpeed.parse(params.optString("speed")) ?: return null to "speed must be gentle, normal or flick"
            else SwipeSpeed.NORMAL
        val region = params.optJSONObject("region")?.let { box ->
            val left = box.optDouble("left", Double.NaN)
            val top = box.optDouble("top", Double.NaN)
            val right = box.optDouble("right", Double.NaN)
            val bottom = box.optDouble("bottom", Double.NaN)
            if (listOf(left, top, right, bottom).any { !it.isFinite() } || right <= left || bottom <= top) {
                return null to "region must have left < right and top < bottom"
            }
            GestureBounds(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        } ?: viewport
        val planned = HandModel.plan(SwipeIntent(direction, amount, speed, region), viewport, handedness, rng, varied = style.natural)
            ?: return null to "that area is too small to swipe in"
        return planned to null
    }
}

/** Always the middle of every range: the RNG Precise hands use so a swipe is the same every time. */
object FixedGestureRng : GestureRng {
    override fun nextUnit(): Double = 0.5
}
