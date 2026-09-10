package com.cyclone.mobile

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.cyclone.mobile.fastpath.FastPathLoop
import com.cyclone.mobile.fastpath.FastPathSettleResult
import com.cyclone.mobile.fastpath.FastPathTimings
import com.cyclone.mobile.gateway.GatewayObservationStore
import com.cyclone.mobile.runtime.session.SessionContract
import com.cyclone.mobile.runtime.session.SessionPlane
import com.cyclone.mobile.runtime.session.SessionPlaneKind
import com.cyclone.mobile.ui.overlay.GateBlockedException
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object PhoneToolExecutor {
    private const val DEFAULT_TIMEOUT_MS = 6_000L
    private const val MAX_TIMEOUT_MS = 30_000L
    private const val DUPLICATE_WINDOW_MS = 350L

    private val mutatingTools = setOf(
        "phone.click", "phone.long_press", "phone.tap", "phone.type", "phone.replace_text",
        "phone.scroll", "phone.swipe", "phone.back", "phone.home", "phone.open_app",
        "phone.open_notification", "phone.set_clipboard", "phone.share", "phone.launch_intent",
    )
    private val mutationLock = com.cyclone.mobile.runtime.workspaces.Layer2Workspaces.engine.mutationLock
    private val resultCache = object : LinkedHashMap<String, PhoneToolResult>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PhoneToolResult>?): Boolean = size > 250
    }
    private val recentActions = LinkedHashMap<String, Long>()

    /**
     * Read-only tools may run concurrently. Phone mutations remain strictly serialized, but a
     * wait/screenshot/read can no longer hold one global monitor and block unrelated observation.
     */
    fun execute(context: Context, request: PhoneToolRequest): PhoneToolResult {
        val layer2 = com.cyclone.mobile.runtime.workspaces.Layer2Workspaces
        val management = request.tool.startsWith("workspace.") || request.tool == "phone.workspace_switch"
        if (management || request.tool in mutatingTools) return synchronized(mutationLock) {
            try {
                val plane = SessionContract.classify(request.params)
                if (management) {
                    check(plane.kind == SessionPlaneKind.FOREGROUND || plane.kind == SessionPlaneKind.LAYER2_WORKSPACE) {
                        "Layer 2 requires default-foreground / display 0"
                    }
                    if (request.tool != "workspace.list") {
                        synchronized(resultCache) { resultCache.clear() }
                        recentActions.clear()
                    }
                    return@synchronized withPlane(layer2.command(context, request), plane)
                }
                layer2.requireMutation(context, request)
                if (layer2.engine.selectedId() != null) {
                    check(plane.kind == SessionPlaneKind.FOREGROUND || plane.kind == SessionPlaneKind.LAYER2_WORKSPACE) {
                        "MUTATE_LOCK: display-0 workspace owns input"
                    }
                }
                executeScoped(context, request, plane)
            } catch (error: Exception) { scopeFailure(request, error) }
        }
        return executeScoped(context, request)
    }

    private fun executeScoped(context: Context, request: PhoneToolRequest, plane: SessionPlane? = null): PhoneToolResult {
        // Validate before cache lookup AND before observing the human display.
        val resolved = try { plane ?: SessionContract.classify(request.params) }
        catch (error: IllegalArgumentException) { return scopeFailure(request, error) }
        val scope = com.cyclone.mobile.runtime.session.ExecutionContext(resolved.sessionId, resolved.displayId)
        if (scope.sessionId != "default-foreground") {
            return synchronized(mutationLock) { withPlane(executeWorkspace(context, request, scope), resolved) }
        }
        if (scope.displayId != 0) return scopeFailure(request, IllegalArgumentException("Display/session mismatch"))
        cached(cacheKey(request))?.let { return withPlane(it, resolved) }
        val result = if (request.tool in mutatingTools) {
            synchronized(mutationLock) {
                cached(cacheKey(request)) ?: executeInternal(context, request, mutating = true)
            }
        } else {
            executeInternal(context, request, mutating = false)
        }
        return withPlane(result, resolved)
    }

    private fun withPlane(result: PhoneToolResult, plane: SessionPlane): PhoneToolResult {
        val payload = result.payload
        if (!result.ok || payload !is JSONObject) return result
        if (payload.optJSONObject("plane") != null) return result
        if (result.tool != "phone.observe" && !result.tool.startsWith("workspace.") && result.tool != "phone.workspace_switch") {
            return result
        }
        return result.copy(payload = SessionContract.attach(payload, plane))
    }

    private fun scopeFailure(request: PhoneToolRequest, error: Exception): PhoneToolResult {
        val now = System.currentTimeMillis()
        return PhoneToolResult(request.commandId, request.tool, false, now, now,
            error = PhoneToolError(PhoneToolErrorCode.CAPABILITY_UNAVAILABLE, error.message.orEmpty()))
    }

    private fun executeWorkspace(context: Context, request: PhoneToolRequest,
        scope: com.cyclone.mobile.runtime.session.ExecutionContext): PhoneToolResult {
        val started = System.currentTimeMillis()
        return try {
            val runtime = com.cyclone.mobile.runtime.background.WorkspaceRuntime
            val session = runtime.requireScope(scope)
            val p = request.params
            val service = CycloneAccessibilityService.instance ?: error("ACCESSIBILITY_NOT_CONNECTED")
            if (request.tool !in mutatingTools) {
                val payload: Any = when (request.tool) {
                    "phone.observe" -> runtime.observe(scope).toJson().put("sessionId", scope.sessionId).put("displayId", scope.displayId)
                    "phone.get_current_app" -> JSONObject().put("package", session.targetPackage).put("sessionId", scope.sessionId).put("displayId", scope.displayId)
                    "phone.screenshot" -> {
                        val artifact = com.cyclone.mobile.ai.vision.live.LiveVisionRuntime.capture(service.cacheDir, sessionId = scope.sessionId)
                            ?: error("FRAME_STREAM_STALLED: no fresh frame from the requested display")
                        artifact.toJson().apply {
                            if (p.optBoolean("includeBase64")) put("pngBase64", Base64.encodeToString(artifact.file.readBytes(), Base64.NO_WRAP))
                        }
                    }
                    "phone.find" -> JSONArray(SelectorEngine.resolve(runtime.observe(scope), requireSelector(p), 20).map { it.toJson() })
                    else -> error("UNSUPPORTED: this workspace capability is unavailable")
                }
                return PhoneToolResult(request.commandId, request.tool, true, started, System.currentTimeMillis(), payload = payload)
            }
            val observation = GatewayObservationStore.current(scope.sessionId) ?: error("STALE_OBSERVATION")
            check(p.optString("observationId") == observation.id) { "STALE_OBSERVATION: observe this workspace again" }
            val generation = p.optLong("executionGeneration", -1)
            check(generation == observation.payload.optLong("executionGeneration", -2)) { "STALE_SESSION" }
            val snapshot = runtime.observe(scope)
            check(snapshot.fingerprint == observation.payload.optString("accessibilityFingerprint")) { "STALE_OBSERVATION" }
            val policy = com.cyclone.mobile.ai.CycloneAiAccessPolicy.evaluate(
                com.cyclone.mobile.ai.CycloneAiAccessProfileStore.read(context), request.tool, p)
            if (!policy.allowed) {
                runtime.pause(scope.sessionId, com.cyclone.mobile.runtime.background.WorkspaceState.BACKGROUND_NEEDS_HANDOFF)
                error("POLICY_DENIED: ${policy.safeMessage}")
            }
            val selector = p.optJSONObject("selector")?.let(ElementSelector::fromJson)
            val chosen = selector?.let { SelectorEngine.resolve(snapshot, it, 1).firstOrNull()?.node }
            fun guardedPoint(): UiNodeSnapshot {
                check(selector == null || chosen != null) { "STALE_OBSERVATION: semantic target is no longer present" }
                val node = chosen ?: snapshot.nodes.filter {
                    val x = p.optDouble("x"); val y = p.optDouble("y")
                    x >= it.bounds.left && x < it.bounds.right && y >= it.bounds.top && y < it.bounds.bottom && it.clickable
                }.minByOrNull { it.bounds.width * it.bounds.height } ?: error("UNSUPPORTED: a grounded control is required")
                val gate = com.cyclone.mobile.policy.GateClassifier.classify(request.tool,
                    com.cyclone.mobile.ui.overlay.ClickGateIntercept.labelsFor(node, node, selector))
                if (gate != null && !runtime.consumeConfirmation(scope.sessionId, request.tool, node.id, snapshot.fingerprint, gate.jsonKey)) {
                    runtime.requestConfirmation(scope.sessionId, request.tool, node.id, snapshot.fingerprint, gate.jsonKey)
                    runtime.pause(scope.sessionId, com.cyclone.mobile.runtime.background.WorkspaceState.BACKGROUND_NEEDS_HANDOFF)
                    error("POLICY_DENIED: human review is required")
                }
                return node
            }
            val commands = com.cyclone.mobile.runtime.background.WorkspaceCommands
            when (request.tool) {
                "phone.click", "phone.tap", "phone.long_press" -> {
                    val node = guardedPoint()
                    val x = node.bounds.centerX; val y = node.bounds.centerY
                    if (request.tool == "phone.long_press") runtime.input(scope, generation, commands.SWIPE, floatArrayOf(x, y, x, y, 650f))
                    else runtime.input(scope, generation, commands.TAP, floatArrayOf(x, y))
                }
                "phone.scroll" -> {
                    val node = chosen?.takeIf { it.scrollable } ?: snapshot.nodes.firstOrNull { it.scrollable }
                        ?: error("UNSUPPORTED: no scrollable control")
                    val x = node.bounds.centerX
                    val top = node.bounds.top + node.bounds.height * 0.25f
                    val bottom = node.bounds.top + node.bounds.height * 0.75f
                    val backwards = p.optString("direction") == "backward"
                    runtime.input(scope, generation, commands.SWIPE, floatArrayOf(x, if (backwards) top else bottom, x, if (backwards) bottom else top, 350f))
                }
                "phone.swipe" -> {
                    val x1 = p.optDouble("x1").toFloat(); val y1 = p.optDouble("y1").toFloat()
                    val x2 = p.optDouble("x2").toFloat(); val y2 = p.optDouble("y2").toFloat()
                    check(kotlin.math.abs(y2 - y1) > kotlin.math.abs(x2 - x1) && snapshot.nodes.any {
                        it.scrollable && it.bounds.contains(x1.toInt(), y1.toInt()) && it.bounds.contains(x2.toInt(), y2.toInt())
                    }) { "UNSUPPORTED: workspace swipes require a vertical scrollable target" }
                    runtime.input(scope, generation, commands.SWIPE, floatArrayOf(x1, y1, x2, y2, p.optLong("durationMs", 350).toFloat()))
                }
                "phone.back" -> runtime.input(scope, generation, commands.BACK)
                "phone.type", "phone.replace_text" -> {
                    val node = chosen?.takeIf { it.editable && it.text.isBlank() } ?: error("UNSUPPORTED: background typing currently requires an empty editable control")
                    guardedPoint()
                    val value = PhoneTypeEngine.typedValue(p).orEmpty()
                    commands.input(scope.displayId, commands.TEXT, floatArrayOf(), value)
                    runtime.input(scope, generation, commands.TAP, floatArrayOf(node.bounds.centerX, node.bounds.centerY))
                    runtime.input(scope, generation, commands.TEXT, text = value)
                }
                "phone.open_app" -> check(p.optString("package") == session.targetPackage) { "UNSUPPORTED: open another workspace for a different app" }
                else -> error("UNSUPPORTED: this operation cannot safely target a workspace")
            }
            GatewayObservationStore.clear(scope.sessionId)
            val settleGeneration = DeviceState.uiGeneration()
            val settle = FastPathLoop.settle(
                beforeFingerprint = snapshot.fingerprint,
                sleepMs = { ms -> DeviceState.awaitUiEventAfter(settleGeneration, ms) },
                observeFingerprint = { runtime.observe(scope).fingerprint },
            )
            val after = runtime.observe(scope)
            PhoneToolResult(request.commandId, request.tool, true, started, System.currentTimeMillis(),
                beforeFingerprint = snapshot.fingerprint,
                afterFingerprint = settle.afterFingerprint ?: after.fingerprint,
                payload = JSONObject()
                    .put("performed", true)
                    .put("verified", settle.verified)
                    .put("screenChanged", settle.changed ?: JSONObject.NULL)
                    .put("fastPath", settle.toJson())
                    .put("sessionId", scope.sessionId)
                    .put("displayId", scope.displayId))
        } catch (error: Exception) {
            if (request.tool in mutatingTools) runCatching { GatewayObservationStore.clear(scope.sessionId) }
            PhoneToolResult(request.commandId, request.tool, false, started, System.currentTimeMillis(),
                error = PhoneToolError(if (error.message?.contains("POLICY_DENIED") == true) PhoneToolErrorCode.POLICY_DENIED else PhoneToolErrorCode.CAPABILITY_UNAVAILABLE,
                    error.message?.take(240) ?: "Workspace operation failed"))
        }
    }

    private fun executeInternal(context: Context, request: PhoneToolRequest, mutating: Boolean): PhoneToolResult {
        val started = System.currentTimeMillis()
        val service = CycloneAccessibilityService.instance
        // Reuse the authoritative current gateway frame whenever possible. The old executor rebuilt
        // the Accessibility tree before every command, even phone.observe itself.
        val before = if (mutating) service?.observe(markFresh = false)?.fingerprint else null

        if (mutating && DeviceState.controller != DeviceState.Controller.AGENT) {
            return finish(request, started, before, before, error = PhoneToolError(PhoneToolErrorCode.HUMAN_HAS_CONTROL, "Human currently owns device input"))
        }
        if (mutating && DeviceState.requireFreshObservation) {
            return finish(request, started, before, before, error = PhoneToolError(PhoneToolErrorCode.FRESH_OBSERVATION_REQUIRED, "Run phone.observe after returning control before issuing actions"))
        }
        val requestedObservation = request.params.optString("observationId").ifBlank {
            request.params.optString("currentObservationId") }
        if (mutating && requestedObservation.isNotBlank()) {
            val observation = GatewayObservationStore.current()
            if (!com.cyclone.mobile.fastpath.MutationGrounding.matches(requestedObservation, observation?.id,
                    observation?.payload?.optString("accessibilityFingerprint"), before,
                    "default-foreground", 0, observation?.execution?.sessionId, observation?.execution?.displayId)) {
                return finish(request, started, before, before, error = PhoneToolError(PhoneToolErrorCode.STALE_ELEMENT,
                    "STALE_OBSERVATION: the screen or observation changed. Observe again before choosing another target."))
            }
        }
        if (mutating && isDuplicateAction(request)) {
            return finish(request, started, before, before, error = PhoneToolError(PhoneToolErrorCode.DUPLICATE_ACTION, "Duplicate action suppressed"))
        }

        val outcome = runCatching { dispatch(context, request, service, before) }
            .getOrElse { err ->
                if (err is PhoneToolException) Outcome(error = err.error)
                else if (err is EmptySelectorException) Outcome(error = PhoneToolError(PhoneToolErrorCode.INVALID_REQUEST, err.message ?: "empty selector"))
                else if (err is GateBlockedException) Outcome(error = PhoneToolError(PhoneToolErrorCode.POLICY_DENIED, err.message ?: "GATE requires confirmation"))
                else errorResult(PhoneToolErrorCode.INTERNAL_ERROR, err.message ?: err.javaClass.simpleName)
            }
        if (mutating) com.cyclone.mobile.ai.vision.live.LiveVisionRuntime.mutationFinished()
        val after = when {
            !mutating -> null
            outcome.afterFingerprint != null -> outcome.afterFingerprint
            else -> CycloneAccessibilityService.instance?.observe(markFresh = false)?.fingerprint
        }
        return finish(request, started, before, after, outcome.payload, outcome.error, outcome.attempts)
    }

    private fun currentFingerprint(service: CycloneAccessibilityService?): String? {
        val cached = GatewayObservationStore.current()
            ?.payload
            ?.optString("accessibilityFingerprint")
            ?.takeIf(String::isNotBlank)
        return cached ?: service?.observe(markFresh = false)?.fingerprint
    }

    private fun cacheKey(request: PhoneToolRequest): String = listOf(request.commandId, request.tool,
        request.params.optString("sessionId", "default-foreground"), request.params.optInt("displayId", 0),
        request.params.optString("workspaceId"), request.params.optLong("workspaceGeneration", -1),
        DeviceState.controllerEpoch()).joinToString("|")

    private fun cached(commandId: String): PhoneToolResult? = synchronized(resultCache) { resultCache[commandId] }

    private data class Outcome(
        val payload: Any? = null,
        val error: PhoneToolError? = null,
        val attempts: Int = 1,
        val afterFingerprint: String? = null,
    )

    private fun requireSelector(params: JSONObject): ElementSelector {
        val selector = ElementSelector.fromJson(params.optJSONObject("selector") ?: params)
        if (selector.isEmpty()) {
            throw PhoneToolException(PhoneToolError(PhoneToolErrorCode.INVALID_REQUEST, "empty selector"))
        }
        return selector
    }

    private fun dispatch(context: Context, request: PhoneToolRequest, service: CycloneAccessibilityService?, before: String?): Outcome {
        val p = request.params
        return when (request.tool) {
            "phone.observe" -> {
                val s = service ?: return errorResult(PhoneToolErrorCode.ACCESSIBILITY_NOT_CONNECTED, "Accessibility service is not connected")
                Outcome(s.observe(markFresh = true).toJson())
            }
            "phone.capabilities" -> Outcome(CapabilityRegistry.toJson(context))
            "phone.get_current_app" -> Outcome(JSONObject()
                .put("package", DeviceState.currentPackage ?: JSONObject.NULL)
                .put("class", DeviceState.currentClassName ?: JSONObject.NULL)
                .put("controller", DeviceState.controller.name.lowercase()))
            "phone.find" -> {
                val s = service ?: return errorResult(PhoneToolErrorCode.ACCESSIBILITY_NOT_CONNECTED, "Accessibility service is not connected")
                val selector = ElementSelector.fromJson(p.optJSONObject("selector") ?: p)
                val limit = p.optInt("limit", 20).coerceIn(1, 100)
                val matches = s.find(selector, limit)
                Outcome(JSONArray().also { array -> matches.forEach { array.put(it.toJson()) } })
            }
            "phone.screenshot" -> screenshot(service, p)
            "phone.click" -> actionWithConfirmation(service, request, before) {
                val selector = requireSelector(p)
                service?.click(selector) == true
            }
            "phone.long_press" -> actionWithConfirmation(service, request, before) {
                val selector = requireSelector(p)
                service?.longPress(selector, p.optLong("durationMs", 650L)) == true
            }
            "phone.tap" -> actionWithConfirmation(service, request, before) {
                service?.tap(p.optDouble("x").toFloat(), p.optDouble("y").toFloat()) == true
            }
            "phone.type", "phone.replace_text" -> typeEditable(service, request)
            "phone.scroll" -> actionWithConfirmation(service, request, before) {
                val selector = p.optJSONObject("selector")?.let(ElementSelector::fromJson)
                val direction = p.optString("direction", "forward")
                service?.scroll(selector, direction != "backward") == true
            }
            "phone.swipe" -> actionWithConfirmation(service, request, before) {
                service?.swipe(
                    p.optDouble("x1").toFloat(), p.optDouble("y1").toFloat(),
                    p.optDouble("x2").toFloat(), p.optDouble("y2").toFloat(),
                    p.optLong("durationMs", 350L),
                ) == true
            }
            "phone.back" -> actionWithConfirmation(service, request, before) { service?.goBack() == true }
            "phone.home" -> actionWithConfirmation(service, request, before) { service?.goHome() == true }
            "phone.open_app" -> {
                val packageName = p.optString("package")
                if (packageName.isBlank()) return errorResult(PhoneToolErrorCode.INVALID_REQUEST, "package is required")
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                    ?: return errorResult(PhoneToolErrorCode.APP_NOT_FOUND, "No launchable app for $packageName")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (packageName == "com.android.settings") {
                    intent.action = Intent.ACTION_MAIN
                    intent.addCategory(Intent.CATEGORY_LAUNCHER)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                val eventGeneration = DeviceState.uiGeneration()
                context.startActivity(intent)
                launchedOutcome(service, before, p, eventGeneration, JSONObject().put("package", packageName).put("launched", true))
            }
            "phone.get_notifications" -> Outcome(notificationJson())
            "phone.open_notification" -> {
                val generation = DeviceState.uiGeneration()
                val opened = openNotification(p.optString("key").takeIf { it.isNotBlank() })
                if (opened.error != null) opened else launchedOutcome(service, before, p, generation, JSONObject().put("opened", true))
            }
            "phone.get_clipboard" -> {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                Outcome(JSONObject().put("text", text))
            }
            "phone.set_clipboard" -> {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                    ?: return errorResult(PhoneToolErrorCode.CAPABILITY_UNAVAILABLE, "Clipboard service unavailable")
                clipboard.setPrimaryClip(ClipData.newPlainText("Cyclone", p.optString("text")))
                Outcome(JSONObject().put("updated", true))
            }
            "phone.share" -> {
                val text = p.optString("text")
                if (text.isBlank()) return errorResult(PhoneToolErrorCode.INVALID_REQUEST, "text is required")
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = p.optString("mimeType", "text/plain")
                    putExtra(Intent.EXTRA_TEXT, text)
                    p.optString("package").takeIf { it.isNotBlank() }?.let(::setPackage)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val generation = DeviceState.uiGeneration()
                context.startActivity(intent)
                launchedOutcome(service, before, p, generation, JSONObject().put("started", true))
            }
            "phone.launch_intent" -> {
                val uri = p.optString("uri")
                if (uri.isBlank()) return errorResult(PhoneToolErrorCode.INVALID_REQUEST, "uri is required")
                val parsed = Uri.parse(uri)
                if (parsed.scheme !in setOf("http", "https", "geo", "mailto", "tel", "sms", "market")) {
                    return errorResult(PhoneToolErrorCode.SECURITY_RESTRICTION, "URI scheme is not allowed")
                }
                val intent = Intent(Intent.ACTION_VIEW, parsed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                p.optString("package").takeIf { it.isNotBlank() }?.let(intent::setPackage)
                val eventGeneration = DeviceState.uiGeneration()
                context.startActivity(intent)
                launchedOutcome(service, before, p, eventGeneration, JSONObject().put("uri", uri).put("started", true))
            }
            "phone.wait_for" -> waitFor(service, p, assertOnly = false)
            "phone.assert" -> waitFor(service, p, assertOnly = true)
            else -> errorResult(PhoneToolErrorCode.UNKNOWN_TOOL, "Unknown tool ${request.tool}")
        }
    }

    private fun typeEditable(service: CycloneAccessibilityService?, request: PhoneToolRequest): Outcome {
        if (service == null) {
            return errorResult(PhoneToolErrorCode.ACCESSIBILITY_NOT_CONNECTED, "Accessibility service is not connected")
        }
        val epoch = DeviceState.controllerEpoch()
        if (DeviceState.controller != DeviceState.Controller.AGENT || epoch != DeviceState.controllerEpoch()) {
            return errorResult(PhoneToolErrorCode.HUMAN_HAS_CONTROL, "Controller changed while action was queued")
        }
        val snapshot = service.observe(markFresh = false)
        val observation = GatewayObservationStore.current()
        val catalog = PhoneTypeEngine.catalog(
            observationId = observation?.id,
            evidenceElements = observation?.elements?.values?.map { element ->
                PhoneTypeEngine.ObservationElementInput(element.id, element.source, element.role, element.evidence)
            } ?: emptyList(),
            snapshot = snapshot,
        )
        return when (val decision = PhoneTypeEngine.decide(request.params, catalog)) {
            is PhoneTypeEngine.Decision.Reject -> errorResult(decision.deny.code, decision.deny.message)
            is PhoneTypeEngine.Decision.Execute -> {
                val value = PhoneTypeEngine.typedValue(request.params).orEmpty()
                val live = service.typeEditable(decision.plan, value)
                if (!live.ok) {
                    Outcome(error = live.error ?: PhoneToolError(PhoneToolErrorCode.ACTION_FAILED, "Type failed"))
                } else {
                    Outcome(payload = live.toPayload())
                }
            }
        }
    }

    private fun actionWithConfirmation(
        service: CycloneAccessibilityService?,
        request: PhoneToolRequest,
        before: String?,
        action: () -> Boolean,
    ): Outcome {
        if (service == null) return errorResult(PhoneToolErrorCode.ACCESSIBILITY_NOT_CONNECTED, "Accessibility service is not connected")
        val epoch = DeviceState.controllerEpoch()
        // Retries apply only when Android rejected the action. Unchanged UI after a performed
        // click must never dispatch a second click channel (Fast Path / ClosePaw soft-success).
        val retries = request.params.optInt("retries", 1).coerceIn(0, 3)
        var attempts = 0
        repeat(retries + 1) {
            attempts++
            if (DeviceState.controller != DeviceState.Controller.AGENT || epoch != DeviceState.controllerEpoch()) {
                return errorResult(PhoneToolErrorCode.HUMAN_HAS_CONTROL, "Controller changed while action was queued", attempts)
            }
            val eventGeneration = DeviceState.uiGeneration()
            if (action()) {
                val (afterSnapshot, settle) = settleAfterMutation(service, before, request.params, eventGeneration)
                val expected = request.params.optJSONObject("expect")
                if (expected != null && afterSnapshot == null) return Outcome(error = PhoneToolError(PhoneToolErrorCode.ASSERTION_FAILED, "Fresh after-state unavailable"), attempts = attempts)
                if (expected != null && afterSnapshot != null) {
                    val verification = evaluateCondition(afterSnapshot, expected)
                    if (!verification.first) {
                        return Outcome(
                            error = PhoneToolError(PhoneToolErrorCode.ASSERTION_FAILED, verification.second),
                            attempts = attempts,
                            afterFingerprint = afterSnapshot.fingerprint,
                        )
                    }
                }
                val payload = JSONObject()
                    .put("performed", true)
                    .put("screenChanged", settle.changed ?: JSONObject.NULL)
                    .put("verified", com.cyclone.mobile.fastpath.MutationGrounding.verifiedTransition(true, settle.changed, expected != null))
                    .put("expectationVerified", expected != null)
                    .put("postconditionVerified", expected != null)
                    .put("fastPath", settle.toJson())
                settle.warning?.let { payload.put("warning", it) }
                return Outcome(
                    payload = payload,
                    attempts = attempts,
                    afterFingerprint = settle.afterFingerprint ?: afterSnapshot?.fingerprint,
                )
            }
            if (attempts <= retries) DeviceState.awaitUiEventAfter(eventGeneration, 100L * attempts)
        }
        return errorResult(PhoneToolErrorCode.ACTION_FAILED, "Android rejected or could not perform the action", attempts)
    }

    private fun launchedOutcome(
        service: CycloneAccessibilityService?,
        before: String?,
        params: JSONObject,
        eventGeneration: Long,
        base: JSONObject,
    ): Outcome {
        if (service == null) return Outcome(base.put("verified", false).put("performed", true))
        val (after, settle) = settleAfterMutation(service, before, params, eventGeneration)
        val expectedPackage = params.optString("package")
        val packageVerified = expectedPackage.isNotBlank() && after?.packageName == expectedPackage
        base.put("performed", true)
            .put("verified", if (expectedPackage.isNotBlank()) packageVerified else settle.verified)
            .put("postconditionVerified", packageVerified)
            .put("screenChanged", settle.changed ?: JSONObject.NULL)
            .put("fastPath", settle.toJson())
        settle.warning?.let { base.put("warning", it) }
        return Outcome(payload = base, afterFingerprint = settle.afterFingerprint)
    }

    /**
     * Default Fast Path: settle 300ms + fingerprint, then +500/+1000 if Unchanged.
     * Explicit waitForChangeMs (including 0 for desktop live controls) keeps the legacy single wait.
     * The settle ladder only re-observes; it never re-clicks.
     */
    private fun settleAfterMutation(
        service: CycloneAccessibilityService,
        before: String?,
        params: JSONObject,
        eventGeneration: Long,
    ): Pair<UiSnapshot?, FastPathSettleResult> {
        if (params.has("waitForChangeMs") && !params.optBoolean("fastPath", false)) {
            val waitForChangeMs = params.optLong("waitForChangeMs", 900L).coerceIn(0L, 5_000L)
            if (waitForChangeMs > 0L) DeviceState.awaitUiEventAfter(eventGeneration, waitForChangeMs)
            val afterSnapshot = if (before != null || params.optJSONObject("expect") != null) {
                service.observe(markFresh = false)
            } else null
            val changed = if (before == null || afterSnapshot == null) null else afterSnapshot.fingerprint != before
            return afterSnapshot to FastPathSettleResult(
                changed = changed,
                verified = changed == true,
                observations = if (afterSnapshot == null) 0 else 1,
                elapsedMs = waitForChangeMs,
                warning = if (changed == false) FastPathTimings.UNCHANGED_WARNING else null,
                afterFingerprint = afterSnapshot?.fingerprint,
            )
        }
        var lastSnapshot: UiSnapshot? = null
        val settle = FastPathLoop.settle(
            beforeFingerprint = before,
            sleepMs = { ms -> DeviceState.awaitUiEventAfter(eventGeneration, ms) },
            observeFingerprint = {
                lastSnapshot = service.observe(markFresh = false)
                lastSnapshot?.fingerprint
            },
        )
        return lastSnapshot to settle
    }

    private fun screenshot(service: CycloneAccessibilityService?, params: JSONObject): Outcome {
        service ?: return errorResult(PhoneToolErrorCode.ACCESSIBILITY_NOT_CONNECTED, "Accessibility service is not connected")
        val crop = params.optJSONObject("crop")?.let {
            UiBounds(it.optInt("left"), it.optInt("top"), it.optInt("right"), it.optInt("bottom"))
        }
        com.cyclone.mobile.ai.vision.live.LiveVisionRuntime.capture(service.cacheDir, crop)?.let { artifact ->
            return Outcome(artifact.toJson().apply {
                if (params.optBoolean("includeBase64", false)) {
                    put("pngBase64", Base64.encodeToString(artifact.file.readBytes(), Base64.NO_WRAP))
                }
            })
        }
        val latch = CountDownLatch(1)
        var captured: Result<CycloneAccessibilityService.ScreenshotArtifact>? = null
        service.takeScreenshot(crop) { result -> captured = result; latch.countDown() }
        if (!latch.await(8, TimeUnit.SECONDS)) return errorResult(PhoneToolErrorCode.TIMEOUT, "Screenshot timed out")
        val artifact = captured?.getOrElse { return errorResult(PhoneToolErrorCode.ACTION_FAILED, it.message ?: "Screenshot failed") }
            ?: return errorResult(PhoneToolErrorCode.ACTION_FAILED, "Screenshot did not return a result")
        val json = artifact.toJson()
        if (params.optBoolean("includeBase64", false)) {
            json.put("pngBase64", Base64.encodeToString(artifact.file.readBytes(), Base64.NO_WRAP))
        }
        return Outcome(json)
    }

    private fun waitFor(service: CycloneAccessibilityService?, params: JSONObject, assertOnly: Boolean): Outcome {
        service ?: return errorResult(PhoneToolErrorCode.ACCESSIBILITY_NOT_CONNECTED, "Accessibility service is not connected")
        val condition = params.optJSONObject("condition") ?: params
        val timeout = if (assertOnly) 0L else params.optLong("timeoutMs", DEFAULT_TIMEOUT_MS).coerceIn(0L, MAX_TIMEOUT_MS)
        val heartbeat = params.optLong("pollMs", 250L).coerceIn(50L, 1_000L)
        val started = System.currentTimeMillis()
        var attempts = 0
        do {
            val generation = DeviceState.uiGeneration()
            attempts++
            val snapshot = service.observe(markFresh = false)
            val result = evaluateCondition(snapshot, condition)
            if (result.first) return Outcome(JSONObject().put("matched", true).put("snapshot", snapshot.toJson()), attempts = attempts)
            val elapsed = System.currentTimeMillis() - started
            if (assertOnly || elapsed >= timeout) {
                return errorResult(if (assertOnly) PhoneToolErrorCode.ASSERTION_FAILED else PhoneToolErrorCode.TIMEOUT, result.second, attempts)
            }
            DeviceState.awaitUiEventAfter(generation, minOf(heartbeat, timeout - elapsed))
        } while (true)
    }

    private fun evaluateCondition(snapshot: UiSnapshot, condition: JSONObject): Pair<Boolean, String> {
        return when (condition.optString("type", "selector_exists")) {
            "selector_exists" -> {
                val matches = SelectorEngine.resolve(snapshot, ElementSelector.fromJson(condition.optJSONObject("selector") ?: condition), 1)
                (matches.isNotEmpty()) to "Expected selector was not found"
            }
            "selector_absent" -> {
                val matches = SelectorEngine.resolve(snapshot, ElementSelector.fromJson(condition.optJSONObject("selector") ?: condition), 1)
                matches.isEmpty() to "Selector is still present"
            }
            "package_equals" -> (snapshot.packageName == condition.optString("package")) to "Current package ${snapshot.packageName} does not match ${condition.optString("package")}" 
            "text_contains" -> {
                val q = condition.optString("text").lowercase()
                snapshot.nodes.any { it.text.lowercase().contains(q) || it.contentDescription.lowercase().contains(q) } to "Text '$q' not found"
            }
            "fingerprint_changed" -> (snapshot.fingerprint != condition.optString("from")) to "Screen fingerprint has not changed"
            else -> false to "Unknown condition type ${condition.optString("type")}" 
        }
    }

    private fun notificationJson(): JSONArray = JSONArray().also { array ->
        DeviceState.notificationSnapshot().forEach { sbn ->
            val extras = sbn.notification.extras
            array.put(JSONObject()
                .put("key", sbn.key)
                .put("package", sbn.packageName)
                .put("postTime", sbn.postTime)
                .put("title", extras.getCharSequence("android.title")?.toString().orEmpty())
                .put("text", extras.getCharSequence("android.text")?.toString().orEmpty())
                .put("hasContentIntent", sbn.notification.contentIntent != null)
                .put("actions", JSONArray().also { actions ->
                    sbn.notification.actions.orEmpty().forEach { action -> actions.put(action.title?.toString().orEmpty()) }
                }))
        }
    }

    private fun openNotification(key: String?): Outcome {
        val sbn = DeviceState.notification(key) ?: return errorResult(PhoneToolErrorCode.NOTIFICATION_NOT_FOUND, "Notification not found")
        val intent = sbn.notification.contentIntent ?: return errorResult(PhoneToolErrorCode.ACTION_FAILED, "Notification has no content intent")
        return try {
            intent.send()
            Outcome(JSONObject().put("key", sbn.key).put("opened", true))
        } catch (e: PendingIntent.CanceledException) {
            errorResult(PhoneToolErrorCode.ACTION_FAILED, "Notification intent is no longer valid")
        }
    }

    private fun errorResult(code: PhoneToolErrorCode, message: String, attempts: Int = 1): Outcome =
        Outcome(error = PhoneToolError(code, message), attempts = attempts)

    @Synchronized
    private fun isDuplicateAction(request: PhoneToolRequest): Boolean {
        val now = System.currentTimeMillis()
        val signature = if (request.tool == "phone.type" || request.tool == "phone.replace_text") {
            PhoneTypeEngine.duplicateSignature(request.tool, request.params)
        } else {
            "${request.tool}|${request.params}"
        }
        val previous = recentActions[signature]
        recentActions[signature] = now
        recentActions.entries.removeIf { now - it.value > 5_000L }
        return previous != null && now - previous < DUPLICATE_WINDOW_MS
    }

    @Synchronized
    private fun finish(
        request: PhoneToolRequest,
        started: Long,
        before: String?,
        after: String?,
        payload: Any? = null,
        error: PhoneToolError? = null,
        attempts: Int = 1,
    ): PhoneToolResult {
        val result = PhoneToolResult(
            commandId = request.commandId,
            tool = request.tool,
            ok = error == null,
            startedAtMs = started,
            finishedAtMs = System.currentTimeMillis(),
            attempts = attempts,
            beforeFingerprint = before,
            afterFingerprint = after,
            payload = payload,
            error = error,
        )
        synchronized(resultCache) { resultCache[cacheKey(request)] = result }
        DeviceState.addAudit(DeviceState.CommandAuditRecord(
            commandId = request.commandId,
            tool = request.tool,
            startedAtMs = result.startedAtMs,
            finishedAtMs = result.finishedAtMs,
            ok = result.ok,
            beforeFingerprint = before,
            afterFingerprint = after,
            errorCode = error?.code?.name,
        ))
        if (error?.code == PhoneToolErrorCode.ACCESSIBILITY_NOT_CONNECTED) {
            SetupReminderState.request(
                SetupNeed.PHONE_CONTROL,
                "Enable Cyclone phone control before using screen-reading or UI-action features.",
            )
        }
        DeviceState.addLog("${request.tool} ${if (result.ok) "OK" else "FAIL:${error?.code}"}")
        return result
    }
}

private class PhoneToolException(val error: PhoneToolError) : RuntimeException(error.message)
