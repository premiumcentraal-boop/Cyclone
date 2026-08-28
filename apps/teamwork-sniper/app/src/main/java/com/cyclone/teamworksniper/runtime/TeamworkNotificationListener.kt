package com.cyclone.teamworksniper.runtime

import android.app.Notification
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.cyclone.teamworksniper.data.TriggerEvent
import com.cyclone.teamworksniper.data.TriggerSource
import java.util.concurrent.ConcurrentHashMap

class TeamworkNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != TeamworkLauncher.PACKAGE) return
        val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = sbn.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val semantic = listOfNotNull(title, text).joinToString(" ")
        if (!OPEN_SHIFT_NOTICE.containsMatchIn(semantic)) return

        val now = SystemClock.elapsedRealtime()
        val key = sbn.key + "|" + text.orEmpty()
        val previous = recent.put(key, now)
        recent.entries.removeIf { now - it.value > DEDUPE_WINDOW_MS }
        if (previous != null && now - previous < DEDUPE_WINDOW_MS) return

        val launch = TeamworkLauncher.open(this, sbn.notification.contentIntent)
        SniperCoordinator.submit(
            TriggerEvent(
                source = TriggerSource.NOTIFICATION,
                wallClockEpochMs = System.currentTimeMillis(),
                elapsedRealtimeMs = now,
                notificationTitle = title,
                notificationText = text,
                launchOutcome = launch,
            ),
        )
    }

    companion object {
        private val OPEN_SHIFT_NOTICE = Regex("(?i)\\bnew open shifts?|open shifts? (?:is|are) available\\b")
        private const val DEDUPE_WINDOW_MS = 30_000L
        private val recent = ConcurrentHashMap<String, Long>()
    }
}
