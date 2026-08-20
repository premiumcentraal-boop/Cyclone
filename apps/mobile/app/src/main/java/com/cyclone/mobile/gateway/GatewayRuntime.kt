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
    @Volatile private var lastError: String? = null

    @Synchronized
    fun startIfEnabled(context: Context) {
        appContext = context.applicationContext
        if (!GatewaySessionStore.enabled(context)) return
        if (GatewaySessionStore.token(context) == null) GatewaySessionStore.rotate(context)
        startLocked(context.applicationContext)
    }

    @Synchronized
    fun enable(context: Context): String {
        appContext = context.applicationContext
        val token = GatewaySessionStore.enable(context)
        startLocked(context.applicationContext)
        return token
    }

    @Synchronized
    fun disable(context: Context) {
        appContext = context.applicationContext
        server?.close()
        server = null
        GatewayObservationStore.clear()
        GatewaySessionStore.disable(context)
        lastError = null
    }

    @Synchronized
    fun rotateToken(context: Context): String {
        appContext = context.applicationContext
        val token = GatewaySessionStore.rotate(context)
        server?.disconnectClients()
        return token
    }

    @Synchronized
    fun disconnect() {
        server?.disconnectClients()
    }

    fun isEnabled(context: Context): Boolean = GatewaySessionStore.enabled(context)
    fun tokenForUser(context: Context): String? = GatewaySessionStore.token(context)

    fun status(context: Context): JSONObject {
        val current = GatewayObservationStore.current()
        val follow = FollowMeLearnerRuntime.progress()
        val socket = server
        val enabled = GatewaySessionStore.enabled(context)
        val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val connected = socket?.connectedClients() ?: 0
        return JSONObject()
            .put("protocolVersion", GatewayProtocol.VERSION)
            .put("appVersion", packageInfo?.versionName ?: BuildConfig.VERSION_NAME)
            .put("package", context.packageName)
            .put("gatewayEnabled", enabled)
            .put("socketListening", enabled && socket?.isRunning() == true)
            .put("socketName", GatewayProtocol.SOCKET_NAME)
            .put("networkListener", false)
            .put("accessibilityConnected", DeviceState.accessibilityConnected)
            .put("controllerOwner", DeviceState.controller.name)
            .put("teachingActive", follow.active)
            .put("currentPackage", DeviceState.currentPackage ?: JSONObject.NULL)
            .put("currentPageKey", current?.page?.pageKey ?: JSONObject.NULL)
            .put("rootAppearsAvailable", rootAppearsAvailable())
            .put("rootShellExposed", false)
            .put("connectedSession", JSONObject()
                .put("connected", connected > 0)
                .put("clientCount", connected)
                .put("transport", if (connected > 0) "adb-forwarded-localabstract" else JSONObject.NULL)
                .put("lastConnectedAt", socket?.lastClientConnectedAt ?: JSONObject.NULL)
                .put("pcIdentityDetectable", false))
            .put("capabilities", JSONObject()
                .put("operations", JSONArray(GatewayProtocol.operations.toList()))
                .put("phoneTools", JSONArray(GatewayActionAdapter.allowedTools.toList()))
                .put("fullSemanticControls", true)
                .put("pageDebugFunnel", true)
                .put("appGraphRetrieval", true)
                .put("adaptiveBrainRecall", true)
                .put("canonicalTeaching", true))
            .put("adbForward", "adb forward tcp:${GatewayProtocol.DEFAULT_FORWARD_PORT} localabstract:${GatewayProtocol.SOCKET_NAME}")
            .put("lastError", lastError ?: JSONObject.NULL)
    }

    private fun rootAppearsAvailable(): Boolean = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/data/adb/magisk",
    ).any { File(it).exists() } || Build.TAGS.orEmpty().contains("test-keys")

    @Synchronized
    private fun startLocked(context: Context) {
        if (server?.isRunning() == true) return
        try {
            server = GatewaySocketServer { line -> GatewayDispatcher.handle(context, line) }.also { it.start() }
            lastError = null
        } catch (error: Exception) {
            server = null
            lastError = (error.message ?: error.javaClass.simpleName).take(240)
        }
    }
}

internal object GatewayDispatcher {
    fun handle(context: Context, line: String): String {
        var id = ""
        return try {
            val request = GatewayProtocol.parse(line)
            id = request.id
            if (!GatewaySessionStore.enabled(context)) {
                GatewayProtocol.error(id, "GATEWAY_DISABLED", "PC Gateway is disabled").toString()
            } else if (!GatewaySessionStore.authenticate(context, request.auth)) {
                GatewayProtocol.error(id, "AUTH_REJECTED", "Session token is invalid or has been rotated").toString()
            } else if (request.op !in GatewayProtocol.operations) {
                GatewayProtocol.error(id, "UNKNOWN_OPERATION", "Unsupported gateway operation: ${request.op}").toString()
            } else {
                GatewayProtocol.success(id, dispatch(context, request)).toString()
            }
        } catch (error: GatewayProtocolException) {
            GatewayProtocol.error(error.requestId.ifBlank { id }, error.code, error.message).toString()
        } catch (error: Exception) {
            GatewayProtocol.error(id, "INTERNAL_ERROR", error.message ?: "Gateway operation failed").toString()
        }
    }

    private fun dispatch(context: Context, request: GatewayRequest): Any = when (request.op) {
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
                ?: throw GatewayProtocolException("NO_OBSERVATION", "Call observe.semantic before ui.element")
            val elementId = request.args.optString("elementId", request.args.optString("id"))
            if (elementId.isBlank()) throw GatewayProtocolException("INVALID_REQUEST", "elementId is required")
            GatewayObservationAdapter.element(observation, elementId)
        }
        "app_graph.get" -> GatewayAppGraphAdapter.query(context, request.args)
        "brain.recall" -> GatewayBrainAdapter.recall(context, request.args)
        "action.execute" -> GatewayActionAdapter.execute(context, request.id, request.args)
        "teach.start" -> GatewayTeachingAdapter.start(context)
        "teach.status" -> GatewayTeachingAdapter.status(context)
        "teach.stop" -> GatewayTeachingAdapter.stop(context)
        "debug.snapshot" -> debugSnapshot(context)
        else -> throw GatewayProtocolException("UNKNOWN_OPERATION", "Unsupported gateway operation: ${request.op}", request.id)
    }

    private fun debugSnapshot(context: Context): JSONObject {
        val observation = GatewayObservationStore.current()
        val latestPageDebug = PageDebugSandboxV293.latest(context)
        return JSONObject()
            .put("status", GatewayRuntime.status(context))
            .put("latestObservation", observation?.payload ?: JSONObject.NULL)
            .put("latestPageDebug", latestPageDebug?.let(GatewayPageDebugAdapter::safeExport) ?: JSONObject.NULL)
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
            .put("privacy", "No session token, API key, password, OTP or typed phone.type value is included.")
    }
}
