package com.cyclone.mobile

import org.junit.Assert.*
import org.junit.Test

class TaskSurfaceWindowsTest {
    @Test fun siblingWindowPathTreatsZeroAsRootRatherThanFirstChild() {
        assertEquals(TaskSurfaceWindows.NodePath(42, emptyList()), TaskSurfaceWindows.parseNodePath("w42/0"))
        assertEquals(TaskSurfaceWindows.NodePath(42, listOf(3, 1)), TaskSurfaceWindows.parseNodePath("w42/0/3/1"))
        assertEquals(TaskSurfaceWindows.NodePath(null, listOf(3, 1)), TaskSurfaceWindows.parseNodePath("0/3/1"))
        listOf("w42/1", "w42", "0/-1", "wunknown/0", "0//1").forEach {
            assertNull(TaskSurfaceWindows.parseNodePath(it))
        }
    }

    private val chrome = TaskSurfaceWindows.Window(1, 1, "com.android.chrome", 1)
    private val overlay = TaskSurfaceWindows.Window(2, 4, "com.cyclone.mobile", 9, active = true, focused = true)

    @Test fun statusBarAndClosedShadeTicksDoNotChangeTheTaskRead() {
        // Alpha 109, captured on a Pixel 8 (Android 16): status bar 1080x132, closed notification shade 996x467, both
        // system windows, neither active nor focused, on a 1080x2400 display.
        assertFalse(TaskSurfaceWindows.changesTaskRead(3, false, false, 1080, 132, 1080, 2400))
        assertFalse(TaskSurfaceWindows.changesTaskRead(3, false, false, 996, 467, 1080, 2400))
        assertFalse(TaskSurfaceWindows.changesTaskRead(4, true, true, 1080, 2400, 1080, 2400))
    }

    @Test fun theAppKeyboardAndAnyFrontOrLargeSystemWindowStillChangeTheTaskRead() {
        assertTrue(TaskSurfaceWindows.changesTaskRead(1, false, false, 1080, 2400, 1080, 2400))  // the app
        assertTrue(TaskSurfaceWindows.changesTaskRead(2, false, false, 1080, 900, 1080, 2400))   // the keyboard
        assertTrue(TaskSurfaceWindows.changesTaskRead(3, false, false, 1080, 2400, 1080, 2400))  // the opened shade
        assertTrue(TaskSurfaceWindows.changesTaskRead(3, true, false, 600, 300, 1080, 2400))     // an active system dialog
        assertTrue(TaskSurfaceWindows.changesTaskRead(3, false, true, 600, 300, 1080, 2400))     // a focused system dialog
        assertTrue(TaskSurfaceWindows.changesTaskRead(3, false, false, 100, 100, 0, 0))          // unknown display: count it
    }

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

    @Test
    fun aDialogAboveItsActivityComesFirstEvenWhileCyclonesOverlayHasFocus() {
        // Settings › Screen timeout: the radio dialog (layer 22) over Display settings (layer 21); Cyclone's overlay
        // (type 4) holds focus, so the active root is not an application window.
        val windows = listOf(
            TaskSurfaceWindows.Window(10, 1, "com.android.settings", 21),
            TaskSurfaceWindows.Window(11, 1, "com.android.settings", 22),
            TaskSurfaceWindows.Window(12, 4, "com.cyclone.mobile", 30, active = true, focused = true),
            TaskSurfaceWindows.Window(13, 3, "com.android.systemui", 25),
        )
        assertEquals(listOf(11, 10), TaskSurfaceWindows.ordered(windows, activeRootId = 12).map { it.id })
        // The caller tries them in order, so an unreadable dialog root falls back to the activity, never to "null".
        assertEquals(11, TaskSurfaceWindows.primary(windows, activeRootId = 12)?.id)
    }
}
