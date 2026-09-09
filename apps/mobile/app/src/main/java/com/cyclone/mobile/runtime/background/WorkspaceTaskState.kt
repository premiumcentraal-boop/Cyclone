package com.cyclone.mobile.runtime.background

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.cyclone.mobile.runtime.session.SessionContract
import com.cyclone.mobile.runtime.session.SessionKernel
import com.cyclone.mobile.runtime.session.SessionPlane
import com.cyclone.mobile.ui.overlay.GlassStepKind
import com.cyclone.mobile.ui.overlay.TaskGlassStep
import com.cyclone.mobile.runtime.workspaces.Workspace
import com.cyclone.mobile.runtime.workspaces.Layer2Workspaces
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

data class ResolvedQueueTarget(val packageName: String, val appLabel: String)

/** Presentation and task routing only. WorkspaceRuntime and the existing agent own execution. */
object WorkspaceTasks {
    val requests = WorkspaceRequestQueue()
    private val attachments = java.util.concurrent.ConcurrentHashMap<String, com.cyclone.mobile.ui.overlay.TaskAttachment>()
    private val promotionLock = Any()
    fun takeAttachment(taskId: String) = attachments.remove(taskId)
    fun queueRequest(goal: String, targetPackageName: String? = null, targetAppLabel: String? = null) =
        requests.add(goal, targetPackageName, targetAppLabel) { com.cyclone.mobile.ui.overlay.PendingTaskAttachment.take() }
    fun canStartRequest(): Boolean = WorkspaceQueuePromotionPolicy.canStart(
        state.value?.phase,
        com.cyclone.mobile.ui.overlay.OverlayChromeRuntime.hasExecutingTask(),
    )
    fun hasCurrentTask(): Boolean = !WorkspaceQueuePromotionPolicy.canPromote(state.value?.phase)
    const val PRODUCT_HOT_BACKGROUND_LIMIT = SessionKernel.PRODUCT_HOT_BACKGROUND_LIMIT
    private val mutable = MutableStateFlow<WorkspaceTaskUi?>(null)
    val state = mutable.asStateFlow()
    fun update(taskId: String, change: (WorkspaceTaskUi) -> WorkspaceTaskUi) {
        mutable.update { it?.takeIf { task -> task.taskId == taskId }?.let(change) ?: it }
    }

    /** Read-only profile inventory for queue steering. No Android profile state is mutated here. */
    fun queueDestinations(context: Context): List<WorkspaceDestinationHint> {
        val own = Layer2Workspaces.currentAndroidUserId()
        val result = mutableListOf(WorkspaceDestinationHint("Profile A", own))
        val secondaryIds = runCatching {
            context.getSystemService(android.os.UserManager::class.java).userProfiles
                .mapNotNull { Layer2Workspaces.profileUserId(context, it) }
                .distinct().filter { it != own }.sorted()
        }.getOrDefault(emptyList())
        secondaryIds.forEachIndexed { index, id ->
            result += WorkspaceDestinationHint("Profile ${('B'.code + index).toChar()}", id)
        }
        return result
    }

    /** Deterministic app resolution mirrors the existing explicit-target rule: exactly one label match. */
    fun resolveQueueTarget(context: Context, request: PendingWorkspaceRequest): ResolvedQueueTarget? {
        request.targetPackageName?.let { packageName ->
            val label = runCatching {
                val info = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(info).toString()
            }.getOrNull()
            if (!label.isNullOrBlank()) return ResolvedQueueTarget(packageName, request.targetAppLabel ?: label)
        }
        val apps = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
        val matches = apps.filter { app ->
            val label = app.loadLabel(context.packageManager).toString()
            label.length >= 3 && Regex(
                "(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(label) + "(?![\\p{L}\\p{N}])",
            ).containsMatchIn(request.goal)
        }
        return matches.singleOrNull()?.let { app ->
            ResolvedQueueTarget(app.activityInfo.packageName, app.loadLabel(context.packageManager).toString())
        }
    }

    fun queuePresentationStatus(context: Context, request: PendingWorkspaceRequest): String {
        val destinations = queueDestinations(context)
        val preferred = request.preferredDestination
        if (preferred != null && destinations.none { it.androidUserId == preferred.androidUserId }) return "Choose destination"
        if (resolveQueueTarget(context, request) == null) return "Choose destination"
        return preferred?.let { "Queued · ${it.label}" } ?: "Queued"
    }

    /**
     * Promotes only the FIFO head into the existing one-hot background runner. A secondary profile
     * hint is retained but not guessed into the named-VD execution plane; it stays queued until a
     * safe profile-capable route can consume it.
     */
    fun tryPromoteNext(context: Context): Boolean = synchronized(promotionLock) {
        val currentPhase = state.value?.phase
        val pending = requests.promotableHead(currentPhase) ?: return@synchronized false
        if (!canStartRequest() || Layer2Workspaces.gated()) return@synchronized false
        val destinations = queueDestinations(context)
        val preferred = pending.preferredDestination
        if (preferred != null && destinations.none { it.androidUserId == preferred.androidUserId }) return@synchronized false
        val own = Layer2Workspaces.currentAndroidUserId()
        if (preferred != null && preferred.androidUserId != own) {
            android.widget.Toast.makeText(context, "This profile needs a profile workspace task. Open Profiles to continue; it cannot run on the isolated background display.", android.widget.Toast.LENGTH_LONG).show()
            return@synchronized false
        }
        val target = resolveQueueTarget(context, pending) ?: return@synchronized false
        if (pending.targetPackageName != target.packageName || pending.targetAppLabel != target.appLabel) {
            requests.bindTarget(pending.id, target.packageName, target.appLabel)
        }
        runCatching {
            start(context.applicationContext, pending.goal, target.packageName, target.appLabel, pending.id)
        }.onFailure { error ->
            android.widget.Toast.makeText(context, error.message ?: "Check Background tasks setup.", android.widget.Toast.LENGTH_LONG).show()
        }.isSuccess
    }

    /** Service teardown retries briefly so overlay visual teardown cannot strand an otherwise-ready FIFO head. */
    fun scheduleQueuePromotion(context: Context) {
        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        listOf(120L, 500L, 1_200L).forEach { delayMs ->
            handler.postDelayed({ if (requests.peek() != null) tryPromoteNext(app) }, delayMs)
        }
    }

    fun start(context: Context, goal: String, packageName: String, label: String, pendingRequestId: String? = null) {
        check(canStartRequest()) { "Finish or close the current phone task before starting another." }
        check(!com.cyclone.mobile.runtime.workspaces.Layer2Workspaces.gated()) { "Review the current task before starting another." }
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
        val pending = pendingRequestId?.let { requests.find(it) ?: error("This saved task was removed. Return to Up next.") }
        val task = WorkspaceTaskUi(UUID.randomUUID().toString(), app = label, packageName = packageName, goal = goal)
        val attachment = if (pending != null) pending.attachment else com.cyclone.mobile.ui.overlay.PendingTaskAttachment.take()
        attachment?.let { attachments[task.taskId] = it }
        mutable.value = task
        try { context.startForegroundService(Intent(context, WorkspaceTaskService::class.java)
            .putExtra("task", task.taskId).putExtra("goal", goal).putExtra("package", packageName).putExtra("label", label))
            pendingRequestId?.let(requests::remove) }
        catch (error: Exception) {
            attachments.remove(task.taskId)?.let { if (pending == null) com.cyclone.mobile.ui.overlay.PendingTaskAttachment.set(it) }
            update(task.taskId) { it.copy(phase = TaskPhase.FAILED,
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
