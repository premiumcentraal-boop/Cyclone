package com.cyclone.mobile.ui.overlay

import android.view.WindowManager
import com.cyclone.mobile.ai.OverlayChromeWindowPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayAskBarPolicyTest {
    private fun source(path: String) = sequenceOf(
        File("src/main/java/com/cyclone/mobile/$path"),
        File("apps/mobile/app/src/main/java/com/cyclone/mobile/$path"),
    ).first { it.isFile }.readText()

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

    @Test
    fun activeSwipeDownUsesTheSameCompactTaskGlassAsAutomaticCollapse() {
        val overlay = source("ui/overlay/OverlayChrome.kt")
        assertFalse(overlay.contains("taskCollapsed"))
        assertTrue(overlay.contains("if (dismiss) onAction(OverlayUserAction.MINIMIZE)"))
    }

    @Test
    fun fullScreenCycloneActivitiesOwnTheScreenWithoutOverlayOrBorderCompetition() {
        val controller = source("ai/OverlayChromeController.kt")
        val workspace = source("runtime/background/WorkspaceActivity.kt")
        assertTrue(controller.contains("!OverlayExternalInteraction.active.value"))
        assertTrue(controller.contains("val externalActive by OverlayExternalInteraction.active.collectAsState()"))
        assertTrue(workspace.contains("override fun onStart()"))
        assertTrue(workspace.contains("OverlayExternalInteraction.active.value = true"))
        assertTrue(workspace.contains("override fun onStop()"))
        assertTrue(workspace.contains("OverlayExternalInteraction.active.value = false"))
    }
}
