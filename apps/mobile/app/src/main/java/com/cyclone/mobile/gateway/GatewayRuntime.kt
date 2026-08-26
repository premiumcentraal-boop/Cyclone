package com.cyclone.mobile.gateway

import android.content.Context
import android.os.Build
import com.cyclone.mobile.BuildConfig
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.applearner.FollowMeLearnerRuntime
import com.cyclone.mobile.debug.PageDebugSandboxV293
import com.mobilerun.portal.diagnostics.CycloneProcessDiagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object GatewayRuntime {
    @Volatile private var appContext: Context? = null
    @Volatile private var server: GatewaySocketServer? = null
    @Volatile private var listenerError: String? = null
    @Volatile private var lastSafeError: String? = null
    @Volatile private var lastSafeErrorCode: String? = null
    @Volatile private var lastSafeErrorAtMs: Long? = null

    internal object PcSessionTracker {
        const val RECENT_WINDOW_MS = 45_000L
        @Volatile private var lastAuthenticatedAtMs: Long? = null

        fun noteAuthenticated(nowMs: Long = System.currentTimeMillis()) {
            lastAuthenticatedAtMs = nowMs
        }

        fun reset() {
            lastAuthenticatedAtMs = null
        }

        fun lastAuthenticatedAt(): Long? = lastAuthenticatedAtMs

        fun isRecent(nowMs: Long = System.currentTimeMillis()): Boolean {
            val last = lastAuthenticatedAtMs ?: return false
            val age = nowMs - last
            return age in 0..RECENT_WINDOW_MS
        }
    }

    @Synchronized
    fun startPairingBootstrap(context: Context) {
        appContext = context.applicationContext
        startLocked(context.applicationContext)
    }

    @Synchronized
    fun startIfEnabled(context: Context) {
        startPairingBootstrap(context)
    }

    /** V3.3 enablement creates no reusable token; trust/session credentials are protocol-owned. */
    @Synchronized
    fun enable(context: Context): String {
        appContext = context.applicationContext
        GatewaySessionStore.enableTrusted(context)
        startLocked(context.applicationContext)
        clearSafeError()
        return ""
    }

    @Synchronized
    fun disable(context: Context) {
        appContext = context.applicationContext
        server?.disconnectClients()
        GatewayV33TrustManager.disconnectSessions(context)
        GatewayObservationStore.clear()
        GatewaySessionStore.disable(context)
        listenerError = null
        clearSafeError()
        PcSessionTracker.reset()
        startLocked(context.applicationContext)
    }

    /** Explicit one-release legacy transition helper; normal V3.3 uses trust.rotate. */
    @Synchronized
    fun rotateToken(context: Context): String {
        appContext = context.applicationContext
        val token = GatewaySessionStore.rotate(context)
        server?.disconnectClients()
        GatewayV33TrustManager.disconnectSessions(context)
        reportSafeError(
            "LEGACY_CREDENTIAL_ROTATED",
            "Legacy transition credential rotated. V3.3 PCs should open a fresh trusted session.",
        )
        return token
    }

    @Synchronized
    fun disconnect() {
        server?.disconnectClients()
        appContext?.let(GatewayV33TrustManager::disconnectSessions)
        clearSafeError()
        PcSessionTracker.reset()
    }

    fun isEnabled(context: Context): Boolean = GatewaySessionStore.enabled(context)

    /** Kept only for compatibility callers. V3.3 UI must never expose this value. */
    fun tokenForUser(context: Context): String? = GatewaySessionStore.token(context)

    internal fun reportSafeError(message: String?) {
        reportSafeError("GATEWAY_DEGRADED", message)
    }

    internal fun reportSafeError(code: String?, message: String?) {
        lastSafeErrorCode = code?.take(80)
        lastSafeError = message?.take(240)
        lastSafeErrorAtMs = if (message.isNullOrBlank()) null else System.currentTimeMillis()
    }

    internal fun clearSafeError() {
        lastSafeError = null
        lastSafeErrorCode = null
        lastSafeErrorAtMs = null
    }

    fun status(context: Context): JSONObject {
        val current = GatewayObservationStore.current()
        val follow = FollowMeLearnerRuntime.progress()
        val socket = server
        val enabled = GatewaySessionStore.enabled(context)
        val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val activeClients = socket?.connectedClients() ?: 0
        val recentlyAuthenticated = PcSessionTracker.isRecent()
        val trust = GatewayV33TrustManager.status(context)
        val trustedSessionCount = trust.optInt("activeSessionCount", 0)
        val pcSessionKnown = trustedSessionCount > 0 || recentlyAuthenticated
        val bootstrapListening = socket?.isRunning() == true
        val listening = enabled && bootstrapListening
        val semanticState = when {
            !DeviceState.accessibilityConnected -> "UNAVAILABLE"
            current == null -> "DEGRADED"
            else -> "READY"
        }
        val authorityState = when {
            !enabled -> "DENIED"
            !GatewayActionAuthorityRegistry.isProductionAuthorityBound() -> "DEGRADED"
            !DeviceState.accessibilityConnected -> "DEGRADED"
            else -> "READY"
        }
        val state = when {
            !enabled -> "OFF"
            !listening || listenerError != null -> "ATTENTION_NEEDED"
            trust.optString("trustState") == "CONFIRMATION_REQUIRED" -> "WAITING_FOR_PC"
            !DeviceState.accessibilityConnected -> "ATTENTION_NEEDED"
            pcSessionKnown -> "CONNECTED"
            else -> "WAITING_FOR_PC"
        }
        return JSONObject()
            .put("protocolVersion", GatewayProtocol.VERSION)
            .put("trustProtocolVersion", GatewayTrustProtocolV33.VERSION)
            .put("supportedProtocolVersions", JSONArray(listOf(GatewayTrustProtocolV33.VERSION)))
            .put("appVersion", packageInfo?.versionName ?: BuildConfig.VERSION_NAME)
            .put("package", context.packageName)
            .put("gatewayState", state)
            .put("gatewayEnabled", enabled)
            .put("socketLifecycleState", when {
                listenerError != null -> "ERROR"
                bootstrapListening -> "LISTENING"
                else -> "STARTING"
            })
            .put("socketListening", listening)
            .put("pairingBootstrapListening", bootstrapListening)
            .put("pairingActive", GatewayDesktopPairingManager.active())
            .put("socketName", GatewayProtocol.SOCKET_NAME)
            .put("networkListener", false)
            .put("accessibilityConnected", DeviceState.accessibilityConnected)
            .put("semanticObservationState", semanticState)
            .put("actionAuthorityState", authorityState)
            .put("controllerOwner", DeviceState.controller.name)
            .put("teachingActive", follow.active)
            .put("currentPackage", DeviceState.currentPackage ?: JSONObject.NULL)
            .put("currentPageKey", current?.page?.pageKey ?: JSONObject.NULL)
            .put("currentObservationId", current?.id ?: JSONObject.NULL)
            .put("rootAppearsAvailable", rootAppearsAvailable())
            .put("rootShellExposed", false)
            .put("actionAuthorityBinding", GatewayActionAuthorityRegistry.bindingName())
            .put("productionActionAuthorityBound", GatewayActionAuthorityRegistry.isProductionAuthorityBound())
            .put("desktopClipboard", GatewayClipboardAdapter.capability(context))
            .put("trust", trust)
            .put("connectedSession", JSONObject()
                .put("connected", pcSessionKnown)
                .put("clientCount", activeClients)
                .put("trustedSessionCount", trustedSessionCount)
                .put("transport", if (pcSessionKnown || activeClients > 0) "adb-forwarded-localabstract" else JSONObject.NULL)
                .put("lastAuthenticatedAtMs", PcSessionTracker.lastAuthenticatedAt() ?: JSONObject.NULL)
                .put("pcIdentityDetectable", trust.optInt("trustedPcCount", 0) > 0))
            .put("capabilities", JSONObject()
                .put("operations", JSONArray(GatewayProtocol.operations.toList()))
                .put("phoneTools", JSONArray(GatewayV33ActionAdapter.allowedTools.toList()))
                .put("manualDesktopKinds", JSONArray(DesktopManualControlContract.allowedKinds.toList()))
                .put("fullSemanticControls", true)
                .put("pageDebugFunnel", true)
                .put("appGraphRetrieval", true)
                .put("adaptiveBrainRecall", true)
                .put("canonicalTeaching", true)
                .put("oneShotScreenshot", true)
                .put("liveVideoOwnedByAndroidBridge", false))
            .put("adbForward", "adb forward tcp:${GatewayProtocol.DEFAULT_FORWARD_PORT} localabstract:${GatewayProtocol.SOCKET_NAME}")
            .put("lastError", listenerError ?: JSONObject.NULL)
            .put("lastSafeError", lastSafeError ?: JSONObject.NULL)
            .put("lastSafeErrorCode", lastSafeErrorCode ?: JSONObject.NULL)
            .put("lastSafeErrorAtMs", lastSafeErrorAtMs ?: JSONObject.NULL)
    }

    private fun rootAppearsAvailable(): Boolean = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/data/adb/magisk",
    ).any { File(it).exists() } || Build.TAGS.orEmpty().contains("test-keys")

    @Synchronized
    private fun startLocked(context: Context) {
        if (server?.isRunning() == true) return
        try {
            server = GatewaySocketServer { line -> GatewayDispatcher.handle(context, line) }.also { it.start() }
            listenerError = null
        } catch (error: Exception) {
            server = null
            listenerError = (error.message ?: error.javaClass.simpleName).take(240)
            reportSafeError("SOCKET_START_FAILED", "Cyclone USB bridge socket could not start.")
        }
    }
}

internal object GatewayDispatcher {
    private val trustSessionBootstrap = setOf("trust.session.begin", "trust.session.complete")

    fun handle(context: Context, line: String): String {
        var id = ""
        return try {
            val request = GatewayProtocol.parse(line)
            id = request.id
            GatewayProtocol.requireKnownOperation(request.op, request.id)
            val unauthenticatedBootstrap = request.op in GatewayProtocol.unauthenticatedOperations
            val enabled = GatewaySessionStore.enabled(context)
            val auth = if (unauthenticatedBootstrap) null else GatewaySessionStore.resolveAuth(context, request.auth)

            when {
                request.op in trustSessionBootstrap && !enabled -> throw GatewayProtocolException(
                    "CAPABILITY_UNAVAILABLE",
                    "Cyclone AI Gateway is disabled on this phone. Complete visible trust or enable it in Cyclone AI.",
                    request.id,
                )
                !unauthenticatedBootstrap && !enabled -> throw GatewayProtocolException(
                    "CAPABILITY_UNAVAILABLE",
                    "PC Gateway is disabled",
                    request.id,
                )
                !unauthenticatedBootstrap && auth == null -> throw GatewayProtocolException(
                    "AUTH_REJECTED",
                    "Trusted session is invalid, expired or revoked",
                    request.id,
                )
                auth?.mode == GatewaySessionAuthMode.LEGACY_READ_ONLY && request.op !in GatewayProtocol.legacyReadOnlyOperations -> {
                    throw GatewayProtocolException(
                        "PROTOCOL_MISMATCH",
                        "V3.2 transition credentials are read-only under V3.3. Update PC Companion and complete Allow this PC trust.",
                        request.id,
                        JSONObject().put("requiredProtocolVersion", GatewayTrustProtocolV33.VERSION),
                    )
                }
                else -> {
                    if (auth != null) GatewayRuntime.PcSessionTracker.noteAuthenticated()
                    val result = GatewaySessionExecutionContext.withAuth(auth) {
                        dispatch(context, request)
                    }
                    GatewayRuntime.clearSafeError()
                    GatewayProtocol.success(id, result).toString()
                }
            }
        } catch (error: GatewayProtocolException) {
            GatewayRuntime.reportSafeError(error.code, error.message)
            GatewayProtocol.error(error.requestId.ifBlank { id }, error.code, error.message, error.details).toString()
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            CycloneProcessDiagnostics.recordNonFatal(context, "gateway.dispatch.boundary", error)
            GatewayRuntime.reportSafeError(
                "INTERNAL_ERROR",
                "Gateway operation failed safely. Open diagnostics or reconnect the USB session.",
            )
            GatewayProtocol.error(id, "INTERNAL_ERROR", "Gateway operation failed").toString()
        }
    }

    private fun dispatch(context: Context, request: GatewayRequest): Any = when (request.op) {
        "trust.negotiate" -> GatewayV33TrustManager.negotiate(context, request.args)
        "trust.begin" -> GatewayV33TrustManager.beginTrust(context, request.args)
        "trust.complete" -> GatewayV33TrustManager.completeTrust(context, request.args).also {
            GatewaySessionStore.enableTrusted(context)
        }
        "trust.session.begin" -> GatewayV33TrustManager.beginSession(context, request.args)
        "trust.session.complete" -> GatewayV33TrustManager.completeSession(context, request.args)
        "trust.rotate" -> GatewayV33TrustManager.rotate(context, request.auth, request.args)
        "trust.revoke" -> GatewayV33TrustManager.revoke(context, request.auth, request.args)
        "pair.begin" -> GatewayDesktopPairingManager.begin(context, request.args)
        "pair.complete" -> GatewayDesktopPairingManager.complete(context, request.args)
        "pair.qr.complete" -> GatewayDesktopPairingManager.completeQr(context, request.args)
        "pair.revoke" -> GatewayDesktopPairingManager.revoke(context)
        "manual.execute" -> GatewayV33ManualDesktopAdapter.execute(context, request.id, request.args)
        "clipboard.get" -> GatewayClipboardAdapter.capability(context)
        "clipboard.set" -> GatewayV33ClipboardAdapter.set(context, request.id, request.args)
        "bridge.status" -> GatewayRuntime.status(context)
        "observe.semantic" -> GatewayObservationAdapter.capture(context, request.args).payload
        "observe.page_debug" -> GatewayPageDebugAdapter.capture(context, request.args)
        "capture.screenshot" -> GatewayCaptureAdapter.capture(context, request.args)
        "ui.search" -> {
            val observation = GatewayObservationStore.current() ?: GatewayObservationAdapter.capture(context, request.args)
            JSONObject()
                .put("observationId", observation.id)
                .put("elementIdScope", "observation-local")
                .put("query", request.args.optString("query"))
                .put(
                    "candidates",
                    GatewayObservationAdapter.search(
                        observation,
                        request.args.optString("query"),
                        request.args.optInt("limit", 30),
                    ),
                )
        }
        "ui.element" -> {
            val observation = GatewayObservationStore.current()
                ?: throw GatewayProtocolException("STALE_OBSERVATION", "Call observe.semantic before ui.element")
            val requestedObservationId = request.args.optString("observationId").trim()
            if (requestedObservationId.isNotBlank() && requestedObservationId != observation.id) {
                throw GatewayProtocolException("STALE_OBSERVATION", "Element belongs to an older observation; observe again")
            }
            val elementId = request.args.optString("elementId", request.args.optString("id"))
            if (elementId.isBlank()) throw GatewayProtocolException("PROTOCOL_MISMATCH", "elementId is required")
            GatewayObservationAdapter.element(observation, elementId)
        }
        "app_graph.get" -> GatewayAppGraphAdapter.query(context, request.args)
        "brain.recall" -> GatewayBrainAdapter.recall(context, request.args)
        "action.execute" -> GatewayV33ActionAdapter.execute(context, request.id, request.args)
        "teach.start" -> GatewayTeachingAdapter.start(context)
        "teach.status" -> GatewayTeachingAdapter.status(context)
        "teach.stop" -> GatewayTeachingAdapter.stop(context)
        "debug.snapshot" -> debugSnapshot(context)
        else -> throw GatewayProtocolException(
            "PROTOCOL_MISMATCH",
            "Unsupported gateway operation: ${request.op}",
            request.id,
        )
    }

    private fun debugSnapshot(context: Context): JSONObject {
        val observation = GatewayObservationStore.current()
        val latestPageDebug = PageDebugSandboxV293.latest(context)
        return JSONObject()
            .put("status", GatewayRuntime.status(context))
            .put("latestObservation", observation?.payload ?: JSONObject.NULL)
            .put(
                "latestPageDebug",
                latestPageDebug?.let { GatewayPageDebugAdapter.safeExport(it) } ?: JSONObject.NULL,
            )
            .put("teaching", GatewayTeachingAdapter.status(context))
            .put("recentActions", JSONArray().also { out ->
                DeviceState.commandAudit.take(30).forEach { audit ->
                    out.put(
                        JSONObject()
                            .put("commandId", audit.commandId)
                            .put("tool", audit.tool)
                            .put("startedAtMs", audit.startedAtMs)
                            .put("finishedAtMs", audit.finishedAtMs)
                            .put("ok", audit.ok)
                            .put("beforeFingerprint", audit.beforeFingerprint ?: JSONObject.NULL)
                            .put("afterFingerprint", audit.afterFingerprint ?: JSONObject.NULL)
                            .put("errorCode", audit.errorCode ?: JSONObject.NULL),
                    )
                }
            })
            .put(
                "privacy",
                "No session token, private key, pairing code, API key, password, OTP, clipboard content or typed phone.type value is included.",
            )
    }
}
