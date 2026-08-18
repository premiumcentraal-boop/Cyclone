package com.cyclone.mobile

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.cyclone.mobile.automation.AutomationRuntime
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object BridgeClient {
    private val client = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
    private val commandExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var appContext: Context? = null

    fun start(context: Context) {
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences("cyclone", Context.MODE_PRIVATE)
        val url = prefs.getString("coreWsUrl", "").orEmpty()
        if (url.isBlank() || socket != null) return
        val deviceId = prefs.getString("deviceId", "").orEmpty().ifBlank {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.let { "android-$it" } ?: "android-${Build.FINGERPRINT.hashCode()}"
        }
        val deviceName = prefs.getString("deviceName", "").orEmpty().ifBlank {
            listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Android device" }
        }
        val requestBuilder = Request.Builder().url(url)
            .header("X-Cyclone-Device-Id", deviceId)
            .header("X-Cyclone-Device-Name", deviceName)
            .header("X-Cyclone-Device-Platform", "android")
        prefs.getString("coreToken", "")?.takeIf { it.isNotBlank() }
            ?.let { requestBuilder.header("Authorization", "Bearer $it") }
        socket = client.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                DeviceState.bridgeConnected = true
                SetupReminderState.clear()
                send(JSONObject()
                    .put("type", "mobile.hello")
                    .put("protocol", "phone-tool-v1")
                    .put("deviceId", deviceId)
                    .put("deviceName", deviceName)
                    .put("platform", "android")
                    .put("androidApi", Build.VERSION.SDK_INT)
                    .put("tools", PhoneToolRegistry.toJson())
                    .put("capabilities", CapabilityRegistry.toJson(context)))
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                DeviceState.bridgeConnected = false
                socket = null
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                DeviceState.bridgeConnected = false
                DeviceState.addLog("Bridge failure: ${t.message}")
                socket = null
            }
        })
    }

    fun stop() {
        socket?.close(1000, "service stopped")
        socket = null
        DeviceState.bridgeConnected = false
    }

    private fun handleMessage(raw: String) {
        val msg = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (msg.has("tool") || msg.optString("action").isNotBlank()) handleCommand(msg) else routeAutomationEvent(msg)
    }

    private fun routeAutomationEvent(msg: JSONObject) {
        val context = appContext ?: return
        val type = msg.optString("type").ifBlank { "websocket.message" }
        val payload = buildMap {
            msg.keys().forEach { key ->
                val value = msg.opt(key)
                if (value != null && value !== JSONObject.NULL) put(key, value.toString())
            }
        }
        when (type) {
            "automation.request", "cyclone.remote", "mobile.automation.request" -> AutomationRuntime.onCycloneRemote(context, payload)
            else -> AutomationRuntime.onWebSocketEvent(context, type, payload)
        }
        DeviceState.addLog("Automation bridge event routed: $type")
    }

    private fun handleCommand(msg: JSONObject) {
        val context = appContext ?: return
        when (msg.optString("action")) {
            "takeover_start" -> {
                DeviceState.setController(DeviceState.Controller.HUMAN)
                sendLegacyControlResult(msg, "human", freshObservationRequired = false)
                return
            }
            "takeover_return" -> {
                DeviceState.setController(DeviceState.Controller.AGENT)
                sendLegacyControlResult(msg, "agent", freshObservationRequired = true)
                return
            }
        }

        val request = normalizeLegacyCommand(msg)
        commandExecutor.submit {
            val result = PhoneToolExecutor.execute(context, request)
            send(JSONObject().put("type", "mobile.tool_result").put("result", result.toJson()))
        }
    }

    private fun sendLegacyControlResult(msg: JSONObject, controller: String, freshObservationRequired: Boolean) {
        send(JSONObject()
            .put("type", "mobile.result")
            .put("id", msg.optString("id"))
            .put("ok", true)
            .put("payload", JSONObject()
                .put("controller", controller)
                .put("freshObservationRequired", freshObservationRequired)))
    }

    private fun normalizeLegacyCommand(msg: JSONObject): PhoneToolRequest {
        if (msg.has("tool")) return PhoneToolRequest.fromJson(msg)
        val legacy = msg.optString("action")
        val tool = when (legacy) {
            "observe" -> "phone.observe"
            "screenshot" -> "phone.screenshot"
            "click_text" -> "phone.click"
            "set_text" -> "phone.replace_text"
            "scroll" -> "phone.scroll"
            "tap" -> "phone.tap"
            "swipe" -> "phone.swipe"
            "back" -> "phone.back"
            "home" -> "phone.home"
            else -> legacy
        }
        val params = JSONObject()
        when (legacy) {
            "click_text" -> params.put("selector", JSONObject().put("textContains", msg.optString("text")))
            "set_text" -> params.put("selector", JSONObject().put("textContains", msg.optString("target"))).put("value", msg.optString("value"))
            "tap" -> params.put("x", msg.optDouble("x")).put("y", msg.optDouble("y"))
            "swipe" -> params.put("x1", msg.optDouble("x1")).put("y1", msg.optDouble("y1")).put("x2", msg.optDouble("x2")).put("y2", msg.optDouble("y2"))
            "screenshot" -> params.put("includeBase64", true)
        }
        return PhoneToolRequest(msg.optString("id").ifBlank { "legacy-${System.nanoTime()}" }, tool, params)
    }

    fun sendNotificationEvent(packageName: String, title: String, text: String, key: String? = null) {
        send(JSONObject().put("type", "mobile.notification").put("key", key ?: JSONObject.NULL).put("package", packageName).put("title", title).put("text", text))
    }

    fun sendAutomationEvent(type: String, payload: Map<String, String>): Boolean {
        val json = JSONObject().put("type", type)
        payload.forEach { (key, value) -> json.put(key, value) }
        val sent = socket?.send(json.toString()) == true
        if (!sent) {
            SetupReminderState.request(
                SetupNeed.CORE,
                "Connect Cyclone Core before using Hermes or AI-generated automations.",
            )
        }
        return sent
    }

    private fun send(json: JSONObject) { socket?.send(json.toString()) }
}
