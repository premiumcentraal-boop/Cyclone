package com.cyclone.mobile.ui.overlay

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the exact multi-surface regressions reproduced from the 4.3.8 device screenshots. */
class VisualHierarchyRegressionTest {
    private fun source(path: String) = sequenceOf(
        File("src/main/java/com/cyclone/mobile/$path"),
        File("apps/mobile/app/src/main/java/com/cyclone/mobile/$path"),
    ).first { it.isFile }.readText()

    @Test
    fun currentTaskOwnsPrimarySurfaceInsteadOfStackingQueueBelowIt() {
        val pending = source("ui/v32/CyclonePendingRequests.kt")
        assertTrue(pending.contains("val currentTask by WorkspaceTasks.state.collectAsState()"))
        assertTrue(pending.contains("currentTask?.phase?.let { it != TaskPhase.STOPPED } == true"))
    }

    @Test
    fun destinationHandoffSuppressesOverlayBeforeLaunchingFullScreenPicker() {
        val pending = source("ui/v32/CyclonePendingRequests.kt")
        val workspace = source("runtime/background/WorkspaceActivity.kt")
        assertTrue(pending.contains("OverlayExternalInteraction.active.value = true"))
        assertTrue(workspace.contains("OverlayExternalInteraction.active.value = true"))
        assertTrue(workspace.contains("OverlayExternalInteraction.active.value = false"))
    }

    @Test
    fun installedAppPickerCannotGrowIntoAFullScreenList() {
        val workspace = source("runtime/background/WorkspaceActivity.kt")
        assertTrue(workspace.contains("Modifier.heightIn(max = 320.dp)"))
    }

    @Test
    fun taskSurfaceDismissesStaleKeyboardAndDeadControlsStayGone() {
        val taskPanel = source("ui/v32/CycloneAskTaskPanel.kt")
        assertTrue(taskPanel.contains("focusManager.clearFocus(force = true)"))
        assertTrue(taskPanel.contains("keyboard?.hide()"))
        assertFalse(taskPanel.contains("Text(\"Autofill\")"))
        assertFalse(taskPanel.contains("Text(\"Soon\")"))
    }

    @Test
    fun compactBackgroundGlassDoesNotContainAnotherComposerOrFullTaskPanel() {
        val compact = source("ui/overlay/BackgroundTaskGlass.kt")
        assertTrue(compact.contains("BackgroundTaskRibbon(task, onAsk)"))
        assertFalse(compact.contains("CycloneAskTaskPanel(task)"))
        assertFalse(compact.contains("Ask Cyclone…"))
    }
}
