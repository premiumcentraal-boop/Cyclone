package com.cyclone.mobile.ui.overlay

import android.view.WindowManager
import com.cyclone.mobile.ai.OverlayChromeWindowPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Alpha.134: the card is drawn untouchable; the owner's touch reaches it through an empty layer. */
class OverlayTouchLayerTest {
    @Test
    fun theDrawnCardNeverTakesATouchButKeepsItsFocusAndSize() {
        for (spec in listOf(OverlayChromeWindowPolicy.main(compact = false), OverlayChromeWindowPolicy.minimizedComposer(), OverlayChromeWindowPolicy.glass())) {
            val drawn = OverlayChromeWindowPolicy.drawn(spec)
            assertTrue(drawn.notTouchable)
            assertEquals(spec.copy(notTouchable = true), drawn)
            val flags = OverlayChromeWindowPolicy.flags(drawn)
            assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertEquals("focus is unchanged: the owner can still type", spec.notFocusable, flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            // The live view must keep mirroring the card: only the Secrets Card is secure.
            assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
        }
    }

    @Test
    fun theTouchLayerTakesTouchesButNeverFocusOrTheScreenAroundIt() {
        val flags = OverlayChromeWindowPolicy.touchLayerFlags()
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
    }

    @Test
    fun aStrokeOnTheGlassMovesOnlyTheEmptyLayer() {
        assertTrue(OverlayTouchForward.shown(cardVisible = true, compact = false, strokeOnGlass = false, laidOut = true))
        assertFalse("the agent's stroke passes", OverlayTouchForward.shown(cardVisible = true, compact = false, strokeOnGlass = true, laidOut = true))
        assertFalse("the idle hotspot is its own empty window", OverlayTouchForward.shown(cardVisible = true, compact = true, strokeOnGlass = false, laidOut = true))
        assertFalse("no card, no layer", OverlayTouchForward.shown(cardVisible = false, compact = false, strokeOnGlass = false, laidOut = true))
        assertFalse("up once the card has a size", OverlayTouchForward.shown(cardVisible = true, compact = false, strokeOnGlass = false, laidOut = false))
    }

    @Test
    fun aTouchLandsWhereTheCardIsEvenMidGlide() {
        assertEquals(0f to 0f, OverlayTouchForward.offset(intArrayOf(0, 1800), intArrayOf(0, 1800)))
        // The card already moved up 40 px with the keyboard; the layer follows a frame later.
        assertEquals(0f to 40f, OverlayTouchForward.offset(intArrayOf(0, 1800), intArrayOf(0, 1760)))
    }

    @Test
    fun aTouchArrivingWhileTheLayerShouldBeAsideIsDroppedAndCounted() {
        assertTrue(OverlayTouchForward.stray(strokeOnGlass = true, down = true))
        assertFalse("only a new touch counts", OverlayTouchForward.stray(strokeOnGlass = true, down = false))
        assertFalse(OverlayTouchForward.stray(strokeOnGlass = false, down = true))
        OverlayGesturePassthrough.resetForTests()
        assertEquals(1, OverlayGesturePassthrough.stray())
        assertEquals(1, OverlayGesturePassthrough.strayCount())
        OverlayGesturePassthrough.resetForTests()
        assertEquals(0, OverlayGesturePassthrough.strayCount())
    }

    @Test
    fun aStrokeThatReachedTheLayerIsReportedOnceForThatStroke() {
        OverlayGesturePassthrough.resetForTests()
        OverlayGesturePassthrough.regions { listOf(intArrayOf(0, 1600, 1080, 2400)) }
        OverlayGesturePassthrough.withHostStroke(floatArrayOf(500f, 1700f, 520f, 1720f)) {
            assertTrue(OverlayGesturePassthrough.hidden())
            OverlayGesturePassthrough.stray()
        }
        assertTrue(OverlayGesturePassthrough.consumeStrayed())
        assertFalse("read once", OverlayGesturePassthrough.consumeStrayed())
        OverlayGesturePassthrough.withHostStroke(floatArrayOf(500f, 1700f, 520f, 1720f)) {}
        assertFalse("a clean stroke", OverlayGesturePassthrough.consumeStrayed())
        OverlayGesturePassthrough.resetForTests()
    }
}
