package com.cyclone.mobile.ui.overlay
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import org.junit.Assert.*
import org.junit.Test
class BackgroundGlassPolicyTest {
    private val task = WorkspaceTaskUi("task", "owned", "Shop", "shop", "Order tea")
    @Test fun lifecycleAndProgressUseExactTask() {
        assertFalse(BackgroundGlassPolicy.visible(null))
        for (phase in listOf(TaskPhase.STARTING, TaskPhase.WORKING, TaskPhase.DONE, TaskPhase.REVIEW, TaskPhase.HUMAN))
            assertTrue(BackgroundGlassPolicy.visible(task.copy(phase = phase)))
        val tick = task.copy(message = "Checking the order")
        assertEquals("owned", tick.sessionId)
        assertEquals("Checking the order", tick.message)
        for (phase in listOf(TaskPhase.FAILED, TaskPhase.STOPPED)) {
            assertFalse(BackgroundGlassPolicy.visible(task.copy(phase = phase)))
            assertTrue(BackgroundGlassPolicy.tearDown(task.copy(phase = phase)))
        }
    }
    @Test fun failedPartialAttachAndRepeatedDismissLeaveZeroWindows() {
        val removed = mutableListOf<String>()
        val windows = OverlayWindowRegistry<String> { removed += it }
        windows.attached("halo"); windows.attached("composer"); windows.attached("share")
        windows.clear(); windows.clear()
        assertEquals(0, windows.size)
        assertEquals(listOf("halo", "composer", "share"), removed)
        windows.attached("fresh-composer"); windows.clear()
        assertEquals(0, windows.size)
    }
    @Test fun barIsTwelveDpHigher() { assertEquals(12, OverlayChromeContract.COMPOSER_BOTTOM_GAP_DP - 18) }
}
