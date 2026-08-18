package com.cyclone.mobile

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.cyclone.mobile.automation.AutomationRuntime

class CycloneNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        DeviceState.latestNotification = sbn
        val title = sbn.notification.extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = sbn.notification.extras.getCharSequence("android.text")?.toString().orEmpty()
        DeviceState.addLog("Notification ${sbn.packageName}: $title $text")
        AutomationRuntime.onNotification(this, sbn.packageName, title, text)
        BridgeClient.sendNotificationEvent(sbn.packageName, title, text)
    }
}
