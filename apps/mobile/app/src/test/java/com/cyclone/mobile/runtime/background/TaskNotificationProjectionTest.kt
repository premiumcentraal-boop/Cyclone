package com.cyclone.mobile.runtime.background
import org.junit.Assert.*
import org.junit.Test
class TaskNotificationProjectionTest {
    private val task = WorkspaceTaskUi("t", "s", "Reddit", "reddit", "goal", displayId = 7)
    @Test fun notificationsProjectTheSameStateAndCapabilities() {
        for (phase in TaskPhase.entries) {
            val state = TaskHarnessState.normalize(task, task.copy(phase = phase))
            assertEquals(state.title, TaskNotificationProjection.title(state))
            assertEquals(state.interruption?.canResumeAfterHuman == true,
                TaskNotificationProjection.actions(state).any { it.first == "resume" })
            assertFalse(TaskNotificationProjection.actions(state).any { it.first == "autofill" })
        }
    }
    @Test fun failedIsTerminalRatherThanActionNeeded() {
        val failed = TaskHarnessState.normalize(task, task.copy(phase = TaskPhase.FAILED, resumable = false))
        assertEquals("Failed", TaskNotificationProjection.title(failed))
        assertTrue(TaskNotificationProjection.actions(failed).isEmpty())
    }
    @Test fun arbitraryProviderOrSecretMessageNeverBecomesNotificationCopy() {
        val state = task.copy(message = "password=123456 sk-or-secret Tap x=421 Model tool call")
        assertEquals("Getting your task ready", state.subtitle)
        assertFalse(state.copy(phase = TaskPhase.WORKING).subtitle.contains("secret"))
    }
    @Test fun commandMatchingNeverTreatsMissingSessionAsWildcard() {
        assertFalse(WorkspaceTasks.matches(task, "t", null))
        assertFalse(WorkspaceTasks.matches(task, "t", "other"))
        assertTrue(WorkspaceTasks.matches(task, "t", "s"))
    }
}
