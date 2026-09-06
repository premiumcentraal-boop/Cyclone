package com.cyclone.mobile.gateway

import android.content.Context
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.PhoneToolRequest
import com.cyclone.mobile.agent.contract.AgentVerificationStatus
import com.cyclone.mobile.runtime.background.WorkspaceRuntime
import com.cyclone.mobile.runtime.session.ExecutionRequestScope
import com.cyclone.mobile.runtime.session.ExecutionSession
import org.json.JSONObject

/**
 * Background-only companion to GatewayV33ActionAdapter.
 *
 * This is a routing adapter, not a second executor or policy engine. It keeps the existing Gateway
 * authority handoff and then delegates the actual operation to PhoneToolExecutor's workspace path.
 */
internal object GatewaySessionActionAdapter {
    private val mutatingTools = setOf(
        "phone.click",
        "phone.long_press",
        "phone.tap",
        "phone.swipe",
        "phone.scroll",
        "phone.type",
        "phone.back",
        "phone.home",
        "phone.open_app",
        "phone.set_clipboard",
    )

    fun execute(context: Context, requestId: String, args: JSONObject): JSONObject {
        val params = try {
            ExecutionRequestScope.merge(args, args.optJSONObject("params") ?: JSONObject())
        } catch (error: IllegalArgumentException) {
            throw GatewayProtocolException("PROTOCOL_MISMATCH", error.message ?: "Invalid execution context", requestId)
        }
        val execution = try {
            ExecutionRequestScope.read(params)
        } catch (error: IllegalArgumentException) {
            throw GatewayProtocolException("PROTOCOL_MISMATCH", error.message ?: "Invalid execution context", requestId)
        }
        if (execution.sessionId == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID || execution.displayId <= 0) {
            throw GatewayProtocolException("PROTOCOL_MISMATCH", "Background action requires a non-default execution session", requestId)
        }
        try {
            WorkspaceRuntime.requireScope(execution)
        } catch (error: Throwable) {
            throw GatewayProtocolException("STALE_SESSION", (error.message ?: "Workspace is unavailable").take(240), requestId)
        }

        val tool = args.optString("tool").trim()
        if (tool !in GatewayV33ActionAdapter.allowedTools) {
            throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "Tool is not enabled for the current PC gateway", requestId)
        }
        val before = if (tool in mutatingTools) requireFreshObservation(requestId, args, execution.sessionId) else {
            GatewayObservationStore.current(execution.sessionId)
        }
        val effectiveObservationId = before?.id ?: args.optString("currentObservationId").takeIf(String::isNotBlank)
        val goal = args.optString("goal").ifBlank { tool.removePrefix("phone.").replace('_', ' ') }

        val authorityRequest = GatewayActionAuthorityRequest(
            requestId = requestId,
            capability = tool,
            parameters = JSONObject(params.toString()),
            currentObservationId = effectiveObservationId,
            source = "PC_CODEX",
            goal = goal,
            missionMetadata = JSONObject((args.optJSONObject("missionMetadata") ?: JSONObject()).toString()),
        )
        val decision = GatewayActionAuthorityRegistry.authorize(context, authorityRequest)
        decision.requireAuthorized(requestId)

        if (tool in mutatingTools) {
            val observation = before
                ?: throw GatewayProtocolException("STALE_OBSERVATION", "Observe this execution session before mutation", requestId)
            params.put("observationId", observation.id)
            params.put("executionGeneration", observation.payload.optLong("executionGeneration", -1L))
        }
        val native = PhoneToolExecutor.execute(context, PhoneToolRequest(requestId, tool, JSONObject(params.toString())))
        val executorAssertionFailed = native.error?.code?.name == "ASSERTION_FAILED"
        val androidExecutionOk = native.ok || executorAssertionFailed
        val after = if (tool in mutatingTools && androidExecutionOk) {
            runCatching { GatewayObservationAdapter.capture(context, params) }.getOrNull()
        } else null
        val verification = GatewayV33ActionAdapter.verifyAfterState(
            tool = tool,
            expectedPackage = params.optString("package"),
            goalLabel = goal,
            beforeObservation = before,
            afterObservation = after,
            androidExecutionOk = androidExecutionOk,
            executorAssertionFailed = executorAssertionFailed,
            explicitExpectation = params.optJSONObject("expect") != null,
        )
        val verificationJson = JSONObject()
            .put("ok", verification.passed)
            .put("status", verification.status.name)
            .put("semanticSuccessClaimed", verification.semanticSuccessClaimed)
            .put("basis", verification.basis ?: JSONObject.NULL)
            .put("message", verification.detail ?: JSONObject.NULL)
        val routeLearning = if (verification.status == AgentVerificationStatus.PASSED) {
            GatewayV33ActionAdapter.recordVerifiedRouteOutcome(
                context = context,
                goal = goal,
                tool = tool,
                params = params,
                before = before,
                after = after,
                transportOk = true,
                androidExecutionOk = androidExecutionOk,
                verification = verificationJson,
                brainSource = "PC_CYCLONE_ONE_VERIFIED_ROUTE",
            )
        } else {
            JSONObject().put("recorded", false).put("reason", "Background route was not semantically verified")
        }

        return JSONObject()
            .put("source", "PC_CODEX")
            .put("tool", tool)
            .put("sessionId", execution.sessionId)
            .put("displayId", execution.displayId)
            .put("authority", JSONObject()
                .put("binding", GatewayActionAuthorityRegistry.bindingName())
                .put("outcome", decision.outcome.name)
                .put("reasonCode", decision.reasonCode))
            .put("sanitizedParams", GatewayPrivacy.redactActionParams(tool, params))
            .put("execution", GatewayPrivacy.sanitizeDeep(native.toJson()))
            .put("transport", JSONObject().put("ok", true).put("protocol", GatewayProtocol.VERSION))
            .put("androidExecution", JSONObject()
                .put("ok", androidExecutionOk)
                .put("executorReportedOk", native.ok)
                .put("errorCode", native.error?.code?.name ?: JSONObject.NULL))
            .put("afterState", after?.let(::compactAfterState) ?: JSONObject.NULL)
            .put("verification", verificationJson)
            .put("routeLearning", routeLearning)
            .put("requiresReobserveBeforeNextMutation", tool in mutatingTools)
            .put("publicCapability", true)
    }

    private fun requireFreshObservation(requestId: String, args: JSONObject, sessionId: String): GatewayObservation {
        val current = GatewayObservationStore.current(sessionId)
            ?: throw GatewayProtocolException("STALE_OBSERVATION", "Call observe.semantic for this session before a mutating action", requestId)
        val nestedObservation = args.optJSONObject("executionContext")?.optString("observationId").orEmpty()
        val requested = args.optString("currentObservationId").trim().ifBlank { nestedObservation.trim() }
        if (requested.isBlank() || requested != current.id) {
            throw GatewayProtocolException(
                "STALE_OBSERVATION",
                "Mutating background actions require the exact current session observationId.",
                requestId,
            )
        }
        if (current.execution.sessionId != sessionId) {
            throw GatewayProtocolException("STALE_OBSERVATION", "Observation belongs to another execution session", requestId)
        }
        return current
    }

    private fun compactAfterState(observation: GatewayObservation): JSONObject = JSONObject()
        .put("observationId", observation.id)
        .put("sessionId", observation.execution.sessionId)
        .put("displayId", observation.execution.displayId)
        .put("package", observation.page.packageName)
        .put("pageKey", observation.page.pageKey)
        .put("structuralKey", observation.page.structuralKey)
        .put("contentKey", observation.page.contentKey)
        .put("accessibilityFingerprint", observation.payload.optString("accessibilityFingerprint"))
        .put("capturedAtMs", observation.capturedAt)
}
