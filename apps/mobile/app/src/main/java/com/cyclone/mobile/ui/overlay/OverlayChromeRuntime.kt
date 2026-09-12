package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.ai.CycloneAiAccessProfile
import com.cyclone.mobile.ai.CycloneAiAccessProfileStore
import com.cyclone.mobile.ai.OpenRouterAdaptiveAgent
import com.cyclone.mobile.ai.OverlayChromeController
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ai.QuickAgentConfig
import com.cyclone.mobile.ai.QuickAgentResult
import com.cyclone.mobile.runtime.background.*
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Facade that attaches one Compose overlay window to the existing Accessibility service.
 * Overlay buttons change Cyclone controller state only; they never click host nodes.
 */
object OverlayChromeRuntime {
    private val lock = Any()
    private val aiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cycloneState = object : OverlayCycloneStateEffects {
        override fun pauseAgentForUser() {
            DeviceState.setController(DeviceState.Controller.HUMAN)
        }

        override fun resumeAgent() {
            DeviceState.setController(DeviceState.Controller.AGENT)
        }
    }

    private var machine = OverlayChromeMachine(
        emit = OverlayChromeBus::publish,
        cycloneState = cycloneState,
    )
    private val mutableActivity = kotlinx.coroutines.flow.MutableStateFlow(machine.state())
    val activity: kotlinx.coroutines.flow.StateFlow<OverlayChromeState> = mutableActivity
    private var controller: OverlayChromeController? = null
    private var service: CycloneAccessibilityService? = null
    private var aiJob: Job? = null
    private var workspaceJob: Job? = null
    private var adaptiveAgent: OpenRouterAdaptiveAgent? = null
    private var foregroundTaskId: String? = null
    private var foregroundResuming = false
    private var suspendedTaskId: String? = null

    private data class GateChallenge(
        val gateClass: OverlayGateClass,
        val action: String,
        val signature: String,
        val sessionId: String,
        val expiresAtMs: Long,
    )

    private var pendingGateChallenge: GateChallenge? = null
    private var approvedGateChallenge: GateChallenge? = null

    fun isAttached(): Boolean = synchronized(lock) { controller != null }

    fun snapshot(): OverlayChromeSnapshot = synchronized(lock) { machine.snapshot() }

    fun attach(service: CycloneAccessibilityService) {
        synchronized(lock) {
            if (controller != null && this.service === service) {
                controller?.render(machine.snapshot())
                return
            }
            workspaceJob?.cancel()
            controller?.dismiss()
            this.service = service
            val next = OverlayChromeController(
                service = service,
                onAction = { action -> dispatch(action) },
                onComposerChanged = { text -> updateComposer(text) },
                onRequestSubmitted = { text -> submitRequest(text) },
                onVoiceStateChanged = { listening, transcript, message ->
                    updateVoice(listening, transcript, message)
                },
                getAiSettings = { readAiSettings(service) },
                onAiSettingsChanged = { settings -> saveAiSettings(service, settings) },
            )
            controller = next
            next.show(machine.snapshot())
            workspaceJob = aiScope.launch {
                var previousTask: String? = null
                com.cyclone.mobile.runtime.background.WorkspaceTasks.state.collect { task ->
                    if (task?.foreground == true) {
                        controller?.background(task)
                        service?.let { AgentTaskNotificationRuntime.renderTask(it, task) }
                        previousTask = task.taskId
                        return@collect
                    }
                    if (BackgroundGlassPolicy.tearDown(task) || (task == null && previousTask != null)) clearBackgroundChrome()
                    else if (BackgroundGlassPolicy.visible(task)) {
                        if (previousTask != task?.taskId) mutate { it.resetIdle() }
                        controller?.background(task)
                    }
                    previousTask = task?.taskId
                }
            }
        }
    }

    fun detach() {
        val context = synchronized(lock) { service }
        synchronized(lock) {
            workspaceJob?.cancel(); workspaceJob = null
            controller?.dismiss()
            controller = null
            service = null
            adaptiveAgent?.cancelActiveTask()
            adaptiveAgent = null
            suspendedTaskId = null
            pendingGateChallenge = null
            approvedGateChallenge = null
            aiJob?.cancel()
            aiJob = null
            machine = OverlayChromeMachine(
                emit = OverlayChromeBus::publish,
                cycloneState = cycloneState,
            )
        }
        context?.let {
                    foregroundTaskId?.let { id -> WorkspaceTasks.update(id) { task -> task.copy(phase = TaskPhase.STOPPED, resumable = false) } }
                    AgentTaskNotificationRuntime.finish(it, false, "Task stopped.")
                    com.cyclone.mobile.runtime.background.WorkspaceTasks.scheduleQueuePromotion(it)
                }
    }

    fun clearBackgroundChrome() {
        synchronized(lock) {
            OverlayExternalInteraction.active.value = false
            // Task presentation ends; the accessibility-owned entry point does not.
            if (!hasExecutingTask()) machine.resetIdle(idleChipVisible = true)
            mutableActivity.value = machine.state()
            controller?.background(null)
            controller?.render(machine.snapshot())
        }
    }
    /** Device hook: task cleanup retains the launcher; only service detach removes all windows. */
    fun overlayWindowCount(): Int = synchronized(lock) { controller?.attachedWindowCount() ?: 0 }
    fun overlayLauncherCount(): Int = synchronized(lock) { controller?.attachedLauncherCount() ?: 0 }

    fun startAnalysis(
        sessionId: String,
        bullets: List<String> = emptyList(),
        cta: OverlayAnalysisCta = OverlayAnalysisCta.CONFIRM,
    ) {
        mutate { it.startAnalysis(sessionId, bullets, cta) }
    }

    fun enterWorking(sessionId: String = snapshot().sessionId) {
        mutate { it.enterWorking(sessionId) }
    }

    fun enterLive() {
        mutate { it.enterLive() }
    }

    fun enterGate(gateClass: OverlayGateClass, pcAutoApprove: Boolean = false, sessionId: String = snapshot().sessionId) {
        mutate { it.enterGate(gateClass, pcAutoApprove, sessionId) }
    }

    fun completeDone(sessionId: String = snapshot().sessionId) {
        mutate { it.completeDone(sessionId) }
    }

    fun resetIdle(idleChipVisible: Boolean = true) {
        synchronized(lock) {
            pendingGateChallenge = null
            approvedGateChallenge = null
        }
        mutate { it.resetIdle(idleChipVisible) }
    }

    /**
     * Records the exact host action that triggered GATE. Labels are normalized in memory only and
     * never persisted or logged. Confirmation can authorize this exact challenge once.
     */
    fun registerGateChallenge(gateClass: OverlayGateClass, action: String, labels: List<String>) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            pendingGateChallenge = GateChallenge(
                gateClass = gateClass,
                action = action,
                signature = gateSignature(action, labels),
                sessionId = machine.snapshot().sessionId,
                expiresAtMs = now + GATE_CHALLENGE_TTL_MS,
            )
            approvedGateChallenge = null
        }
    }

    fun hasGateApproval(gateClass: OverlayGateClass, action: String, labels: List<String>): Boolean =
        synchronized(lock) {
            val grant = approvedGateChallenge ?: return@synchronized false
            val now = System.currentTimeMillis()
            if (grant.expiresAtMs < now) {
                approvedGateChallenge = null
                return@synchronized false
            }
            val currentSession = machine.snapshot().sessionId
            grant.gateClass == gateClass &&
                grant.action == action &&
                grant.signature == gateSignature(action, labels) &&
                (grant.sessionId.isBlank() || grant.sessionId == currentSession)
        }

    fun consumeGateApproval(gateClass: OverlayGateClass, action: String, labels: List<String>): Boolean =
        synchronized(lock) {
            val grant = approvedGateChallenge ?: return@synchronized false
            val now = System.currentTimeMillis()
            if (grant.expiresAtMs < now) {
                approvedGateChallenge = null
                return@synchronized false
            }
            val currentSession = machine.snapshot().sessionId
            val matches = grant.gateClass == gateClass &&
                grant.action == action &&
                grant.signature == gateSignature(action, labels) &&
                (grant.sessionId.isBlank() || grant.sessionId == currentSession)
            if (matches) approvedGateChallenge = null
            matches
        }

    fun dispatch(action: OverlayUserAction) {
        val before = snapshot()
        if (action == OverlayUserAction.TAKE_CONTROL) {
            WorkspaceTasks.state.value?.takeIf { it.foreground && it.taskId == foregroundTaskId }?.let { task ->
                commandForegroundTask(task.taskId, if (before.userPaused) "resume" else "handoff")
                return
            }
        }
        if (action == OverlayUserAction.GATE_CONFIRM) approvePendingGateChallenge(before)
        mutate { it.dispatch(action) }
        when (action) {
            OverlayUserAction.EXIT, OverlayUserAction.STOP_TASK -> {
                val context = synchronized(lock) { service }
                synchronized(lock) {
                    adaptiveAgent?.cancelActiveTask()
                    adaptiveAgent = null
                    suspendedTaskId = null
                    pendingGateChallenge = null
                    approvedGateChallenge = null
                    aiJob?.cancel()
                    aiJob = null
                }
                context?.let {
                    foregroundTaskId?.let { id -> WorkspaceTasks.update(id) { task -> task.copy(phase = TaskPhase.STOPPED, resumable = false) } }
                    AgentTaskNotificationRuntime.finish(it, false, "Task stopped.")
                    com.cyclone.mobile.runtime.background.WorkspaceTasks.scheduleQueuePromotion(it)
                }
            }
            OverlayUserAction.GATE_CONFIRM -> resumeSuspendedTask()
            OverlayUserAction.TAKE_CONTROL -> {
                if (before.userPaused && DeviceState.controller == DeviceState.Controller.AGENT) {
                    resumeSuspendedTask()
                }
            }
            else -> Unit
        }
    }

    fun updateComposer(text: String) {
        mutate { it.updateComposer(text) }
    }

    /** Composer animation is not execution ownership. Suspended/GATE tasks still own their slot. */
    fun hasExecutingTask(): Boolean = synchronized(lock) {
        aiJob?.isActive == true || suspendedTaskId != null || pendingGateChallenge != null
    }

    fun submitRequest(text: String) {
        val request = text.trim().take(2_000)
        if (request.isBlank()) return
        val context = synchronized(lock) { service } ?: return
        val busy = !com.cyclone.mobile.runtime.background.WorkspaceTasks.canStartRequest()
        if (busy) {
            runCatching { com.cyclone.mobile.runtime.background.WorkspaceTasks.queueRequest(request) }
                .onSuccess { updateComposer("") }
                .onFailure { android.widget.Toast.makeText(context, it.message, android.widget.Toast.LENGTH_LONG).show() }
            return
        }
        // Only explicit intent selects isolation. Naming an app is ordinary foreground use.
        val target = com.cyclone.mobile.runtime.background.ExecutionTargetResolver.resolve(request)
        if (target is com.cyclone.mobile.runtime.background.ExecutionTarget.Profile) {
            android.widget.Toast.makeText(context, "Open the requested profile in Profiles before continuing.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val apps = context.packageManager.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != context.packageName }.distinctBy { it.activityInfo.packageName }
        val matches = apps.filter { app ->
            val label = app.loadLabel(context.packageManager).toString()
            label.length >= 3 && Regex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(label) + "(?![\\p{L}\\p{N}])").containsMatchIn(request)
        }
        if (target == com.cyclone.mobile.runtime.background.ExecutionTarget.BackgroundWorkspace) {
            synchronized(lock) { adaptiveAgent?.cancelActiveTask(); aiJob?.cancel() }
            if (matches.size == 1) {
                val app = matches.single()
                runCatching { com.cyclone.mobile.runtime.background.WorkspaceTasks.start(context, request,
                    app.activityInfo.packageName, app.loadLabel(context.packageManager).toString()) }
                    .onFailure { error -> clearBackgroundChrome(); android.widget.Toast.makeText(context, error.message ?: "Open Background tasks setup to recheck access.", android.widget.Toast.LENGTH_LONG).show() }
                updateComposer("")
            } else {
                context.startActivity(android.content.Intent(context, com.cyclone.mobile.runtime.background.WorkspaceActivity::class.java)
                    .putExtra("goal", request).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        val accepted = synchronized(lock) {
            pendingGateChallenge = null
            approvedGateChallenge = null
            if (machine.state() !in setOf(OverlayChromeState.ANALYSIS, OverlayChromeState.WORKING, OverlayChromeState.LIVE)) {
                machine.startAnalysis(java.util.UUID.randomUUID().toString())
            }
            val before = machine.snapshot()
            machine.submitRequest(request)
            val changed = before.state == OverlayChromeState.ANALYSIS ||
                before.state == OverlayChromeState.WORKING ||
                before.state == OverlayChromeState.LIVE
            mutableActivity.value = machine.state()
            controller?.render(machine.snapshot())
            changed
        }
        if (accepted) {
            val launch = matches.singleOrNull()?.takeIf {
                !PendingTaskAttachment.present.value && com.cyclone.mobile.runtime.background.ExecutionTargetResolver.isSimpleLaunch(
                    request, it.loadLabel(context.packageManager).toString())
            }?.activityInfo?.packageName
            runAiRequest(request, launch)
        }
    }

    fun updateVoice(listening: Boolean, transcript: String? = null, message: String? = null) {
        mutate { it.updateVoice(listening, transcript, message) }
    }

    fun beginVoiceInput() {
        synchronized(lock) { controller?.beginVoiceInput() }
    }

    private fun runAiRequest(request: String, launchPackage: String? = null) {
        val context = synchronized(lock) { service } ?: return
        synchronized(lock) {
            aiJob?.cancel()
            adaptiveAgent?.cancelActiveTask()
        }
        val shared = WorkspaceTaskUi("foreground-${java.util.UUID.randomUUID()}", "default-foreground",
            "your app", launchPackage ?: "", request, phase = TaskPhase.WORKING, displayId = 0)
        WorkspaceTasks.publishStart(shared)
        foregroundTaskId = shared.taskId
        AgentTaskNotificationRuntime.start(context)
        val agent = OpenRouterAdaptiveAgent(context).also { agent ->
            var revision = 0L
            agent.onOperation = { tool, result ->
                WorkspaceTasks.update(shared.taskId) { task ->
                    if (result == null) { revision = task.controlRevision; TaskHarnessState.begin(task, tool) }
                    else TaskHarnessState.finish(task, TaskOperationEvidence("default-foreground", 0, revision,
                        result.androidExecutionOk, result.verification.passed,
                        result.afterObservationId != null && result.afterObservationId != result.beforeObservationId &&
                            result.after?.sessionId == "default-foreground" && result.after?.displayId == 0,
                        result.verification.basis))
                }
            }
        }
        synchronized(lock) {
            adaptiveAgent = agent
            suspendedTaskId = null
        }
        val job = aiScope.launch {
            mutate {
                it.enterWorking()
                // Once execution begins, collapse Cyclone's own accessibility overlay mechanically.
                // Progress belongs in the task notification/run log, not the request composer.
                it.dispatch(OverlayUserAction.MINIMIZE)
                it.updateStatus("Starting…")
            }
            if (launchPackage != null) {
                val outcome = runCatching {
                    val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        com.cyclone.mobile.PhoneToolExecutor.execute(context, com.cyclone.mobile.PhoneToolRequest(
                            java.util.UUID.randomUUID().toString(), "phone.open_app",
                            org.json.JSONObject().put("package", launchPackage)
                                .put("sessionId", "default-foreground").put("displayId", 0)))
                    }
                    check(result.ok) { result.error?.message ?: "Couldn't open the app." }
                    var observed = false
                    repeat(10) {
                        if (com.cyclone.mobile.runtime.background.BackgroundSetup.foregroundPackage() == launchPackage) observed = true
                        if (!observed) kotlinx.coroutines.delay(150)
                    }
                    check(observed) { "Couldn't verify the app opened." }
                }
                outcome.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
                handleAgentResult(QuickAgentResult(outcome.isSuccess,
                    if (outcome.isSuccess) "App opened." else outcome.exceptionOrNull()?.message ?: "Couldn't open the app.",
                    1, "", classification = if (outcome.isSuccess) "COMPLETE" else "FAILED"))
                return@launch
            }
            val settings = readAiSettings(context)
            val accessProfile = CycloneAiAccessProfileStore.read(context)
            val result = agent.execute(
                request,
                QuickAgentConfig(
                    attachment = PendingTaskAttachment.take(),
                    model = OpenRouterModelPresets.byId(settings.modelId).copy(
                        reasoningEffort = settings.reasoningEffort,
                    ),
                    safeMode = accessProfile != CycloneAiAccessProfile.FULL,
                    accessProfile = accessProfile,
                ),
            ) { progress ->
                val clean = progress.trim()
                // Brain consolidation is internal bookkeeping. Do not surface it as a task/composer
                // state; the trace already records the successful local Brain write.
                if (!clean.equals(INTERNAL_BRAIN_UPDATED, ignoreCase = true)) {
                    AgentTaskNotificationRuntime.progress(context, clean)
                    mutate { it.updateStatus("Checking the current page") }
                }
            }
            handleAgentResult(result)
        }
        synchronized(lock) { aiJob = job }
    }

    /** Exact-task service command; retains the original foreground agent and controller machinery. */
    fun commandForegroundTask(id: String, command: String) {
        val task = WorkspaceTasks.state.value?.takeIf { it.foreground && it.taskId == id && id == foregroundTaskId } ?: return
        when (command) {
            "handoff", "pause" -> {
                if (!task.working && task.interruption?.canTakeOver != true && task.phase != TaskPhase.DONE) return
                WorkspaceTasks.update(id) { it.copy(phase = TaskPhase.HUMAN) }
                DeviceState.setController(DeviceState.Controller.HUMAN)
                if (!snapshot().userPaused) mutate { it.dispatch(OverlayUserAction.TAKE_CONTROL) }
            }
            "resume" -> {
                if (task.interruption?.canResumeAfterHuman != true) return
                val prior = synchronized(lock) {
                    if (foregroundResuming) return
                    foregroundResuming = true
                    aiJob
                }
                aiScope.launch {
                    try {
                        prior?.join()
                        val current = WorkspaceTasks.state.value
                        if (current?.taskId != id || current.controlRevision != task.controlRevision ||
                            current.interruption?.canResumeAfterHuman != true) return@launch
                        DeviceState.setController(DeviceState.Controller.AGENT)
                        if (snapshot().userPaused) mutate { it.dispatch(OverlayUserAction.TAKE_CONTROL) }
                        resumeSuspendedTask()
                    } finally {
                        synchronized(lock) { foregroundResuming = false }
                    }
                }
            }
            "cancel" -> {
                dispatch(OverlayUserAction.STOP_TASK)
                WorkspaceTasks.clearClosedTask(id, task.sessionId)
                service?.let { AgentTaskNotificationRuntime.cancel(it) }
                foregroundTaskId = null
            }
        }
    }

    private fun resumeSuspendedTask() {
        val agent = synchronized(lock) { adaptiveAgent } ?: return
        val taskId = synchronized(lock) { suspendedTaskId } ?: return
        val context = synchronized(lock) { service } ?: return
        val job = aiScope.launch {
            mutate { machine ->
                when (machine.state()) {
                    OverlayChromeState.DONE -> {
                        machine.startAnalysis(taskId)
                        machine.enterWorking(taskId)
                    }
                    OverlayChromeState.LIVE -> machine.enterWorking(taskId)
                    OverlayChromeState.WORKING -> Unit
                    else -> return@mutate
                }
                machine.updateStatus("Re-observing after handoff…")
            }
            foregroundTaskId?.let { id -> WorkspaceTasks.update(id) { it.copy(phase = TaskPhase.WORKING, message = "Checking the current page") } }
            AgentTaskNotificationRuntime.progress(context, "Checking the current page")
            val result = agent.resume { progress ->
                val clean = progress.trim()
                if (!clean.equals(INTERNAL_BRAIN_UPDATED, ignoreCase = true)) {
                    AgentTaskNotificationRuntime.progress(context, clean)
                    mutate { it.updateStatus("Checking the current page") }
                }
            }
            handleAgentResult(result)
        }
        synchronized(lock) { aiJob = job }
    }

    private fun handleAgentResult(result: QuickAgentResult) {
        val context = synchronized(lock) { service }
        foregroundTaskId?.let { id -> WorkspaceTasks.update(id) { task ->
            if (!task.working && result.classification == "HUMAN_OR_GATE") task.copy(resumable = true)
            else task.copy(phase = when (result.classification) {
                "COMPLETE" -> TaskPhase.DONE
                "HUMAN_OR_GATE" -> TaskPhase.REVIEW
                "CANCELLED" -> TaskPhase.STOPPED
                else -> TaskPhase.FAILED
            }, resumable = result.classification == "HUMAN_OR_GATE")
        } }
        when (result.classification) {
            "HUMAN_OR_GATE" -> {
                context?.let { AgentTaskNotificationRuntime.waiting(it, result.message) }
                synchronized(lock) { suspendedTaskId = result.taskId }
                val gate = result.gateClass?.let { raw ->
                    runCatching { OverlayGateClass.parse(raw) }.getOrNull()
                }
                if (gate != null) {
                    mutate { it.enterGate(gate, sessionId = result.taskId ?: it.snapshot().sessionId) }
                } else {
                    mutate { machine ->
                        if (machine.state() == OverlayChromeState.WORKING) machine.enterLive()
                        machine.updateStatus(result.message)
                        if (!machine.snapshot().userPaused) machine.dispatch(OverlayUserAction.TAKE_CONTROL)
                    }
                }
            }
            "COMPLETE" -> {
                context?.let { AgentTaskNotificationRuntime.finish(it, true, result.message) }
                synchronized(lock) {
                    suspendedTaskId = null
                    adaptiveAgent = null
                }
                when (snapshot().state) {
                    OverlayChromeState.GATE, OverlayChromeState.IDLE -> Unit
                    OverlayChromeState.WORKING, OverlayChromeState.LIVE -> mutate { it.completeDone() }
                    OverlayChromeState.DONE -> Unit
                    else -> Unit
                }
            }
            "CANCELLED" -> {
                context?.let { AgentTaskNotificationRuntime.finish(it, false, result.message) }
                synchronized(lock) {
                    suspendedTaskId = null
                    adaptiveAgent = null
                }
                mutate { it.finishStopped(result.message) }
            }
            else -> {
                context?.let { AgentTaskNotificationRuntime.finish(it, false, result.message) }
                synchronized(lock) { suspendedTaskId = null; adaptiveAgent = null }
                mutate { it.finishStopped(result.message) }
            }
        }
        if (result.classification != "HUMAN_OR_GATE") {
            context?.let { com.cyclone.mobile.runtime.background.WorkspaceTasks.scheduleQueuePromotion(it) }
        }
    }

    private fun mutate(block: (OverlayChromeMachine) -> Unit) {
        synchronized(lock) {
            block(machine)
            mutableActivity.value = machine.state()
            controller?.render(machine.snapshot())
        }
    }

    private fun approvePendingGateChallenge(before: OverlayChromeSnapshot) {
        synchronized(lock) {
            val pending = pendingGateChallenge ?: return
            val now = System.currentTimeMillis()
            if (before.state != OverlayChromeState.GATE ||
                before.gateClass != pending.gateClass ||
                pending.expiresAtMs < now ||
                (pending.sessionId.isNotBlank() && pending.sessionId != before.sessionId)
            ) {
                pendingGateChallenge = null
                return
            }
            approvedGateChallenge = pending.copy(expiresAtMs = now + GATE_APPROVAL_TTL_MS)
            pendingGateChallenge = null
        }
    }

    private fun gateSignature(action: String, labels: List<String>): String =
        buildString {
            append(action.trim().lowercase())
            append('|')
            labels.asSequence()
                .map { it.trim().lowercase().replace(Regex("\\s+"), " ") }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .forEach { label -> append(label).append('|') }
        }

    private const val GATE_CHALLENGE_TTL_MS = 60_000L
    private const val GATE_APPROVAL_TTL_MS = 30_000L
    private const val INTERNAL_BRAIN_UPDATED = "Cyclone Brain updated"

    private fun readAiSettings(context: Context): OverlayAiSettings {
        val prefs = context.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE)
        val savedModel = prefs.getString(MODEL_KEY, OpenRouterModelPresets.DEFAULT.id).orEmpty()
        val modelId = com.cyclone.mobile.ai.model.ModelRegistry.resolve(savedModel)?.let(com.cyclone.mobile.ai.model.ModelRegistry::preset)?.id
            ?: OpenRouterModelPresets.DEFAULT.id
        val effort = prefs.getString(EFFORT_KEY, "medium").orEmpty()
            .takeIf { it in REASONING_LEVELS } ?: "medium"
        return OverlayAiSettings(modelId, effort)
    }

    private fun saveAiSettings(context: Context, settings: OverlayAiSettings) {
        val modelId = settings.modelId.takeIf { id -> OpenRouterModelPresets.all.any { it.id == id } }
            ?: OpenRouterModelPresets.DEFAULT.id
        val effort = settings.reasoningEffort.takeIf { it in REASONING_LEVELS } ?: "medium"
        context.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE).edit()
            .putString(MODEL_KEY, modelId)
            .putString(EFFORT_KEY, effort)
            .apply()
    }

    private const val AI_PREFS = "cyclone_ai"
    private const val MODEL_KEY = "openrouter_model"
    private const val EFFORT_KEY = "openrouter_reasoning_effort"
    private val REASONING_LEVELS = setOf("low", "medium", "high", "max")
}
