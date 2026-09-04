package com.cyclone.mobile.agent.tools

import android.content.Context
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.PhoneToolErrorCode
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.PhoneToolRequest
import com.cyclone.mobile.PhoneToolResult
import com.cyclone.mobile.agent.contract.AgentActionEnvelope
import com.cyclone.mobile.agent.contract.AgentElementCandidate
import com.cyclone.mobile.agent.contract.AgentFailure
import com.cyclone.mobile.agent.contract.AgentFailureClass
import com.cyclone.mobile.agent.contract.AgentFailureLayer
import com.cyclone.mobile.agent.contract.AgentInspectResult
import com.cyclone.mobile.agent.contract.AgentKnowledgeResult
import com.cyclone.mobile.agent.contract.AgentLearningResult
import com.cyclone.mobile.agent.contract.AgentObservationResult
import com.cyclone.mobile.agent.contract.AgentPageCard
import com.cyclone.mobile.agent.contract.AgentScreenshotResult
import com.cyclone.mobile.agent.contract.AgentSearchResult
import com.cyclone.mobile.agent.contract.AgentSemanticVerification
import com.cyclone.mobile.agent.contract.AgentStateDelta
import com.cyclone.mobile.agent.contract.AgentVerificationStatus
import com.cyclone.mobile.ai.CycloneAiAccessPolicy
import com.cyclone.mobile.ai.CycloneAiAccessProfileStore
import com.cyclone.mobile.gateway.GatewayAppGraphAdapter
import com.cyclone.mobile.gateway.GatewayBrainAdapter
import com.cyclone.mobile.gateway.GatewayCaptureAdapter
import com.cyclone.mobile.gateway.GatewayObservation
import com.cyclone.mobile.gateway.GatewayObservationAdapter
import com.cyclone.mobile.gateway.GatewayObservationStore
import com.cyclone.mobile.gateway.GatewayProtocolException
import com.cyclone.mobile.gateway.GatewayV33ActionAdapter
import com.cyclone.mobile.policy.GateClassifier
import com.cyclone.mobile.ui.overlay.ClickGateIntercept
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import com.cyclone.mobile.ui.overlay.OverlayChromeState
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID

/** Native in-process eyes/hands/verification contract for the standalone mobile agent. */
interface CycloneAgentEnvironmentApi {
    fun observe(goal: String = ""): AgentObservationResult
    fun locate(goal: String): AgentSearchResult
    fun search(query: String, goal: String = query): AgentSearchResult
    fun inspect(elementId: String): AgentInspectResult
    fun screenshot(goal: String = ""): AgentScreenshotResult
    fun act(tool: String, params: JSONObject = JSONObject(), goal: String = ""): AgentActionEnvelope
    fun history(): List<AgentActionEnvelope>
    fun brainRecall(goal: String): AgentKnowledgeResult
    fun knownRoutes(goal: String): AgentKnowledgeResult
}

class CycloneAgentEnvironment internal constructor(
    private val runtime: CycloneAgentRuntimePort,
) : CycloneAgentEnvironmentApi {
    constructor(context: Context) : this(AndroidCycloneAgentRuntimePort(context.applicationContext))

    private val scope = AgentObservationScope()
    private val actionHistory = ArrayDeque<AgentActionEnvelope>()

    override fun observe(goal: String): AgentObservationResult = synchronized(this) {
        runCatching {
            val observation = runtime.capture()
            val generation = scope.publish(observation.id)
            AgentObservationResult(page = pageCard(observation, goal, generation, actionable = true))
        }.getOrElse { AgentObservationResult(failure = failureFromThrowable(it, AgentFailureLayer.OBSERVATION)) }
    }

    override fun locate(goal: String): AgentSearchResult = synchronized(this) {
        runCatching {
            val observation = runtime.capture()
            val generation = scope.publish(observation.id)
            AgentSearchResult(
                page = pageCard(observation, goal, generation, actionable = true),
                observationId = observation.id,
                generation = generation,
                query = goal,
                goal = goal,
                candidates = candidates(observation, goal, DEFAULT_LOCATE_LIMIT),
            )
        }.getOrElse {
            AgentSearchResult(query = goal, goal = goal, failure = failureFromThrowable(it, AgentFailureLayer.OBSERVATION))
        }
    }

    override fun search(query: String, goal: String): AgentSearchResult = synchronized(this) {
        if (query.isBlank()) {
            return@synchronized AgentSearchResult(
                query = query,
                goal = goal,
                failure = AgentFailure(
                    AgentFailureClass.INVALID_REQUEST,
                    AgentFailureLayer.INPUT,
                    false,
                    "Search query is required.",
                ),
            )
        }
        runCatching {
            val observation = currentVisibleObservation() ?: runtime.capture().also { scope.publish(it.id) }
            val generation = scope.generation
            AgentSearchResult(
                page = pageCard(observation, goal, generation, actionable = true),
                observationId = observation.id,
                generation = generation,
                query = query,
                goal = goal,
                candidates = candidates(observation, query, DEFAULT_SEARCH_LIMIT),
            )
        }.getOrElse {
            AgentSearchResult(query = query, goal = goal, failure = failureFromThrowable(it, AgentFailureLayer.OBSERVATION))
        }
    }

    override fun inspect(elementId: String): AgentInspectResult = synchronized(this) {
        val observation = currentVisibleObservation()
            ?: return@synchronized AgentInspectResult(
                elementId = elementId,
                failure = staleFailure("Observe or search the current page again before inspecting an element."),
            )
        if (!belongsToObservation(elementId, observation.id)) {
            return@synchronized AgentInspectResult(
                observationId = observation.id,
                generation = scope.generation,
                elementId = elementId,
                failure = staleFailure("Element ID belongs to an expired observation."),
            )
        }
        runCatching { runtime.element(observation, elementId) }
            .fold(
                onSuccess = { evidence -> AgentInspectResult(observation.id, scope.generation, elementId, evidence) },
                onFailure = { error ->
                    AgentInspectResult(
                        observation.id,
                        scope.generation,
                        elementId,
                        failure = failureFromThrowable(error, AgentFailureLayer.OBSERVATION),
                    )
                },
            )
    }

    override fun screenshot(goal: String): AgentScreenshotResult = synchronized(this) {
        val visibleId = currentVisibleObservation()?.id
        runCatching { runtime.screenshot(goal) }.fold(
            onSuccess = { frame ->
                AgentScreenshotResult(
                    goal = goal,
                    observationId = visibleId,
                    filePath = frame.optString("filePath").takeIf(String::isNotBlank),
                    width = frame.optInt("width").takeIf { frame.has("width") },
                    height = frame.optInt("height").takeIf { frame.has("height") },
                    timestampMs = frame.optLong("timestampMs").takeIf { frame.has("timestampMs") },
                )
            },
            onFailure = { error ->
                AgentScreenshotResult(
                    goal,
                    visibleId,
                    failure = failureFromThrowable(error, AgentFailureLayer.CAPABILITY),
                )
            },
        )
    }

    override fun act(tool: String, params: JSONObject, goal: String): AgentActionEnvelope = synchronized(this) {
        val effectiveGoal = goal.ifBlank { tool.removePrefix("phone.").replace('_', ' ') }
        if (tool !in LOCAL_MUTATING_TOOLS) {
            return@synchronized failureEnvelope(
                tool,
                effectiveGoal,
                AgentFailure(
                    AgentFailureClass.CAPABILITY_UNAVAILABLE,
                    AgentFailureLayer.CAPABILITY,
                    false,
                    "Tool is not exposed to the local agent contract.",
                ),
            )
        }

        val before = currentVisibleObservation()
            ?: return@synchronized failureEnvelope(
                tool,
                effectiveGoal,
                staleFailure("Fresh observe/locate/search is required before every mutation."),
            )
        val visibleGeneration = scope.generation
        val rawElementId = elementId(params)
        if (tool in ELEMENT_ID_REQUIRED_TOOLS && rawElementId == null) {
            return@synchronized failureEnvelope(
                tool,
                effectiveGoal,
                AgentFailure(
                    AgentFailureClass.INVALID_REQUEST,
                    AgentFailureLayer.INPUT,
                    false,
                    "Use a current observation-scoped elementId from observe/locate/search for this action.",
                    "ELEMENT_ID_REQUIRED",
                ),
                before,
                visibleGeneration,
            )
        }
        if (tool in ELEMENT_SCOPED_TOOLS && rawElementId == null && hasFreeformSelector(params)) {
            return@synchronized failureEnvelope(
                tool,
                effectiveGoal,
                AgentFailure(
                    AgentFailureClass.INVALID_REQUEST,
                    AgentFailureLayer.INPUT,
                    false,
                    "Free-form selectors are not accepted by the local agent contract; re-locate the target.",
                    "SCOPED_ELEMENT_ID_REQUIRED",
                ),
                before,
                visibleGeneration,
            )
        }
        if (rawElementId != null && !belongsToObservation(rawElementId, before.id)) {
            return@synchronized failureEnvelope(
                tool,
                effectiveGoal,
                staleFailure("Element ID belongs to an expired observation."),
                before,
                visibleGeneration,
            )
        }

        val normalizedParams = JSONObject(params.toString())
        if (rawElementId != null) {
            val evidence = runCatching { runtime.element(before, rawElementId) }.getOrElse { error ->
                return@synchronized failureEnvelope(
                    tool,
                    effectiveGoal,
                    failureFromThrowable(error, AgentFailureLayer.OBSERVATION),
                    before,
                    visibleGeneration,
                )
            }
            if (normalizedParams.optJSONObject("selector") == null ||
                normalizedParams.optJSONObject("selector")?.length() == 0
            ) {
                evidence.optJSONObject("selector")?.let {
                    normalizedParams.put("selector", JSONObject(it.toString()))
                }
            }
            normalizedParams.put("elementId", rawElementId)
        }

        runtime.readinessFailure()?.let { failure ->
            return@synchronized failureEnvelope(tool, effectiveGoal, failure, before, visibleGeneration)
        }
        runtime.policyFailure(tool, normalizedParams)?.let { failure ->
            return@synchronized failureEnvelope(tool, effectiveGoal, failure, before, visibleGeneration)
        }

        // IDs expire the instant a mutation is handed to the canonical PhoneToolExecutor.
        scope.expire()
        val requestId = "local-agent-${UUID.randomUUID()}"
        val result = runCatching { runtime.execute(requestId, tool, normalizedParams) }.getOrElse { error ->
            val envelope = failureEnvelope(
                tool,
                effectiveGoal,
                failureFromThrowable(error, AgentFailureLayer.EXECUTION),
                before,
                visibleGeneration,
            )
            remember(envelope)
            return@synchronized envelope
        }

        val executorAssertionFailed = result.error?.code == PhoneToolErrorCode.ASSERTION_FAILED
        val androidExecutionOk = result.ok || executorAssertionFailed
        val after = if (androidExecutionOk) {
            runCatching { runtime.captureAfter(tool, normalizedParams, before) }.getOrNull()
        } else {
            null
        }
        val verification = runtime.verify(
            tool = tool,
            expectedPackage = normalizedParams.optString("package"),
            goalLabel = effectiveGoal,
            before = before,
            after = after,
            androidExecutionOk = androidExecutionOk,
            executorAssertionFailed = executorAssertionFailed,
            explicitExpectation = normalizedParams.optJSONObject("expect") != null,
        )

        val executionFailure = if (!androidExecutionOk) failureFromPhoneResult(result) else null
        val verificationFailure = when (verification.status) {
            AgentVerificationStatus.PASSED -> null
            AgentVerificationStatus.DEGRADED -> AgentFailure(
                AgentFailureClass.AFTER_OBSERVATION_FAILED,
                AgentFailureLayer.VERIFICATION,
                true,
                verification.detail ?: "Fresh after-observation was unavailable.",
                verification.basis,
            )
            AgentVerificationStatus.FAILED,
            AgentVerificationStatus.OBSERVED,
            -> AgentFailure(
                AgentFailureClass.VERIFICATION_FAILED,
                AgentFailureLayer.VERIFICATION,
                true,
                verification.detail ?: "Fresh after-state did not prove semantic success.",
                verification.basis,
            )
            AgentVerificationStatus.NOT_REQUIRED -> null
        }
        val failure = executionFailure ?: verificationFailure

        val learning = if (verification.passed && before.page.pageKey != after?.page?.pageKey) {
            runCatching {
                runtime.recordLearning(
                    effectiveGoal,
                    tool,
                    normalizedParams,
                    before,
                    after,
                    androidExecutionOk,
                    verification,
                )
            }.getOrElse {
                AgentLearningResult(
                    false,
                    "Verified route persistence failed safely: ${it.javaClass.simpleName}",
                )
            }
        } else {
            AgentLearningResult(
                false,
                "Only a semantically PASSED page transition may enter verified route learning.",
            )
        }

        val delta = stateDelta(before, after, verification)
        val envelope = AgentActionEnvelope(
            tool = tool,
            goal = effectiveGoal,
            androidExecutionOk = androidExecutionOk,
            executorReportedOk = result.ok,
            verification = verification,
            before = pageCard(before, effectiveGoal, visibleGeneration, actionable = false),
            after = after?.let { pageCard(it, effectiveGoal, visibleGeneration + 1, actionable = false) },
            pageChanged = delta.pageChanged,
            delta = delta,
            errorClass = failure?.errorClass ?: AgentFailureClass.NONE,
            failureLayer = failure?.failureLayer ?: AgentFailureLayer.NONE,
            retryable = failure?.retryable ?: false,
            semanticSuccessClaimed = verification.semanticSuccessClaimed,
            beforeObservationId = before.id,
            afterObservationId = after?.id,
            observationGeneration = visibleGeneration,
            learning = learning,
            safeMessage = failure?.message,
        )
        remember(envelope)
        envelope
    }

    override fun history(): List<AgentActionEnvelope> = synchronized(this) { actionHistory.toList() }

    override fun brainRecall(goal: String): AgentKnowledgeResult = synchronized(this) {
        runCatching { runtime.brainRecall(goal) }.fold(
            onSuccess = { AgentKnowledgeResult(goal, it) },
            onFailure = {
                AgentKnowledgeResult(
                    goal,
                    failure = failureFromThrowable(it, AgentFailureLayer.LEARNING),
                )
            },
        )
    }

    override fun knownRoutes(goal: String): AgentKnowledgeResult = synchronized(this) {
        runCatching { runtime.knownRoutes(goal) }.fold(
            onSuccess = { AgentKnowledgeResult(goal, it) },
            onFailure = {
                AgentKnowledgeResult(
                    goal,
                    failure = failureFromThrowable(it, AgentFailureLayer.LEARNING),
                )
            },
        )
    }

    private fun currentVisibleObservation(): GatewayObservation? {
        val visibleId = scope.observationId ?: return null
        val current = runtime.current() ?: return null
        return current.takeIf { it.id == visibleId }
    }

    private fun candidates(
        observation: GatewayObservation,
        query: String,
        limit: Int,
    ): List<AgentElementCandidate> {
        val results = runtime.search(observation, query, limit)
        return buildList {
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val id = item.optString("elementId")
                val evidence = runCatching { runtime.element(observation, id) }.getOrNull() ?: JSONObject()
                add(candidateFrom(item, evidence))
            }
        }
    }

    private fun pageCard(
        observation: GatewayObservation,
        goal: String,
        generation: Long,
        actionable: Boolean,
    ): AgentPageCard {
        val ranked = if (goal.isBlank()) {
            emptyList()
        } else {
            candidates(observation, goal, PAGE_CARD_GOAL_CANDIDATES)
        }
        val byId = linkedMapOf<String, AgentElementCandidate>()
        ranked.forEach { byId[it.elementId] = it }

        val semantic = observation.payload.optJSONArray("semanticControls") ?: JSONArray()
        for (index in 0 until semantic.length()) {
            if (byId.size >= PAGE_CARD_CONTROL_LIMIT) break
            val evidence = semantic.optJSONObject(index) ?: continue
            val id = evidence.optString("elementId")
            if (id.isBlank() || id in byId) continue
            byId[id] = AgentElementCandidate(
                elementId = id,
                observationId = observation.id,
                label = evidence.optString("label"),
                semanticName = evidence.optString("semanticName"),
                role = evidence.optString("role"),
                source = evidence.optString("source"),
                relevance = 0.0,
                evidence = JSONObject(evidence.toString()),
            )
        }

        return AgentPageCard(
            observationId = observation.id,
            generation = generation,
            actionable = actionable,
            capturedAtMs = observation.capturedAt,
            packageName = observation.page.packageName,
            activity = observation.payload.optString("activity").takeIf {
                it.isNotBlank() && it != "null"
            },
            pageKey = observation.page.pageKey,
            structuralKey = observation.page.structuralKey,
            contentKey = observation.page.contentKey,
            accessibilityFingerprint = observation.payload.optString("accessibilityFingerprint"),
            pageSummary = copyObject(observation.payload.optJSONObject("pageSummary")),
            pageText = copyObject(observation.payload.optJSONObject("pageText")),
            pageEvidence = copyObject(observation.payload.optJSONObject("pageEvidence")),
            controls = byId.values.toList(),
            nextHopHints = copyArray(observation.payload.optJSONArray("nextHopHints")),
        )
    }

    private fun candidateFrom(item: JSONObject, evidence: JSONObject) = AgentElementCandidate(
        elementId = item.optString("elementId"),
        observationId = item.optString("observationId"),
        label = item.optString("label"),
        semanticName = item.optString("semanticName"),
        role = item.optString("role"),
        source = item.optString("source"),
        relevance = item.optDouble("relevance", 0.0),
        evidence = JSONObject(evidence.toString()),
    )

    private fun stateDelta(
        before: GatewayObservation,
        after: GatewayObservation?,
        verification: AgentSemanticVerification,
    ): AgentStateDelta {
        if (after == null) {
            return AgentStateDelta(
                false,
                false,
                false,
                emptyList(),
                false,
                "No authoritative after-observation available.",
            )
        }
        val pageChanged = before.page.pageKey != after.page.pageKey
        val packageChanged = before.page.packageName != after.page.packageName
        val accessibilityChanged =
            before.payload.optString("accessibilityFingerprint") !=
                after.payload.optString("accessibilityFingerprint")
        val semanticChanges = verification.basis
            ?.takeIf { it.endsWith("_STATE_CHANGED") || it == "EDITABLE_TEXT_CHANGED" }
            ?.let(::listOf)
            .orEmpty()
        val goalAppeared = verification.basis == "GOAL_LABEL_APPEARED"
        val pieces = buildList {
            if (packageChanged) add("package changed")
            if (pageChanged) add("page changed")
            if (semanticChanges.isNotEmpty()) add(semanticChanges.joinToString())
            if (goalAppeared) add("goal label appeared")
            if (accessibilityChanged && !pageChanged && semanticChanges.isEmpty()) {
                add("accessibility fingerprint changed")
            }
        }
        return AgentStateDelta(
            pageChanged,
            packageChanged,
            accessibilityChanged,
            semanticChanges,
            goalAppeared,
            pieces.joinToString("; ").ifBlank { "No semantic delta proven." },
        )
    }

    private fun failureEnvelope(
        tool: String,
        goal: String,
        failure: AgentFailure,
        before: GatewayObservation? = null,
        generation: Long? = null,
    ): AgentActionEnvelope = AgentActionEnvelope(
        tool = tool,
        goal = goal,
        androidExecutionOk = false,
        executorReportedOk = false,
        verification = AgentSemanticVerification(
            AgentVerificationStatus.NOT_REQUIRED,
            false,
            false,
            "EXECUTION_NOT_ACCEPTED",
        ),
        before = before?.let {
            pageCard(
                it,
                goal,
                generation ?: scope.generation,
                actionable = scope.observationId == it.id,
            )
        },
        after = null,
        pageChanged = false,
        delta = AgentStateDelta(
            false,
            false,
            false,
            emptyList(),
            false,
            "No mutation was verified.",
        ),
        errorClass = failure.errorClass,
        failureLayer = failure.failureLayer,
        retryable = failure.retryable,
        semanticSuccessClaimed = false,
        beforeObservationId = before?.id,
        afterObservationId = null,
        observationGeneration = generation,
        learning = AgentLearningResult(false, "No verified route outcome to learn."),
        safeMessage = failure.message,
    )

    private fun failureFromPhoneResult(result: PhoneToolResult): AgentFailure {
        val error = result.error
        val code = error?.code
        val message = error?.message ?: "Canonical phone executor did not accept the action."
        return when (code) {
            PhoneToolErrorCode.FRESH_OBSERVATION_REQUIRED,
            PhoneToolErrorCode.STALE_ELEMENT,
            -> staleFailure(message)
            PhoneToolErrorCode.ELEMENT_NOT_FOUND -> AgentFailure(
                AgentFailureClass.TARGET_NOT_FOUND,
                AgentFailureLayer.OBSERVATION,
                true,
                message,
                code.name,
            )
            PhoneToolErrorCode.HUMAN_HAS_CONTROL -> AgentFailure(
                AgentFailureClass.HUMAN_HAS_CONTROL,
                AgentFailureLayer.DEVICE,
                true,
                message,
                code.name,
            )
            PhoneToolErrorCode.ACCESSIBILITY_NOT_CONNECTED -> AgentFailure(
                AgentFailureClass.ACCESSIBILITY_UNAVAILABLE,
                AgentFailureLayer.DEVICE,
                true,
                message,
                code.name,
            )
            PhoneToolErrorCode.CAPABILITY_UNAVAILABLE,
            PhoneToolErrorCode.APP_NOT_FOUND,
            -> AgentFailure(
                AgentFailureClass.CAPABILITY_UNAVAILABLE,
                AgentFailureLayer.CAPABILITY,
                false,
                message,
                code.name,
            )
            PhoneToolErrorCode.TIMEOUT -> AgentFailure(
                AgentFailureClass.TIMEOUT,
                AgentFailureLayer.EXECUTION,
                true,
                message,
                code.name,
            )
            PhoneToolErrorCode.POLICY_DENIED,
            PhoneToolErrorCode.SECURITY_RESTRICTION,
            -> {
                val gate = OverlayChromeRuntime.snapshot().state == OverlayChromeState.GATE
                AgentFailure(
                    if (gate) AgentFailureClass.GATE_REQUIRED else AgentFailureClass.POLICY_DENIED,
                    AgentFailureLayer.POLICY,
                    gate,
                    message,
                    code.name,
                )
            }
            PhoneToolErrorCode.INVALID_REQUEST -> AgentFailure(
                AgentFailureClass.INVALID_REQUEST,
                AgentFailureLayer.INPUT,
                false,
                message,
                code.name,
            )
            else -> AgentFailure(
                AgentFailureClass.EXECUTION_FAILED,
                AgentFailureLayer.EXECUTION,
                true,
                message,
                code?.name,
            )
        }
    }

    private fun failureFromThrowable(
        error: Throwable,
        defaultLayer: AgentFailureLayer,
    ): AgentFailure {
        if (error is GatewayProtocolException) {
            return when (error.code) {
                "STALE_OBSERVATION", "STALE_ELEMENT" -> staleFailure(
                    error.message ?: "Observation is stale.",
                )
                "ELEMENT_NOT_FOUND" -> AgentFailure(
                    AgentFailureClass.TARGET_NOT_FOUND,
                    AgentFailureLayer.OBSERVATION,
                    true,
                    error.message ?: "Target not found.",
                    error.code,
                )
                "POLICY_DENIED" -> AgentFailure(
                    AgentFailureClass.POLICY_DENIED,
                    AgentFailureLayer.POLICY,
                    false,
                    error.message ?: "Policy denied action.",
                    error.code,
                )
                "ACCESSIBILITY_NOT_CONNECTED" -> AgentFailure(
                    AgentFailureClass.ACCESSIBILITY_UNAVAILABLE,
                    AgentFailureLayer.DEVICE,
                    true,
                    error.message ?: "Accessibility is unavailable.",
                    error.code,
                )
                "CAPABILITY_UNAVAILABLE" -> AgentFailure(
                    AgentFailureClass.CAPABILITY_UNAVAILABLE,
                    AgentFailureLayer.CAPABILITY,
                    false,
                    error.message ?: "Capability unavailable.",
                    error.code,
                )
                "AUTH_REJECTED" -> AgentFailure(
                    AgentFailureClass.AUTH_REQUIRED,
                    AgentFailureLayer.POLICY,
                    true,
                    error.message ?: "Authorization required.",
                    error.code,
                )
                "TIMEOUT" -> AgentFailure(
                    AgentFailureClass.TIMEOUT,
                    defaultLayer,
                    true,
                    error.message ?: "Operation timed out.",
                    error.code,
                )
                else -> AgentFailure(
                    AgentFailureClass.EXECUTION_FAILED,
                    defaultLayer,
                    true,
                    error.message ?: "Operation failed safely.",
                    error.code,
                )
            }
        }
        return AgentFailure(
            AgentFailureClass.EXECUTION_FAILED,
            defaultLayer,
            true,
            error.message ?: error.javaClass.simpleName,
        )
    }

    private fun remember(envelope: AgentActionEnvelope) {
        actionHistory.addFirst(
            envelope.copy(
                before = envelope.before?.copy(controls = emptyList()),
                after = envelope.after?.copy(controls = emptyList()),
            ),
        )
        while (actionHistory.size > HISTORY_LIMIT) actionHistory.removeLast()
    }

    private fun hasFreeformSelector(params: JSONObject): Boolean =
        params.optJSONObject("selector") != null ||
            SELECTOR_KEYS.any { params.has(it) }

    private fun elementId(params: JSONObject): String? =
        params.optString("elementId").takeIf(String::isNotBlank)
            ?: params.optJSONObject("selector")
                ?.optString("elementId")
                ?.takeIf(String::isNotBlank)
            ?: params.optJSONObject("selector")
                ?.optString("ref")
                ?.takeIf(String::isNotBlank)

    private fun belongsToObservation(elementId: String, observationId: String): Boolean =
        elementId.split(':').getOrNull(1)?.let { it == observationId } ?: false

    private fun staleFailure(message: String) = AgentFailure(
        AgentFailureClass.STALE_OBSERVATION,
        AgentFailureLayer.OBSERVATION,
        true,
        message,
        "STALE_OBSERVATION",
    )

    private fun copyObject(value: JSONObject?): JSONObject =
        value?.let { JSONObject(it.toString()) } ?: JSONObject()

    private fun copyArray(value: JSONArray?): JSONArray =
        value?.let { JSONArray(it.toString()) } ?: JSONArray()

    companion object {
        private const val DEFAULT_LOCATE_LIMIT = 8
        private const val DEFAULT_SEARCH_LIMIT = 20
        private const val PAGE_CARD_GOAL_CANDIDATES = 10
        private const val PAGE_CARD_CONTROL_LIMIT = 36
        private const val HISTORY_LIMIT = 40

        private val LOCAL_MUTATING_TOOLS = setOf(
            "phone.click",
            "phone.long_press",
            "phone.scroll",
            "phone.type",
            "phone.replace_text",
            "phone.back",
            "phone.home",
            "phone.open_app",
            "phone.launch_intent",
        )
        private val ELEMENT_ID_REQUIRED_TOOLS = setOf(
            "phone.click",
            "phone.long_press",
            "phone.type",
            "phone.replace_text",
        )
        private val ELEMENT_SCOPED_TOOLS = ELEMENT_ID_REQUIRED_TOOLS + "phone.scroll"
        private val SELECTOR_KEYS = setOf(
            "resourceId",
            "text",
            "textContains",
            "contentDescription",
            "contentDescriptionContains",
            "class",
            "role",
            "ancestorText",
            "descendantText",
            "x",
            "y",
            "relativeToText",
            "relativeDirection",
            "fuzzyText",
            "path",
        )
    }
}

internal class AgentObservationScope {
    var observationId: String? = null
        private set

    var generation: Long = 0
        private set

    fun publish(id: String): Long {
        require(id.isNotBlank())
        generation += 1
        observationId = id
        return generation
    }

    fun expire() {
        observationId = null
    }
}

internal interface CycloneAgentRuntimePort {
    fun capture(): GatewayObservation
    fun current(): GatewayObservation?
    fun search(observation: GatewayObservation, query: String, limit: Int): JSONArray
    fun element(observation: GatewayObservation, elementId: String): JSONObject
    fun screenshot(goal: String): JSONObject
    fun readinessFailure(): AgentFailure?
    fun policyFailure(tool: String, params: JSONObject): AgentFailure?
    fun execute(requestId: String, tool: String, params: JSONObject): PhoneToolResult
    fun captureAfter(
        tool: String,
        params: JSONObject,
        before: GatewayObservation,
    ): GatewayObservation?

    fun verify(
        tool: String,
        expectedPackage: String,
        goalLabel: String,
        before: GatewayObservation,
        after: GatewayObservation?,
        androidExecutionOk: Boolean,
        executorAssertionFailed: Boolean,
        explicitExpectation: Boolean,
    ): AgentSemanticVerification

    fun recordLearning(
        goal: String,
        tool: String,
        params: JSONObject,
        before: GatewayObservation,
        after: GatewayObservation?,
        androidExecutionOk: Boolean,
        verification: AgentSemanticVerification,
    ): AgentLearningResult

    fun brainRecall(goal: String): JSONObject
    fun knownRoutes(goal: String): JSONObject
}

private class AndroidCycloneAgentRuntimePort(
    private val context: Context,
) : CycloneAgentRuntimePort {
    override fun capture(): GatewayObservation =
        GatewayObservationAdapter.capture(context, JSONObject())

    override fun current(): GatewayObservation? = GatewayObservationStore.current()

    override fun search(
        observation: GatewayObservation,
        query: String,
        limit: Int,
    ): JSONArray = GatewayObservationAdapter.search(observation, query, limit)

    override fun element(
        observation: GatewayObservation,
        elementId: String,
    ): JSONObject = GatewayObservationAdapter.element(observation, elementId)

    override fun screenshot(goal: String): JSONObject = GatewayCaptureAdapter.capture(
        context,
        JSONObject().put("maxDimension", 960).put("includeBase64", false),
    ).apply {
        remove("base64")
    }

    override fun readinessFailure(): AgentFailure? = when {
        CycloneAccessibilityService.instance == null || !DeviceState.accessibilityConnected -> AgentFailure(
            AgentFailureClass.ACCESSIBILITY_UNAVAILABLE,
            AgentFailureLayer.DEVICE,
            true,
            "Cyclone Accessibility is not connected.",
            "ACCESSIBILITY_UNAVAILABLE",
        )
        DeviceState.controller != DeviceState.Controller.AGENT -> AgentFailure(
            AgentFailureClass.HUMAN_HAS_CONTROL,
            AgentFailureLayer.DEVICE,
            true,
            "Human currently owns device input.",
            "HUMAN_HAS_CONTROL",
        )
        DeviceState.requireFreshObservation -> AgentFailure(
            AgentFailureClass.STALE_OBSERVATION,
            AgentFailureLayer.OBSERVATION,
            true,
            "A fresh observation is required before mutation.",
            "STALE_OBSERVATION",
        )
        else -> null
    }

    override fun policyFailure(tool: String, params: JSONObject): AgentFailure? {
        val decision = CycloneAiAccessPolicy.evaluate(
            CycloneAiAccessProfileStore.read(context),
            tool,
            params,
        )
        if (decision.allowed) return null
        val localConfirmation = decision.reasonCode == "LOCAL_CONFIRMATION_REQUIRED"
        if (localConfirmation && tool in setOf("phone.click", "phone.long_press")) {
            val selector = params.optJSONObject("selector") ?: params
            val labels = listOf(
                selector.optString("text"),
                selector.optString("textContains"),
                selector.optString("contentDescription"),
                selector.optString("contentDescriptionContains"),
                selector.optString("fuzzyText"),
                selector.optString("descendantText"),
                selector.optString("resourceId"),
            ).map(String::trim).filter(String::isNotBlank)
            val gateClass = GateClassifier.classify(tool, labels)?.let(ClickGateIntercept::overlayClass)
            if (gateClass != null) {
                // Policy may let an explicitly confirmed exact action proceed, but does not consume
                // the grant. The final Accessibility click interceptor consumes the one-shot token.
                if (OverlayChromeRuntime.hasGateApproval(gateClass, tool, labels)) return null
                OverlayChromeRuntime.registerGateChallenge(gateClass, tool, labels)
                OverlayChromeRuntime.enterGate(gateClass)
                return AgentFailure(
                    AgentFailureClass.GATE_REQUIRED,
                    AgentFailureLayer.POLICY,
                    true,
                    decision.safeMessage,
                    decision.reasonCode,
                )
            }
        }
        if (localConfirmation && tool in setOf("phone.type", "phone.replace_text")) {
            return AgentFailure(
                AgentFailureClass.AUTH_REQUIRED,
                AgentFailureLayer.POLICY,
                true,
                decision.safeMessage,
                decision.reasonCode,
            )
        }
        return AgentFailure(
            errorClass = if (localConfirmation) AgentFailureClass.GATE_REQUIRED else AgentFailureClass.POLICY_DENIED,
            failureLayer = AgentFailureLayer.POLICY,
            retryable = localConfirmation,
            message = decision.safeMessage,
            reasonCode = decision.reasonCode,
        )
    }

    override fun execute(
        requestId: String,
        tool: String,
        params: JSONObject,
    ): PhoneToolResult = PhoneToolExecutor.execute(
        context,
        PhoneToolRequest(requestId, tool, JSONObject(params.toString())),
    )

    override fun captureAfter(
        tool: String,
        params: JSONObject,
        before: GatewayObservation,
    ): GatewayObservation? =
        GatewayV33ActionAdapter.captureAfterAction(context, tool, params, before)

    override fun verify(
        tool: String,
        expectedPackage: String,
        goalLabel: String,
        before: GatewayObservation,
        after: GatewayObservation?,
        androidExecutionOk: Boolean,
        executorAssertionFailed: Boolean,
        explicitExpectation: Boolean,
    ): AgentSemanticVerification = GatewayV33ActionAdapter.verifyAfterState(
        tool = tool,
        expectedPackage = expectedPackage,
        goalLabel = goalLabel,
        beforeObservation = before,
        afterObservation = after,
        androidExecutionOk = androidExecutionOk,
        executorAssertionFailed = executorAssertionFailed,
        explicitExpectation = explicitExpectation,
    )

    override fun recordLearning(
        goal: String,
        tool: String,
        params: JSONObject,
        before: GatewayObservation,
        after: GatewayObservation?,
        androidExecutionOk: Boolean,
        verification: AgentSemanticVerification,
    ): AgentLearningResult {
        val verificationJson = JSONObject()
            .put("ok", verification.passed)
            .put("status", verification.status.name)
            .put("semanticSuccessClaimed", verification.semanticSuccessClaimed)
            .put("basis", verification.basis ?: JSONObject.NULL)
        val result = GatewayV33ActionAdapter.recordVerifiedRouteOutcome(
            context = context,
            goal = goal,
            tool = tool,
            params = params,
            before = before,
            after = after,
            transportOk = true,
            androidExecutionOk = androidExecutionOk,
            verification = verificationJson,
            brainSource = "LOCAL_AGENT_VERIFIED_ROUTE",
        )
        return AgentLearningResult(
            recorded = result.optBoolean("recorded", false),
            reason = result.optString("reason").ifBlank {
                "Verified route learning returned no reason."
            },
            evidence = JSONObject(result.toString()),
        )
    }

    override fun brainRecall(goal: String): JSONObject =
        GatewayBrainAdapter.recall(context, JSONObject().put("goal", goal))

    override fun knownRoutes(goal: String): JSONObject =
        GatewayAppGraphAdapter.query(context, JSONObject().put("goal", goal))
}
