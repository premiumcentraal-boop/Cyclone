package com.cyclone.mobile.ui.overlay

import java.util.concurrent.atomic.AtomicInteger

/**
 * Yield Cyclone overlay windows for an already-authorized host
 * [android.accessibilityservice.AccessibilityService.dispatchGesture].
 *
 * TYPE_ACCESSIBILITY_OVERLAY still receives accessibility gestures when it is visible and
 * important-for-accessibility, even with FLAG_NOT_TOUCHABLE. The idle ball and expanded Ask
 * card must leave the hit tree (GONE + not important) for the stroke, then come back.
 * Overlay buttons still never click host nodes. Stop is unusable only for that stroke.
 */
object OverlayGesturePassthrough {
    private val depth = AtomicInteger(0)
    @Volatile private var enabled = false
    @Volatile private var applyFlags: ((Boolean) -> Unit)? = null

    fun active(): Boolean = enabled

    fun bind(applyFlags: (Boolean) -> Unit) {
        this.applyFlags = applyFlags
    }

    fun unbind() {
        applyFlags = null
    }

    fun <T> withHostPassthrough(block: () -> T): T {
        val entered = depth.getAndIncrement() == 0
        if (entered) {
            enabled = true
            applyFlags?.invoke(true)
        }
        try {
            return block()
        } finally {
            if (depth.decrementAndGet() == 0) {
                enabled = false
                applyFlags?.invoke(false)
            }
        }
    }

    internal fun resetForTests() {
        depth.set(0)
        enabled = false
        applyFlags = null
    }
}
