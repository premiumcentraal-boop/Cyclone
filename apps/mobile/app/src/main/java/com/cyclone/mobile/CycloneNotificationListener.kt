package com.cyclone.mobile

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class CycloneNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications.orEmpty().forEach(DeviceState::storeNotification)
        DeviceState.addLog("Notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        DeviceState.storeNotification(sbn)
        val title = sbn.notification.extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = sbn.notification.extras.getCharSequence("android.text")?.toString().orEmpty()
        DeviceState.addLog("Notification ${sbn.packageName}: $title $text")
        ShiftAutomationEngine.onNotification(this, sbn, title, text)
        BridgeClient.sendNotificationEvent(sbn.packageName, title, text, sbn.key)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        DeviceState.removeNotification(sbn.key)
        BridgeClient.sendNotificationEvent(sbn.packageName, "", "", sbn.key)
    }
}
