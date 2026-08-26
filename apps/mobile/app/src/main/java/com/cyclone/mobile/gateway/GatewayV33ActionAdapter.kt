package com.cyclone.mobile.gateway

import android.content.Context
import android.os.PowerManager
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.PhoneToolRequest
import org.json.JSONObject

/**
 * V3.3 boundary around the existing canonical GatewayActionAdapter/PhoneToolExecutor path.
 * It adds strict observation freshness, a bounded normalized-tap fallback and explicit separation
 * of Android execution from after-state verification. It is not a second executor.
 */
internal object GatewayV33ActionAdapter {
    val allowedTools = linkedSetOf<String>().apply {
        addAll(GatewayActionAdapter.allowedTools)
        add("phone.tap")
    }

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
        val tool = args.optString("tool").trim()
        if (tool !in allowedTools) {
            throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "Tool is not enabled for the V3.3 PC gateway", requestId)
        }
        return executeTyped(context, requestId, tool, args, publicCapability = true)
    }

    internal fun executeInternal(
        context: Context,
        requestId: String,
        tool: String,
        args: JSONObject,
    ): JSONObject {
        if (tool !in allowedTools && tool != "phone.set_clipboard") {
            throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "Internal typed phone capability is unavailable", requestId)
        }
        return executeTyped(context, requestId, tool, args, publicCapability = false)
    }

    private fun executeTyped(
        context: Context,
        requestId: String,
        tool: String,
        args: JSONObject,
        publicCapability: Boolean,
    ): JSONObject {
        val beforeObservation = if (tool in mutatingTools) requireFreshObservation(requestId, args) else GatewayObservationStore.current()
        val normalizedArgs = JSONObject(args.toString())
        val normalizedParams = JSONObject((args.optJSONObject("params") ?: JSONObject()).toString())

        val baseResult = when (tool) {
            "phone.tap" -> {
                val observation = beforeObservation
                    ?: throw GatewayProtocolException("STALE_OBSERVATION", "Observe the phone before using coordinate fallback", requestId)
                val snapshot = CycloneAccessibilityService.instance?.observe(markFresh = false)
                    ?: throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "Accessibility is unavailable for coordinate fallback", requestId)
                val expectedFingerprint = observation.payload.optString("accessibilityFingerprint")
                if (expectedFingerprint.isNotBlank() && snapshot.fingerprint != expectedFingerprint) {
                    throw GatewayProtocolException("STALE_OBSERVATION", "Phone geometry changed; observe again before coordinate fallback", requestId)
                }
                val x = try {
                    DesktopManualControlContract.normalizedPixel(
                        normalizedParams.optDouble("normalizedX", Double.NaN),
                        snapshot.screenWidth,
                    )
                } catch (_: IllegalArgumentException) {
                    throw GatewayProtocolException("PROTOCOL_MISMATCH", "normalizedX must be between 0 and 1", requestId)
                }
                val y = try {
                    DesktopManualControlContract.normalizedPixel(
                        normalizedParams.optDouble("normalizedY", Double.NaN),
                        snapshot.screenHeight,
                    )
                } catch (_: IllegalArgumentException) {
                    throw GatewayProtocolException("PROTOCOL_MISMATCH", "normalizedY must be between 0 and 1", requestId)
                }
                normalizedParams.remove("normalizedX")
                normalizedParams.remove("normalizedY")
                normalizedParams.put("x", x).put("y", y)
                normalizedArgs.put("params", normalizedParams)
                executeDirect(context, requestId, tool, normalizedArgs, normalizedParams)
                    .put("coordinateBasisObservationId", observation.id)
                    .put("coordinateSpace", "normalized-current-display")
            }
            "phone.set_clipboard" -> executeDirect(context, requestId, tool, normalizedArgs, normalizedParams)
            else -> GatewayActionAdapter.execute(context, requestId, normalizedArgs)
        }

        val execution = baseResult.optJSONObject("execution") ?: JSONObject()
        val error = execution.optJSONObject("error")
        val errorCode = error?.optString("code").orEmpty()
        val executorReportedOk = execution.optBoolean("ok", false)
        val verificationFailedInExecutor = errorCode == "ASSERTION_FAILED"
        val androidExecutionOk = executorReportedOk || verificationFailedInExecutor
        val afterObservation = if (tool in mutatingTools && androidExecutionOk) {
            runCatching { GatewayObservationAdapter.capture(context, JSONObject()) }.getOrNull()
        } else null
        val expect = normalizedParams.optJSONObject("expect")
        val verification = when {
            verificationFailedInExecutor -> JSONObject()
                .put("ok", false)
                .put("status", "FAILED")
                .put("code", "VERIFICATION_FAILED")
                .put("message", "Android performed the action but the requested after-state assertion did not pass.")
            !androidExecutionOk -> JSONObject()
                .put("ok", false)
                .put("status", "NOT_RUN")
                .put("code", JSONObject.NULL)
            tool !in mutatingTools -> JSONObject()
                .put("ok", true)
                .put("status", "NOT_REQUIRED")
                .put("code", JSONObject.NULL)
            afterObservation == null -> JSONObject()
                .put("ok", false)
                .put("status", "DEGRADED")
                .put("code", "VERIFICATION_FAILED")
                .put("message", "Action executed but a fresh after-observation could not be captured.")
            expect != null -> JSONObject()
                .put("ok", true)
                .put("status", "PASSED")
                .put("code", JSONObject.NULL)
            else -> JSONObject()
                .put("ok", true)
                .put("status", "OBSERVED")
                .put("code", JSONObject.NULL)
                .put("semanticSuccessClaimed", false)
        }

        return baseResult
            .put("transport", JSONObject().put("ok", true).put("protocol", GatewayProtocol.VERSION))
            .put("androidExecution", JSONObject()
                .put("ok", androidExecutionOk)
                .put("executorReportedOk", executorReportedOk)
                .put("errorCode", errorCode.takeIf(String::isNotBlank) ?: JSONObject.NULL))
            .put("afterState", afterObservation?.let(::compactAfterState) ?: JSONObject.NULL)
            .put("verification", verification)
            .put("requiresReobserveBeforeNextMutation", tool in mutatingTools)
            .put("publicCapability", publicCapability)
    }

    private fun executeDirect(
        context: Context,
        requestId: String,
        tool: String,
        args: JSONObject,
        params: JSONObject,
    ): JSONObject {
        val observationId = args.optString("currentObservationId").trim()
        val authorityRequest = GatewayActionAuthorityRequest(
            requestId = requestId,
            capability = tool,
            parameters = JSONObject(params.toString()),
            currentObservationId = observationId,
            source = "PC_CODEX",
            goal = args.optString("goal").ifBlank { tool.removePrefix("phone.").replace('_', ' ') },
            missionMetadata = JSONObject((args.optJSONObject("missionMetadata") ?: JSONObject()).toString()),
        )
        val decision = GatewayActionAuthorityRegistry.authorize(context, authorityRequest)
        decision.requireAuthorized(requestId)
        val result = PhoneToolExecutor.execute(context, PhoneToolRequest(requestId, tool, JSONObject(params.toString())))
        val safeParams = if (tool == "phone.set_clipboard") {
            JSONObject().put("text", "[REDACTED]").put("redacted", true)
        } else {
            GatewayPrivacy.redactActionParams(tool, params)
        }
        return JSONObject()
            .put("source", "PC_CODEX")
            .put("tool", tool)
            .put("authority", JSONObject()
                .put("binding", GatewayActionAuthorityRegistry.bindingName())
                .put("outcome", decision.outcome.name)
                .put("reasonCode", decision.reasonCode))
            .put("sanitizedParams", safeParams)
            .put("execution", GatewayPrivacy.sanitizeDeep(result.toJson()))
    }

    private fun requireFreshObservation(requestId: String, args: JSONObject): GatewayObservation {
        val current = GatewayObservationStore.current()
            ?: throw GatewayProtocolException("STALE_OBSERVATION", "Call observe.semantic before a mutating phone action", requestId)
        val requested = args.optString("currentObservationId").trim()
        if (requested.isBlank() || requested != current.id) {
            throw GatewayProtocolException(
                "STALE_OBSERVATION",
                "Mutating V3.3 actions require the exact current observationId; observe again after page changes.",
                requestId,
            )
        }
        return current
    }

    private fun compactAfterState(observation: GatewayObservation): JSONObject = JSONObject()
        .put("observationId", observation.id)
        .put("package", observation.page.packageName)
        .put("pageKey", observation.page.pageKey)
        .put("structuralKey", observation.page.structuralKey)
        .put("contentKey", observation.page.contentKey)
        .put("accessibilityFingerprint", observation.payload.optString("accessibilityFingerprint"))
        .put("capturedAtMs", observation.capturedAt)
}

internal object GatewayV33ManualDesktopAdapter {
    fun execute(context: Context, requestId: String, args: JSONObject): JSONObject {
        val kind = args.optString("kind")
        if (kind !in DesktopManualControlContract.allowedKinds) {
            throw GatewayProtocolException("PROTOCOL_MISMATCH", "Unsupported manual control kind", requestId)
        }
        if (kind == "wake") {
            val power = context.getSystemService(PowerManager::class.java)
            if (power?.isInteractive == true) {
                return JSONObject().put("ok", true).put("kind", "wake").put("status", "ALREADY_AWAKE")
            }
            throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "Safe wake is unavailable on this build", requestId)
        }
        val observation = GatewayObservationAdapter.capture(context, JSONObject())
        val toolArgs = JSONObject()
            .put("source", "PC_CODEX")
            .put("currentObservationId", observation.id)
            .put("goal", "User-owned desktop $kind control")
            .put("params", JSONObject())
        val params = toolArgs.getJSONObject("params")
        when (kind) {
            "tap" -> {
                toolArgs.put("tool", "phone.tap")
                params.put("normalizedX", args.optDouble("x", Double.NaN))
                params.put("normalizedY", args.optDouble("y", Double.NaN))
                params.put("waitForChangeMs", 0)
            }
            "back" -> toolArgs.put("tool", "phone.back")
            "home" -> toolArgs.put("tool", "phone.home")
            "scroll_up" -> {
                toolArgs.put("tool", "phone.scroll")
                params.put("direction", "backward")
            }
            "scroll_down" -> {
                toolArgs.put("tool", "phone.scroll")
                params.put("direction", "forward")
            }
            "text" -> {
                val value = args.optString("text")
                if (value.isBlank() || value.length > 4096) {
                    throw GatewayProtocolException("PROTOCOL_MISMATCH", "text batch is invalid", requestId)
                }
                toolArgs.put("tool", "phone.type")
                params.put("value", value)
                params.put("waitForChangeMs", 0)
            }
        }
        return GatewayV33ActionAdapter.execute(context, requestId, toolArgs)
            .put("manualDesktopKind", kind)
            .put("typedValueRedacted", kind == "text")
    }
}

internal object GatewayV33ClipboardAdapter {
    fun set(context: Context, requestId: String, args: JSONObject): JSONObject {
        if (!GatewayDesktopPreferences.clipboardEnabled(context)) {
            throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "Clipboard sync is disabled on the phone", requestId)
        }
        val value = args.optString("text")
        if (value.isBlank() || value.length > 16_384 || DesktopClipboardPolicy.looksSensitive(value)) {
            throw GatewayProtocolException("POLICY_DENIED", "Clipboard content was rejected by the privacy filter", requestId)
        }
        val observation = GatewayObservationStore.current() ?: GatewayObservationAdapter.capture(context, JSONObject())
        val actionArgs = JSONObject()
            .put("source", "PC_CODEX")
            .put("currentObservationId", observation.id)
            .put("goal", "Set user-approved PC to phone clipboard")
            .put("params", JSONObject().put("text", value).put("waitForChangeMs", 0))
        return GatewayV33ActionAdapter.executeInternal(context, requestId, "phone.set_clipboard", actionArgs)
            .put("updated", true)
            .put("mode", "PC_TO_PHONE")
            .put("contentRedacted", true)
    }
}
