package com.cyclone.mobile.agent.integration

import android.content.Context
import com.cyclone.mobile.agent.CycloneObservation
import com.cyclone.mobile.agent.contract.AgentActionEnvelope
import com.cyclone.mobile.agent.contract.AgentElementCandidate
import com.cyclone.mobile.agent.contract.AgentFailureClass
import com.cyclone.mobile.agent.contract.AgentPageCard
import com.cyclone.mobile.agent.contract.GoalContractCompiler
import com.cyclone.mobile.agent.recovery.AgenticRecoveryRuntimePort
import com.cyclone.mobile.agent.recovery.DefaultAgenticRecoveryRuntimePort
import com.cyclone.mobile.agent.recovery.EvidenceSource
import com.cyclone.mobile.agent.recovery.ObservationEvidence
import com.cyclone.mobile.agent.recovery.ProgressClassification
import com.cyclone.mobile.agent.recovery.ProgressResult
import com.cyclone.mobile.agent.recovery.RecoverableCause
import com.cyclone.mobile.agent.recovery.RecoveryDecision
import com.cyclone.mobile.agent.recovery.RecoveryLevel
import com.cyclone.mobile.agent.recovery.RecoveryMemory
import com.cyclone.mobile.agent.recovery.RecoveryRequest
import com.cyclone.mobile.agent.tools.CycloneAgentEnvironment
import com.cyclone.mobile.agent.tools.CycloneAgentEnvironmentApi
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.ai.PageAgentAction
import com.cyclone.mobile.ai.PageAgentProtocol
import com.cyclone.mobile.applearner.LearnedAction
import com.cyclone.mobile.applearner.PageContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Production bridge between the persistent local task runtime and the same Android contract used
 * by Cyclone PC. This class owns no Android execution: CycloneAgentEnvironment remains the only
 * action/observation boundary and PhoneToolExecutor remains canonical underneath it.
 */
class CyclonePcParityBridge internal constructor(
    private val environment: CycloneAgentEnvironmentApi,
    private val recovery: AgenticRecoveryRuntimePort = DefaultAgenticRecoveryRuntimePort(),
) {
    constructor(context: Context) : this(CycloneAgentEnvironment(context.applicationContext))

    private var page: AgentPageCard? = null
    private var memory: RecoveryMemory = RecoveryMemory()
    private var lastRecovery: RecoveryDecision? = null
    private var searchEvidence: List<AgentElementCandidate> = emptyList()
    private var inspectionEvidence: List<JSONObject> = emptyList()
    private var brainEvidence: JSONObject? = null
    private var routeEvidence: JSONObject? = null
    private var forceVision = false

    fun observe(goal: String): AgentPageCard? {
        val previousKey = page?.pageKey
        val result = environment.locate(goal)
        val fresh = result.page ?: return null
        page = fresh
        if (previousKey == null || previousKey != fresh.pageKey) {
            memory = RecoveryMemory(
                attemptedLevels = setOf(RecoveryLevel.CURRENT_SEMANTIC_PAGE),
                attemptedEvidence = setOf(EvidenceSource.CURRENT_SEMANTIC_PAGE),
            )
            searchEvidence = emptyList()
            inspectionEvidence = emptyList()
            forceVision = false
            lastRecovery = null
        } else {
            memory = memory.copy(
                attemptedLevels = memory.attemptedLevels + RecoveryLevel.CURRENT_SEMANTIC_PAGE,
                attemptedEvidence = memory.attemptedEvidence + EvidenceSource.CURRENT_SEMANTIC_PAGE,
            )
        }
        return fresh
    }

    fun currentPage(): AgentPageCard? = page

    fun observation(): CycloneObservation? = page?.let { card ->
        // Observation IDs intentionally do NOT participate in convergence. They rotate after every
        // capture for selector safety, while this witness changes only when semantic evidence does.
        val semanticWitness = listOf(card.pageKey, card.contentKey, card.accessibilityFingerprint)
            .joinToString("|")
        CycloneObservation(
            identity = semanticWitness,
            pageIdentity = card.pageKey,
            evidenceIdentity = semanticWitness,
        )
    }

    fun promptContext(goal: String): JSONObject {
        val card = page
        val history = environment.history()
        val contract = GoalContractCompiler.compile(goal)
        val completion = GoalContractCompiler.evaluate(contract, card, history)
        val out = JSONObject()
            .put("contract", "cyclone-pc-parity-local-v2")
            .put("goal", goal)
            .put("goalContract", contract.toJson())
            .put("completionState", completion.toJson())
            .put("staleIdRule", "elementId is valid only for the current observation; re-locate after every mutation")
            .put("verificationRule", "executor acceptance is never semantic success; Android after-state verification is authoritative")
            .put("completionRule", "done means every goalContract requirement is independently satisfied; do not keep acting after the contract is satisfied")
        if (card != null) {
            out.put("pageCard", pageCardJson(card))
            out.put(
                "scene",
                JSONObject()
                    .put("authoritativePackage", card.packageName)
                    .put("activity", card.activity ?: JSONObject.NULL)
                    .put("pageKey", card.pageKey)
                    .put("taskSurfaceLooksCycloneOwned", card.packageName == "com.cyclone.mobile")
                    .put("pageSummary", JSONObject(card.pageSummary.toString()))
                    .put("visibleTextExcerpt", compactText(card))
                    .put("interruptions", interruptionCandidates(card)),
            )
        }
        out.put("recentOutcomes", recentOutcomeJson(history))
        val recoveryJson = JSONObject()
            .put("attemptedLevels", JSONArray(memory.attemptedLevels.sortedBy { it.stage }.map { it.name }))
            .put("attemptedEvidence", JSONArray(memory.attemptedEvidence.map { it.name }))
            .put("semanticSearchExhausted", memory.semanticSearchExhausted)
            .put("materiallyDifferentActionsWithoutProgress", memory.materiallyDifferentActionsWithoutProgress)
        lastRecovery?.let {
            recoveryJson
                .put("suggestedLevel", it.level?.name ?: JSONObject.NULL)
                .put("reason", it.reason)
                .put("visionTriggers", JSONArray(it.visionTriggers.map { trigger -> trigger.name }))
        }
        if (searchEvidence.isNotEmpty()) {
            recoveryJson.put("searchCandidates", JSONArray().also { array ->
                searchEvidence.take(12).forEach { array.put(candidateJson(it)) }
            })
        }
        if (inspectionEvidence.isNotEmpty()) {
            recoveryJson.put("inspectedElements", JSONArray().also { array ->
                inspectionEvidence.take(6).forEach { array.put(JSONObject(it.toString())) }
            })
        }
        brainEvidence?.let { recoveryJson.put("brainRecall", JSONObject(it.toString())) }
        routeEvidence?.let { recoveryJson.put("knownRoutes", JSONObject(it.toString())) }
        out.put("recovery", recoveryJson)
        traceModelContext(out, card, history)
        return out
    }

    fun act(action: PageAgentAction, legacyPage: PageContext, goal: String): AgentActionEnvelope {
        // Normalize strict non-element tool arguments (for example Chrome -> package)
        // before entering the PC-parity action boundary. Element targeting is still re-resolved
        // against the authoritative current observation below.
        val params = PageAgentProtocol.resolveParams(action, legacyPage)
            .getOrElse { JSONObject(action.params.toString()) }
        params.remove("selector")
        val needsElement = action.tool in ELEMENT_TOOLS
        if (needsElement) {
            val elementId = resolveElementId(action.controlId, legacyPage, goal)
            if (elementId != null) params.put("elementId", elementId)
        }
        return environment.act(action.tool, params, goal).also { envelope ->
            page = envelope.after ?: page
        }
    }

    fun actGraph(action: LearnedAction, goal: String): AgentActionEnvelope {
        val query = action.label.ifBlank { action.id }
        val candidate = search(query, goal).firstOrNull()
        val params = JSONObject()
        if (candidate != null) params.put("elementId", candidate.elementId)
        return environment.act("phone.click", params, goal).also { envelope ->
            page = envelope.after ?: page
        }
    }

    fun classifyProgress(envelope: AgentActionEnvelope): ProgressResult {
        val before = envelope.before?.let(::observationEvidence)
        val after = envelope.after?.let(::observationEvidence)
        return if (after == null) {
            ProgressResult(ProgressClassification.NO_PROGRESS, setOf("after_observation_missing"))
        } else {
            recovery.classifyProgress(before, after)
        }
    }

    /**
     * Selects and performs only read-only recovery escalation. Mutation recovery (scroll/back/backtrack)
     * remains an explicit next model/tool decision so GATE/policy and user intent stay authoritative.
     */
    fun recover(cause: RecoverableCause, goal: String): RecoveryDecision? {
        val card = page ?: observe(goal) ?: return null
        val request = RecoveryRequest(
            observation = observationEvidence(card),
            memory = memory,
            cause = cause,
            targetAbsentFromStructuredControls = cause == RecoverableCause.TARGET_MISSING_FROM_COMPACT_CONTROLS,
            actionAfterStateAmbiguous = cause in setOf(
                RecoverableCause.VERIFICATION_FAILED,
                RecoverableCause.AFTER_STATE_MISSING,
                RecoverableCause.AMBIGUOUS_SEMANTICS,
            ),
            repeatedStaleOrVanishingTargets = cause == RecoverableCause.STALE_SELECTOR,
            knownVerifiedRouteAvailable = knownKnowledgeAvailable(goal),
            semanticSearchAvailable = true,
            supplementalInspectionAvailable = card.controls.isNotEmpty(),
            boundedExplorationAvailable = true,
            backtrackOrAlternateBranchAvailable = true,
        )
        val decision = recovery.selectRecovery(request)
        lastRecovery = decision
        when (decision.level) {
            RecoveryLevel.KNOWN_VERIFIED_ROUTE -> loadKnowledge(goal)
            RecoveryLevel.GOAL_RANKED_SEARCH -> {
                val results = environment.search(goal, goal)
                results.page?.let { page = it }
                searchEvidence = results.candidates
                memory = memory.copy(
                    attemptedLevels = memory.attemptedLevels + RecoveryLevel.GOAL_RANKED_SEARCH,
                    attemptedEvidence = memory.attemptedEvidence + EvidenceSource.SEMANTIC_SEARCH,
                    semanticSearchExhausted = results.candidates.isEmpty(),
                )
            }
            RecoveryLevel.ADDITIONAL_ELEMENT_INSPECTION -> {
                val candidates = if (searchEvidence.isNotEmpty()) searchEvidence else card.controls
                inspectionEvidence = candidates.take(4).mapNotNull { candidate ->
                    environment.inspect(candidate.elementId).evidence
                }
                memory = memory.copy(
                    attemptedLevels = memory.attemptedLevels + RecoveryLevel.ADDITIONAL_ELEMENT_INSPECTION,
                    attemptedEvidence = memory.attemptedEvidence + EvidenceSource.ELEMENT_INSPECTION,
                )
            }
            RecoveryLevel.BOUNDED_PAGE_EXPLORATION -> {
                memory = memory.copy(
                    attemptedLevels = memory.attemptedLevels + RecoveryLevel.BOUNDED_PAGE_EXPLORATION,
                    attemptedEvidence = memory.attemptedEvidence + EvidenceSource.PAGE_EXPLORATION,
                )
            }
            RecoveryLevel.SILENT_SCREENSHOT_VISION -> {
                forceVision = true
                memory = memory.copy(
                    attemptedLevels = memory.attemptedLevels + RecoveryLevel.SILENT_SCREENSHOT_VISION,
                    attemptedEvidence = memory.attemptedEvidence + EvidenceSource.SCREENSHOT_VISION,
                    capturesForSemanticState = memory.capturesForSemanticState + 1,
                )
            }
            RecoveryLevel.BACKTRACK_OR_REPLAN -> {
                memory = memory.copy(
                    attemptedLevels = memory.attemptedLevels + RecoveryLevel.BACKTRACK_OR_REPLAN,
                    attemptedEvidence = memory.attemptedEvidence + EvidenceSource.BACKTRACK,
                )
            }
            RecoveryLevel.CURRENT_SEMANTIC_PAGE -> {
                memory = memory.copy(
                    attemptedLevels = memory.attemptedLevels + RecoveryLevel.CURRENT_SEMANTIC_PAGE,
                    attemptedEvidence = memory.attemptedEvidence + EvidenceSource.CURRENT_SEMANTIC_PAGE,
                )
            }
            RecoveryLevel.HUMAN_GATE, null -> Unit
        }
        if (cause in setOf(
                RecoverableCause.VERIFICATION_FAILED,
                RecoverableCause.WRONG_TARGET,
                RecoverableCause.SAME_PAGE_NO_EFFECT,
            )
        ) {
            memory = memory.copy(
                materiallyDifferentActionsWithoutProgress = memory.materiallyDifferentActionsWithoutProgress + 1,
            )
        }
        return decision
    }

    fun consumeForcedVision(): Boolean {
        val value = forceVision
        forceVision = false
        return value
    }

    fun markVerifiedProgress() {
        memory = RecoveryMemory(
            attemptedLevels = setOf(RecoveryLevel.CURRENT_SEMANTIC_PAGE),
            attemptedEvidence = setOf(EvidenceSource.CURRENT_SEMANTIC_PAGE),
        )
        lastRecovery = null
        searchEvidence = emptyList()
        inspectionEvidence = emptyList()
        forceVision = false
    }

    fun completionEvidence(goal: String): Boolean {
        val contract = GoalContractCompiler.compile(goal)
        return GoalContractCompiler.evaluate(contract, page, environment.history()).satisfied
    }

    fun verifiedSimpleNavigation(goal: String): Boolean =
        GoalContractCompiler.isSimpleWebNavigation(goal) && completionEvidence(goal)

    fun completionEvaluation(goal: String): JSONObject {
        val contract = GoalContractCompiler.compile(goal)
        return GoalContractCompiler.evaluate(contract, page, environment.history()).toJson()
    }

    fun causeFor(envelope: AgentActionEnvelope): RecoverableCause = when (envelope.errorClass) {
        AgentFailureClass.STALE_OBSERVATION -> RecoverableCause.STALE_SELECTOR
        AgentFailureClass.TARGET_NOT_FOUND -> RecoverableCause.TARGET_MISSING_FROM_COMPACT_CONTROLS
        AgentFailureClass.VERIFICATION_FAILED -> RecoverableCause.VERIFICATION_FAILED
        AgentFailureClass.AFTER_OBSERVATION_FAILED -> RecoverableCause.AFTER_STATE_MISSING
        AgentFailureClass.TIMEOUT -> RecoverableCause.PAGE_LOAD_SLOW
        else -> if (envelope.androidExecutionOk) RecoverableCause.SAME_PAGE_NO_EFFECT
        else RecoverableCause.RETRYABLE_TOOL_OR_TRANSPORT_ERROR
    }

    private fun resolveElementId(controlId: String?, legacyPage: PageContext, goal: String): String? {
        val card = page ?: observe(goal) ?: return null
        if (!controlId.isNullOrBlank()) {
            card.controls.firstOrNull { it.elementId == controlId }?.let { return it.elementId }
        }
        val legacy = controlId?.let { id -> legacyPage.controls.firstOrNull { it.key == id } }
        val query = legacy?.semanticName?.takeIf { it.isNotBlank() }
            ?: legacy?.label?.takeIf { it.isNotBlank() }
            ?: controlId?.takeIf { it.isNotBlank() }
            ?: goal
        val normalized = normalize(query)
        card.controls.firstOrNull {
            normalize(it.semanticName) == normalized || normalize(it.label) == normalized
        }?.let { return it.elementId }
        return search(query, goal).firstOrNull()?.elementId
    }

    private fun search(query: String, goal: String): List<AgentElementCandidate> {
        val result = environment.search(query, goal)
        result.page?.let { page = it }
        searchEvidence = result.candidates
        return result.candidates
    }

    private fun knownKnowledgeAvailable(goal: String): Boolean {
        val route = environment.knownRoutes(goal)
        val brain = environment.brainRecall(goal)
        routeEvidence = route.evidence
        brainEvidence = brain.evidence
        return route.evidence != null || brain.evidence != null
    }

    private fun loadKnowledge(goal: String) {
        knownKnowledgeAvailable(goal)
        memory = memory.copy(
            attemptedLevels = memory.attemptedLevels + RecoveryLevel.KNOWN_VERIFIED_ROUTE,
            attemptedEvidence = memory.attemptedEvidence + EvidenceSource.KNOWN_ROUTE,
        )
    }

    private fun observationEvidence(card: AgentPageCard): ObservationEvidence {
        val interactions = linkedMapOf<String, String>()
        card.controls.forEach { control ->
            val e = control.evidence
            interactions[control.semanticName.ifBlank { control.elementId }] = listOf(
                e.optBoolean("selected"),
                e.optBoolean("checked"),
                e.optBoolean("focused"),
                e.optString("textStateDigest"),
            ).joinToString("|")
        }
        val goalControls = card.controls
            .map { normalize(it.semanticName.ifBlank { it.label }) }
            .filter(String::isNotBlank)
            .toSet()
        val rawCount = card.pageEvidence.optInt("rawNodeCount", card.controls.size)
        val summary = card.pageSummary.toString().lowercase()
        return ObservationEvidence(
            semanticStateKey = card.pageKey,
            accessibilityFingerprint = card.accessibilityFingerprint,
            contentKey = card.contentKey,
            goalRelevantControls = goalControls,
            interactionState = interactions,
            packageName = card.packageName,
            activityName = card.activity,
            collectedEvidence = memory.attemptedEvidence,
            structuredControlCount = card.controls.size,
            rawNodeCount = rawCount,
            pageLooksWebOrCanvas = listOf("webview", "canvas", "composeview").any(summary::contains),
        )
    }

    private fun pageCardJson(card: AgentPageCard): JSONObject = JSONObject()
        .put("observationId", card.observationId)
        .put("generation", card.generation)
        .put("package", card.packageName)
        .put("activity", card.activity ?: JSONObject.NULL)
        .put("pageKey", card.pageKey)
        .put("structuralKey", card.structuralKey)
        .put("contentKey", card.contentKey)
        .put("accessibilityFingerprint", card.accessibilityFingerprint)
        .put("pageSummary", JSONObject(card.pageSummary.toString()))
        .put("pageText", JSONObject(card.pageText.toString()))
        .put("pageEvidence", JSONObject(card.pageEvidence.toString()))
        .put("controls", JSONArray().also { array -> card.controls.forEach { array.put(candidateJson(it)) } })
        .put("nextHopHints", JSONArray(card.nextHopHints.toString()))

    private fun compactText(card: AgentPageCard): String {
        val combined = buildString {
            append(card.pageText.toString()).append(' ')
            card.controls.take(40).forEach { control ->
                append(control.label).append(' ')
                append(control.semanticName).append(' ')
            }
        }.replace(Regex("\\s+"), " ").trim()
        return combined.take(2_400)
    }

    private fun interruptionCandidates(card: AgentPageCard): JSONArray {
        val markers = listOf(
            "cookie", "consent", "privacy", "tracking", "translate", "notification", "permission",
            "allow", "deny", "newsletter", "sign in", "login", "captcha", "open in app",
        )
        val array = JSONArray()
        card.controls.asSequence()
            .filter { control ->
                val text = "${control.label} ${control.semanticName}".lowercase()
                markers.any(text::contains)
            }
            .take(12)
            .forEach { array.put(candidateJson(it)) }
        return array
    }

    private fun recentOutcomeJson(history: List<AgentActionEnvelope>): JSONArray = JSONArray().also { array ->
        history.takeLast(8).forEach { envelope ->
            array.put(
                JSONObject()
                    .put("tool", envelope.tool)
                    .put("androidExecutionOk", envelope.androidExecutionOk)
                    .put("verificationStatus", envelope.verification.status.name)
                    .put("verificationPassed", envelope.verification.passed)
                    .put("verificationBasis", envelope.verification.basis ?: JSONObject.NULL)
                    .put("semanticSuccessClaimed", envelope.semanticSuccessClaimed)
                    .put("delta", envelope.delta.summary.take(240))
                    .put("errorClass", envelope.errorClass.name)
                    .put("learningRecorded", envelope.learning.recorded),
            )
        }
    }

    /**
     * The downloadable trace should show what the model was actually grounded on without storing a
     * raw accessibility tree or hidden provider reasoning. AgentTraceRuntime is already initialized
     * before production promptContext calls; tests/offline callers fail this optional trace safely.
     */
    private fun traceModelContext(
        contextJson: JSONObject,
        card: AgentPageCard?,
        history: List<AgentActionEnvelope>,
    ) {
        runCatching {
            val active = AgentTraceRuntime.store.listSessions(8)
                .firstOrNull { it.status == "RUNNING" || it.status == "SUSPENDED" }
                ?: return@runCatching
            val goalContract = contextJson.optJSONObject("goalContract")
            val completion = contextJson.optJSONObject("completionState")
            val controls = card?.controls.orEmpty().take(14).joinToString(" | ") { control ->
                val label = control.semanticName.ifBlank { control.label }.replace(Regex("\\s+"), " ").take(70)
                "${control.role}:$label"
            }
            val recent = history.takeLast(4).joinToString(" | ") { outcome ->
                "${outcome.tool}:${outcome.verification.status.name}:${outcome.verification.basis.orEmpty()}"
            }
            val detail = buildString {
                append("package=").append(card?.packageName.orEmpty())
                append(" activity=").append(card?.activity.orEmpty())
                append(" page=").append(card?.pageKey?.takeLast(16).orEmpty())
                append(" completion=").append(completion?.optBoolean("satisfied", false))
                append(" goalContract=").append(goalContract?.optJSONArray("requirements")?.toString().orEmpty().take(420))
                append(" summary=").append(card?.pageSummary?.toString().orEmpty().replace(Regex("\\s+"), " ").take(360))
                append(" controls=").append(controls.take(900))
                if (recent.isNotBlank()) append(" recent=").append(recent.take(500))
            }
            AgentTraceRuntime.store.append(
                active.id,
                "MODEL_CONTEXT",
                "Sanitized model context prepared",
                code = "model.context.v2",
                ok = true,
                detail = detail,
            )
        }
    }

    private fun candidateJson(candidate: AgentElementCandidate): JSONObject = JSONObject()
        .put("controlId", candidate.elementId)
        .put("elementId", candidate.elementId)
        .put("label", candidate.label)
        .put("semanticName", candidate.semanticName)
        .put("role", candidate.role)
        .put("source", candidate.source)
        .put("relevance", candidate.relevance)
        .put("androidActions", candidate.evidence.optJSONArray("androidActions") ?: JSONArray())
        .put("risk", candidate.evidence.optString("risk"))
        .put("expectedEffect", candidate.evidence.opt("expectedEffect") ?: JSONObject.NULL)
        .put("clickable", candidate.evidence.optBoolean("clickable"))
        .put("editable", candidate.evidence.optBoolean("editable"))
        .put("scrollable", candidate.evidence.optBoolean("scrollable"))
        .put("selected", candidate.evidence.optBoolean("selected"))
        .put("checked", candidate.evidence.optBoolean("checked"))
        .put("focused", candidate.evidence.optBoolean("focused"))

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    companion object {
        private val ELEMENT_TOOLS = setOf(
            "phone.click",
            "phone.long_press",
            "phone.type",
            "phone.replace_text",
        )
    }
}
