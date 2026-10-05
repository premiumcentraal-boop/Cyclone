package com.cyclone.mobile

/** Android-independent selection policy shared by observation and live target lookup. */
object TaskSurfaceWindows {
    data class NodePath(val windowId: Int?, val children: List<Int>)

    fun parseNodePath(path: String): NodePath? {
        val parts = path.split('/')
        val explicitWindow = parts.firstOrNull()?.startsWith("w") == true
        val windowId = if (explicitWindow) parts.first().drop(1).toIntOrNull() ?: return null else null
        val rootIndex = if (explicitWindow) 1 else 0
        if (parts.getOrNull(rootIndex) != "0") return null
        val children = parts.drop(rootIndex + 1).map { it.toIntOrNull()?.takeIf { index -> index >= 0 } ?: return null }
        return NodePath(windowId, children)
    }
    data class Window(val id: Int, val type: Int, val packageName: String, val layer: Int,
        val active: Boolean = false, val focused: Boolean = false)

    fun primary(windows: List<Window>, activeRootId: Int?): Window? = ordered(windows, activeRootId).firstOrNull()

    /**
     * The application windows in the order to try them as the task's page: the active root's window, then the active or
     * focused one, then the highest layer (a dialog or bottom sheet sits above its activity). Alpha 91: the caller takes
     * the first one whose root it can actually read, so a dialog whose root is briefly unavailable no longer turns the
     * whole page into "null".
     */
    fun ordered(windows: List<Window>, activeRootId: Int?): List<Window> {
        val applications = windows.filter { it.type == 1 && it.packageName.isNotBlank() && it.packageName != "com.android.systemui" }
        val active = applications.filter { it.id == activeRootId }
        return active + applications.filter { it.id != activeRootId }
            .sortedWith(compareByDescending<Window> { it.active || it.focused }.thenByDescending { it.layer })
    }

    fun includeSibling(type: Int, packageName: String, hostPackage: String): Boolean =
        type != 4 && type != 2 && packageName.isNotBlank() && packageName != "com.android.systemui" &&
            (packageName != "com.cyclone.mobile" || hostPackage == "com.cyclone.mobile")

    /**
     * Alpha 109: whether an event in this window can change what a read of the task's screen sees. The status bar and
     * the closed notification shade are system windows (type 3) that are neither active nor focused and cover a strip
     * of the display; their clock, icons and notifications tick about once a second, and counting them made Cyclone
     * throw away its own screen reads ("the screen could not be read") on every page. A system window that is active
     * or focused, or covers at least half the display (an opened shade, a full-screen system surface), still counts.
     * Windows appearing, moving, resizing or taking focus are caught by the window signature either way. Cyclone's own
     * overlays (type 4) never count.
     */
    fun changesTaskRead(type: Int, active: Boolean, focused: Boolean, width: Int, height: Int, displayWidth: Int, displayHeight: Int): Boolean {
        if (type == 4) return false
        if (type != 3) return true
        if (active || focused) return true
        val area = width.coerceAtLeast(0).toLong() * height.coerceAtLeast(0)
        val display = displayWidth.coerceAtLeast(0).toLong() * displayHeight.coerceAtLeast(0)
        return display <= 0L || area * 2 >= display
    }

    fun eventBelongsToTask(type: Int?, packageName: String, hostPackage: String?): Boolean =
        type != 4 && type != 2 &&
            (packageName != "com.cyclone.mobile" || hostPackage == "com.cyclone.mobile")
}
