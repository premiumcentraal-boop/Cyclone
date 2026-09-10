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
    fun start(context: Context) = renderCurrent(context)
    fun progress(context: Context, message: String) = renderCurrent(context)
    fun waiting(context: Context, message: String?) = renderCurrent(context)
    fun finish(context: Context, success: Boolean, message: String?) = renderCurrent(context)
    fun cancel(context: Context) { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
    private fun renderCurrent(context: Context) {
        com.cyclone.mobile.runtime.background.WorkspaceTasks.state.value?.takeIf { it.foreground }?.let { renderTask(context, it) }
    }
    fun renderTask(context: Context, task: com.cyclone.mobile.runtime.background.WorkspaceTaskUi) {
        if (!task.foreground) return
        post(context, task)
    }

    private fun post(context: Context, task: com.cyclone.mobile.runtime.background.WorkspaceTaskUi) {
        val ongoing = task.working
        val message = task.subtitle
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
            .setContentTitle(com.cyclone.mobile.runtime.background.TaskNotificationProjection.title(task))
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
        com.cyclone.mobile.runtime.background.TaskNotificationProjection.actions(task).forEach { (command, label) ->
            val pending = PendingIntent.getService(context, 0,
                com.cyclone.mobile.runtime.background.WorkspaceTasks.commandIntent(context, task, command),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(null, label, pending).build())
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }
}
