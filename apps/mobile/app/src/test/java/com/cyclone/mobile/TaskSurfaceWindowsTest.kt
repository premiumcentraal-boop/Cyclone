package com.cyclone.mobile

import org.junit.Assert.*
import org.junit.Test

class TaskSurfaceWindowsTest {
    private val chrome = TaskSurfaceWindows.Window(1, 1, "com.android.chrome", 1)
    private val overlay = TaskSurfaceWindows.Window(2, 4, "com.cyclone.mobile", 9, active = true, focused = true)

    @Test fun activeCycloneOverlayCannotBecomeTaskRoot() {
        assertEquals(chrome, TaskSurfaceWindows.primary(listOf(chrome, overlay), overlay.id))
        assertFalse(TaskSurfaceWindows.includeSibling(4, "com.cyclone.mobile", "com.android.chrome"))
        assertFalse(TaskSurfaceWindows.eventBelongsToTask(4, "com.cyclone.mobile", "com.android.chrome"))
    }

    @Test fun overlayChurnDoesNotChangeSelectedApp() {
        for (layer in 2..20) {
            assertEquals(chrome, TaskSurfaceWindows.primary(listOf(chrome, overlay.copy(id = layer, layer = layer)), layer))
        }
    }

    @Test fun realCycloneApplicationAndPermissionDialogRemainObservable() {
        val app = overlay.copy(type = 1)
        assertEquals(app, TaskSurfaceWindows.primary(listOf(chrome, app), app.id))
        assertTrue(TaskSurfaceWindows.includeSibling(1, "com.cyclone.mobile", "com.cyclone.mobile"))
        val permission = TaskSurfaceWindows.Window(3, 1, "com.google.android.permissioncontroller", 12, focused = true)
        assertEquals(permission, TaskSurfaceWindows.primary(listOf(chrome, overlay, permission), overlay.id))
        assertTrue(TaskSurfaceWindows.includeSibling(1, permission.packageName, chrome.packageName))
    }

    @Test fun unknownOverlayOnlySurfaceDoesNotFallBackToCyclone() {
        assertNull(TaskSurfaceWindows.primary(listOf(overlay), overlay.id))
        assertFalse(TaskSurfaceWindows.eventBelongsToTask(null, "com.cyclone.mobile", "com.android.chrome"))
        assertFalse(TaskSurfaceWindows.includeSibling(2, "keyboard", chrome.packageName))
    }
}
