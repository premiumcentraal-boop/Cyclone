package com.cyclone.mobile.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.cyclone.mobile.R

/**
 * The microphone's foreground service (plan 24 §5.3): it runs only while Drive is listening, so Android keeps the
 * microphone open behind other apps and shows its privacy indicator. It never runs while Cyclone speaks or works.
 */
class VoiceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Cyclone listening", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cyclone_status)
            .setContentTitle("Cyclone is listening")
            .setContentText("Only while the orb is open. Tap Stop to end.")
            .setOngoing(true)
            .build()
        runCatching { startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
            .onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "cyclone_voice"
        private const val NOTIFICATION_ID = 49_001

        fun start(context: Context) {
            // Without the microphone permission Android refuses the microphone service after it was started, and a
            // started service that never goes foreground crashes the app: then there is nothing to keep open anyway.
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            runCatching { context.startForegroundService(Intent(context, VoiceService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, VoiceService::class.java)) }
        }
    }
}
