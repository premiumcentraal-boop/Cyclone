package com.cyclone.mobile.ui.v32

import com.cyclone.mobile.runtime.background.*
import com.cyclone.mobile.ui.overlay.GlassStepKind
import org.junit.Assert.*
import org.junit.Test

class CycloneTaskPresentationTest {
    @Test fun projectionRetainsExactIdentity() {
        val source = WorkspaceTaskUi("task", "default-foreground", "Instagram", "com.instagram.android", "Find creators", displayId = 0, workspaceId = "creator", workspaceGeneration = 19)
        assertSame(source, UiTask(source).source)
        assertEquals(19L, UiTask(source).source.workspaceGeneration)
    }
    @Test fun profileDetailRejectsOtherPlanesAndIncompleteIdentity() {
        val source = WorkspaceTaskUi("task", "default-foreground", "App", "com.app", "Goal", displayId = 0, workspaceId = "profile", workspaceGeneration = 4)
        assertTrue(UiTask(source).belongsToProfile("profile"))
        assertFalse(UiTask(source).belongsToProfile("other"))
        assertFalse(UiTask(source.copy(sessionId = "named-vd", displayId = 7)).belongsToProfile("profile"))
        assertFalse(UiTask(source.copy(workspaceGeneration = null)).belongsToProfile("profile"))
        assertFalse(UiTask(source.copy(workspaceId = null, workspaceGeneration = null)).belongsToProfile("profile"))
    }
    @Test fun consumerCopyDoesNotExposeExecutionPlane() {
        val source = WorkspaceTaskUi("task", "named-vd", "Instagram", "com.instagram.android", "Find creators", phase = TaskPhase.WORKING, displayId = 7, glassStepKind = GlassStepKind.FAST_PATH, message = "Fast Path · phone.click")
        assertEquals("Checking the current page", UiTask(source).subtitle)
        assertEquals("named-vd", UiTask(source).source.sessionId)
        assertEquals(7, UiTask(source).source.displayId)
    }
    @Test fun terminalPhasesAreNotActive() {
        TaskPhase.entries.forEach { phase ->
            val task = UiTask(WorkspaceTaskUi("t", app = "App", packageName = "com.app", goal = "Goal", phase = phase))
            assertEquals(phase !in setOf(TaskPhase.DONE, TaskPhase.STOPPED, TaskPhase.FAILED), task.active)
        }
    }
}
