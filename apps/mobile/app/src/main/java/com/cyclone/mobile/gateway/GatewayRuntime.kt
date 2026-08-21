package com.cyclone.mobile.gateway

import android.content.Context
import android.os.Build
import com.cyclone.mobile.BuildConfig
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.applearner.FollowMeLearnerRuntime
import com.cyclone.mobile.debug.PageDebugSandboxV293
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object GatewayRuntime {
    @Volatile private var appContext: Context? = null
    @Volatile private var server: GatewaySocketServer? = null
    @Volatile private var listenerError: String? = null
    @Volatile private var lastSafeError: String? = null

    /**
     * Desktop V1 keeps the localabstract socket available as an ADB-only pairing bootstrap.
     * When the full gateway is disabled, only pair.begin/pair.complete can pass the dispatcher.
     */
    @Synchronized
    fun startPairingBootstrap(context: Context) {
        appContext = context.applicationContext
        startLocked(context.applicationContext)
        if (GatewaySessionStore.enabled(context) && GatewaySessionStore.token(context) == null) {
            GatewaySessionStore.rotate(context)
        }
    }

    @Synchronized
    fun startIfEnabled(context: Context) {
        startPairingBootstrap(context)
    }

    @Synchronized
    fun enable(context: Context): String {
        appContext = context.applicationContext
        val token = GatewaySessionStore.enable(context)
        startLocked(context.applicationContext)
        lastSafeError = null
        return token
    }

    @Synchronized
    fun disable(context: Context) {
        appContext = context.applicationContext
        server?.disconnectClients()
        GatewayObservationStore.clear()
        GatewaySessionStore.disable(context)
        listenerError = null
        lastSafeError = null
        // Keep the ADB-only listener available for zero-authority Desktop pairing bootstrap.
        startLocked(context.applicationContext)
    }

    @Synchronized
    fun rotateToken(context: Context): String {
        appContext = context.applicationContext
        val token = GatewaySessionStore.rotate(context)
        server?.disconnectClients()
        lastSafeError = "Session token rotated. Reconnect the PC with the new token."
        return token
    }

    @Synchronized
    fun disconnect() {
        server?.disconnectClients()
        lastSafeError = null
    }

    fun isEnabled(context: Context): Boolean = GatewaySessionStore.enabled(context)
    fun tokenForUser(context: Context): String? = GatewaySessionStore.token(context)

    internal fun reportSafeError(message: String?) {
        lastSafeError = message?.take(240)
    }

    internal fun clearSafeError() {
        lastSafeError = null
    }

    fun status(context: Context): JSONObject {
        val current = GatewayObservationStore.current()
        val follow = FollowMeLearnerRuntime.progress()
        val socket = server
        val enabled = GatewaySessionStore.enabled(context)
        val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val connected = socket?.connectedClients() ?: 0
        val bootstrapListening = socket?.isRunning() == true
        val listening = enabled && bootstrapListening
        val state = when {
            !enabled -> "OFF"
            !listening || !DeviceState.accessibilityConnected || listenerError != null -> "ATTENTION_NEEDED"
            connected > 0 -> "CONNECTED"
            else -> "WAITING_FOR_PC"
        }
        return JSONObject()
            .put("protocolVersion", GatewayProtocol.VERSION)
            .put("appVersion", packageInfo?.versionName ?: BuildConfig.VERSION_NAME)
            .put("package", context.packageName)
            .put("gatewayState", state)
            .put("gatewayEnabled", enabled)
            .put("socketListening", listening)
            .put("pairingBootstrapListening", bootstrapListening)
            .put("pairingActive", GatewayDesktopPairingManager.active())
            .put("socketName", GatewayProtocol.SOCKET_NAME)
            .put("networkListener", false)
            .put("accessibilityConnected", DeviceState.accessibilityConnected)
            .put("controllerOwner", DeviceState.controller.name)
            .put("teachingActive", follow.active)
            .put("currentPackage", DeviceState.currentPackage ?: JSONObject.NULL)
            .put("currentPageKey", current?.page?.pageKey ?: JSONObject.NULL)
            .put("rootAppearsAvailable", rootAppearsAvailable())
            .put("rootShellExposed", false)
            .put("actionAuthorityBinding", GatewayActionAuthorityRegistry.bindingName())
            .put("productionActionAuthorityBound", GatewayActionAuthorityRegistry.isProductionAuthorityBound())
            .put("desktopClipboard", GatewayClipboardAdapter.capability(context))
            .put("connectedSession", JSONObject()
                .put("connected", connected > 0)
                .put("clientCount", connected)
                .put("transport", if (connected > 0) "adb-forwarded-localabstract" else JSONObject.NULL)
                .put("lastConnectedAt", socket?.lastClientConnectedAt ?: JSONObject.NULL)
                .put("pcIdentityDetectable", false))
            .put("capabilities", JSONObject()
                .put("operations", JSONArray(GatewayProtocol.operations.toList()))
                .put("phoneTools", JSONArray(GatewayActionAdapter.allowedTools.toList()))
                .put("manualDesktopKinds", JSONArray(DesktopManualControlContract.allowedKinds.toList()))
                .put("fullSemanticControls", true)
                .put("pageDebugFunnel", true)
                .put("appGraphRetrieval", true)
                .put("adaptiveBrainRecall", true)
                .put("canonicalTeaching", true))
            .put("adbForward", "adb forward tcp:${GatewayProtocol.DEFAULT_FORWARD_PORT} localabstract:${GatewayProtocol.SOCKET_NAME}")
            .put("lastError", listenerError ?: JSONObject.NULL)
            .put("lastSafeError", lastSafeError ?: JSONObject.NULL)
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
        }
    }
}

internal object GatewayDispatcher {
    fun handle(context: Context, line: String): String {
        var id = ""
        return try {
            val request = GatewayProtocol.parse(line)
            id = request.id
            GatewayProtocol.requireKnownOperation(request.op, request.id)
            val pairingBootstrap = request.op in GatewayProtocol.unauthenticatedOperations
            if (!pairingBootstrap && !GatewaySessionStore.enabled(context)) {
                GatewayRuntime.reportSafeError("Full PC + Codex Gateway is off on this phone.")
                GatewayProtocol.error(id, "CAPABILITY_UNAVAILABLE", "PC Gateway is disabled").toString()
            } else if (!pairingBootstrap && !GatewaySessionStore.authenticate(context, request.auth)) {
                GatewayRuntime.reportSafeError("PC authentication failed. Pair again or use the current session token.")
                GatewayProtocol.error(id, "AUTH_REJECTED", "Session token is invalid or has been rotated").toString()
            } else {
                val result = dispatch(context, request)
                GatewayRuntime.clearSafeError()
                GatewayProtocol.success(id, result).toString()
            }
        } catch (error: GatewayProtocolException) {
            GatewayRuntime.reportSafeError(error.message)
            GatewayProtocol.error(error.requestId.ifBlank { id }, error.code, error.message).toString()
        } catch (error: Exception) {
            GatewayRuntime.reportSafeError("Gateway operation failed safely. Open diagnostics or reconnect the USB session.")
            GatewayProtocol.error(id, "INTERNAL_ERROR", "Gateway operation failed").toString()
        }
    }

    private fun dispatch(context: Context, request: GatewayRequest): Any = when (request.op) {
        "pair.begin" -> GatewayDesktopPairingManager.begin(context, request.args)
        "pair.complete" -> GatewayDesktopPairingManager.complete(context, request.args)
        "pair.revoke" -> GatewayDesktopPairingManager.revoke(context)
        "manual.execute" -> GatewayManualDesktopAdapter.execute(context, request.id, request.args)
        "clipboard.get" -> GatewayClipboardAdapter.capability(context)
        "clipboard.set" -> GatewayClipboardAdapter.set(context, request.id, request.args)
        "bridge.status" -> GatewayRuntime.status(context)
        "observe.semantic" -> GatewayObservationAdapter.capture(context).payload
        "observe.page_debug" -> GatewayPageDebugAdapter.capture(context, request.args)
        "ui.search" -> {
            val observation = GatewayObservationStore.current() ?: GatewayObservationAdapter.capture(context)
            JSONObject()
                .put("observationId", observation.id)
                .put("elementIdScope", "observation-local")
                .put("query", request.args.optString("query"))
                .put("candidates", GatewayObservationAdapter.search(observation, request.args.optString("query"), request.args.optInt("limit", 30)))
        }
        "ui.element" -> {
            val observation = GatewayObservationStore.current()
                ?: throw GatewayProtocolException("STALE_OBSERVATION", "Call observe.semantic before ui.element")
            val elementId = request.args.optString("elementId", request.args.optString("id"))
            if (elementId.isBlank()) throw GatewayProtocolException("PROTOCOL_MISMATCH", "elementId is required")
            GatewayObservationAdapter.element(observation, elementId)
        }
        "app_graph.get" -> GatewayAppGraphAdapter.query(context, request.args)
        "brain.recall" -> GatewayBrainAdapter.recall(context, request.args)
        "action.execute" -> GatewayActionAdapter.execute(context, request.id, request.args)
        "teach.start" -> GatewayTeachingAdapter.start(context)
        "teach.status" -> GatewayTeachingAdapter.status(context)
        "teach.stop" -> GatewayTeachingAdapter.stop(context)
        "debug.snapshot" -> debugSnapshot(context)
        else -> throw GatewayProtocolException("PROTOCOL_MISMATCH", "Unsupported gateway operation: ${request.op}", request.id)
    }

    private fun debugSnapshot(context: Context): JSONObject {
        val observation = GatewayObservationStore.current()
        val latestPageDebug = PageDebugSandboxV293.latest(context)
        return JSONObject()
            .put("status", GatewayRuntime.status(context))
            .put("latestObservation", observation?.payload ?: JSONObject.NULL)
            .put("latestPageDebug", latestPageDebug?.let { GatewayPageDebugAdapter.safeExport(it) } ?: JSONObject.NULL)
            .put("teaching", GatewayTeachingAdapter.status(context))
            .put("recentActions", JSONArray().also { out ->
                DeviceState.commandAudit.take(30).forEach { audit ->
                    out.put(JSONObject()
                        .put("commandId", audit.commandId)
                        .put("tool", audit.tool)
                        .put("startedAtMs", audit.startedAtMs)
                        .put("finishedAtMs", audit.finishedAtMs)
                        .put("ok", audit.ok)
                        .put("beforeFingerprint", audit.beforeFingerprint ?: JSONObject.NULL)
                        .put("afterFingerprint", audit.afterFingerprint ?: JSONObject.NULL)
                        .put("errorCode", audit.errorCode ?: JSONObject.NULL))
                }
            })
            .put("privacy", "No session token, pairing code, API key, password, OTP, clipboard content or typed phone.type value is included.")
    }
}
