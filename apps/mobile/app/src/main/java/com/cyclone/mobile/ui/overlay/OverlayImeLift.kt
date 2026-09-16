package com.cyclone.mobile.ui.overlay

import androidx.compose.runtime.compositionLocalOf

/**
 * Overlay windows do not resize with the IME the way activities do. Gravity.BOTTOM + y is the
 * lift: rest above the nav bar, then ride 8dp above the keyboard once it reaches the composer.
 */
internal object OverlayImeLift {
    const val KEYBOARD_GAP_DP = 8

    fun windowY(
        followKeyboard: Boolean,
        specBottomMarginPx: Int,
        imeBottomPx: Int,
        navigationBottomPx: Int,
        restGapPx: Int,
        keyboardGapPx: Int,
    ): Int {
        if (!followKeyboard) return specBottomMarginPx.coerceAtLeast(0)
        val rest = (navigationBottomPx + restGapPx).coerceAtLeast(0)
        val keyed = (imeBottomPx + keyboardGapPx).coerceAtLeast(0)
        return maxOf(rest, keyed)
    }
}

internal val LocalOverlayImeBottomPx = compositionLocalOf { 0 }
