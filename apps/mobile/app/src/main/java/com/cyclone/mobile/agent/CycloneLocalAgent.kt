package com.cyclone.mobile.agent

import java.util.UUID

enum class CycloneAgentStage { START, OBSERVE, PLAN_OR_RECALL, ACT, VERIFY, CLASSIFY_RESULT, SUSPENDED, TERMINAL }
enum class CycloneTaskClassification { COMPLETE, RECOVERABLE, HUMAN_OR_GATE, HARD_BLOCKER, CANCELLED, NON_CONVERGENCE }
enum class CycloneModelDirective { ACT, DONE, NEED_HUMAN, BLOCKED, NEED_VISION }
enum class CycloneRecoveryKind { OBSERVATION_FAILURE, MALFORMED_MODEL, MODEL_BLOCKED_UNCONFIRMED, EMPTY_OR_INVALID_PLAN, TOOL_FAILURE, VERIFICATION_FAILURE, VISION_UNCHANGED, STALE_TARGET, BACKTRACK, POLICY_DENIED }
enum class CycloneTraceEventType { TASK_STARTED, OBSERVE, PLAN, TOOL_REQUESTED, TOOL_RESULT, VERIFY, RECOVERY_CLASSIFIED, REPLAN, VISION_ESCALATION, GATE_SUSPEND, GATE_RESUME, COMPLETE, HARD_BLOCKER, NON_CONVERGENCE, CANCELLED }

data class CycloneConvergencePolicy(
    val taskTimeoutMs: Long = 180_000,
    val maxRepeatedIdenticalActionWithoutProgress: Int = 2,
    val maxConsecutiveRecoveryCyclesWithoutNewEvidence: Int = 8,
    val maxMalformedModelResponses: Int = 3,
    val maxVisionAttemptsOnUnchangedState: Int = 1,
    val maxBacktrackAttempts: Int = 3,
    val maxStaleTargetRetries: Int = 2,
) {
    init {
        require(taskTimeoutMs > 0)
        require(maxRepeatedIdenticalActionWithoutProgress > 0)
        require(maxConsecutiveRecoveryCyclesWithoutNewEvidence > 0)
        require(maxMalformedModelResponses > 0)
        require(maxVisionAttemptsOnUnchangedState > 0)
        require(maxBacktrackAttempts > 0)
        require(maxStaleTargetRetries > 0)
    }
}

data class CycloneObservation(val identity: String, val pageIdentity: String, val evidenceIdentity: String = identity)
data class CycloneModelTurn(val directive: CycloneModelDirective, val actionSignature: String? = null, val reason: String? = null, val payload: Any? = null)
sealed interface CyclonePlanResult { data class Valid(val turn: CycloneModelTurn) : CyclonePlanResult; data class Malformed(val reason: String? = null) : CyclonePlanResult }
data class CycloneToolResult(
    val ok: Boolean,
    val actionSignature: String? = null,
    val evidenceIdentity: String? = null,
    val policyAllowed: Boolean = true,
    val gateRequired: Boolean = false,
    val hardBlocker: Boolean = false,
    val staleTarget: Boolean = false,
    val backtracked: Boolean = false,
    val message: String? = null,
    val payload: Any? = null,
)
data class CycloneVerificationResult(val verified: Boolean, val progress: Boolean, val complete: Boolean = false, val evidenceIdentity: String? = null, val message: String? = null)
data class CycloneTraceEvent(
    val type: CycloneTraceEventType,
    val timestampMs: Long,
    val taskId: String,
    val stage: CycloneAgentStage,
    val code: String? = null,
    val observationIdentity: String? = null,
    val pageIdentity: String? = null,
    val actionSignature: String? = null,
)

fun interface CycloneAgentTraceSink { fun emit(event: CycloneTraceEvent); object NoOp : CycloneAgentTraceSink { override fun emit(event: CycloneTraceEvent) = Unit } }
interface CycloneTaskCheckpointStore { fun save(state: CycloneTaskState); object NoOp : CycloneTaskCheckpointStore { override fun save(state: CycloneTaskState) = Unit } }
interface CycloneAgentModel { fun plan(state: CycloneTaskState, observation: CycloneObservation): CyclonePlanResult }
interface CycloneAgentTools {
    fun observe(state: CycloneTaskState): CycloneObservation?
    fun execute(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn): CycloneToolResult
    fun verify(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn, toolResult: CycloneToolResult): CycloneVerificationResult
    fun classifyModelBoundary(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn): CycloneTaskClassification = CycloneTaskClassification.RECOVERABLE
    fun verifyCompletion(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn): CycloneVerificationResult = CycloneVerificationResult(false, false)
}

data class CycloneTaskState(
    val taskId: String,
    val goal: String,
    val currentStage: CycloneAgentStage,
    val latestObservationIdentity: String?,
    val latestPageIdentity: String?,
    val recentSuccessfulVerifiedActions: List<String>,
    val recentFailedActions: List<String>,
    val recoveryAttempts: Map<CycloneRecoveryKind, Int>,
    val visionUseState: Map<String, Int>,
    val taskStartTimeMs: Long,
    val lastVerifiedProgressTimeMs: Long,
    val gateSuspended: Boolean,
    val requireFreshObservation: Boolean,
    val finalClassification: CycloneTaskClassification?,
    val modelTurns: Int,
    val consecutiveRecoveryCyclesWithoutNewEvidence: Int,
    val repeatedIdenticalActionWithoutProgress: Int,
    val backtrackAttempts: Int,
    val staleTargetRetries: Int,
    val lastActionSignature: String?,
)

sealed interface CycloneAgentRunResult {
    val state: CycloneTaskState
    data class Completed(override val state: CycloneTaskState, val message: String? = null) : CycloneAgentRunResult
    data class Suspended(override val state: CycloneTaskState, val message: String? = null) : CycloneAgentRunResult
    data class Stopped(override val state: CycloneTaskState, val message: String? = null) : CycloneAgentRunResult
    data class Cancelled(override val state: CycloneTaskState, val message: String? = null) : CycloneAgentRunResult
}

/** Cyclone-owned task graph. Provider/model turn count is telemetry, never a termination budget. */
class CycloneLocalAgent(
    goal: String,
    private val model: CycloneAgentModel,
    private val tools: CycloneAgentTools,
    private val convergence: CycloneConvergencePolicy = CycloneConvergencePolicy(),
    private val trace: CycloneAgentTraceSink = CycloneAgentTraceSink.NoOp,
    private val checkpoints: CycloneTaskCheckpointStore = CycloneTaskCheckpointStore.NoOp,
    private val now: () -> Long = System::currentTimeMillis,
    private val externallyCancelled: () -> Boolean = { false },
    taskId: String = "local-${UUID.randomUUID()}",
) {
    private var cancelled = false
    private var state = CycloneTaskState(taskId, goal, CycloneAgentStage.START, null, null, emptyList(), emptyList(), emptyMap(), emptyMap(), now(), now(), false, true, null, 0, 0, 0, 0, 0, null)

    init { require(goal.isNotBlank()); emit(CycloneTraceEventType.TASK_STARTED); checkpoint() }
    fun snapshot(): CycloneTaskState = state
    fun cancel() { cancelled = true }
    fun resume(): Boolean {
        if (!state.gateSuspended || state.finalClassification != CycloneTaskClassification.HUMAN_OR_GATE) return false
        state = state.copy(currentStage = CycloneAgentStage.OBSERVE, gateSuspended = false, requireFreshObservation = true, finalClassification = null, consecutiveRecoveryCyclesWithoutNewEvidence = 0, repeatedIdenticalActionWithoutProgress = 0)
        emit(CycloneTraceEventType.GATE_RESUME); checkpoint(); return true
    }

    fun runUntilBoundary(): CycloneAgentRunResult {
        if (state.currentStage == CycloneAgentStage.TERMINAL) return terminalResult()
        if (state.gateSuspended) return CycloneAgentRunResult.Suspended(state)
        while (true) {
            cancellation()?.let { return it }
            if (now() - state.taskStartTimeMs > convergence.taskTimeoutMs) return nonConvergence("convergence.task_timeout")

            state = state.copy(currentStage = CycloneAgentStage.OBSERVE)
            val oldObs = state.latestObservationIdentity
            val oldPage = state.latestPageIdentity
            val observation = tools.observe(state)
            if (observation == null) {
                recover(CycloneRecoveryKind.OBSERVATION_FAILURE, "observe.failed", false)?.let { return it }
                continue
            }
            val newEvidence = observation.identity != oldObs || observation.pageIdentity != oldPage
            state = state.copy(
                latestObservationIdentity = observation.identity,
                latestPageIdentity = observation.pageIdentity,
                requireFreshObservation = false,
                repeatedIdenticalActionWithoutProgress = if (newEvidence) 0 else state.repeatedIdenticalActionWithoutProgress,
                lastActionSignature = if (newEvidence) null else state.lastActionSignature,
                consecutiveRecoveryCyclesWithoutNewEvidence = if (newEvidence) 0 else state.consecutiveRecoveryCyclesWithoutNewEvidence,
            )
            emit(CycloneTraceEventType.OBSERVE, observation = observation); checkpoint()
            cancellation()?.let { return it }

            state = state.copy(currentStage = CycloneAgentStage.PLAN_OR_RECALL)
            val plan = model.plan(state, observation)
            state = state.copy(modelTurns = state.modelTurns + 1)
            if (plan is CyclonePlanResult.Malformed) {
                emit(CycloneTraceEventType.PLAN, "model.malformed", observation)
                recover(CycloneRecoveryKind.MALFORMED_MODEL, "model.malformed", newEvidence)?.let { return it }
                continue
            }
            val turn = (plan as CyclonePlanResult.Valid).turn
            emit(CycloneTraceEventType.PLAN, turn.directive.name.lowercase(), observation, turn.actionSignature); checkpoint()

            when (turn.directive) {
                CycloneModelDirective.DONE -> {
                    state = state.copy(currentStage = CycloneAgentStage.VERIFY)
                    val v = tools.verifyCompletion(state, observation, turn)
                    emit(CycloneTraceEventType.VERIFY, if (v.verified && v.complete) "completion.verified" else "completion.unverified", observation)
                    if (v.verified && v.complete) return complete(v.message)
                    recover(CycloneRecoveryKind.VERIFICATION_FAILURE, "completion.unverified", newEvidence)?.let { return it }
                    continue
                }
                CycloneModelDirective.BLOCKED, CycloneModelDirective.NEED_HUMAN -> {
                    state = state.copy(currentStage = CycloneAgentStage.CLASSIFY_RESULT)
                    when (tools.classifyModelBoundary(state, observation, turn)) {
                        CycloneTaskClassification.HUMAN_OR_GATE -> return suspendForGate(turn.reason)
                        CycloneTaskClassification.HARD_BLOCKER -> return hardBlocker(turn.reason)
                        CycloneTaskClassification.CANCELLED -> return cancelResult(turn.reason)
                        CycloneTaskClassification.NON_CONVERGENCE -> return nonConvergence("classifier.non_convergence")
                        CycloneTaskClassification.COMPLETE -> {
                            val v = tools.verifyCompletion(state, observation, turn)
                            if (v.verified && v.complete) return complete(v.message)
                            recover(CycloneRecoveryKind.VERIFICATION_FAILURE, "boundary.complete_unverified", newEvidence)?.let { return it }
                        }
                        CycloneTaskClassification.RECOVERABLE -> recover(CycloneRecoveryKind.MODEL_BLOCKED_UNCONFIRMED, "model.blocked_unconfirmed", newEvidence)?.let { return it }
                    }
                    continue
                }
                CycloneModelDirective.NEED_VISION -> {
                    val used = state.visionUseState[observation.identity] ?: 0
                    if (used >= convergence.maxVisionAttemptsOnUnchangedState) {
                        recover(CycloneRecoveryKind.VISION_UNCHANGED, "vision.unchanged", false)?.let { return it }
                        continue
                    }
                    state = state.copy(visionUseState = state.visionUseState + (observation.identity to used + 1))
                    emit(CycloneTraceEventType.VISION_ESCALATION, "vision.escalate", observation)
                }
                CycloneModelDirective.ACT -> Unit
            }

            if (turn.actionSignature.isNullOrBlank()) {
                recover(CycloneRecoveryKind.EMPTY_OR_INVALID_PLAN, "plan.no_action_signature", newEvidence)?.let { return it }
                continue
            }
            val repeated = if (turn.actionSignature == state.lastActionSignature) state.repeatedIdenticalActionWithoutProgress + 1 else 1
            state = state.copy(currentStage = CycloneAgentStage.ACT, lastActionSignature = turn.actionSignature, repeatedIdenticalActionWithoutProgress = repeated)
            emit(CycloneTraceEventType.TOOL_REQUESTED, "tool.requested", observation, turn.actionSignature)
            if (repeated > convergence.maxRepeatedIdenticalActionWithoutProgress) return nonConvergence("convergence.repeated_action")

            val tool = tools.execute(state, observation, turn)
            emit(CycloneTraceEventType.TOOL_RESULT, if (tool.ok) "tool.ok" else "tool.failed", observation, tool.actionSignature ?: turn.actionSignature); checkpoint()
            cancellation()?.let { return it }
            if (!tool.policyAllowed) {
                if (tool.gateRequired) return suspendForGate(tool.message)
                if (tool.hardBlocker) return hardBlocker(tool.message)
                recover(CycloneRecoveryKind.POLICY_DENIED, "policy.denied", false)?.let { return it }
                continue
            }
            if (tool.gateRequired) return suspendForGate(tool.message)
            if (tool.hardBlocker) return hardBlocker(tool.message)
            if (tool.staleTarget) {
                state = state.copy(staleTargetRetries = state.staleTargetRetries + 1)
                if (state.staleTargetRetries > convergence.maxStaleTargetRetries) return nonConvergence("convergence.stale_target")
                recover(CycloneRecoveryKind.STALE_TARGET, "target.stale", tool.evidenceIdentity != null && tool.evidenceIdentity != observation.evidenceIdentity)?.let { return it }
                continue
            }
            if (tool.backtracked) {
                state = state.copy(backtrackAttempts = state.backtrackAttempts + 1)
                if (state.backtrackAttempts > convergence.maxBacktrackAttempts) return nonConvergence("convergence.backtrack")
            }
            if (!tool.ok) {
                recover(CycloneRecoveryKind.TOOL_FAILURE, "tool.failed", tool.evidenceIdentity != null && tool.evidenceIdentity != observation.evidenceIdentity)?.let { return it }
                continue
            }

            state = state.copy(currentStage = CycloneAgentStage.VERIFY)
            val verification = tools.verify(state, observation, turn, tool)
            emit(CycloneTraceEventType.VERIFY, when { verification.complete && verification.verified -> "verify.complete"; verification.verified && verification.progress -> "verify.progress"; else -> "verify.failed" }, observation, turn.actionSignature)
            if (verification.complete && verification.verified) return complete(verification.message)
            if (verification.verified && verification.progress) {
                state = state.copy(
                    currentStage = CycloneAgentStage.CLASSIFY_RESULT,
                    recentSuccessfulVerifiedActions = bounded(state.recentSuccessfulVerifiedActions + turn.actionSignature, 24),
                    lastVerifiedProgressTimeMs = now(),
                    latestObservationIdentity = verification.evidenceIdentity ?: tool.evidenceIdentity ?: observation.evidenceIdentity,
                    consecutiveRecoveryCyclesWithoutNewEvidence = 0,
                    repeatedIdenticalActionWithoutProgress = 0,
                    staleTargetRetries = 0,
                    finalClassification = CycloneTaskClassification.RECOVERABLE,
                )
                emit(CycloneTraceEventType.RECOVERY_CLASSIFIED, "progress.continue", observation, turn.actionSignature); checkpoint(); continue
            }
            recover(CycloneRecoveryKind.VERIFICATION_FAILURE, "verify.no_progress", verification.evidenceIdentity != null && verification.evidenceIdentity != observation.evidenceIdentity)?.let { return it }
        }
    }

    private fun recover(kind: CycloneRecoveryKind, code: String, newEvidence: Boolean): CycloneAgentRunResult? {
        val attempts = (state.recoveryAttempts[kind] ?: 0) + 1
        val consecutive = if (newEvidence) 0 else state.consecutiveRecoveryCyclesWithoutNewEvidence + 1
        state = state.copy(
            currentStage = CycloneAgentStage.CLASSIFY_RESULT,
            recoveryAttempts = state.recoveryAttempts + (kind to attempts),
            consecutiveRecoveryCyclesWithoutNewEvidence = consecutive,
            recentFailedActions = bounded(state.recentFailedActions + code, 24),
            finalClassification = CycloneTaskClassification.RECOVERABLE,
        )
        emit(CycloneTraceEventType.RECOVERY_CLASSIFIED, code); checkpoint()
        if (kind == CycloneRecoveryKind.MALFORMED_MODEL && attempts > convergence.maxMalformedModelResponses) return nonConvergence("convergence.malformed_model")
        if (consecutive > convergence.maxConsecutiveRecoveryCyclesWithoutNewEvidence) return nonConvergence("convergence.recovery_without_evidence")
        state = state.copy(currentStage = CycloneAgentStage.PLAN_OR_RECALL); emit(CycloneTraceEventType.REPLAN, code); checkpoint(); return null
    }

    private fun cancellation(): CycloneAgentRunResult? = if (cancelled || externallyCancelled()) cancelResult("user.cancel") else null
    private fun cancelResult(message: String?) = finish(CycloneTaskClassification.CANCELLED, CycloneTraceEventType.CANCELLED, message) { CycloneAgentRunResult.Cancelled(it, message) }
    private fun complete(message: String?) = finish(CycloneTaskClassification.COMPLETE, CycloneTraceEventType.COMPLETE, "task.complete") { CycloneAgentRunResult.Completed(it, message) }
    private fun suspendForGate(message: String?): CycloneAgentRunResult.Suspended {
        state = state.copy(currentStage = CycloneAgentStage.SUSPENDED, gateSuspended = true, finalClassification = CycloneTaskClassification.HUMAN_OR_GATE)
        emit(CycloneTraceEventType.GATE_SUSPEND, message); checkpoint(); return CycloneAgentRunResult.Suspended(state, message)
    }
    private fun hardBlocker(message: String?) = finish(CycloneTaskClassification.HARD_BLOCKER, CycloneTraceEventType.HARD_BLOCKER, message) { CycloneAgentRunResult.Stopped(it, message) }
    private fun nonConvergence(code: String) = finish(CycloneTaskClassification.NON_CONVERGENCE, CycloneTraceEventType.NON_CONVERGENCE, code) { CycloneAgentRunResult.Stopped(it, code) }
    private fun <T : CycloneAgentRunResult> finish(classification: CycloneTaskClassification, event: CycloneTraceEventType, code: String?, build: (CycloneTaskState) -> T): T {
        state = state.copy(currentStage = CycloneAgentStage.TERMINAL, gateSuspended = false, finalClassification = classification)
        emit(event, code); checkpoint(); return build(state)
    }
    private fun terminalResult(): CycloneAgentRunResult = when (state.finalClassification) {
        CycloneTaskClassification.COMPLETE -> CycloneAgentRunResult.Completed(state)
        CycloneTaskClassification.CANCELLED -> CycloneAgentRunResult.Cancelled(state)
        CycloneTaskClassification.HUMAN_OR_GATE -> CycloneAgentRunResult.Suspended(state)
        else -> CycloneAgentRunResult.Stopped(state)
    }
    private fun emit(type: CycloneTraceEventType, code: String? = null, observation: CycloneObservation? = null, actionSignature: String? = null) {
        trace.emit(CycloneTraceEvent(type, now(), state.taskId, state.currentStage, code?.take(160), observation?.identity, observation?.pageIdentity, actionSignature?.take(160)))
    }
    private fun checkpoint() = checkpoints.save(state)
    private fun bounded(values: List<String>, max: Int) = if (values.size <= max) values else values.takeLast(max)
}
