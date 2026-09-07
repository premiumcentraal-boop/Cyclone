package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi

/** Presentation only: never grants phone input authority. */
object BackgroundGlassPolicy {
    fun visible(task: WorkspaceTaskUi?): Boolean = task != null && task.phase !in
        setOf(TaskPhase.FAILED, TaskPhase.STOPPED)
    fun tearDown(task: WorkspaceTaskUi?): Boolean = task?.phase in setOf(TaskPhase.FAILED, TaskPhase.STOPPED)
}

/** Every successfully attached window is owned until synchronous removal completes. */
internal class OverlayWindowRegistry<T>(private val remove: (T) -> Unit) {
    private val windows = linkedSetOf<T>()
    val size get() = windows.size
    fun attached(window: T) { windows += window }
    fun remove(window: T) { if (window in windows) { remove.invoke(window); windows -= window } }
    fun clear() { windows.toList().forEach(::remove) }
}
