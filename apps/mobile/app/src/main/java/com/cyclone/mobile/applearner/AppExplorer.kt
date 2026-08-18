package com.cyclone.mobile.applearner

import android.content.Context
import android.content.pm.PackageManager
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.PhoneToolRequest
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AppExplorer(
    private val context: Context,
    private val store: AppKnowledgeStore,
    private val aiPlanner: AppLearnerAiPlanner = AppLearnerAiPlanner(context),
) {
    data class SessionConfig(
        val packageName: String,
        val appLabel: String,
        val instruction: String,
        val mode: LearningMode,
        val maxTransitions: Int = if (mode == LearningMode.TASK) 16 else 32,
        val useAiPlanner: Boolean = true,
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val paused = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private var sessionId: String? = null
    private var config: SessionConfig? = null
    private var startedAt: Long = 0L
    private val sessionActions = mutableSetOf<String>()
    private val backStack = ArrayDeque<String>()
    private var passivePreviousScreenId: String? = null
    private var passivePendingAction: LearnedAction? = null
    @Volatile private var progress = LearnerProgress()
    @Volatile private var progressListener: ((LearnerProgress) -> Unit)? = null

    fun progress(): LearnerProgress = progress
    fun setProgressListener(listener: ((LearnerProgress) -> Unit)?) { progressListener = listener }

    fun start(value: SessionConfig) {
        stopInternal(markStopped = false)
        config = value
        startedAt = System.currentTimeMillis()
        sessionId = UUID.randomUUID().toString()
        stopped.set(false)
        paused.set(false)
        sessionActions.clear()
        backStack.clear()
        passivePreviousScreenId = null
        passivePendingAction = null
        publish(LearnerProgress(
            sessionId = sessionId,
            packageName = value.packageName,
            appLabel = value.appLabel,
            mode = value.mode,
            state = LearnerSessionState.STARTING,
            instruction = value.instruction,
            currentActivity = if (value.mode == LearningMode.PASSIVE) "Waiting for you to use the selected app" else "Opening selected app",
        ))
        persistSession()
        upsertAppMetadata(value)
        if (value.mode == LearningMode.PASSIVE) {
            publish(progress.copy(state = LearnerSessionState.LEARNING, currentActivity = "Passively learning selected-app navigation"))
            persistSession()
            return
        }
        executor.submit { runExploration(value) }
    }

    fun pause() {
        paused.set(true)
        publish(progress.copy(state = LearnerSessionState.PAUSED, currentActivity = "Paused by user"))
        persistSession()
    }

    fun resume() {
        if (stopped.get()) return
        paused.set(false)
        publish(progress.copy(state = LearnerSessionState.LEARNING, currentActivity = "Continuing learning"))
        persistSession()
    }

    fun takeOver() {
        paused.set(true)
        DeviceState.setController(DeviceState.Controller.HUMAN)
        publish(progress.copy(state = LearnerSessionState.WAITING_FOR_HUMAN, currentActivity = "You have control. Return control when ready."))
        persistSession()
    }

    fun returnFromTakeover() {
        if (stopped.get()) return
        DeviceState.setController(DeviceState.Controller.AGENT)
        val observed = PhoneToolExecutor.execute(context, PhoneToolRequest("learner-resume-${UUID.randomUUID()}", "phone.observe", JSONObject()))
        if (!observed.ok) {
            publish(progress.copy(state = LearnerSessionState.WAITING_FOR_HUMAN, message = observed.error?.message ?: "Fresh observation failed"))
            return
        }
        paused.set(false)
        publish(progress.copy(state = LearnerSessionState.LEARNING, currentActivity = "Resumed from fresh phone state"))
        persistSession()
    }

    fun stop() = stopInternal(markStopped = true)

    fun updateInstruction(instruction: String) {
        val current = config ?: return
        config = current.copy(instruction = instruction)
        publish(progress.copy(instruction = instruction, currentActivity = "Learning focus updated"))
        persistSession()
    }

    fun notePassiveAction(node: JSONObject) {
        val cfg = config ?: return
        if (cfg.mode != LearningMode.PASSIVE || progress.state !in setOf(LearnerSessionState.LEARNING, LearnerSessionState.PAUSED)) return
        if (paused.get() || stopped.get()) return
        val label = node.optString("text").trim().ifBlank { node.optString("contentDescription").trim() }
            .ifBlank { node.optString("resourceId").substringAfterLast('/') }
        if (label.isBlank()) return
        passivePendingAction = LearnedAction(
            packageName = cfg.packageName,
            screenId = passivePreviousScreenId ?: "passive-pending",
            semanticName = ScreenSemanticizer.slugify(label).ifBlank { "user_action" },
            label = label.take(120),
            androidActions = listOf("USER_DEMONSTRATED"),
            selectorJson = JSONObject().apply {
                node.optString("resourceId").takeIf { it.isNotBlank() }?.let { put("resourceId", it) }
                node.optString("text").takeIf { it.isNotBlank() && !ActionSafetyPolicy.looksSensitiveField(node) }?.let { put("text", it.take(180)) }
                node.optString("contentDescription").takeIf { it.isNotBlank() && !ActionSafetyPolicy.looksSensitiveField(node) }?.let { put("contentDescription", it.take(180)) }
                node.optString("role").takeIf { it.isNotBlank() }?.let { put("role", it) }
            }.toString(),
            risk = ActionSafetyPolicy.classify(label, node.optString("resourceId"), node.optString("contentDescription")),
            knowledgeState = KnowledgeState.UNDERSTOOD,
            confidence = 0.78,
        )
    }

    fun observePassive(snapshot: JSONObject) {
        val cfg = config ?: return
        if (cfg.mode != LearningMode.PASSIVE || paused.get() || stopped.get()) return
        if (snapshot.optString("package") != cfg.packageName) return
        executor.submit {
            val screen = learnSnapshot(cfg, snapshot)
            val previous = passivePreviousScreenId
            val action = passivePendingAction
            if (previous != null && previous != screen.id && action != null) {
                val normalized = action.copy(screenId = previous)
                store.upsertAction(normalized)
                val stored = store.listActions(cfg.packageName).lastOrNull { it.screenId == previous && it.semanticName == normalized.semanticName }
                if (stored != null) {
                    store.upsertTransition(LearnedTransition(
                        packageName = cfg.packageName,
                        fromScreenId = previous,
                        actionId = stored.id,
                        toScreenId = screen.id,
                        knowledgeState = KnowledgeState.UNDERSTOOD,
                        confidence = 0.76,
                    ))
                }
            }
            passivePreviousScreenId = screen.id
            passivePendingAction = null
            updateCounts("Observed ${screen.title} while you used the app")
        }
    }

    private fun runExploration(initial: SessionConfig) {
        DeviceState.setController(DeviceState.Controller.AGENT)
        PhoneToolExecutor.execute(context, PhoneToolRequest("learner-fresh-${UUID.randomUUID()}", "phone.observe", JSONObject()))
        val open = PhoneToolExecutor.execute(
            context,
            PhoneToolRequest("learner-open-${UUID.randomUUID()}", "phone.open_app", JSONObject().put("package", initial.packageName)),
        )
        if (!open.ok) return fail(open.error?.message ?: "Could not open selected app")
        publish(progress.copy(state = LearnerSessionState.LEARNING, currentActivity = "Inspecting the first screen"))

        var transitions = 0
        var consecutiveDeadEnds = 0
        while (!stopped.get() && transitions < initial.maxTransitions) {
            waitIfPaused()
            if (stopped.get()) break
            val cfg = config ?: initial
            val snapshot = observe() ?: return fail("Accessibility observation failed")
            val packageName = snapshot.optString("package")
            if (packageName != cfg.packageName) {
                PhoneToolExecutor.execute(context, PhoneToolRequest("learner-boundary-back-${UUID.randomUUID()}", "phone.back", JSONObject()))
                publish(progress.copy(currentActivity = "Blocked a cross-app transition", message = "Learning is constrained to ${cfg.appLabel}."))
                Thread.sleep(300)
                continue
            }

            val current = learnSnapshot(cfg, snapshot)
            if (backStack.isEmpty() || backStack.last() != current.id) backStack.addLast(current.id)
            updateCounts("Inspecting ${current.title}")

            if (cfg.mode == LearningMode.TASK && goalSatisfied(cfg.instruction, current)) {
                complete("Task knowledge found: ${current.title}")
                return
            }

            val graph = store.graph(cfg.packageName) ?: continue
            val exploredActionIds = graph.transitions.filter { it.fromScreenId == current.id }.map { it.actionId }.toSet()
            val currentActions = graph.actions.filter { it.screenId == current.id }
            val boundaries = currentActions.count { it.risk == ActionRisk.CONSEQUENTIAL || it.risk == ActionRisk.AUTHENTICATION }
            val candidates = currentActions
                .filter { it.id !in exploredActionIds && it.requiredInput == null && it.risk == ActionRisk.SAFE }
                .filterNot { excludedByInstruction(it, cfg.instruction) }
                .filter { it.id !in sessionActions }

            val next = chooseAction(cfg, current, graph, candidates)
            if (next == null) {
                consecutiveDeadEnds++
                if (boundaries > 0 && candidates.isEmpty() && backStack.size <= 1) {
                    complete("Mapped this safe area and stopped before ${boundaries} approval/authentication boundary actions.")
                    return
                }
                if (backStack.size > 1) {
                    backStack.removeLast()
                    PhoneToolExecutor.execute(context, PhoneToolRequest("learner-back-${UUID.randomUUID()}", "phone.back", JSONObject()))
                    Thread.sleep(350)
                    continue
                }
                if (consecutiveDeadEnds >= 2) {
                    complete("No more unexplored safe transitions were found in the selected area.")
                    return
                }
                Thread.sleep(250)
                continue
            }
            consecutiveDeadEnds = 0
            sessionActions += next.id
            publish(progress.copy(currentActivity = "Exploring ${next.label}", currentScreen = current.title))
            val beforeFingerprint = snapshot.optString("fingerprint")
            val clickParams = JSONObject().put("selector", JSONObject(next.selectorJson)).put("retries", 1).put("waitForChangeMs", 1200)
            val clicked = PhoneToolExecutor.execute(context, PhoneToolRequest("learner-click-${UUID.randomUUID()}", "phone.click", clickParams))
            if (!clicked.ok) {
                store.markActionFailure(next.id)
                continue
            }
            Thread.sleep(220)
            val after = observe() ?: continue
            val afterPackage = after.optString("package")
            if (afterPackage != cfg.packageName) {
                store.markActionFailure(next.id)
                markCrossApp(next)
                PhoneToolExecutor.execute(context, PhoneToolRequest("learner-return-${UUID.randomUUID()}", "phone.back", JSONObject()))
                publish(progress.copy(currentActivity = "Blocked ${next.label} because it left ${cfg.appLabel}"))
                Thread.sleep(350)
                continue
            }
            val target = learnSnapshot(cfg, after)
            if (after.optString("fingerprint") == beforeFingerprint && target.id == current.id) {
                store.markActionFailure(next.id)
                continue
            }
            store.markActionSuccess(next.id)
            store.upsertTransition(LearnedTransition(
                packageName = cfg.packageName,
                fromScreenId = current.id,
                actionId = next.id,
                toScreenId = target.id,
                knowledgeState = KnowledgeState.UNDERSTOOD,
                confidence = minOf(next.confidence, target.confidence, 0.86),
            ))
            transitions++
            backStack.addLast(target.id)
            updateCounts("Learned ${current.title} → ${target.title}")
            store.mirror(cfg.packageName)
        }
        if (!stopped.get()) complete("Learning limit reached. You can continue this app later.")
    }

    private fun learnSnapshot(cfg: SessionConfig, snapshot: JSONObject): LearnedScreen {
        val candidate = ScreenSemanticizer.fromSnapshot(cfg.packageName, snapshot)
        val match = store.findBestScreenMatch(cfg.packageName, candidate.recognition)
        val packageInfo = runCatching { context.packageManager.getPackageInfo(cfg.packageName, 0) }.getOrNull()
        val screenshot = capturePreview()
        val screen = if (match != null) {
            val existing = match.first
            existing.copy(
                title = if (candidate.title != "Screen") candidate.title else existing.title,
                purpose = if (existing.purpose.startsWith("A learned screen")) candidate.purpose else existing.purpose,
                recognition = candidate.recognition,
                knowledgeState = if (match.second >= 0.90) KnowledgeState.UNDERSTOOD else existing.knowledgeState,
                confidence = maxOf(existing.confidence, match.second.coerceIn(0.55, 0.96)),
                appVersion = packageInfo?.versionName,
                lastSeenAt = System.currentTimeMillis(),
                screenshotPath = screenshot ?: existing.screenshotPath,
            )
        } else {
            LearnedScreen(
                packageName = cfg.packageName,
                identity = uniqueIdentity(candidate.identity),
                title = candidate.title,
                purpose = candidate.purpose,
                recognition = candidate.recognition,
                knowledgeState = KnowledgeState.DISCOVERED,
                confidence = 0.64,
                appVersion = packageInfo?.versionName,
                screenshotPath = screenshot,
            )
        }
        store.upsertScreen(screen)
        candidate.actions.forEach { store.upsertAction(it.copy(screenId = screen.id)) }
        upsertAppMetadata(cfg)
        return screen
    }

    private fun chooseAction(cfg: SessionConfig, current: LearnedScreen, graph: AppGraphSnapshot, candidates: List<LearnedAction>): LearnedAction? {
        if (candidates.isEmpty()) return null
        val terms = AppGraphRetriever.goalTerms(cfg.instruction)
        val deterministic = candidates.sortedByDescending { action ->
            val hay = (action.label + " " + action.semanticName).lowercase()
            terms.count { term -> hay.contains(term) } * 4.0 + action.confidence
        }
        val topHits = deterministic.firstOrNull()?.let { action -> terms.count { (action.label + " " + action.semanticName).lowercase().contains(it) } } ?: 0
        if (!cfg.useAiPlanner || cfg.instruction.isBlank() || candidates.size <= 2 || topHits > 0) return deterministic.firstOrNull()
        val plannedId = aiPlanner.chooseNext(cfg.appLabel, cfg.packageName, cfg.instruction, current, candidates, graph)
        return candidates.firstOrNull { it.id == plannedId } ?: deterministic.firstOrNull()
    }

    private fun goalSatisfied(instruction: String, screen: LearnedScreen): Boolean {
        val terms = AppGraphRetriever.goalTerms(instruction)
        if (terms.isEmpty()) return false
        val hay = (screen.title + " " + screen.identity + " " + screen.purpose + " " + screen.recognition.stableAnchors.joinToString(" ")).lowercase()
        val matches = terms.count(hay::contains)
        return matches >= minOf(2, terms.size) && screen.confidence >= 0.58
    }

    private fun excludedByInstruction(action: LearnedAction, instruction: String): Boolean {
        val lower = instruction.lowercase()
        val label = (action.label + " " + action.semanticName).lowercase()
        val forbiddenPhrases = listOf("don't", "do not", "avoid", "ignore", "never touch", "stop before")
        return forbiddenPhrases.any { marker ->
            val pos = lower.indexOf(marker)
            if (pos < 0) false else {
                val tail = lower.substring(pos + marker.length).take(80)
                AppGraphRetriever.goalTerms(tail).any(label::contains)
            }
        }
    }

    private fun observe(): JSONObject? {
        val result = PhoneToolExecutor.execute(context, PhoneToolRequest("learner-observe-${UUID.randomUUID()}", "phone.observe", JSONObject()))
        return result.payload as? JSONObject
    }

    private fun capturePreview(): String? {
        val result = PhoneToolExecutor.execute(context, PhoneToolRequest("learner-shot-${UUID.randomUUID()}", "phone.screenshot", JSONObject()))
        return (result.payload as? JSONObject)?.optString("filePath")?.takeIf { it.isNotBlank() }
    }

    private fun uniqueIdentity(base: String): String {
        val cfg = config ?: return base
        val known = store.listScreens(cfg.packageName).map { it.identity }.toSet()
        if (base !in known) return base
        var n = 2
        while ("${base}_$n" in known) n++
        return "${base}_$n"
    }

    private fun markCrossApp(action: LearnedAction) {
        store.markActionFailure(action.id)
    }

    private fun updateCounts(activity: String) {
        val cfg = config ?: return
        val graph = store.graph(cfg.packageName)
        val actions = graph?.actions.orEmpty()
        publish(progress.copy(
            state = if (paused.get()) LearnerSessionState.PAUSED else LearnerSessionState.LEARNING,
            currentActivity = activity,
            screens = graph?.screens?.size ?: 0,
            actions = actions.size,
            transitions = graph?.transitions?.size ?: 0,
            forms = actions.count { it.requiredInput != null },
            unknownAreas = actions.count { it.risk == ActionRisk.UNKNOWN || it.knowledgeState == KnowledgeState.STALE },
            approvalBoundaries = actions.count { it.risk == ActionRisk.CONSEQUENTIAL || it.risk == ActionRisk.AUTHENTICATION },
        ))
        persistSession()
    }

    private fun complete(message: String) {
        val cfg = config ?: return
        val graph = store.graph(cfg.packageName)
        val confidence = graph?.let { g ->
            val screenAvg = g.screens.map { it.confidence }.average().takeUnless { it.isNaN() } ?: 0.0
            val transitionAvg = g.transitions.map { it.confidence }.average().takeUnless { it.isNaN() } ?: 0.0
            (screenAvg * 0.55 + transitionAvg * 0.45).coerceIn(0.0, 0.98)
        } ?: 0.0
        store.getApp(cfg.packageName)?.let { app ->
            store.upsertApp(app.copy(
                knowledgeState = if ((graph?.transitions?.size ?: 0) > 0) KnowledgeState.UNDERSTOOD else KnowledgeState.DISCOVERED,
                confidence = confidence,
                lastLearnedAt = System.currentTimeMillis(),
                instructionSummary = cfg.instruction.take(500),
            ))
        }
        store.mirror(cfg.packageName)
        publish(progress.copy(state = LearnerSessionState.COMPLETE, currentActivity = "Learning complete", message = message))
        persistSession(endedAt = System.currentTimeMillis())
    }

    private fun fail(message: String) {
        publish(progress.copy(state = LearnerSessionState.FAILED, currentActivity = "Learning stopped", message = message))
        persistSession(endedAt = System.currentTimeMillis())
    }

    private fun stopInternal(markStopped: Boolean) {
        stopped.set(true)
        paused.set(false)
        if (markStopped && sessionId != null) {
            publish(progress.copy(state = LearnerSessionState.STOPPED, currentActivity = "Stopped by user"))
            persistSession(endedAt = System.currentTimeMillis())
        }
    }

    private fun waitIfPaused() {
        while (paused.get() && !stopped.get()) Thread.sleep(150)
    }

    private fun publish(value: LearnerProgress) {
        progress = value
        progressListener?.invoke(value)
    }

    private fun persistSession(endedAt: Long? = null) {
        val id = sessionId ?: return
        val cfg = config ?: return
        store.saveSession(id, cfg.packageName, cfg.mode, cfg.instruction, progress.state, startedAt, endedAt, JSONObject()
            .put("screens", progress.screens).put("actions", progress.actions).put("transitions", progress.transitions)
            .put("forms", progress.forms).put("unknownAreas", progress.unknownAreas).put("approvalBoundaries", progress.approvalBoundaries)
            .put("message", progress.message ?: JSONObject.NULL))
    }

    private fun upsertAppMetadata(cfg: SessionConfig) {
        val pm = context.packageManager
        val info = runCatching { pm.getPackageInfo(cfg.packageName, 0) }.getOrNull()
        val existing = store.getApp(cfg.packageName)
        store.upsertApp(LearnedApp(
            packageName = cfg.packageName,
            label = cfg.appLabel,
            versionName = info?.versionName,
            versionCode = info?.longVersionCode,
            knowledgeState = existing?.knowledgeState ?: KnowledgeState.DISCOVERED,
            confidence = existing?.confidence ?: 0.0,
            lastLearnedAt = System.currentTimeMillis(),
            lastVerifiedAt = existing?.lastVerifiedAt,
            instructionSummary = cfg.instruction.take(500),
        ))
    }
}
