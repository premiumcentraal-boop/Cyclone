package com.cyclone.mobile.runtime.background

import org.junit.Assert.*
import org.junit.Test

class WorkspaceAdmissionTest {
    @Test fun emptyComposerDoesNotOwnExecution() {
        assertTrue(WorkspaceQueuePromotionPolicy.canStart(null, false))
        assertFalse(WorkspaceQueuePromotionPolicy.canStart(null, true))
    }
    @Test fun suspendedAndRetainedTasksKeepTheirSlot() {
        listOf(TaskPhase.STARTING, TaskPhase.WORKING, TaskPhase.PAUSED, TaskPhase.HUMAN,
            TaskPhase.REVIEW, TaskPhase.DONE).forEach {
            assertFalse(WorkspaceQueuePromotionPolicy.canStart(it, false))
        }
    }
    @Test fun stoppedAndFailedTasksReleaseTheirSlot() {
        listOf(TaskPhase.STOPPED, TaskPhase.FAILED).forEach {
            assertTrue(WorkspaceQueuePromotionPolicy.canStart(it, false))
            assertFalse(WorkspaceQueuePromotionPolicy.canStart(it, true))
        }
    }
}
