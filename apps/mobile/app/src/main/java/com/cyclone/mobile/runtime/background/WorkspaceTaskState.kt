package com.cyclone.mobile.runtime.background

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

enum class TaskPhase { STARTING, WORKING, PAUSED, REVIEW, HUMAN, DONE, FAILED, STOPPED }
data class WorkspaceTaskUi(
    val taskId: String,
    val sessionId: String? = null,
    val app: String,
    val packageName: String,
    val goal: String,
    val phase: TaskPhase = TaskPhase.STARTING,
    val message: String = "I'm on it. You can keep using your phone.",
    val steps: List<String> = emptyList(),
    val queued: String? = null,
    val resumable: Boolean = true,
) {
    val working get() = phase == TaskPhase.STARTING || phase == TaskPhase.WORKING
    val title get() = when (phase) {
        TaskPhase.STARTING, TaskPhase.WORKING -> "Working on this task"
        TaskPhase.PAUSED -> "Task paused"
        TaskPhase.REVIEW -> "Ready for your review"
        TaskPhase.HUMAN -> "You're in control"
        TaskPhase.DONE -> "Task done"
        TaskPhase.FAILED -> "Couldn't finish this task"
        TaskPhase.STOPPED -> "Task stopped"
    }
}

/** Presentation and task routing only. WorkspaceRuntime and the existing agent own execution. */
object WorkspaceTasks {
    private val mutable = MutableStateFlow<WorkspaceTaskUi?>(null)
    val state = mutable.asStateFlow()
    fun update(taskId: String, change: (WorkspaceTaskUi) -> WorkspaceTaskUi) {
        mutable.update { it?.takeIf { task -> task.taskId == taskId }?.let(change) ?: it }
    }
    fun start(context: Context, goal: String, packageName: String, label: String) {
        check(mutable.value?.let { it.phase !in setOf(TaskPhase.STOPPED, TaskPhase.FAILED) } != true) {
            "Finish or stop your current task first."
        }
        val task = WorkspaceTaskUi(UUID.randomUUID().toString(), app = label, packageName = packageName, goal = goal)
        mutable.value = task
        try { context.startForegroundService(Intent(context, WorkspaceTaskService::class.java)
            .putExtra("task", task.taskId).putExtra("goal", goal).putExtra("package", packageName).putExtra("label", label)) }
        catch (error: Exception) { update(task.taskId) { it.copy(phase = TaskPhase.FAILED,
            message = "Couldn't start background work. Open Cyclone and try again.") }; throw error }
    }
    fun command(context: Context, task: WorkspaceTaskUi, action: String) {
        context.startService(commandIntent(context, task, action))
    }
    fun commandIntent(context: Context, task: WorkspaceTaskUi, action: String) =
        Intent(context, WorkspaceTaskService::class.java).setAction(action)
            .setData(Uri.parse("cyclone://task/${task.taskId}/$action"))
            .putExtra("task", task.taskId).putExtra("session", task.sessionId)
    fun progressIntent(context: Context, task: WorkspaceTaskUi) = Intent(context, WorkspaceProgressActivity::class.java)
        .setData(Uri.parse("cyclone://task/${task.taskId}/progress"))
        .putExtra("task", task.taskId).putExtra("session", task.sessionId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun matches(task: WorkspaceTaskUi?, taskId: String?, sessionId: String?) =
        task != null && task.taskId == taskId && (sessionId == null || task.sessionId == sessionId)
}
