package com.cyclone.mobile.ui.overlay

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager

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
    fun cancel(context: Context) {
        synchronized(pacer) { pacer.reset() }
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
    private fun renderCurrent(context: Context) {
        com.cyclone.mobile.runtime.background.WorkspaceTasks.state.value?.takeIf { it.foreground }?.let { renderTask(context, it) }
    }
    fun renderTask(context: Context, task: com.cyclone.mobile.runtime.background.WorkspaceTaskUi) {
        if (!task.foreground) return
        post(context, task)
    }

    @Volatile private var channelReady = false
    private val pacer = NotificationPacer()
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    @Volatile private var trailing: Runnable? = null

    private fun post(context: Context, task: com.cyclone.mobile.runtime.background.WorkspaceTaskUi) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!channelReady) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL,
                "Cyclone tasks",
                NotificationManager.IMPORTANCE_DEFAULT,
            ))
            channelReady = true
        }
        val notification = com.cyclone.mobile.runtime.background.TaskProgressNotification.build(context, CHANNEL, task)
        // Alpha.134: the shade is rebuilt only when what it shows changed, and at most about once a second while only
        // the words move. A new state (working, needs you, done: the buttons change) posts at once.
        when (val decision = synchronized(pacer) { pacer.decide(NotificationPacer.fingerprint(notification), android.os.SystemClock.uptimeMillis()) }) {
            NotificationPacer.Decision.Skip -> Unit
            NotificationPacer.Decision.Post -> manager.notify(NOTIFICATION_ID, notification)
            is NotificationPacer.Decision.Later -> if (trailing == null) {
                val again = Runnable { trailing = null; renderCurrent(context.applicationContext) }
                trailing = again
                main.postDelayed(again, decision.afterMs)
            }
        }
    }
}

/**
 * Alpha.134: paces the task notification. Pure, so it is JVM-tested. [Fingerprint.state] is what changes the buttons
 * and the ongoing flag (a new state posts at once); [Fingerprint.words] is the title, text and progress.
 */
internal class NotificationPacer(private val minGapMs: Long = MIN_GAP_MS) {
    data class Fingerprint(val state: String, val words: String)

    sealed class Decision {
        object Post : Decision()
        object Skip : Decision()
        data class Later(val afterMs: Long) : Decision()
    }

    private var last: Fingerprint? = null
    private var lastAtMs = Long.MIN_VALUE / 2

    fun reset() { last = null; lastAtMs = Long.MIN_VALUE / 2 }

    fun decide(next: Fingerprint, nowMs: Long): Decision {
        val before = last
        if (before == next) return Decision.Skip
        val wait = lastAtMs + minGapMs - nowMs
        if (before == null || before.state != next.state || wait <= 0) {
            last = next
            lastAtMs = nowMs
            return Decision.Post
        }
        return Decision.Later(wait)
    }

    companion object {
        const val MIN_GAP_MS = 1_000L

        fun fingerprint(n: android.app.Notification): Fingerprint {
            val extras = n.extras
            val actions = n.actions?.joinToString("|") { it.title?.toString().orEmpty() }.orEmpty()
            val state = "${n.flags and android.app.Notification.FLAG_ONGOING_EVENT}|$actions"
            val words = listOf(android.app.Notification.EXTRA_TITLE, android.app.Notification.EXTRA_TEXT, android.app.Notification.EXTRA_BIG_TEXT,
                android.app.Notification.EXTRA_PROGRESS, android.app.Notification.EXTRA_PROGRESS_MAX, android.app.Notification.EXTRA_PROGRESS_INDETERMINATE)
                .joinToString("|") { extras?.get(it)?.toString().orEmpty() }
            return Fingerprint(state, words)
        }
    }
}
