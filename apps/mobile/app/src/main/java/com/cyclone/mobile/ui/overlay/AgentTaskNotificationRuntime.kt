package com.cyclone.mobile.ui.overlay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import com.cyclone.mobile.MainActivity
import com.cyclone.mobile.R
import java.util.concurrent.atomic.AtomicBoolean

internal enum class AgentTaskNotificationState { WORKING, WAITING, COMPLETED, STOPPED }

internal object AgentTaskNotificationCopy {
    fun title(state: AgentTaskNotificationState): String = when (state) {
        AgentTaskNotificationState.WORKING -> "Cyclone is working"
        AgentTaskNotificationState.WAITING -> "Cyclone needs you"
        AgentTaskNotificationState.COMPLETED -> "Task completed"
        AgentTaskNotificationState.STOPPED -> "Task stopped"
    }

    fun text(value: String?, fallback: String): String = value.orEmpty()
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { fallback }
        .take(240)
}

/**
 * User-visible notification lifecycle for the normal display-0 Ask Cyclone task.
 *
 * Background WorkspaceTaskService owns its own foreground notification, so this runtime is invoked
 * only by OverlayChromeRuntime. The notification never exposes task text on the public lock-screen
 * version and never requests notification permission on the user's behalf.
 */
internal object AgentTaskNotificationRuntime {
    private const val CHANNEL = "cyclone-agent-task"
    private const val NOTIFICATION_ID = 903
    private val active = AtomicBoolean(false)

    fun start(context: Context) {
        active.set(true)
        post(context, AgentTaskNotificationState.WORKING, "Starting your task…", ongoing = true)
    }

    fun progress(context: Context, message: String) {
        if (!active.get()) return
        post(context, AgentTaskNotificationState.WORKING,
            AgentTaskNotificationCopy.text(message, "Cyclone is working…"), ongoing = true)
    }

    fun waiting(context: Context, message: String?) {
        if (!active.get()) return
        post(context, AgentTaskNotificationState.WAITING,
            AgentTaskNotificationCopy.text(message, "Open Cyclone to continue."), ongoing = false)
    }

    fun finish(context: Context, success: Boolean, message: String?) {
        if (!active.compareAndSet(true, false)) return
        val state = if (success) AgentTaskNotificationState.COMPLETED else AgentTaskNotificationState.STOPPED
        post(context, state,
            AgentTaskNotificationCopy.text(message, if (success) "Cyclone finished and checked the task." else "Cyclone stopped safely."),
            ongoing = false,
        )
    }

    private fun post(context: Context, state: AgentTaskNotificationState, message: String, ongoing: Boolean) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL,
            "Cyclone tasks",
            NotificationManager.IMPORTANCE_DEFAULT,
        ))
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val publicVersion = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_cyclone_status)
            .setContentTitle("Cyclone task")
            .setContentText("Unlock to view task progress")
            .build()
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_cyclone_status)
            .setColor(Color.rgb(141, 227, 210))
            .setContentTitle(AgentTaskNotificationCopy.title(state))
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setSubText("Cyclone")
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setShowWhen(false)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(ongoing)
        if (ongoing) builder.setProgress(0, 0, true)
        manager.notify(NOTIFICATION_ID, builder.build())
    }
}
