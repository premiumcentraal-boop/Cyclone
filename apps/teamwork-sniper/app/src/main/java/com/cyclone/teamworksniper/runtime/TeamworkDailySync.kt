package com.cyclone.teamworksniper.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cyclone.teamworksniper.data.SettingsStore
import com.cyclone.teamworksniper.data.TriggerEvent
import com.cyclone.teamworksniper.data.TriggerSource

class TeamworkDailySyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = SettingsStore(context).load()
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (settings.teamworkDailySync) TeamworkDailySync.schedule(context, force = true)
            return
        }
        if (!settings.teamworkDailySync) return
        SettingsStore(context).save(settings.copy(lastTeamworkSyncMs = System.currentTimeMillis()))
        TeamworkLauncher.open(context)
        SniperCoordinator.submit(
            TriggerEvent(
                source = TriggerSource.MANUAL,
                wallClockEpochMs = System.currentTimeMillis(),
                elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime(),
                notificationTitle = "Teamwork daily sync",
                notificationText = "Pulling the coming 3 weeks",
            ),
        )
        TeamworkDailySync.schedule(context, force = true)
    }
}

object TeamworkDailySync {
    private const val INTERVAL_MS = 24L * 60L * 60L * 1000L
    private const val REQUEST = 71

    fun schedule(context: Context, force: Boolean = false) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pending(context)
        val triggerAt = System.currentTimeMillis() + if (force) INTERVAL_MS else 5_000L
        runCatching {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pending(context))
    }

    fun syncNow(context: Context) {
        SettingsStore(context).save(SettingsStore(context).load().copy(lastTeamworkSyncMs = System.currentTimeMillis()))
        TeamworkLauncher.open(context)
        SniperCoordinator.submit(
            TriggerEvent(
                source = TriggerSource.MANUAL,
                wallClockEpochMs = System.currentTimeMillis(),
                elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime(),
                notificationTitle = "Teamwork sync now",
                notificationText = "Pulling the coming 3 weeks",
            ),
        )
        schedule(context, force = true)
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, TeamworkDailySyncReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
