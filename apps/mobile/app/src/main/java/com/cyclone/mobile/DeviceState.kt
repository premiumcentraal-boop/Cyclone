package com.cyclone.mobile

import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

object DeviceState {
    enum class Controller { AGENT, HUMAN }

    @Volatile private var _controller: Controller = Controller.AGENT
    val controller: Controller get() = _controller

    @Volatile var accessibilityConnected: Boolean = false
    @Volatile var bridgeConnected: Boolean = false
    @Volatile var currentPackage: String? = null
    @Volatile var currentClassName: String? = null
    @Volatile var latestNotification: StatusBarNotification? = null
    @Volatile var lastScreenshotPath: String? = null
    @Volatile var requireFreshObservation: Boolean = false
    @Volatile var lastUiEventAtMs: Long = 0L

    private val controllerEpoch = AtomicLong(0)
    private val notifications = ConcurrentHashMap<String, StatusBarNotification>()
    val log = CopyOnWriteArrayList<String>()
    val commandAudit = CopyOnWriteArrayList<CommandAuditRecord>()

    data class CommandAuditRecord(
        val commandId: String,
        val tool: String,
        val startedAtMs: Long,
        val finishedAtMs: Long,
        val ok: Boolean,
        val beforeFingerprint: String?,
        val afterFingerprint: String?,
        val errorCode: String?,
    )

    fun controllerEpoch(): Long = controllerEpoch.get()

    @Synchronized
    fun setController(controller: Controller) {
        if (_controller == controller) return
        _controller = controller
        controllerEpoch.incrementAndGet()
        // Returning control invalidates every queued target. A fresh observe is mandatory.
        requireFreshObservation = controller == Controller.AGENT
        addLog("Controller changed to ${controller.name}")
    }

    fun markObserved() {
        if (_controller == Controller.AGENT) requireFreshObservation = false
    }

    fun storeNotification(sbn: StatusBarNotification) {
        latestNotification = sbn
        notifications[sbn.key] = sbn
        if (notifications.size > 100) {
            notifications.values.sortedBy { it.postTime }.take(notifications.size - 100).forEach { notifications.remove(it.key) }
        }
    }

    fun removeNotification(key: String) {
        notifications.remove(key)
        if (latestNotification?.key == key) latestNotification = notifications.values.maxByOrNull { it.postTime }
    }

    fun notification(key: String?): StatusBarNotification? =
        if (key.isNullOrBlank()) latestNotification else notifications[key]

    fun notificationSnapshot(): List<StatusBarNotification> = notifications.values.sortedByDescending { it.postTime }

    fun addAudit(record: CommandAuditRecord) {
        commandAudit.add(0, record)
        while (commandAudit.size > 250) commandAudit.removeLast()
    }

    fun addLog(message: String) {
        log.add(0, "${System.currentTimeMillis()} $message")
        while (log.size > 100) log.removeLast()
    }
}
