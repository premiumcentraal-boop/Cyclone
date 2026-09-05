package com.cyclone.mobile.policy

/**
 * GATE classes owned by [PolicyGovernor]: pay, send, delete, grant.
 *
 * Phone-side only. A PC envelope with `autoApprove=true` is ignored and never becomes
 * user authority. Workers cannot use GATE as a promotion path.
 */
enum class GateClass {
    PAY,
    SEND,
    DELETE,
    GRANT,
    ;

    val capability: String
        get() = when (this) {
            PAY -> "commerce.pay"
            SEND -> "messages.send"
            DELETE -> "data.delete"
            GRANT -> "authority.grant"
        }

    val risk: ActionRisk
        get() = when (this) {
            PAY -> ActionRisk.FINANCIAL
            SEND -> ActionRisk.EXTERNAL_COMMUNICATION
            DELETE -> ActionRisk.DESTRUCTIVE
            GRANT -> ActionRisk.SECURITY_CRITICAL
        }

    val jsonKey: String get() = name.lowercase()
}

/**
 * PC-supplied GATE envelope. [autoApprove] is always ignored. [requestedCapsuleStatus]
 * of `verified` is never honored on this path.
 */
data class PcGateEnvelope(
    val autoApprove: Boolean = false,
    val origin: String = "pc",
    val requestedCapsuleStatus: String? = null,
)

object GateClassifier {
    /**
     * Classify the selected action before looking at surrounding prose. This prevents a safe
     * notification-denial control named "Block" from becoming SEND merely because its modal says
     * "wants to send you notifications".
     */
    fun classify(action: String, labels: List<String> = emptyList()): GateClass? =
        ActionIntentClassifier.gateClass(ActionIntentClassifier.classify(action, labels))
}

sealed interface GateDecision {
    val mutationAllowed: Boolean
    val ignoredPcAutoApprove: Boolean
    val writesVerified: Boolean get() = false

    data class NotGated(
        override val ignoredPcAutoApprove: Boolean,
    ) : GateDecision {
        override val mutationAllowed: Boolean = true
    }

    data class Allowed(
        val gateClass: GateClass,
        val evaluation: PolicyEvaluation,
        override val ignoredPcAutoApprove: Boolean,
    ) : GateDecision {
        override val mutationAllowed: Boolean = true
    }

    data class Blocked(
        val gateClass: GateClass,
        val evaluation: PolicyEvaluation,
        override val ignoredPcAutoApprove: Boolean,
    ) : GateDecision {
        override val mutationAllowed: Boolean = false
    }
}

/**
 * Phone-side GATE authority. Every pay/send/delete/grant action is evaluated by
 * [PolicyGovernor]. PC `autoApprove` is dropped on the floor.
 */
class GatePolicy(private val governor: PolicyGovernor) {
    fun evaluate(
        actionId: String,
        action: String,
        labels: List<String> = emptyList(),
        packageName: String? = null,
        principal: PolicyPrincipal,
        requestedAtEpochMillis: Long,
        missionId: String? = null,
        grantId: String? = null,
        pcEnvelope: PcGateEnvelope? = null,
        gateClass: GateClass? = null,
        authorityClaims: List<AuthorityClaim> = emptyList(),
        contextEvidence: List<AuthorityClaim> = emptyList(),
    ): GateDecision {
        val ignoredPcAutoApprove = pcEnvelope?.autoApprove == true
        val classified = gateClass ?: GateClassifier.classify(action, labels)
            ?: return GateDecision.NotGated(ignoredPcAutoApprove)

        val phoneAuthority = if (pcEnvelope != null) {
            authorityClaims.filter {
                it.origin.canIssueGrant && it.origin != AuthorityOrigin.CURRENT_CONFIRMATION
            }
        } else {
            authorityClaims
        }

        val evidence = buildList {
            addAll(contextEvidence)
            if (pcEnvelope != null) {
                add(AuthorityClaim(AuthorityOrigin.TOOL_OUTPUT, "pc-gate-envelope"))
            }
            if (ignoredPcAutoApprove) {
                add(AuthorityClaim(AuthorityOrigin.SUBAGENT_INSTRUCTION, "pc-autoapprove-ignored"))
            }
        }

        val request = PolicyRequest(
            actionId = actionId,
            capability = classified.capability,
            risk = classified.risk,
            principal = principal,
            requestedAtEpochMillis = requestedAtEpochMillis,
            missionId = missionId,
            target = PolicyTarget(
                packageName = packageName,
                targetType = "gate",
                targetId = classified.jsonKey,
            ),
            grantId = if (pcEnvelope != null) null else grantId,
            authorityClaims = phoneAuthority,
            contextEvidence = evidence,
        )
        val evaluation = governor.evaluate(request)
        return if (evaluation.isAllowed) {
            GateDecision.Allowed(classified, evaluation, ignoredPcAutoApprove)
        } else {
            GateDecision.Blocked(classified, evaluation, ignoredPcAutoApprove)
        }
    }

    companion object {
        const val FUNCTION = "GatePolicy.evaluate"
        val CLASSES = GateClass.entries.map { it.jsonKey }
    }
}
