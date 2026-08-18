package com.cyclone.mobile

import android.content.Context
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object BridgeClient {
    private val client = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
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
                send(JSONObject().put("type", "mobile.hello").put("androidApi", android.os.Build.VERSION.SDK_INT))
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
        val id = msg.optString("id")
        val action = msg.optString("action")
        val service = CycloneAccessibilityService.instance
        fun reply(ok: Boolean, payload: Any? = null) {
            send(JSONObject().put("type", "mobile.result").put("id", id).put("ok", ok).put("payload", payload ?: JSONObject.NULL))
        }
        when (action) {
            "observe" -> reply(service != null, service?.observe())
            "click_text" -> reply(service?.clickText(msg.optString("text")) == true)
            "set_text" -> reply(service?.setText(msg.optString("target"), msg.optString("value")) == true)
            "scroll" -> reply(service?.scrollForward() == true)
            "tap" -> reply(service?.tap(msg.optDouble("x").toFloat(), msg.optDouble("y").toFloat()) == true)
            "swipe" -> reply(service?.swipe(msg.optDouble("x1").toFloat(), msg.optDouble("y1").toFloat(), msg.optDouble("x2").toFloat(), msg.optDouble("y2").toFloat()) == true)
            "back" -> reply(service?.goBack() == true)
            "home" -> reply(service?.goHome() == true)
            "takeover_start" -> { DeviceState.controller = DeviceState.Controller.HUMAN; reply(true) }
            "takeover_return" -> { DeviceState.controller = DeviceState.Controller.AGENT; reply(true, service?.observe()) }
            "screenshot" -> {
                if (service == null) reply(false) else service.takeScreenshot { result ->
                    result.onSuccess { file ->
                        val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                        reply(true, JSONObject().put("pngBase64", encoded).put("bytes", file.length()))
                    }.onFailure { reply(false, it.message) }
                }
            }
            else -> reply(false, "unknown_action")
        }
    }

    fun sendNotificationEvent(packageName: String, title: String, text: String) {
        send(JSONObject().put("type", "mobile.notification").put("package", packageName).put("title", title).put("text", text))
    }

    private fun send(json: JSONObject) { socket?.send(json.toString()) }
}
