package com.cyclone.mobile.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayImeLiftTest {
    @Test
    fun compactAndGlassKeepTheirOwnMargin() {
        assertEquals(
            84,
            OverlayImeLift.windowY(
                followKeyboard = false,
                specBottomMarginPx = 84,
                imeBottomPx = 800,
                navigationBottomPx = 128,
                restGapPx = 78,
                keyboardGapPx = 24,
            ),
        )
    }

    @Test
    fun expandedRestsAboveTheNavBarUntilTheKeyboardReachesIt() {
        val rest = OverlayImeLift.windowY(
            followKeyboard = true,
            specBottomMarginPx = 0,
            imeBottomPx = 0,
            navigationBottomPx = 128,
            restGapPx = 78,
            keyboardGapPx = 24,
        )
        assertEquals(206, rest)
        assertEquals(
            206,
            OverlayImeLift.windowY(
                followKeyboard = true,
                specBottomMarginPx = 0,
                imeBottomPx = 100,
                navigationBottomPx = 128,
                restGapPx = 78,
                keyboardGapPx = 24,
            ),
        )
    }

    @Test
    fun expandedRidesEightDpAboveTheKeyboard() {
        assertEquals(
            424,
            OverlayImeLift.windowY(
                followKeyboard = true,
                specBottomMarginPx = 0,
                imeBottomPx = 400,
                navigationBottomPx = 128,
                restGapPx = 78,
                keyboardGapPx = 24,
            ),
        )
    }
}
