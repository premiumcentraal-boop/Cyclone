package com.cyclone.mobile

import android.content.Context
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
    private val commandExecutor = Executors.newFixedThreadPool(2)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var appContext: Context? = null

    fun start(context: Context) {
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences("cyclone", Context.MODE_PRIVATE)
        val url = prefs.getString("coreWsUrl", "").orEmpty()
        if (url.isBlank() || socket != null) return
        val requestBuilder = Request.Builder().url(url)
        prefs.getString("coreToken", "")?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("Authorization", "Bearer $it") }
        socket = client.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                DeviceState.bridgeConnected = true
                send(JSONObject()
                    .put("type", "mobile.hello")
                    .put("protocol", "phone-tool-v1")
                    .put("androidApi", android.os.Build.VERSION.SDK_INT)
                    .put("tools", org.json.JSONArray(PhoneToolNames.all.toList()))
                    .put("capabilities", CapabilityRegistry.toJson(context)))
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleCommand(text)

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

    private fun handleCommand(raw: String) {
        val msg = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val context = appContext ?: return
        val request = normalizeLegacyCommand(msg)
        commandExecutor.submit {
            val result = PhoneToolExecutor.execute(context, request)
            send(JSONObject().put("type", "mobile.tool_result").put("result", result.toJson()))
        }
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
            "set_text" -> params
                .put("selector", JSONObject().put("textContains", msg.optString("target")))
                .put("value", msg.optString("value"))
            "tap" -> params.put("x", msg.optDouble("x")).put("y", msg.optDouble("y"))
            "swipe" -> params.put("x1", msg.optDouble("x1")).put("y1", msg.optDouble("y1"))
                .put("x2", msg.optDouble("x2")).put("y2", msg.optDouble("y2"))
            "screenshot" -> params.put("includeBase64", true)
        }
        return PhoneToolRequest(msg.optString("id").ifBlank { "legacy-${System.nanoTime()}" }, tool, params)
    }

    fun sendNotificationEvent(packageName: String, title: String, text: String, key: String? = null) {
        send(JSONObject()
            .put("type", "mobile.notification")
            .put("key", key ?: JSONObject.NULL)
            .put("package", packageName)
            .put("title", title)
            .put("text", text))
    }

    private fun send(json: JSONObject) {
        socket?.send(json.toString())
    }
}
