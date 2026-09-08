package com.cyclone.mobile.runtime.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.cyclone.mobile.R
import com.cyclone.mobile.ai.*
import com.cyclone.mobile.runtime.session.ExecutionContext
import com.cyclone.mobile.ui.overlay.GlassStepKind
import com.cyclone.mobile.ui.overlay.PendingTaskAttachment
import com.cyclone.mobile.ui.overlay.TaskGlassStep
import kotlinx.coroutines.*

/** One task, one existing agent, one owned display. UI dismissal never ends execution. */
class WorkspaceTaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var taskId: String? = null
    @Volatile private var sessionId: String? = null
    private var agent: OpenRouterAdaptiveAgent? = null
    private var running: Job? = null
    private var switching = false
    @Volatile private var stopped = false
    private var observer: Job? = null
    private val current get() = WorkspaceTasks.state.value?.takeIf { it.taskId == taskId }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (intent.action != null) {
            if (!WorkspaceTasks.matches(current, intent.getStringExtra("task"), intent.getStringExtra("session"))) {
                if (taskId == null) stopSelf()
                return START_NOT_STICKY
            }
            when (intent.action) {
                "cancel" -> stopTask()
                "handoff" -> transferToHuman()
                "resume" -> continueTask()
                "confirm" -> {
                    val token = intent.getStringExtra("confirmation")
                    if (token != null && token == current?.confirmation?.token && current?.phase == TaskPhase.REVIEW) {
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { sessionId?.let { WorkspaceRuntime.approveConfirmation(it, token) } } }
                                .onSuccess { update { it.copy(confirmation = null) }; continueTask() }
                        }
                    }
                }
                "pause" -> pauseTask()
            }
            return START_NOT_STICKY
        }
        if (taskId != null) return START_NOT_STICKY
        val task = WorkspaceTasks.state.value
        if (task == null || task.taskId != intent.getStringExtra("task")) { stopSelf(); return START_NOT_STICKY }
        taskId = task.taskId
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Cyclone tasks", NotificationManager.IMPORTANCE_LOW))
        if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification(task), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION, notification(task), 0)
        observer = scope.launch { WorkspaceTasks.state.collect { state ->
            if (state != null && state.taskId == taskId) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state))
        } }
        scope.launch {
            while (!stopped) {
                delay(1_000)
                val missing = BackgroundSetup.read(applicationContext).setupFailure
                if (missing != null) {
                    stopTask()
                    update { it.copy(phase = TaskPhase.FAILED, message = missing, resumable = false) }
                    return@launch
                }
            }
        }
        running = scope.launch {
            try {
                withTimeout(5_000) {
                    while (!BackgroundSetup.read(applicationContext, task.packageName).ready) {
                        BackgroundSetup.read(applicationContext, task.packageName).setupFailure?.let { error(it) }
                        delay(200)
                    }
                }
                val session = withContext(Dispatchers.IO + NonCancellable) {
                    WorkspaceRuntime.create(applicationContext, task.packageName).also {
                        sessionId = it.sessionId
                        if (stopped) WorkspaceRuntime.close(it.sessionId)
                    }
                }
                sessionId = session.sessionId
                if (stopped) { withContext(Dispatchers.IO) { WorkspaceRuntime.close(session.sessionId) }; return@launch }
                update { it.copy(
                    sessionId = session.sessionId,
                    displayId = session.displayId,
                    phase = TaskPhase.WORKING,
                    message = TaskGlassStep.subtitle(GlassStepKind.FAST_PATH, "Opened ${it.app}"),
                    glassStepKind = GlassStepKind.FAST_PATH,
                    steps = listOf("Opened ${it.app}"),
                ) }
                val settings = getSharedPreferences("cyclone_ai", MODE_PRIVATE)
                val profile = CycloneAiAccessProfileStore.read(applicationContext)
                val config = QuickAgentConfig(
                    model = (settings.getString("openrouter_model", null)?.let(OpenRouterModelPresets::byId) ?: OpenRouterModelPresets.DEFAULT).copy(
                        reasoningEffort = settings.getString("openrouter_reasoning_effort", "medium")?.takeIf { it in setOf("low", "medium", "high", "max") } ?: "medium"),
                    safeMode = profile != CycloneAiAccessProfile.FULL, accessProfile = profile,
                    attachment = WorkspaceTasks.takeAttachment(task.taskId))
                awaitWorkspace(session.sessionId, ExecutionContext.from(session))
                agent = OpenRouterAdaptiveAgent(applicationContext, ExecutionContext.from(session))
                finishTask(agent!!.execute(task.goal, config) { text -> progress(text) })
            } catch (error: Exception) {
                WorkspaceTasks.takeAttachment(task.taskId)
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                sessionId?.let { withContext(Dispatchers.IO) { WorkspaceRuntime.close(it, WorkspaceState.FAILED) } }
                sessionId = null
                update { it.copy(phase = TaskPhase.FAILED, resumable = false,
                    message = BackgroundSetup.failure(applicationContext, task.packageName, error)) }
                stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun finishTask(result: QuickAgentResult) {
        if (stopped) return
        val task = current ?: return
        if (task.phase == TaskPhase.HUMAN || task.phase == TaskPhase.PAUSED || switching) {
            if (result.ok || result.classification != "HUMAN_OR_GATE")
                update { it.copy(resumable = false) }
            return
        }
        val id = sessionId ?: return
        // Even verified completion retains its exact app page until Open or Stop.
        withContext(Dispatchers.IO) { WorkspaceRuntime.pause(id, WorkspaceState.BACKGROUND_NEEDS_HANDOFF) }
        update {
            when {
                result.ok -> it.copy(phase = TaskPhase.DONE, resumable = false,
                    message = WorkspaceCopy.result(result.message), steps = it.steps + "Checked the result")
                result.classification == "HUMAN_OR_GATE" -> it.copy(phase = TaskPhase.REVIEW,
                    message = "Review the prepared page in ${it.app} before continuing.")
                else -> it.copy(phase = TaskPhase.REVIEW, resumable = false,
                    message = "I couldn't finish. Your place in ${it.app} is saved for you.")
            }
        }
    }

    private fun pauseTask() {
        val id = sessionId ?: return
        if (switching || current?.working != true) return
        switching = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { WorkspaceRuntime.pause(id) }
                update { it.copy(phase = TaskPhase.PAUSED, message = "Take your time. Your place is saved.") }
            } catch (_: Exception) {
                update { it.copy(phase = TaskPhase.PAUSED, message = "The workspace is unavailable. Your task has stopped safely.") }
            } finally { switching = false }
        }
    }
    private fun transferToHuman() {
        val id = sessionId ?: return
        if (switching || current?.phase == TaskPhase.HUMAN) return
        switching = true
        scope.launch {
            try {
                // Revoke before moving; the agent suspends at its next normal execution boundary.
                withContext(Dispatchers.IO) { WorkspaceRuntime.handoff(id) }
                update { it.copy(phase = TaskPhase.HUMAN, confirmation = null, message = "Your prepared page is open in ${it.app}.") }
            } catch (_: Exception) {
                update { it.copy(phase = TaskPhase.PAUSED, message = "Couldn't move this page. Your task is paused safely.") }
            } finally { switching = false }
        }
    }
    private fun continueTask() {
        val id = sessionId ?: return
        val task = current ?: return
        if (switching || !task.resumable || task.phase !in setOf(TaskPhase.HUMAN, TaskPhase.PAUSED, TaskPhase.REVIEW)) return
        switching = true
        val previousRun = running
        running = scope.launch {
            try {
                // Do not race the in-flight model call or restart its agent/checkpoint.
                previousRun?.join()
                if (stopped) return@launch
                withContext(Dispatchers.IO) { WorkspaceRuntime.resume(id) }
                awaitWorkspace(id, ExecutionContext(id, com.cyclone.mobile.ai.vision.live.LiveVisionRuntime.sessions.lookup(id).displayId))
                update {
                    it.copy(
                        phase = TaskPhase.WORKING,
                        message = TaskGlassStep.subtitle(GlassStepKind.FAST_PATH, "Continuing in ${it.app}"),
                        glassStepKind = GlassStepKind.FAST_PATH,
                    )
                }
                switching = false
                finishTask(agent?.resume { text -> progress(text) } ?: error("Task unavailable"))
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                withContext(Dispatchers.IO) { runCatching { WorkspaceRuntime.pause(id) } }
                update { it.copy(phase = TaskPhase.PAUSED, message = "Couldn't continue this page. Take control to finish it.") }
            } finally { switching = false }
        }
    }
    private suspend fun awaitWorkspace(id: String, execution: ExecutionContext) {
        withTimeout(10_000) {
            while (!withContext(Dispatchers.IO) {
                runCatching { WorkspaceRuntime.observe(execution).nodes.isNotEmpty() &&
                    com.cyclone.mobile.ai.vision.live.LiveVisionRuntime.healthy(id) }.getOrDefault(false)
            }) delay(200)
        }
    }
    private fun stopTask() {
        if (stopped) return
        stopped = true
        agent?.cancelActiveTask()
        update { it.copy(phase = TaskPhase.STOPPED, message = "Stopped. You're in control.", resumable = false) }
        scope.launch {
            // Creation may still be running: its post-create check will close its own display.
            sessionId?.let { withContext(Dispatchers.IO) { WorkspaceRuntime.close(it) } }
            running?.cancelAndJoin()
            sessionId = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
    private fun progress(text: String) {
        val step = TaskGlassStep.fromProgress(text) ?: return
        update {
            if (it.working) it.copy(message = step.label, glassStepKind = step.kind) else it
        }
    }
    private fun update(change: (WorkspaceTaskUi) -> WorkspaceTaskUi) { taskId?.let { WorkspaceTasks.update(it, change) } }
    private fun notification(task: WorkspaceTaskUi): Notification {
        fun action(command: String) = PendingIntent.getService(this, 0,
            WorkspaceTasks.commandIntent(this, task, command), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val progress = PendingIntent.getActivity(this, 0, WorkspaceTasks.progressIntent(this, task),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_cyclone_status)
            .setContentTitle(when (task.phase) {
                TaskPhase.WORKING, TaskPhase.STARTING -> "Cyclone is working"
                TaskPhase.REVIEW -> "Finish your task"
                else -> task.title
            }).setContentText(task.subtitle).setOnlyAlertOnce(true).setShowWhen(false)
            .setOngoing(task.phase !in setOf(TaskPhase.FAILED, TaskPhase.STOPPED))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(progress)
            .setPublicVersion(Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_cyclone_status)
                .setContentTitle("Cyclone task").setContentText("Unlock to view progress").build())
        builder.addAction(Notification.Action.Builder(null, "View progress", progress).build())
        if (task.phase in setOf(TaskPhase.REVIEW, TaskPhase.DONE))
            builder.addAction(Notification.Action.Builder(null, "Open ${task.app}", action("handoff")).build())
        else if (task.phase == TaskPhase.HUMAN && task.resumable)
            builder.addAction(Notification.Action.Builder(null, "Continue with Cyclone", action("resume")).build())
        if (task.working) builder.addAction(Notification.Action.Builder(null, "Stop task", action("cancel")).build())
        return builder.build()
    }
    override fun onDestroy() {
        stopped = true
        agent?.cancelActiveTask()
        scope.cancel()
        sessionId?.let { id -> Thread { runCatching { WorkspaceRuntime.close(id) } }.start() }
        if (current?.phase !in setOf(TaskPhase.FAILED, TaskPhase.STOPPED))
            update { it.copy(phase = TaskPhase.STOPPED, message = "Task ended. Start a new task when you're ready.", resumable = false) }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { private const val CHANNEL = "cyclone-workspace-task"; private const val NOTIFICATION = 902 }
}

internal object WorkspaceCopy {
    fun result(message: String): String {
        val technical = Regex("(?i)brain|tool[._ ]|provider|reasoning|gate|policy|observation|session|json|```|\\b(?:verified_|completion\\.)")
        return if (message.isBlank() || technical.containsMatchIn(message)) "Your task is complete. Open the app to see the result."
        else message.trim().take(600)
    }
}
