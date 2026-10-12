package com.cyclone.mobile.gesture

import com.cyclone.mobile.UiNodeSnapshot
import com.cyclone.mobile.UiSnapshot

/**
 * Plan 52 run 2: whether a grounded click may be performed as a real finger tap instead of `ACTION_CLICK`.
 *
 * Cyclone picks ONE channel before acting, so "Unchanged is not a second click" still holds: a touch-first click
 * that changes nothing is reported as unchanged, never retried semantically (and the reverse).
 *
 * A touch is only chosen when it is as safe as the semantic click would be:
 * - the owner's Hands style is Natural or Relaxed and the caller did not ask for `humanize=off`;
 * - the target is visible, enabled, clickable itself, on screen and at least [MIN_SIZE_PX] in both directions;
 * - the deepest visible node under the tap area belongs to the target (nothing of the app covers it), and no
 *   control of its own inside it (a button within a row) sits under the tap area, since a finger would press that;
 * - no other window (another app, the keyboard, a system window) sits above the target's window there.
 * Otherwise the semantic click path runs as before.
 */
object TouchFirst {
    const val MIN_SIZE_PX = 32
    private const val TYPE_ACCESSIBILITY_OVERLAY = 4

    enum class Verdict(val reason: String) {
        TOUCH("touch_first"),
        STYLE_PRECISE("style_precise"),
        CALLER_OFF("humanize_off"),
        NOT_VISIBLE("not_visible"),
        TOO_SMALL("too_small"),
        OFF_SCREEN("off_screen"),
        COVERED_BY_APP("covered_in_app"),
        COVERED_BY_WINDOW("covered_by_window"),
        CHILD_CONTROL("child_control"),
        NOT_CLICKABLE("not_clickable"),
    }

    fun decide(
        snapshot: UiSnapshot,
        target: UiNodeSnapshot,
        style: HandsStyle,
        preference: HumanizePreference,
    ): Verdict {
        if (!style.natural) return Verdict.STYLE_PRECISE
        if (preference == HumanizePreference.OFF) return Verdict.CALLER_OFF
        if (!target.visibleToUser || !target.enabled) return Verdict.NOT_VISIBLE
        // A finger only does what ACTION_CLICK would when the target itself takes clicks (not a label beside a switch).
        if (!target.clickable) return Verdict.NOT_CLICKABLE
        val b = target.bounds
        if (b.width < MIN_SIZE_PX || b.height < MIN_SIZE_PX) return Verdict.TOO_SMALL
        if (b.left < 0 || b.top < 0 || b.right > snapshot.screenWidth || b.bottom > snapshot.screenHeight) return Verdict.OFF_SCREEN

        // The inner half of the target is where a tap may land; every probe point there must be the target's.
        val probes = listOf(
            b.centerX to b.centerY,
            b.left + b.width * 0.3f to b.top + b.height * 0.3f,
            b.left + b.width * 0.7f to b.top + b.height * 0.3f,
            b.left + b.width * 0.3f to b.top + b.height * 0.7f,
            b.left + b.width * 0.7f to b.top + b.height * 0.7f,
        )
        for ((x, y) in probes) {
            val xi = x.toInt()
            val yi = y.toInt()
            if (windowAbove(snapshot, target.windowId, xi, yi)) return Verdict.COVERED_BY_WINDOW
            val under = snapshot.nodes
                .filter { it.windowId == target.windowId && it.visibleToUser && it.bounds.width > 0 && it.bounds.height > 0 && it.bounds.contains(xi, yi) }
            val deepest = under.maxByOrNull { it.depth }
            if (deepest != null && !belongsTo(deepest, target)) return Verdict.COVERED_BY_APP
            val innerControl = under.any {
                it.path.startsWith(target.path + "/") && (it.clickable || it.longClickable || it.editable || it.checkable)
            }
            if (innerControl) return Verdict.CHILD_CONTROL
        }
        return Verdict.TOUCH
    }

    /** A touch-first press is at least LIGHT: AUTO resolves to LIGHT for taps, NORMAL stays NORMAL. */
    fun touchPreference(preference: HumanizePreference): HumanizePreference =
        if (preference == HumanizePreference.NORMAL) HumanizePreference.NORMAL else HumanizePreference.LIGHT

    /** True when another window (not Cyclone's own overlay) sits above [windowId] at the point. */
    fun windowAbove(snapshot: UiSnapshot, windowId: Int, x: Int, y: Int): Boolean {
        val own = snapshot.windows.firstOrNull { it.id == windowId } ?: return false
        return snapshot.windows.any { window ->
            window.id != windowId && window.type != TYPE_ACCESSIBILITY_OVERLAY && window.layer > own.layer && window.bounds.contains(x, y)
        }
    }

    private fun belongsTo(node: UiNodeSnapshot, target: UiNodeSnapshot): Boolean =
        node.path == target.path || node.path.startsWith(target.path + "/") || target.path.startsWith(node.path + "/")
}

/**
 * Plan 52 run 2: a scroll done with the thumb instead of `ACTION_SCROLL_FORWARD/BACKWARD`, chosen up front (one
 * channel). Only for an upright, visible, large-enough scrollable whose swipe would start on the list itself (not
 * on a nested carousel, not under another window). Otherwise null and the semantic scroll runs as before.
 */
object NaturalScroll {
    const val MIN_HEIGHT_PX = 240
    const val MIN_WIDTH_PX = 120

    fun plan(
        snapshot: UiSnapshot,
        node: UiNodeSnapshot,
        forward: Boolean,
        style: HandsStyle,
        preference: HumanizePreference,
        viewport: GestureBounds,
        handedness: Handedness,
        rng: GestureRng,
    ): PlannedSwipe? {
        if (!style.natural || preference == HumanizePreference.OFF) return null
        if (!node.visibleToUser || !node.scrollable) return null
        val b = node.bounds
        if (b.height < MIN_HEIGHT_PX || b.width < MIN_WIDTH_PX || b.width > b.height * 1.6f) return null
        val region = GestureBounds(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        val nested = snapshot.nodes.filter { it.scrollable && it.path.startsWith(node.path + "/") && it.visibleToUser }
        repeat(3) {
            val amount = if (rng.nextUnit() < 0.65) SwipeAmount.PAGE else SwipeAmount.HALF
            val intent = SwipeIntent(if (forward) SwipeDirection.UP else SwipeDirection.DOWN, amount, SwipeSpeed.NORMAL, region)
            val planned = HandModel.plan(intent, viewport, handedness, rng) ?: return null
            val sx = planned.start.x.toInt()
            val sy = planned.start.y.toInt()
            val onNested = nested.any { it.bounds.contains(sx, sy) }
            if (!onNested && !TouchFirst.windowAbove(snapshot, node.windowId, sx, sy)) return planned
        }
        return null
    }
}
