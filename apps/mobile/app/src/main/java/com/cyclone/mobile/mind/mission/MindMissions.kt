package com.cyclone.mobile.mind.mission

import android.content.Context
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.agent.tools.CycloneAgentEnvironment
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.ai.OpenRouterCatalogStore
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.ProviderCancellation
import com.cyclone.mobile.mind.MindBudget
import com.cyclone.mobile.mind.MindCheckpoint
import com.cyclone.mobile.mind.MindConversation
import com.cyclone.mobile.mind.MindListener
import com.cyclone.mobile.mind.MindLoop
import com.cyclone.mobile.mind.MindMessage
import com.cyclone.mobile.mind.MindModel
import com.cyclone.mobile.mind.MindOutcome
import com.cyclone.mobile.mind.MindPlanStep
import com.cyclone.mobile.mind.MindPrompt
import com.cyclone.mobile.mind.MindStatus
import com.cyclone.mobile.mind.MindToolCall
import com.cyclone.mobile.mind.MindToolResult
import com.cyclone.mobile.mind.OpenRouterMindModel
import com.cyclone.mobile.mind.PhoneMindToolbox
import com.cyclone.mobile.runtime.background.TaskInterruption
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import com.cyclone.mobile.ui.overlay.TaskAttachment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Cyclone Mind on the phone: one mission at a time, one model, one continuous conversation, journaled after every
 * turn so an interrupted mission can pick up where it stopped. This object only wires the engine to Android; the
 * decisions are the model's and the boundaries are the harness's.
 */
object MindMissions {
    private const val PREFS = "cyclone_ai"
    private const val ENABLED_KEY = "mind_runtime_enabled"
    private const val MINUTES_KEY = "mind_mission_minutes"

    val inbox = OwnerInbox()
    private val liveState = MutableStateFlow<Mission?>(null)
    private val historyState = MutableStateFlow<List<Mission>>(emptyList())
    val live: StateFlow<Mission?> = liveState
    val history: StateFlow<List<Mission>> = historyState

    private val lock = Any()
    private var worker: Thread? = null
    @Volatile private var stopRequested = false
    private var cancellation: ProviderCancellation? = null
    private val ownerMessages = ConcurrentLinkedQueue<String>()
    @Volatile private var store: MissionStore? = null
    @Volatile private var recovered = false
    @Volatile private var resumeCandidate: String? = null
    private const val AUTO_RESUME_WINDOW_MS = 5 * 60_000L
    private const val AUTO_RESUME_LIMIT = 3
    private const val QUEUE_START_DELAY_MS = 1_500L
    @Volatile private var memory: com.cyclone.mobile.mind.MindMemory? = null
    @Volatile private var livePlanes: com.cyclone.mobile.runtime.plane.MissionPlaneSession? = null

    private val hooks = object : OverlayChromeRuntime.MissionHooks {
        override fun stop() = MindMissions.stop()
        override fun ownerText(text: String): Boolean = steer(text)

    }

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED_KEY, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED_KEY, enabled).apply()
    }

    fun workingMinutes(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(MINUTES_KEY, 30).coerceIn(5, 120)

    fun setWorkingMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(MINUTES_KEY, minutes.coerceIn(5, 120)).apply()
    }

    /** Learned-screen advice for the Mind; missing knowledge (or a store that will not open) just means no advice. */
    private fun learnedHints(context: Context): ((String, String) -> String?)? = runCatching {
        com.cyclone.mobile.applearner.AppLearnerRuntime.initialize(context.applicationContext)
        val hints = com.cyclone.mobile.mind.learn.LearnedHints(com.cyclone.mobile.mind.learn.AppKnowledgeReader(com.cyclone.mobile.applearner.AppLearnerRuntime.store))
        val fn: (String, String) -> String? = { pkg, key -> hints.forScreen(pkg, key) }
        fn
    }.getOrNull()

    /** The learned maps for one mission, with walks and surprises fed back into the app knowledge store. */
    private fun missionMaps(context: Context): com.cyclone.mobile.mind.map.MindMaps? = runCatching {
        com.cyclone.mobile.applearner.AppLearnerRuntime.initialize(context.applicationContext)
        val store = com.cyclone.mobile.applearner.AppLearnerRuntime.store
        com.cyclone.mobile.mind.map.MindMaps(com.cyclone.mobile.mind.learn.AppKnowledgeReader(store)) { pkg ->
            com.cyclone.mobile.mind.map.AppKnowledgeMapFeedback(store, pkg)
        }
    }.getOrNull()

    fun store(context: Context): MissionStore = store ?: synchronized(lock) {
        store ?: MissionStore(File(context.applicationContext.filesDir, "Cyclone Brain/Missions")).also { store = it }
    }

    fun refresh(context: Context) {
        val missions = store(context)
        if (!recovered) {
            recovered = true
            val now = System.currentTimeMillis()
            // A mission that was working moments ago died with the process, not by the owner's choice.
            resumeCandidate = missions.list().firstOrNull {
                it.status == MissionStatus.RUNNING && it.id != liveState.value?.id &&
                    now - it.updatedAtMs <= AUTO_RESUME_WINDOW_MS && it.resumes < AUTO_RESUME_LIMIT
            }?.id
            missions.recover(now, liveState.value?.id)
        }
        historyState.value = missions.list()
    }

    /**
     * Called when Cyclone's Accessibility service (re)connects. A mission interrupted by a crash or process death a
     * few minutes ago continues on its own; anything older waits for the owner's Resume.
     */
    fun onServiceReady(context: Context) {
        refresh(context)
        // Plan 26: is background work still ready? Told once, quietly, not in the middle of a task.
        runCatching { com.cyclone.mobile.runtime.plane.BackgroundWatch.check(context) }
        val id = resumeCandidate ?: return
        resumeCandidate = null
        if (!enabled(context) || isLive()) return
        val mission = store(context).load(id) ?: return
        if (System.currentTimeMillis() - mission.createdAtMs > 3 * 60 * 60_000L) return
        resume(context, id)
    }

    fun isLive(): Boolean = synchronized(lock) { worker?.isAlive == true }

    @Volatile private var queue: MissionQueue? = null

    fun queue(context: Context): MissionQueue = queue ?: synchronized(lock) {
        queue ?: MissionQueue(File(context.applicationContext.filesDir, "Cyclone Brain/Missions/queue.json")).also { queue = it }
    }

    /**
     * Plan 26 (A42-8): owner text while a mission runs. A clearly separate task waits as "Runs next"; anything else
     * steers the running mission as before. Returns the queued goal, or null when it steered.
     */
    fun offer(context: Context, text: String): String? {
        if (!isLive() || !MissionQueue.isNewTask(text)) { steer(text); return null }
        val next = queue(context).add(text) ?: run { steer(text); return null }
        liveState.value?.let { mission ->
            WorkspaceTasks.update("mission-${mission.id}") { it.copy(message = "Runs next: ${next.goal.take(80)}") }
        }
        return next.goal
    }

    /** Starts a new mission. Returns false when another mission is still running. */
    fun start(context: Context, goal: String, attachment: TaskAttachment? = null): Boolean {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val id = "m" + now.toString(36) + UUID.randomUUID().toString().take(8)
        val mission = Mission(id, goal.trim().take(2_000), MissionStatus.RUNNING, now, now, "", "")
        return launch(app, mission, resume = null, attachment = attachment)
    }

    /**
     * Starts a Cyclone Lab mission: same Mind, same boundaries, with [variant] applied to this one mission and the
     * run tagged so the PC can score it. Returns the mission id, or null when another mission is running.
     */
    fun startLab(context: Context, goal: String, runId: String, variant: com.cyclone.mobile.mind.lab.MindLabVariant): String? {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val id = "m" + now.toString(36) + UUID.randomUUID().toString().take(8)
        val mission = Mission(id, goal.trim().take(2_000), MissionStatus.RUNNING, now, now, "", "",
            lab = com.cyclone.mobile.mind.lab.MissionLab(runId, variant))
        return id.takeIf { launch(app, mission, resume = null, attachment = null) }
    }

    /** The live mission's metrics so far (the PC lab polls this), or null when [id] is not the live mission. */
    fun liveMetrics(id: String): org.json.JSONObject? = liveMetrics?.takeIf { liveState.value?.id == id }?.toJson()

    @Volatile private var liveMetrics: com.cyclone.mobile.mind.lab.MissionMetrics? = null
    @Volatile private var liveTrail: com.cyclone.mobile.mind.learn.MindTrailRecorder? = null

    /** Continues a paused, failed or interrupted mission with its full conversation. */
    fun resume(context: Context, id: String): Boolean {
        val app = context.applicationContext
        val missions = store(app)
        val mission = missions.load(id)?.takeIf { it.status.resumable } ?: return false
        val journal = missions.loadJournal(id) ?: return false
        val reason = when (mission.status) {
            MissionStatus.PAUSED -> "its working time ran out and the owner gave it more"
            MissionStatus.INTERRUPTED -> "Cyclone was stopped or restarted"
            else -> "it failed and the owner asked to try again"
        }
        return launch(app, mission.copy(status = MissionStatus.RUNNING, resumes = mission.resumes + 1, summary = ""), journal, null, reason)
    }

    fun stop() {
        stopRequested = true
        com.cyclone.mobile.runtime.plane.MissionPlanes.release()
        synchronized(lock) { cancellation?.cancel() }
        inbox.withdrawAll()
    }

    /**
     * Owner text while a mission runs: it answers the open question, or joins the conversation as a new instruction
     * the model reads at its next turn.
     */
    fun steer(text: String): Boolean {
        if (!isLive()) return false
        val clean = text.trim().take(2_000)
        if (clean.isBlank()) return true
        inbox.pending.value?.let { request ->
            when (request.kind) {
                OwnerRequestKind.QUESTION -> if (inbox.respond(request.id, OwnerResponse.Answer(clean))) return true
                OwnerRequestKind.CONTROL -> if (clean.lowercase() in setOf("done", "ok", "klaar", "go")) {
                    if (inbox.respond(request.id, OwnerResponse.Done)) return true
                }
                else -> Unit
            }
        }
        ownerMessages += clean
        return true
    }

    fun answer(requestId: String, response: OwnerResponse): Boolean = inbox.respond(requestId, response)

    /**
     * "I'm done" from any surface. Whatever the mission is waiting for gets its answer: a hand-back completes, an open
     * question learns the owner did it on the screen, an open check-in card counts as done by hand. Cyclone always
     * gets the phone back.
     */
    fun ownerDone(): Boolean {
        if (!isLive()) return false
        inbox.pending.value?.let { request ->
            when (request.kind) {
                OwnerRequestKind.CONTROL, OwnerRequestKind.VALUES -> inbox.respond(request.id, OwnerResponse.Done)
                OwnerRequestKind.QUESTION -> inbox.respond(request.id,
                    OwnerResponse.Answer("I did it myself on the phone. Look at the screen again."))
                OwnerRequestKind.APPROVAL, OwnerRequestKind.SECRET -> Unit
            }
        }
        OverlayChromeRuntime.missionHandBack()
        return true
    }

    /** The owner took the phone from the task card; the mission's next action waits until they hand it back. */
    fun ownerTakesPhone(): Boolean {
        if (!isLive()) return false
        com.cyclone.mobile.runtime.plane.MissionPlanes.ownerNeedsScreen()
        OverlayChromeRuntime.missionHandoff()
        liveState.value?.let { mission ->
            WorkspaceTasks.update("mission-${mission.id}") {
                it.copy(phase = TaskPhase.HUMAN, message = "You have the phone. Tap I'm done to let Cyclone continue.",
                    interruption = TaskInterruption(reason = "MIND_OWNER_HAS_PHONE", prompt = "You have the phone", canResumeAfterHuman = true))
            }
        }
        return true
    }

    fun delete(context: Context, id: String) {
        if (liveState.value?.id == id) return
        store(context).delete(id)
        refresh(context)
    }

    private fun launch(context: Context, initial: Mission, resume: MissionJournal?, attachment: TaskAttachment?, resumeReason: String = ""): Boolean {
        synchronized(lock) {
            if (worker?.isAlive == true) return false
            stopRequested = false
            ownerMessages.clear()
            cancellation = ProviderCancellation()
            liveState.value = initial
            val thread = Thread({ run(context, initial, resume, attachment, resumeReason) }, "cyclone-mind")
            worker = thread
            OverlayChromeRuntime.attachMission(hooks)
            thread.start()
        }
        return true
    }

    private fun run(context: Context, initial: Mission, resume: MissionJournal?, attachment: TaskAttachment?, resumeReason: String) {
        val missions = store(context)
        var mission = initial
        val taskId = "mission-${mission.id}"
        fun save(change: (Mission) -> Mission) {
            mission = change(mission).copy(updatedAtMs = System.currentTimeMillis())
            liveState.value = mission
            runCatching { missions.save(mission) }
        }
        publishTask(context, taskId, mission)
        MindMissionService.start(context, taskId, (workingMinutes(context) + 60) * 60_000L)
        DeviceState.setController(DeviceState.Controller.AGENT)
        OverlayChromeRuntime.missionWorking(taskId, "Thinking")
        var traceId: String? = null
        var outcome: MindOutcome? = null
        var failure: String? = null
        try {
            val key = OpenRouterSecretStore.read(context)
            require(key.isNotBlank()) { "Add your OpenRouter API key in Cyclone's AI settings first." }
            // A lab variant changes only what it names, for this mission only. Lab runs never fall back to a backup
            // model, so a result always belongs to the model the variant asked for.
            val variant = mission.lab?.variant
            val primaryId = variant?.modelId?.let(OpenRouterCatalogStore::canonicalId) ?: OpenRouterCatalogStore.activeId(context)
            require(primaryId.isNotBlank()) { "Choose a verified model in Cyclone's AI settings first." }
            val backupId = if (variant != null) null else OpenRouterCatalogStore.backupId(context).takeIf { it.isNotBlank() }
            val effort = variant?.effort ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("openrouter_reasoning_effort", "medium")
            traceId = mission.traceId?.takeIf { resume != null } ?: AgentTraceRuntime.start(context, mission.goal, primaryId)
            val trace = traceId
            val cancel = synchronized(lock) { cancellation } ?: ProviderCancellation()
            fun model(id: String): MindModel {
                val preset = OpenRouterCatalogStore.preset(context, id)
                return OpenRouterMindModel(key, id, preset.label, preset.vision, effort, trace, cancel, { stopRequested }) { phase, _ ->
                    if (phase.isNotBlank()) OverlayChromeRuntime.missionStatus("Thinking")
                }
            }
            val primary = model(primaryId)
            val backup = backupId?.let(::model)
            save { it.copy(modelId = primaryId, modelLabel = primary.label, traceId = trace) }
            WorkspaceTasks.update(taskId) { it.copy(traceSessionId = trace) }
            AgentTraceRuntime.event(context, trace, if (resume == null) "MISSION_START" else "MISSION_RESUME",
                if (resume == null) "Mission started with ${primary.label}" else "Mission resumed (${mission.resumes}) with ${primary.label}")

            val device = AndroidMindDevice(context)
            val owner = AndroidMindOwner(context, inbox, mission.id, { stopRequested },
                onWaiting = { question ->
                    waiting(context, taskId, question)
                    save { it.copy(status = if (question == null) MissionStatus.RUNNING else MissionStatus.WAITING, waitingFor = question) }
                },
                onStatus = { text -> status(context, taskId, text) },
                onPlan = { steps -> save { it.copy(plan = steps) }; planToTask(taskId, steps) },
                onHuman = { instruction ->
                    human(context, taskId, instruction)
                    save { it.copy(status = if (instruction == null) MissionStatus.RUNNING else MissionStatus.WAITING, waitingFor = instruction) }
                })
            // Planes (plan 25): the owner's missions may move between the main screen and a background screen. Lab
            // runs stay on the screen so a measurement never depends on where it ran.
            val planes = if (mission.lab == null) com.cyclone.mobile.runtime.plane.MissionPlanes.begin(context, mission.id, mission.goal, trace).also { livePlanes = it } else null
            val environment = planes?.initialEnvironment() ?: CycloneAgentEnvironment(context, userTaskGoal = mission.goal, ownerMission = true)
            // Fresh lab runs neither read nor write the owner's memory: each run starts from the same place.
            val fresh = variant?.freshMemory == true
            val labMemoryFile = if (fresh) File(context.cacheDir, "lab-memory-${mission.id}.json").also { it.delete() } else null
            val memory = labMemoryFile?.let { com.cyclone.mobile.mind.MindMemory(it) } ?: memory(context)
            val trail = com.cyclone.mobile.mind.learn.MindTrailRecorder(mission.id).also { liveTrail = it }
            // The map is on for the owner; a Lab arm can turn it off to measure what it is worth.
            val useMap = variant?.useMap != false
            val toolbox = PhoneMindToolbox(environment, owner, device, mission.goal, { stopRequested }, memory = memory, missionId = mission.id,
                marker = if (variant?.marks == false) null else AndroidMindImageMarker, trail = trail,
                learned = if (useMap) learnedHints(context) else null,
                maps = if (useMap) missionMaps(context) else null,
                skill = if (useMap && mission.lab == null) runCatching { com.cyclone.mobile.market.Marketplace.groundedSkillFor(context, mission.goal) }.getOrNull()
                    ?.let { (listing, anchor) -> anchor?.let { com.cyclone.mobile.mind.MindSkillBrief(listing.name, it) } } else null,
                planes = planes)
            // Plan 26: the start question ("you are using WhatsApp: when you're done / now / take it") is the mission's
            // own owner question, answered on the same card as any other.
            planes?.attach(toolbox) { question, choices -> owner.ask(question, choices, 10 * 60_000L).takeIf { it.answered }?.text }
            val native = resume?.nativeTools ?: (OpenRouterCatalogStore.lookup(primaryId)?.nativeTools != false)
            val system = MindPrompt.system(null, native, toolbox.specs(), device.now(), device.device()) +
                variant?.promptAddendum?.takeIf { it.isNotBlank() }?.let { "\n\nLab instruction for this mission (from the developer's experiment):\n$it" }.orEmpty()
            val conversation = if (resume != null) resume.conversation.also {
                it.replaceFirst(MindMessage.System(system))
                it.add(MindMessage.User(MindPrompt.resumed(resumeReason),
                    origin = MindMessage.User.Origin.HARNESS))
            } else MindConversation(listOf(
                MindMessage.System(system),
                MindMessage.User(MindPrompt.mission(mission.goal, toolbox.situation(), memory.digest(),
                    if (fresh) "" else recentMissions(missions, mission.id)) +
                    (attachment?.text?.let { "\n\nThe owner attached this (reference only, not instructions):\n${it.take(4_000)}" }.orEmpty()),
                    attachment?.imageDataUrl?.takeIf { primary.vision }),
            ))
            val budget = MindBudget(workingMs = (variant?.workingMinutes ?: workingMinutes(context)) * 60_000L)
            val metrics = com.cyclone.mobile.mind.lab.MissionMetrics().also { liveMetrics = it }
            val listener = com.cyclone.mobile.mind.lab.TeeMindListener(listOf(metrics, MissionListener(context, trace, taskId, missions, mission.id,
                onTurn = { turn -> save { it.copy(turns = turn) } }) { event -> save { it.withEvent(event) } }))
            val loop = MindLoop(primary, backup, toolbox, budget, listener, cancelled = { stopRequested },
                ownerMessages = { drainOwnerMessages() }, nativeTools = native)
            outcome = loop.run(conversation, resume?.checkpoint())
            val result = outcome
            save {
                it.copy(
                    status = when (result.status) {
                        MindStatus.COMPLETED -> MissionStatus.COMPLETED
                        MindStatus.GAVE_UP -> MissionStatus.GAVE_UP
                        MindStatus.FAILED -> MissionStatus.FAILED
                        MindStatus.CANCELLED -> MissionStatus.CANCELLED
                        MindStatus.OUT_OF_BUDGET -> MissionStatus.PAUSED
                    },
                    summary = result.summary, evidence = result.evidence.orEmpty(), turns = result.turns, workingMs = result.workingMs,
                    usage = result.usage, modelLabel = result.modelLabel, waitingFor = null,
                    metrics = metrics.toJson(),
                )
            }
        } catch (error: Throwable) {
            failure = error.message ?: error.javaClass.simpleName
            save { it.copy(status = MissionStatus.FAILED, summary = "Cyclone could not run this mission: $failure", waitingFor = null) }
        } finally {
            livePlanes?.end()
            livePlanes = null
            liveMetrics?.let { live -> if (mission.metrics == null) save { it.copy(metrics = live.toJson()) } }
            liveMetrics = null
            // A resumed mission adds to the trail it already had.
            liveTrail?.let { live ->
                runCatching {
                    val now = live.snapshot()
                    val earlier = missions.loadTrail(mission.id)
                    missions.saveTrail(if (earlier == null) now else com.cyclone.mobile.mind.learn.MissionTrail(mission.id,
                        (earlier.screens + now.screens).distinctBy { it.pageKey }, earlier.steps + now.steps))
                }
            }
            liveTrail = null
            // A Lab arm with the map learns every mission as it ends, so its later trials start from what it saw.
            if (mission.lab?.variant?.useMap == true && !mission.status.live) {
                runCatching {
                    (com.cyclone.mobile.mind.learn.MissionLearning.learn(context, mission.id) as? com.cyclone.mobile.mind.learn.LearnOutcome.Learned)
                        ?.let { learned -> mission = mission.copy(learned = learned.report) }
                }
            }
            runCatching { File(context.cacheDir, "lab-memory-${mission.id}.json").delete() }
            // A saved skill that ran to the end keeps its place on the map fresh (plan 23). Lab runs never touch skills.
            if (mission.status == MissionStatus.COMPLETED && mission.lab == null) {
                runCatching { com.cyclone.mobile.market.Marketplace.regroundAfterRun(context, mission.id, mission.goal) }
            }
            val ok = mission.status == MissionStatus.COMPLETED
            traceId?.let { trace ->
                AgentTraceRuntime.finish(context, trace, when (mission.status) {
                    MissionStatus.COMPLETED -> "COMPLETED"
                    MissionStatus.CANCELLED -> "CANCELLED"
                    MissionStatus.PAUSED -> "PAUSED"
                    else -> "FAILED"
                }, mission.summary.take(500), mission.turns)
            }
            finishTask(context, taskId, mission)
            MindMissionService.stop(context)
            OverlayChromeRuntime.missionFinished(taskId, ok, mission.summary.take(200).ifBlank { "Mission ended." })
            synchronized(lock) {
                worker = null
                cancellation = null
                OverlayChromeRuntime.detachMission(hooks)
            }
            inbox.withdrawAll()
            liveState.value = null
            historyState.value = runCatching { missions.list() }.getOrDefault(historyState.value)
            WorkspaceTasks.scheduleQueuePromotion(context)
            // Plan 26 (A42-8): the next queued task starts once this one has fully ended (not after a Stop).
            if (mission.status != MissionStatus.CANCELLED) {
                runCatching { queue(context).take() }.getOrNull()?.let { next ->
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ start(context, next.goal) }, QUEUE_START_DELAY_MS)
                }
            }
        }
    }

    fun memory(context: Context): com.cyclone.mobile.mind.MindMemory = memory ?: synchronized(lock) {
        memory ?: com.cyclone.mobile.mind.MindMemory(File(context.applicationContext.filesDir, "Cyclone Brain/Mind memory.json")).also { memory = it }
    }

    /** The last few missions of the past day, so a follow-up ("now do the same for…") has its context. */
    private fun recentMissions(missions: MissionStore, currentId: String): String {
        val since = System.currentTimeMillis() - 24 * 60 * 60_000L
        val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ENGLISH)
        // Lab missions are experiments, not the owner's history; they never become a follow-up's context.
        return MindPrompt.recentMissions(missions.list().filter { it.id != currentId && it.updatedAtMs >= since && it.lab == null }.map { m ->
            val outcome = m.summary.ifBlank { m.status.name.lowercase() }
            "${format.format(java.util.Date(m.createdAtMs))} \"${MindRedaction.scrub(m.goal).take(140)}\" → ${m.status.name.lowercase()}: ${MindRedaction.scrub(outcome).take(200)}"
        })
    }

    private fun drainOwnerMessages(): List<String> = buildList { while (true) add(ownerMessages.poll() ?: break) }

    // ---- the task card and notification --------------------------------------------------------------------------

    private fun publishTask(context: Context, taskId: String, mission: Mission) {
        val task = WorkspaceTaskUi(taskId, "default-foreground", "Cyclone Mind", "", mission.goal, phase = TaskPhase.WORKING,
            engine = com.cyclone.mobile.task.TaskEngine.MIND,
            message = "On it.", displayId = 0, startedAtMs = System.currentTimeMillis(), traceSessionId = mission.traceId,
            plannedMilestones = mission.plan.map { it.text })
        runCatching { WorkspaceTasks.publishStart(task) }.onFailure { WorkspaceTasks.update(taskId) { task } }
        com.cyclone.mobile.ui.overlay.AgentTaskNotificationRuntime.start(context)
    }

    private fun status(context: Context, taskId: String, text: String) {
        WorkspaceTasks.update(taskId) { it.copy(message = text.take(160), phase = TaskPhase.WORKING, interruption = null) }
        OverlayChromeRuntime.missionStatus(text.take(120))
        com.cyclone.mobile.ui.overlay.AgentTaskNotificationRuntime.progress(context, text)
    }

    /** The owner has the phone for a step: the task card and overlay ribbon offer "I'm done". */
    private fun human(context: Context, taskId: String, instruction: String?) {
        if (instruction == null) {
            WorkspaceTasks.update(taskId) { it.copy(phase = TaskPhase.WORKING, interruption = null) }
            OverlayChromeRuntime.refreshExternalSurface()
            return
        }
        WorkspaceTasks.update(taskId) {
            it.copy(phase = TaskPhase.HUMAN, message = instruction.take(160),
                interruption = TaskInterruption(reason = "MIND_OWNER_HAS_PHONE", prompt = instruction.take(300), canResumeAfterHuman = true))
        }
        OverlayChromeRuntime.missionStatus("Your turn: ${instruction.take(100)}")
        com.cyclone.mobile.ui.overlay.AgentTaskNotificationRuntime.waiting(context, instruction)
        OverlayChromeRuntime.refreshExternalSurface()
    }

    private fun waiting(context: Context, taskId: String, question: String?) {
        // The overlay window changes shape (focusable card) when a check-in card opens or closes.
        OverlayChromeRuntime.refreshExternalSurface()
        if (question == null) {
            WorkspaceTasks.update(taskId) { it.copy(phase = TaskPhase.WORKING, interruption = null) }
            return
        }
        WorkspaceTasks.update(taskId) {
            it.copy(phase = TaskPhase.REVIEW, message = question.take(160),
                interruption = TaskInterruption(reason = "MIND_OWNER_REQUEST", prompt = question.take(300), canResumeAfterHuman = true))
        }
        OverlayChromeRuntime.missionStatus(question.take(120))
        com.cyclone.mobile.ui.overlay.AgentTaskNotificationRuntime.waiting(context, question)
    }

    private fun planToTask(taskId: String, steps: List<MindPlanStep>) {
        WorkspaceTasks.update(taskId) { task ->
            task.copy(plannedMilestones = steps.map { it.text.take(80) },
                plannedMilestoneIndex = steps.indexOfFirst { it.status == "doing" || it.status == "todo" }.coerceAtLeast(0))
        }
    }

    private fun finishTask(context: Context, taskId: String, mission: Mission) {
        val phase = when (mission.status) {
            MissionStatus.COMPLETED -> TaskPhase.DONE
            MissionStatus.CANCELLED -> TaskPhase.STOPPED
            else -> TaskPhase.FAILED
        }
        val message = mission.summary.ifBlank { "Mission ended." }.take(300)
        WorkspaceTasks.update(taskId) { it.copy(phase = phase, message = message, outcome = message, interruption = null,
            resumable = mission.status.resumable) }
        com.cyclone.mobile.ui.overlay.AgentTaskNotificationRuntime.finish(context, mission.status == MissionStatus.COMPLETED, message)
    }

    /** Journals every turn and mirrors the model-visible story into the run trace. Never provider reasoning. */
    private class MissionListener(
        private val context: Context,
        private val traceId: String,
        private val taskId: String,
        private val store: MissionStore,
        private val missionId: String,
        private val onTurn: (Int) -> Unit,
        private val onEvent: (MissionEvent) -> Unit,
    ) : MindListener {
        override fun onModelStart(turn: Int, model: MindModel) {
            OverlayChromeRuntime.missionStatus("Thinking")
            onTurn(turn)
        }

        override fun onAssistant(turn: Int, message: MindMessage.Assistant) {
            val said = MindRedaction.scrub(message.text.trim())
            val calls = message.toolCalls.joinToString { it.name }
            AgentTraceRuntime.event(context, traceId, "MIND_TURN", "Turn $turn: ${said.take(300).ifBlank { calls.ifBlank { "(no action)" } }}",
                code = "MIND_TURN", detail = if (calls.isBlank()) null else "calls: $calls")
            if (said.isNotBlank()) WorkspaceTasks.update(taskId) { it.copy(message = said.take(160)) }
        }

        override fun onToolStart(turn: Int, call: MindToolCall) {
            AgentTraceRuntime.event(context, traceId, "MIND_ACTION", call.name, code = call.name,
                detail = MindRedaction.scrub(call.arguments).take(600))
        }

        override fun onToolResult(turn: Int, call: MindToolCall, result: MindToolResult) {
            val brief = MindRedaction.scrub(result.brief)
            AgentTraceRuntime.event(context, traceId, "MIND_RESULT", brief.take(300), code = call.name, ok = result.ok,
                detail = MindRedaction.scrub(result.text).take(1_500))
            if (call.name !in QUIET_TOOLS) onEvent(MissionEvent(System.currentTimeMillis(), brief.take(200), result.ok))
        }

        override fun onNotice(turn: Int, text: String) {
            AgentTraceRuntime.event(context, traceId, "MIND_NOTICE", text.take(300), code = "MIND_NOTICE")
            onEvent(MissionEvent(System.currentTimeMillis(), text.take(200)))
        }

        override fun checkpoint(checkpoint: MindCheckpoint) {
            runCatching { store.saveJournal(missionId, checkpoint) }
        }

        private companion object {
            val QUIET_TOOLS = setOf("screen_read", "note")
        }
    }
}
