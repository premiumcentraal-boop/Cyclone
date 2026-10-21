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

    fun primary(windows: List<Window>, activeRootId: Int?): Window? {
        val applications = windows.filter { it.type == 1 && it.packageName.isNotBlank() && it.packageName != "com.android.systemui" }
        return applications.firstOrNull { it.id == activeRootId }
            ?: applications.sortedWith(compareByDescending<Window> { it.active || it.focused }.thenByDescending { it.layer }).firstOrNull()
    }

    fun includeSibling(type: Int, packageName: String, hostPackage: String): Boolean =
        type != 4 && type != 2 && packageName.isNotBlank() && packageName != "com.android.systemui" &&
            (packageName != "com.cyclone.mobile" || hostPackage == "com.cyclone.mobile")

    fun eventBelongsToTask(type: Int?, packageName: String, hostPackage: String?): Boolean =
        type != 4 && type != 2 &&
            (packageName != "com.cyclone.mobile" || hostPackage == "com.cyclone.mobile")
}
