package com.cyclone.mobile.infrastructure.v3

import com.cyclone.mobile.PhoneToolRequest
import com.cyclone.mobile.brain.memory.api.CycloneMemoryService
import com.cyclone.mobile.brain.memory.api.MemoryProposalStatus
import com.cyclone.mobile.brain.memory.api.MemoryWriteProposalRequest
import com.cyclone.mobile.observability.context.ContextLedger
import com.cyclone.mobile.observability.events.ContextEventRequest
import com.cyclone.mobile.observability.events.VerificationStatus
import com.cyclone.mobile.policy.GuardedPolicyResult
import com.cyclone.mobile.policy.PolicyAuthorization
import com.cyclone.mobile.policy.PolicyGovernor
import com.cyclone.mobile.policy.PolicyGuard
import com.cyclone.mobile.policy.PolicyRequest

enum class DecisionSource { AI, ROUTINE }

data class DecisionEvidence(
    val goalId: String,
    val knowledgeReferences: List<String>,
    val pageObservationId: String,
    val selectorObservationId: String,
    val decisionSource: DecisionSource,
    val routineId: String? = null,
) {
    init {
        require(goalId.isNotBlank() && pageObservationId.isNotBlank() && selectorObservationId.isNotBlank())
        require(knowledgeReferences.none { it.isBlank() })
        require(decisionSource != DecisionSource.ROUTINE || !routineId.isNullOrBlank())
    }
}

data class AuthorizedPhoneActionProposal(
    val request: PhoneToolRequest,
    val authorization: PolicyAuthorization,
    val evidence: DecisionEvidence,
)

/**
 * Proposal-only boundary to the existing canonical PhoneToolExecutor adapter. Implementations may
 * enqueue the approved proposal, but this V3 composition layer contains no second action engine.
 */
fun interface CanonicalPhoneExecutorProposalSink {
    fun propose(action: AuthorizedPhoneActionProposal): String
}

sealed interface ActionCompositionDecision {
    data class Proposed(val handoffId: String) : ActionCompositionDecision
    data class Blocked(val reasonCode: String) : ActionCompositionDecision
}

sealed interface VerifiedCompletionDecision {
    data class Recorded(val memoryStatus: MemoryProposalStatus) : VerifiedCompletionDecision
    data class Blocked(val reasonCode: String) : VerifiedCompletionDecision
}

/** Small contract-composition seam: policy precedes a proposal and verified evidence precedes memory. */
class CycloneV3ActionComposition(
    policyGovernor: PolicyGovernor,
    private val executorProposalSink: CanonicalPhoneExecutorProposalSink,
    private val ledger: ContextLedger,
    private val memory: CycloneMemoryService?,
) {
    private val policy = PolicyGuard(policyGovernor)

    fun propose(
        policyRequest: PolicyRequest,
        phoneRequest: PhoneToolRequest,
        evidence: DecisionEvidence,
    ): ActionCompositionDecision {
        if (evidence.selectorObservationId != evidence.pageObservationId) {
            return ActionCompositionDecision.Blocked("STALE_SELECTOR")
        }
        return when (val guarded = policy.authorizeThen(policyRequest) { authorization ->
            executorProposalSink.propose(AuthorizedPhoneActionProposal(phoneRequest, authorization, evidence))
        }) {
            is GuardedPolicyResult.Blocked -> ActionCompositionDecision.Blocked("POLICY_${guarded.evaluation.audit.reason.name}")
            is GuardedPolicyResult.Executed -> ActionCompositionDecision.Proposed(guarded.value)
        }
    }

    fun recordVerifiedCompletion(
        event: ContextEventRequest,
        memoryProposal: MemoryWriteProposalRequest,
    ): VerifiedCompletionDecision {
        if (event.payload.verification?.status != VerificationStatus.VERIFIED) {
            return VerifiedCompletionDecision.Blocked("VERIFICATION_REQUIRED")
        }
        ledger.append(event)
        val service = memory ?: return VerifiedCompletionDecision.Blocked("MEMORY_UNAVAILABLE")
        return VerifiedCompletionDecision.Recorded(service.proposeWrite(memoryProposal).status)
    }
}

data class CycloneV3Health(
    val visionAvailable: Boolean = true,
    val memoryAvailable: Boolean = true,
    val optionalModulesHealthy: Boolean = true,
    val runtimeHealthy: Boolean = true,
    val gatewayConnected: Boolean = true,
)

enum class DegradationDirective {
    USE_STRUCTURED_PAGE_EVIDENCE,
    CONTINUE_WITHOUT_MEMORY_RECALL,
    QUARANTINE_OPTIONAL_MODULE,
    REQUEST_RECOVERY_ROLLBACK,
    PAUSE_FOR_GATEWAY_RECONNECT,
}

fun CycloneV3Health.degradationPlan(): List<DegradationDirective> = buildList {
    if (!visionAvailable) add(DegradationDirective.USE_STRUCTURED_PAGE_EVIDENCE)
    if (!memoryAvailable) add(DegradationDirective.CONTINUE_WITHOUT_MEMORY_RECALL)
    if (!optionalModulesHealthy) add(DegradationDirective.QUARANTINE_OPTIONAL_MODULE)
    if (!runtimeHealthy) add(DegradationDirective.REQUEST_RECOVERY_ROLLBACK)
    if (!gatewayConnected) add(DegradationDirective.PAUSE_FOR_GATEWAY_RECONNECT)
}
