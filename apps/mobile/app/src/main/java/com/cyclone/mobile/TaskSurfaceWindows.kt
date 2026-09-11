package com.cyclone.mobile

/** Android-independent selection policy shared by observation and live target lookup. */
object TaskSurfaceWindows {
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
