package com.cyclone.mobile

import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

object DeviceState {
    enum class Controller { AGENT, HUMAN }

    @Volatile var controller: Controller = Controller.AGENT
    @Volatile var accessibilityConnected: Boolean = false
    @Volatile var bridgeConnected: Boolean = false
    @Volatile var currentPackage: String? = null
    @Volatile var latestNotification: StatusBarNotification? = null
    @Volatile var lastScreenshotPath: String? = null
    val log = CopyOnWriteArrayList<String>()

    fun addLog(message: String) {
        log.add(0, "${System.currentTimeMillis()} $message")
        while (log.size > 100) log.removeLast()
    }
}
