package com.cyclone.mobile

enum class DeviceRealitySurfaceKind {
    APPLICATION,
    ACCESSIBILITY_OVERLAY,
    SYSTEM,
    OTHER,
}

data class DeviceRealityWindowCandidate(
    val id: Int,
    val packageName: String,
    val kind: DeviceRealitySurfaceKind,
    val layer: Int,
    val active: Boolean,
    val focused: Boolean,
    val title: String = "",
)

data class DeviceRealitySelection(
    val taskWindowId: Int?,
    val taskPackage: String?,
    val agentOverlayWindowId: Int?,
    val groundingConflict: Boolean,
    val reason: String,
)

/**
 * Selects the application Cyclone should reason about independently from Cyclone's accessibility
 * overlay. An active/focused accessibility overlay is never allowed to shadow a real external
 * application window. This is intentionally pure so the arbitration contract is regression tested
 * without Android window objects.
 */
object DeviceRealityArbiter {
    const val CYCLONE_PACKAGE = "com.cyclone.mobile"
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    fun select(
        windows: List<DeviceRealityWindowCandidate>,
        cyclonePackage: String = CYCLONE_PACKAGE,
    ): DeviceRealitySelection {
        val usable = windows.filter { it.packageName.isNotBlank() && it.packageName != SYSTEM_UI_PACKAGE }
        val agentOverlay = usable
            .filter { it.kind == DeviceRealitySurfaceKind.ACCESSIBILITY_OVERLAY && it.packageName == cyclonePackage }
            .maxWithOrNull(compareBy<DeviceRealityWindowCandidate> { it.layer }.thenBy { it.id })

        val applications = usable.filter { it.kind == DeviceRealitySurfaceKind.APPLICATION }
        val externalApplications = applications.filter { it.packageName != cyclonePackage }

        val task = chooseApplication(externalApplications)
            ?: chooseApplication(applications)

        val activeNonTask = usable.firstOrNull { it.active || it.focused }
        val conflict = task != null && activeNonTask != null &&
            activeNonTask.id != task.id &&
            activeNonTask.kind == DeviceRealitySurfaceKind.ACCESSIBILITY_OVERLAY

        val reason = when {
            task == null -> "no_application_window"
            conflict && activeNonTask.packageName == cyclonePackage -> "cyclone_overlay_excluded_from_task_reality"
            conflict -> "overlay_excluded_from_task_reality"
            task.packageName == cyclonePackage -> "cyclone_application_is_task"
            else -> "external_application_is_task"
        }

        return DeviceRealitySelection(
            taskWindowId = task?.id,
            taskPackage = task?.packageName,
            agentOverlayWindowId = agentOverlay?.id,
            groundingConflict = conflict,
            reason = reason,
        )
    }

    private fun chooseApplication(candidates: List<DeviceRealityWindowCandidate>): DeviceRealityWindowCandidate? =
        candidates
            .sortedWith(
                compareByDescending<DeviceRealityWindowCandidate> { it.active || it.focused }
                    .thenByDescending { it.focused }
                    .thenByDescending { it.active }
                    .thenByDescending { it.layer }
                    .thenByDescending { it.id },
            )
            .firstOrNull()
}
