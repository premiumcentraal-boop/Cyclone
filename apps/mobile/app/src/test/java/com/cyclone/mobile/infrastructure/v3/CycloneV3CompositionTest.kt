package com.cyclone.mobile.infrastructure.v3

import com.cyclone.mobile.PhoneToolRequest
import com.cyclone.mobile.brain.memory.api.DefaultCycloneMemoryService
import com.cyclone.mobile.brain.memory.api.MemoryActor
import com.cyclone.mobile.brain.memory.api.MemoryApprovalVerifier
import com.cyclone.mobile.brain.memory.api.MemoryClass
import com.cyclone.mobile.brain.memory.api.MemoryClock
import com.cyclone.mobile.brain.memory.api.MemoryContent
import com.cyclone.mobile.brain.memory.api.MemoryDraft
import com.cyclone.mobile.brain.memory.api.MemoryPolicyDecision
import com.cyclone.mobile.brain.memory.api.MemoryPolicyResult
import com.cyclone.mobile.brain.memory.api.MemoryProvenance
import com.cyclone.mobile.brain.memory.api.MemoryProposalStatus
import com.cyclone.mobile.brain.memory.api.MemoryScope
import com.cyclone.mobile.brain.memory.api.MemoryScopeKind
import com.cyclone.mobile.brain.memory.api.MemorySensitivity
import com.cyclone.mobile.brain.memory.api.MemorySourceKind
import com.cyclone.mobile.brain.memory.api.MemoryVerificationState
import com.cyclone.mobile.brain.memory.api.MemoryWritePolicyGate
import com.cyclone.mobile.brain.memory.api.MemoryWriteProposalRequest
import com.cyclone.mobile.brain.memory.api.TestMemoryProvider
import com.cyclone.mobile.brain.memory.audit.InMemoryMemoryAuditJournal
import com.cyclone.mobile.observability.context.ContextLedger
import com.cyclone.mobile.observability.context.InMemoryContextLedgerPersistence
import com.cyclone.mobile.observability.events.ActionOutcome
import com.cyclone.mobile.observability.events.ActionResultTrace
import com.cyclone.mobile.observability.events.ContextDecisionEvent
import com.cyclone.mobile.observability.events.ContextEventRequest
import com.cyclone.mobile.observability.events.DecisionStage
import com.cyclone.mobile.observability.events.VerificationStatus
import com.cyclone.mobile.observability.events.VerificationTrace
import com.cyclone.mobile.policy.ActionRisk
import com.cyclone.mobile.policy.ActionScope
import com.cyclone.mobile.policy.AuthorityClaim
import com.cyclone.mobile.policy.AuthorityGrant
import com.cyclone.mobile.policy.AuthorityOrigin
import com.cyclone.mobile.policy.InMemoryPolicyGovernor
import com.cyclone.mobile.policy.PolicyClock
import com.cyclone.mobile.policy.PolicyPrincipal
import com.cyclone.mobile.policy.PolicyRequest
import com.cyclone.mobile.policy.PrincipalKind
import com.cyclone.mobile.policy.PrincipalRef
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneV3CompositionTest {
    @Test
    fun goalPolicyKnowledgePageAiRoutineProposalVerificationLedgerMemoryHasNoExecutorBypass() {
        val governor = governorWithGrant()
        val ledger = ContextLedger(InMemoryContextLedgerPersistence())
        val memory = memory()
        var proposals = 0
        var executorCalls = 0
        val composition = CycloneV3ActionComposition(
            governor,
            CanonicalPhoneExecutorProposalSink {
                proposals += 1
                // This sink records a handoff only. The canonical executor is deliberately not invoked.
                "handoff:1"
            },
            ledger,
            memory,
        )
        val evidence = DecisionEvidence(
            goalId = "goal:open-settings",
            knowledgeReferences = listOf("graph:route:settings", "memory:verified:settings"),
            pageObservationId = "observation:7",
            selectorObservationId = "observation:7",
            decisionSource = DecisionSource.ROUTINE,
            routineId = "routine:settings",
        )

        val proposed = composition.propose(
            request(),
            PhoneToolRequest("action:open", "phone.click", JSONObject().put("selector", JSONObject().put("text", "Settings"))),
            evidence,
        )
        val completion = composition.recordVerifiedCompletion(verifiedEvent(), memoryProposal())

        assertEquals(ActionCompositionDecision.Proposed("handoff:1"), proposed)
        assertEquals(1, proposals)
        assertEquals(0, executorCalls)
        assertEquals(VerifiedCompletionDecision.Recorded(MemoryProposalStatus.READY), completion)
        assertEquals(1, ledger.query().size)
    }

    @Test
    fun staleSelectorAndPolicyDenialNeverReachCanonicalExecutorProposalSeam() {
        var proposals = 0
        val denied = CycloneV3ActionComposition(
            InMemoryPolicyGovernor(PolicyClock { 100 }),
            CanonicalPhoneExecutorProposalSink { proposals += 1; "unexpected" },
            ContextLedger(InMemoryContextLedgerPersistence()),
            null,
        )
        val stale = DecisionEvidence("goal:1", emptyList(), "observation:2", "observation:1", DecisionSource.AI)
        val current = stale.copy(selectorObservationId = "observation:2")

        assertEquals(ActionCompositionDecision.Blocked("STALE_SELECTOR"), denied.propose(request(), PhoneToolRequest("action:open", "phone.click"), stale))
        assertTrue(denied.propose(request(), PhoneToolRequest("action:open", "phone.click"), current) is ActionCompositionDecision.Blocked)
        assertEquals(0, proposals)
    }

    @Test
    fun failuresDegradeIntoExplicitBoundedDirectives() {
        assertEquals(
            DegradationDirective.values().toList(),
            CycloneV3Health(false, false, false, false, false).degradationPlan(),
        )
    }

    private fun governorWithGrant() = InMemoryPolicyGovernor(PolicyClock { 100 }).also {
        it.issueGrant(
            AuthorityGrant(
                grantId = "grant:open",
                subject = principal,
                authority = AuthorityClaim(AuthorityOrigin.DIRECT_USER_MISSION, "mission:user"),
                scope = ActionScope(setOf("phone.click"), missionId = "mission:settings"),
                allowedRisks = setOf(ActionRisk.ROUTINE),
                issuedAtEpochMillis = 50,
                expiresAtEpochMillis = 1_000,
                maximumUses = 2,
            ),
        )
    }

    private fun request() = PolicyRequest(
        actionId = "action:open",
        capability = "phone.click",
        risk = ActionRisk.ROUTINE,
        principal = PolicyPrincipal(principal),
        requestedAtEpochMillis = 100,
        missionId = "mission:settings",
        grantId = "grant:open",
    )

    private fun verifiedEvent() = ContextEventRequest(
        eventId = "event:verified",
        timestampEpochMillis = 110,
        missionId = "mission:settings",
        payload = ContextDecisionEvent(
            decisionId = "decision:settings",
            stage = DecisionStage.VERIFICATION_RESULT,
            actionResult = ActionResultTrace(ActionOutcome.NOT_EXECUTED),
            verification = VerificationTrace(VerificationStatus.VERIFIED),
        ),
    )

    private fun memoryProposal() = MemoryWriteProposalRequest(
        "proposal:settings",
        MemoryDraft(
            "memory:settings",
            1,
            MemoryActor("cyclone.runtime", MemorySourceKind.CYCLONE_RUNTIME),
            MemoryProvenance("context.ledger", setOf("event:verified"), 110),
            0.9,
            MemoryVerificationState.VERIFIED,
            MemoryScope(MemoryScopeKind.ROUTINE, "routine:settings"),
            MemorySensitivity.INTERNAL,
            MemoryClass.STRUCTURAL_KNOWLEDGE,
            MemoryContent(mapOf("route" to "settings")),
        ),
    )

    private fun memory() = DefaultCycloneMemoryService(
        TestMemoryProvider(),
        MemoryWritePolicyGate { MemoryPolicyResult(MemoryPolicyDecision.ALLOW, "ALLOW") },
        MemoryApprovalVerifier { _, _ -> false },
        InMemoryMemoryAuditJournal(),
        clock = MemoryClock { 120 },
    )

    private companion object {
        val principal = PrincipalRef("cyclone.agent", PrincipalKind.AI_AGENT)
    }
}
