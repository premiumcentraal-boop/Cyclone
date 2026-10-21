package com.cyclone.mobile.ui.overlay

import android.view.WindowManager
import com.cyclone.mobile.ai.OverlayChromeWindowPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayAskBarPolicyTest {
    @Test
    fun composerBottomGapIsThirtyDp() {
        assertEquals(30, OverlayChromeContract.COMPOSER_BOTTOM_GAP_DP)
    }

    @Test
    fun askBarIsTwelveDpHigherThanLegacyEighteen() {
        assertEquals(12, OverlayChromeContract.COMPOSER_BOTTOM_GAP_DP - 18)
    }

    @Test
    fun glassDoesNotStealFocusOrBlockInstagramScrollOutsideThePanel() {
        val glass = OverlayChromeWindowPolicy.glass()
        assertTrue(glass.notFocusable)
        assertTrue(glass.notTouchModal)
        assertFalse(glass.notTouchable)
    }

    @Test
    fun glassFlagsAllowHumanScrollOnInstagram() {
        val flags = OverlayChromeWindowPolicy.flags(OverlayChromeWindowPolicy.glass())
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
    }

    @Test
    fun compactIdleIsNotFullWidthAndDoesNotBlockInstagram() {
        val compact = OverlayChromeWindowPolicy.main(compact = true)
        assertFalse(compact.matchParentWidth)
    }
}
