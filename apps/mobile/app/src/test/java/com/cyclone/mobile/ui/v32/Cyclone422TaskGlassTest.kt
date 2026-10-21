package com.cyclone.mobile.ui.v32

import com.cyclone.mobile.runtime.background.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Cyclone422TaskGlassTest {
    private fun source(path: String) = sequenceOf(
        File("src/main/java/com/cyclone/mobile/$path"),
        File("apps/mobile/app/src/main/java/com/cyclone/mobile/$path"),
    ).first { it.isFile }.readText()

    private fun task(phase: TaskPhase = TaskPhase.WORKING, goal: String = "open chrome") =
        WorkspaceTaskUi(
            taskId = "task-1",
            app = "Chrome",
            packageName = "com.android.chrome",
            goal = goal,
            phase = phase,
        )

    @Test fun coldLaunchNeverShowsFakeCurrentTask() {
        assertNull(TaskGlassPresentation.current(null))
    }

    @Test fun analysisWithoutTaskNeverShowsFakeCurrentTask() {
        assertNull(TaskGlassPresentation.current(null))
        assertFalse(source("ui/overlay/OverlayChrome.kt").contains("Current phone task"))
    }

    @Test fun workingVisualStateWithoutTaskNeverShowsFakeCurrentTask() {
        assertNull(TaskGlassPresentation.current(null))
        val overlay = source("ui/overlay/OverlayChrome.kt")
        assertFalse(overlay.contains("snapshot.state in setOf(OverlayChromeState.ANALYSIS"))
    }

    @Test fun realWorkingTaskRendersCurrentCardModel() {
        val card = TaskGlassPresentation.current(task())
        assertNotNull(card)
        assertEquals("Working on this task", card!!.status)
        assertEquals("Opening Chrome", card.taskLabel)
    }

    @Test fun collapsedCurrentTaskHasNoStopOrClose() {
        val panel = source("ui/v32/CycloneAskTaskPanel.kt")
        val compact = source("ui/overlay/BackgroundTaskGlass.kt")
        assertFalse(panel.contains("Stop task"))
        assertFalse(panel.contains("Close task"))
        assertFalse(panel.contains("WorkspaceTasks.command"))
        assertFalse(compact.contains("WorkspaceTasks.command"))
    }

    @Test fun viewProgressUsesExactTaskProjection() {
        val panel = source("ui/v32/CycloneAskTaskPanel.kt")
        assertTrue(panel.contains("UiTask(task).open(context)"))
        val exact = task()
        assertSame(exact, UiTask(exact).source)
        assertEquals("task-1", UiTask(exact).id)
    }

    @Test fun queuedTasksExposeSteerAndStopNotStartAndRemove() {
        val queue = source("ui/v32/CyclonePendingRequests.kt")
        assertTrue(queue.contains("Text(\"Steer\")"))
        assertTrue(queue.contains("Text(\"Stop\")"))
        assertFalse(queue.contains("Text(\"Start\")"))
        assertFalse(queue.contains("Text(\"Remove\")"))
    }

    @Test fun stoppedTaskIsNotPresentedAsWorking() {
        assertNull(TaskGlassPresentation.current(task(TaskPhase.STOPPED)))
    }

    @Test fun reviewTaskUsesRequiredCopy() {
        val card = TaskGlassPresentation.current(task(TaskPhase.REVIEW))!!
        assertEquals("Review required", card.status)
        assertEquals("Review", card.actionLabel)
    }

    @Test fun queuedRequestOnlyMapsToQueueCard() {
        val request = PendingWorkspaceRequest("q", "open chrome", null)
        val queue = TaskGlassPresentation.queued(request)
        assertEquals("Opening Chrome", queue.taskLabel)
        assertEquals("Queued", queue.status)
    }

    @Test fun openChromeHumanizesDeterministically() {
        assertEquals("Opening Chrome", TaskHumanizer.humanize("open chrome"))
    }

    @Test fun instagramLoginHumanizesDeterministically() {
        assertEquals(
            "Checking Instagram login status",
            TaskHumanizer.humanize("please check Instagram to see if I am logged in"),
        )
    }

    @Test fun batterySettingsHumanizesDeterministically() {
        assertEquals("Checking battery settings", TaskHumanizer.humanize("open settings and check battery"))
    }

    @Test fun unknownTaskHasSafeConciseFallback() {
        val label = TaskHumanizer.humanize(
            "please compare the first several visible choices and tell me which public option looks best for this task without buying anything",
        )
        assertTrue(label.isNotBlank())
        assertTrue(label.length <= 65)
        assertFalse(label.contains("```"))
    }

    @Test fun overlayVisualStateCannotManufactureCurrentTask() {
        val overlay = source("ui/overlay/OverlayChrome.kt")
        assertTrue(overlay.contains("A real WorkspaceTaskUi is the only source of truth"))
        assertFalse(overlay.contains("Current phone task"))
        assertFalse(overlay.contains("onAction(OverlayUserAction.STOP_TASK)"))
    }

    @Test fun lightThemeUsesSemanticContentColors() {
        val panel = source("ui/v32/CycloneAskTaskPanel.kt")
        assertTrue(panel.contains("MaterialTheme.colorScheme.onSurface"))
        assertTrue(panel.contains("MaterialTheme.colorScheme.onSurfaceVariant"))
        assertTrue(panel.contains("MaterialTheme.colorScheme.onPrimary"))
        assertFalse(panel.contains("Color.Black"))
    }

    @Test fun darkThemeUsesSemanticContentColorsWithoutHardcodedWhite() {
        val files = listOf(
            source("ui/v32/CycloneAskTaskPanel.kt"),
            source("ui/v32/CyclonePendingRequests.kt"),
            source("ui/overlay/BackgroundTaskGlass.kt"),
        ).joinToString("\n")
        assertFalse(files.contains("Color.Black"))
        assertFalse(files.contains("Color.White"))
        assertTrue(files.contains("MaterialTheme.colorScheme.onSurface"))
    }

    @Test fun largeFontLayoutIsBoundedAndNoLegacy150DpQueueBoxRemains() {
        val overlay = source("ui/overlay/OverlayChrome.kt")
        val queue = source("ui/v32/CyclonePendingRequests.kt")
        val contract = source("ui/overlay/OverlayChromeContract.kt")
        assertTrue(overlay.contains("TASK_AREA_MAX_HEIGHT_DP"))
        assertTrue(overlay.contains("verticalScroll(rememberScrollState())"))
        assertFalse(queue.contains("max = 150.dp"))
        assertTrue(contract.contains("TASK_AREA_MAX_HEIGHT_DP = 430"))
    }

    @Test fun keyboardCompactionRetainsQueuedControlsSafely() {
        val queue = source("ui/v32/CyclonePendingRequests.kt")
        val overlay = source("ui/overlay/OverlayChrome.kt")
        assertTrue(queue.contains("WindowInsets.ime"))
        assertTrue(queue.contains("requests.take(KEYBOARD_VISIBLE_QUEUE_CARDS)"))
        assertTrue(queue.contains("Text(\"Steer\")"))
        assertTrue(queue.contains("Text(\"Stop\")"))
        assertTrue(overlay.contains("TASK_AREA_KEYBOARD_MAX_HEIGHT_DP"))
    }

    @Test fun accessibilityDescriptionsNameExactHumanizedTask() {
        val current = TaskGlassPresentation.current(task())!!
        assertEquals("View progress for Opening Chrome", current.actionContentDescription)
        val queued = TaskGlassPresentation.queued(PendingWorkspaceRequest("q", "open chrome", null))
        assertEquals("Steer Opening Chrome", queued.steerContentDescription)
        assertEquals("Stop queued task Opening Chrome", queued.stopContentDescription)
    }
}
