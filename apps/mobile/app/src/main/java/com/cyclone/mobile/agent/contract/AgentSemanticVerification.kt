package com.cyclone.mobile.agent.contract

enum class AgentVerificationStatus {
    OBSERVED,
    PASSED,
    FAILED,
    DEGRADED,
    NOT_REQUIRED,
}

data class SemanticElementState(
    val stableKey: String,
    val label: String,
    val role: String,
    val selected: Boolean,
    val checked: Boolean,
    val focused: Boolean,
    val editableTextState: String? = null,
)

data class SemanticObservationState(
    val packageName: String,
    val pageKey: String,
    val accessibilityFingerprint: String,
    val haystack: String,
    val elements: List<SemanticElementState>,
)

data class AgentSemanticVerification(
    val status: AgentVerificationStatus,
    val passed: Boolean,
    val semanticSuccessClaimed: Boolean,
    val basis: String? = null,
    val detail: String? = null,
)

/**
 * Transport/executor acceptance is deliberately separate from semantic verification.
 * This object is Android-free so the PC gateway and local mobile agent consume identical rules.
 */
object AgentSemanticVerifier {
    private val mutatingTools = setOf(
        "phone.click",
        "phone.long_press",
        "phone.tap",
        "phone.swipe",
        "phone.scroll",
        "phone.type",
        "phone.replace_text",
        "phone.back",
        "phone.home",
        "phone.open_app",
        "phone.set_clipboard",
    )

    // PhoneToolExecutor evaluates params.expect only for actionWithConfirmation tools.
    // A model-supplied expectation on any other tool is data, not verification evidence.
    private val executorExpectationTools = setOf(
        "phone.click",
        "phone.long_press",
        "phone.tap",
        "phone.swipe",
        "phone.scroll",
        "phone.back",
        "phone.home",
    )

    fun verify(
        tool: String,
        androidExecutionOk: Boolean,
        executorAssertionFailed: Boolean,
        explicitExpectation: Boolean,
        expectedPackage: String,
        goalLabel: String,
        before: SemanticObservationState?,
        after: SemanticObservationState?,
    ): AgentSemanticVerification {
        if (executorAssertionFailed) {
            return AgentSemanticVerification(
                status = AgentVerificationStatus.FAILED,
                passed = false,
                semanticSuccessClaimed = false,
                basis = "EXPLICIT_EXPECTATION_FAILED",
                detail = "Android performed the action but the requested after-state assertion did not pass.",
            )
        }
        if (!androidExecutionOk || tool !in mutatingTools) {
            return AgentSemanticVerification(
                status = AgentVerificationStatus.NOT_REQUIRED,
                passed = false,
                semanticSuccessClaimed = false,
                basis = if (androidExecutionOk) "READ_ONLY_TOOL" else "EXECUTION_NOT_ACCEPTED",
            )
        }
        if (after == null) {
            return AgentSemanticVerification(
                status = AgentVerificationStatus.DEGRADED,
                passed = false,
                semanticSuccessClaimed = false,
                basis = "AFTER_OBSERVATION_FAILED",
                detail = "Action executed but a fresh authoritative after-observation was unavailable.",
            )
        }
        if (explicitExpectation && tool in executorExpectationTools) {
            return passed("EXPLICIT_EXPECTATION")
        }
        if (tool == "phone.open_app" && expectedPackage.isNotBlank()) {
            return if (after.packageName == expectedPackage) {
                passed("EXPECTED_PACKAGE")
            } else {
                AgentSemanticVerification(
                    status = AgentVerificationStatus.FAILED,
                    passed = false,
                    semanticSuccessClaimed = false,
                    basis = "EXPECTED_PACKAGE_MISMATCH",
                    detail = "The requested app package was not the authoritative after-state package.",
                )
            }
        }

        if (before != null) {
            if (before.pageKey.isNotBlank() && after.pageKey.isNotBlank() && before.pageKey != after.pageKey) {
                return passed("PAGE_KEY_CHANGED")
            }
            val semanticBasis = samePageSemanticProgress(before, after)
            if (semanticBasis != null) return passed(semanticBasis)
            if (goalLabelAppeared(before.haystack, after.haystack, goalLabel)) {
                return passed("GOAL_LABEL_APPEARED")
            }
            if (
                before.accessibilityFingerprint.isNotBlank() &&
                after.accessibilityFingerprint.isNotBlank() &&
                before.accessibilityFingerprint != after.accessibilityFingerprint
            ) {
                return passed("ACCESSIBILITY_FINGERPRINT_CHANGED")
            }
        }

        return AgentSemanticVerification(
            status = AgentVerificationStatus.OBSERVED,
            passed = false,
            semanticSuccessClaimed = false,
            basis = "NO_SEMANTIC_PROGRESS",
            detail = "Android accepted the action, but the fresh after-state did not prove semantic progress.",
        )
    }

    fun samePageSemanticProgress(
        before: SemanticObservationState,
        after: SemanticObservationState,
    ): String? {
        if (before.pageKey.isNotBlank() && after.pageKey.isNotBlank() && before.pageKey != after.pageKey) return null
        val beforeByKey = before.elements.associateBy { it.stableKey }
        for (element in after.elements) {
            val prior = beforeByKey[element.stableKey] ?: continue
            if (prior.selected != element.selected) return "SELECTED_STATE_CHANGED"
            if (prior.checked != element.checked) return "CHECKED_STATE_CHANGED"
            if (prior.focused != element.focused) return "FOCUSED_STATE_CHANGED"
            val beforeText = prior.editableTextState
            val afterText = element.editableTextState
            if (
                element.role in setOf("textbox", "edit_text") &&
                !beforeText.isNullOrBlank() &&
                !afterText.isNullOrBlank() &&
                beforeText != afterText
            ) {
                return "EDITABLE_TEXT_CHANGED"
            }
        }
        return null
    }

    fun goalLabelAppeared(beforeHaystack: String, afterHaystack: String, goalLabel: String): Boolean {
        val needle = goalLabel.trim()
        if (needle.isBlank()) return false
        return !beforeHaystack.contains(needle, ignoreCase = true) && afterHaystack.contains(needle, ignoreCase = true)
    }

    private fun passed(basis: String) = AgentSemanticVerification(
        status = AgentVerificationStatus.PASSED,
        passed = true,
        semanticSuccessClaimed = true,
        basis = basis,
    )
}
