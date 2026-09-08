package com.cyclone.mobile

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.cyclone.mobile.gesture.AndroidGestureRenderer
import com.cyclone.mobile.gesture.GestureBounds
import com.cyclone.mobile.gesture.GesturePoint
import com.cyclone.mobile.gesture.HumanGestureEngine
import com.cyclone.mobile.gesture.HumanGestureRuntimePolicy
import com.cyclone.mobile.gesture.HumanGestureSeed
import com.cyclone.mobile.gesture.HumanizePreference
import com.cyclone.mobile.gesture.HumanizeProfile
import com.cyclone.mobile.gesture.RuntimeGestureKind
import java.util.concurrent.atomic.AtomicLong

/**
 * Android execution adapter for already-authorized physical touches.
 *
 * This object deliberately owns no GATE, stale-observation, duplicate, confirmation, control-owner,
 * or SessionContract decisions. Callers reach it only after those existing authorities have allowed
 * the mutation. Named-VD and Layer2 backends do not use this adapter in V0.2 because their current
 * input protocol accepts only start/end/duration rather than cubic paths.
 */
object HumanGestureDispatch {
    private val localOrdinal = AtomicLong(0L)

    fun tap(
        service: CycloneAccessibilityService,
        x: Float,
        y: Float,
        preference: HumanizePreference,
        kind: RuntimeGestureKind,
        commandId: String? = null,
        targetBounds: UiBounds? = null,
    ): Boolean {
        val profile = HumanGestureRuntimePolicy.resolve(preference, kind)
        if (profile == HumanizeProfile.OFF && targetBounds == null) {
            return legacyTap(service, x, y)
        }
        val viewport = viewport(service)
        val target = targetBounds?.takeIf { it.width > 0 && it.height > 0 }?.toGestureBounds()
            ?: pointBounds(x, y)
        val ordinal = localOrdinal.incrementAndGet()
        val plan = runCatching {
            HumanGestureEngine.planTap(
                target = target,
                viewport = viewport,
                profile = profile,
                seed = HumanGestureSeed.derive(commandId, kind.name, ordinal, floatArrayOf(x, y), 80L),
                preferredDurationMs = 80L,
            )
        }.getOrElse { return false }
        val path = AndroidGestureRenderer.tapPath(plan)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, plan.durationMs))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    fun longPress(
        service: CycloneAccessibilityService,
        x: Float,
        y: Float,
        durationMs: Long,
        preference: HumanizePreference,
        kind: RuntimeGestureKind,
        commandId: String? = null,
        targetBounds: UiBounds? = null,
    ): Boolean {
        val profile = HumanGestureRuntimePolicy.resolve(preference, kind)
        if (profile == HumanizeProfile.OFF && targetBounds == null) {
            return legacyLongPress(service, x, y, durationMs)
        }
        val viewport = viewport(service)
        val target = targetBounds?.takeIf { it.width > 0 && it.height > 0 }?.toGestureBounds()
            ?: pointBounds(x, y)
        val ordinal = localOrdinal.incrementAndGet()
        val tapPlan = runCatching {
            HumanGestureEngine.planTap(
                target = target,
                viewport = viewport,
                profile = profile,
                seed = HumanGestureSeed.derive(commandId, kind.name, ordinal, floatArrayOf(x, y), durationMs),
                preferredDurationMs = 80L,
            )
        }.getOrElse { return false }
        val path = AndroidGestureRenderer.tapPath(tapPlan)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(450L, 3_000L)))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    fun swipe(
        service: CycloneAccessibilityService,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
        preference: HumanizePreference,
        kind: RuntimeGestureKind,
        commandId: String? = null,
    ): Boolean {
        val profile = HumanGestureRuntimePolicy.resolve(preference, kind)
        if (profile == HumanizeProfile.OFF) {
            return legacySwipe(service, x1, y1, x2, y2, durationMs)
        }
        val viewport = viewport(service)
        val ordinal = localOrdinal.incrementAndGet()
        val plan = runCatching {
            HumanGestureEngine.planSwipe(
                start = GesturePoint(x1, y1),
                end = GesturePoint(x2, y2),
                viewport = viewport,
                profile = profile,
                seed = HumanGestureSeed.derive(
                    commandId,
                    kind.name,
                    ordinal,
                    floatArrayOf(x1, y1, x2, y2),
                    durationMs,
                ),
                preferredDurationMs = durationMs,
            )
        }.getOrElse { return false }
        val path = AndroidGestureRenderer.strokePath(plan)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, plan.durationMs))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun viewport(service: CycloneAccessibilityService): GestureBounds {
        val metrics = service.resources.displayMetrics
        return GestureBounds(0f, 0f, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
    }

    private fun pointBounds(x: Float, y: Float): GestureBounds = GestureBounds(
        left = x - 0.5f,
        top = y - 0.5f,
        right = x + 0.5f,
        bottom = y + 0.5f,
    )

    private fun UiBounds.toGestureBounds(): GestureBounds = GestureBounds(
        left.toFloat(),
        top.toFloat(),
        right.toFloat(),
        bottom.toFloat(),
    )

    private fun legacyTap(service: CycloneAccessibilityService, x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80L))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun legacyLongPress(
        service: CycloneAccessibilityService,
        x: Float,
        y: Float,
        durationMs: Long,
    ): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(450L, 3_000L)))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun legacySwipe(
        service: CycloneAccessibilityService,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
    ): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(100L, 3_000L)))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }
}
