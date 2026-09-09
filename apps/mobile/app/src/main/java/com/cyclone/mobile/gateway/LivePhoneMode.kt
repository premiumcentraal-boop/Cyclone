package com.cyclone.mobile.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Presentation and an additional stop boundary, never a mutation engine. */
object LivePhoneMode {
    private const val ID = 42015
    private val current = MutableStateFlow("Not connected")
    val state = current.asStateFlow()
    private fun prefs(context: Context) = context.getSharedPreferences("live_phone", Context.MODE_PRIVATE)
    fun setPaused(context: Context, paused: Boolean, stopped: Boolean = false) {
        prefs(context).edit().putBoolean("paused", paused).apply()
        current.value = if (stopped) "Stopped" else if (paused) "Paused" else "Waiting for Cloud ChatGPT"
        if (stopped) context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
    fun check(context: Context, request: GatewayRequest) {
        if (!request.args.optBoolean("livePhone")) return
        if (request.args.optString("sessionId") != "default-foreground" || request.args.optInt("displayId", -1) != 0) {
            throw GatewayProtocolException("PLANE_MISMATCH", "Live Phone requires the physical foreground screen")
        }
        val paused = prefs(context).getBoolean("paused", false)
        if (paused) throw GatewayProtocolException("LIVE_PHONE_PAUSED", "Resume Live Phone on the phone")
        current.value = "Cloud ChatGPT · Connected"
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("live_phone", "Live Phone", NotificationManager.IMPORTANCE_LOW))
            fun action(name: String, code: Int): PendingIntent = PendingIntent.getBroadcast(context, code,
                Intent(context, LivePhoneControlReceiver::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(ID, Notification.Builder(context, "live_phone")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("LIVE PHONE · Cloud ChatGPT")
                .setContentText("Your visible phone screen. Pause or stop new phone actions.")
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(context, 42015, Intent(context, GatewaySettingsActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .addAction(Notification.Action.Builder(null, "Pause", action("pause", 42016)).build())
                .addAction(Notification.Action.Builder(null, "Stop", action("stop", 42017)).build())
                .build())
        }
    }
}

class LivePhoneControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LivePhoneMode.setPaused(context, true, intent.action == "stop")
    }
}
