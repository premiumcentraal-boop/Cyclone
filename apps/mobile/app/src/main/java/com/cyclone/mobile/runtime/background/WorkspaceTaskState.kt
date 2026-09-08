package com.cyclone.mobile.runtime.background

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.cyclone.mobile.runtime.session.SessionContract
import com.cyclone.mobile.runtime.session.SessionKernel
import com.cyclone.mobile.runtime.session.SessionPlane
import com.cyclone.mobile.ui.overlay.GlassStepKind
import com.cyclone.mobile.ui.overlay.TaskGlassStep
import com.cyclone.mobile.runtime.workspaces.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
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
    val confirmation: WorkspaceConfirmation? = null,
    val displayId: Int? = null,
    val workspaceId: String? = null,
    val workspaceGeneration: Long? = null,
    val glassStepKind: GlassStepKind? = null,
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
    /** Collapsed glass subtitle: real Fast Path / skill / Layer 2 slice, not a generic placeholder. */
    val subtitle: String get() = TaskConsumerCopy.subtitle(this)

    fun identityJson(): JSONObject {
        val json = JSONObject()
        if (workspaceId != null || workspaceGeneration != null) {
            json.put("sessionId", sessionId ?: "default-foreground")
            json.put("displayId", displayId ?: 0)
            workspaceId?.let { json.put("workspaceId", it) }
            workspaceGeneration?.let { json.put("workspaceGeneration", it) }
        } else if (!sessionId.isNullOrBlank() && sessionId != "default-foreground") {
            json.put("sessionId", sessionId)
            json.put("displayId", displayId ?: -1)
        } else {
            json.put("sessionId", sessionId ?: "default-foreground")
            json.put("displayId", displayId ?: 0)
        }
        return json
    }

    fun plane(): SessionPlane = SessionContract.classify(identityJson())
}

/** Presentation and task routing only. WorkspaceRuntime and the existing agent own execution. */
object WorkspaceTasks {
    const val PRODUCT_HOT_BACKGROUND_LIMIT = SessionKernel.PRODUCT_HOT_BACKGROUND_LIMIT
    private val mutable = MutableStateFlow<WorkspaceTaskUi?>(null)
    val state = mutable.asStateFlow()
    fun update(taskId: String, change: (WorkspaceTaskUi) -> WorkspaceTaskUi) {
        mutable.update { it?.takeIf { task -> task.taskId == taskId }?.let(change) ?: it }
    }
    fun start(context: Context, goal: String, packageName: String, label: String) {
        val liveCount = mutable.value?.takeIf { it.phase !in setOf(TaskPhase.STOPPED, TaskPhase.FAILED) }?.let { 1 } ?: 0
        check(liveCount < PRODUCT_HOT_BACKGROUND_LIMIT) {
            "Finish or stop your current task first."
        }
        BackgroundSetup.read(context, packageName).setupFailure?.let { reason ->
            com.cyclone.mobile.ui.overlay.OverlayChromeRuntime.clearBackgroundChrome()
            error(reason)
        }
        if (BackgroundSetup.foregroundPackage() == context.packageName) {
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
    fun progressIntent(context: Context, task: WorkspaceTaskUi) = ViewProgressRouter.intent(context, task)
    fun matches(task: WorkspaceTaskUi?, taskId: String?, sessionId: String?) =
        task != null && task.taskId == taskId && (sessionId == null || task.sessionId == sessionId)

    /**
     * Queue observability only. Does not start a Session Kernel VD and never replaces an in-flight
     * named VD task. Layer 2 stays default-foreground / display 0.
     */
    fun canPublishLayer2Slice(current: WorkspaceTaskUi?): Boolean {
        if (current == null || current.phase in setOf(TaskPhase.STOPPED, TaskPhase.FAILED)) return true
        if (current.workspaceId != null) return true
        return current.sessionId.isNullOrBlank() || current.sessionId == "default-foreground"
    }

    fun observeLayer2Slice(workspace: Workspace, generation: Long, goal: String) {
        val current = mutable.value
        if (!canPublishLayer2Slice(current)) return
        val step = TaskGlassStep.layer2Slice(workspace.label)
        val slice = WorkspaceTaskUi(
            taskId = current?.takeIf { it.workspaceId != null }?.taskId ?: "layer2-${workspace.id}",
            sessionId = "default-foreground",
            app = workspace.label,
            packageName = workspace.appPackage,
            goal = goal.ifBlank { workspace.label },
            phase = TaskPhase.WORKING,
            message = step.label,
            displayId = 0,
            workspaceId = workspace.id,
            workspaceGeneration = generation,
            glassStepKind = step.kind,
        )
        if (current?.workspaceId != null && current.phase !in setOf(TaskPhase.STOPPED, TaskPhase.FAILED)) {
            mutable.value = slice
            return
        }
        if (current == null || current.phase in setOf(TaskPhase.STOPPED, TaskPhase.FAILED)) {
            mutable.value = slice
        }
    }
}
