package com.cyclone.mobile.agent

import com.cyclone.mobile.agent.contract.AgentFailure
import com.cyclone.mobile.agent.contract.AgentFailureClass
import org.json.JSONObject

enum class ObservationState { HEALTHY, EMPTY_VALID, TIMEOUT, PERMISSION_REQUIRED, DISCONNECTED, SCOPE_MISMATCH, UNAVAILABLE }
data class ObservationHealth(
    val state: ObservationState,
    val sessionId: String,
    val displayId: Int,
    val attempts: Int = 0,
    val lastSuccessMs: Long? = null,
    val cooldownUntilMs: Long = 0,
) {
    val terminal get() = state in setOf(ObservationState.PERMISSION_REQUIRED, ObservationState.DISCONNECTED, ObservationState.SCOPE_MISMATCH) || attempts >= 2
    val reason get() = "observation.${state.name.lowercase()}"
    val message get() = when (state) {
        ObservationState.HEALTHY -> "Semantic observation is current."
        ObservationState.EMPTY_VALID -> "The current screen has no semantic controls."
        ObservationState.TIMEOUT -> "Semantic screen capture timed out. Check the target app and Accessibility service."
        ObservationState.PERMISSION_REQUIRED -> "Accessibility permission or service is unavailable. Restore it before continuing."
        ObservationState.DISCONNECTED -> "The device or workspace bridge is disconnected. Reconnect it before continuing."
        ObservationState.SCOPE_MISMATCH -> "The observation belongs to a different display or session. Reopen this task's workspace."
        ObservationState.UNAVAILABLE -> "No current semantic observation is available after bounded recovery."
    }
    fun toJson() = JSONObject().put("backend", "cyclone_accessibility").put("state", state.name)
        .put("sessionId", sessionId).put("displayId", displayId).put("attempts", attempts)
        .put("lastSuccessMs", lastSuccessMs ?: JSONObject.NULL).put("cooldownUntilMonotonicMs", cooldownUntilMs)
        .put("nextRecovery", if (terminal) "user_or_lifecycle_change" else "same_scope_capture_after_cooldown")
    companion object {
        fun failure(failure: AgentFailure?, session: String, display: Int, attempts: Int, lastSuccess: Long?, now: Long): ObservationHealth {
            val state = when (failure?.errorClass) {
                AgentFailureClass.TIMEOUT -> ObservationState.TIMEOUT
                AgentFailureClass.ACCESSIBILITY_UNAVAILABLE, AgentFailureClass.AUTH_REQUIRED -> ObservationState.PERMISSION_REQUIRED
                AgentFailureClass.DEVICE_DISCONNECTED -> ObservationState.DISCONNECTED
                AgentFailureClass.STALE_OBSERVATION, AgentFailureClass.INVALID_REQUEST -> ObservationState.SCOPE_MISMATCH
                else -> ObservationState.UNAVAILABLE
            }
            return ObservationHealth(state, session, display, attempts, lastSuccess, now + 500)
        }
    }
}
