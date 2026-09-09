package com.cyclone.mobile.runtime.background

import org.junit.Assert.*
import org.junit.Test

class WorkspaceTaskCloseTest {
    @Test fun staleCloseCannotMatchAnotherTask() {
        val task = WorkspaceTaskUi("new", "session-new", "Chrome", "com.android.chrome", "open")
        assertFalse(WorkspaceTasks.matches(task, "old", "session-old"))
        assertFalse(WorkspaceTasks.matches(task, "new", "session-old"))
        assertTrue(WorkspaceTasks.matches(task, "new", "session-new"))
    }
    @Test fun closeAfterAlreadyClearedIsIdempotent() {
        assertFalse(WorkspaceTasks.clearClosedTask("missing", null))
        assertNull(WorkspaceTasks.state.value)
    }
}
