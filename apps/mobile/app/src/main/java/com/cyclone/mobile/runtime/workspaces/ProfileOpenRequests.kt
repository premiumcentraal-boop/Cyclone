package com.cyclone.mobile.runtime.workspaces

import android.content.Context
import android.content.Intent
import java.util.UUID

/**
 * Plan 57 (alpha.122, Cloak handoff CC7): a connector (Cyclone Cloak) asks the owner to open a profile. This object only
 * asks: it holds one waiting request and shows Cyclone's own screen ([com.cyclone.mobile.ui.ProfileOpenRequestActivity]),
 * where only the owner's tap on **Open** switches. Nothing here switches a profile (CI guard).
 *
 * - One request waits at a time, for at most [EXPIRES_MS]; each connector may ask once every [PER_CONNECTOR_MS].
 * - Refused (`BUSY`) while a task runs, a review waits, or this profile isn't the one in front.
 * - On a locked screen it only posts a notification; the screen never opens by itself there.
 */
object ProfileOpenRequests {
    const val EXPIRES_MS = 120_000L
    const val PER_CONNECTOR_MS = 10_000L
    const val EXTRA_NONCE = "com.cyclone.profile.OPEN_REQUEST"
    private const val CHANNEL = "cyclone-profile-open"
    private const val NOTICE = 906

    /** What the owner is asked. [profileId] is a Cyclone profile id, or [ProfileApps.MAIN]. */
    data class Request(
        val nonce: String,
        val connectorId: String,
        val connectorLabel: String,
        val profileId: String,
        val profileLabel: String,
        val atMs: Long,
    )

    /** The one waiting request and each connector's last ask. Pure apart from the clock. */
    class Gate(private val clock: () -> Long = System::currentTimeMillis) {
        private var pending: Request? = null
        private val last = HashMap<String, Long>()

        /** Holds [request] and returns null, or returns why not (BUSY, RATE_LIMITED). */
        @Synchronized fun offer(request: Request): String? {
            val now = clock()
            last[request.connectorId]?.let { if (now - it < PER_CONNECTOR_MS) return "RATE_LIMITED" }
            last[request.connectorId] = now
            if (pending?.let { now - it.atMs < EXPIRES_MS } == true) return "BUSY"
            pending = request.copy(atMs = now)
            return null
        }

        /** The waiting request with this nonce, while it is still fresh. */
        @Synchronized fun peek(nonce: String): Request? = pending?.takeIf { it.nonce == nonce && clock() - it.atMs < EXPIRES_MS }

        /** Answered (either way) or gone. */
        @Synchronized fun close(nonce: String) { if (pending?.nonce == nonce) pending = null }
    }

    val gate = Gate()

    /** Asks the owner; returns null when Cyclone's screen (or its notification) is shown, else an error code. */
    fun ask(context: Context, connectorId: String, connectorLabel: String, profileId: String, profileLabel: String): String? {
        val app = context.applicationContext
        val inFront = runCatching { app.getSystemService(android.os.UserManager::class.java).isUserForeground }.getOrDefault(false)
        val busy = runCatching {
            Layer2Workspaces.gated() || com.cyclone.mobile.runtime.background.WorkspaceTasks.hasCurrentTask() ||
                com.cyclone.mobile.ui.overlay.OverlayChromeRuntime.hasExecutingTask()
        }.getOrDefault(true)
        if (!inFront || busy) return "BUSY"
        val request = Request(UUID.randomUUID().toString(), connectorId, connectorLabel.take(40), profileId, profileLabel.take(40), 0L)
        gate.offer(request)?.let { return it }
        runCatching { show(app, request) }.onFailure { gate.close(request.nonce); return "BUSY" }
        return null
    }

    fun intent(context: Context, nonce: String): Intent =
        Intent(context, com.cyclone.mobile.ui.ProfileOpenRequestActivity::class.java)
            .putExtra(EXTRA_NONCE, nonce).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun show(context: Context, request: Request) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "Requests to open a profile",
            android.app.NotificationManager.IMPORTANCE_HIGH))
        val open = android.app.PendingIntent.getActivity(context, NOTICE, intent(context, request.nonce),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(NOTICE, android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setContentTitle("${request.connectorLabel} asks to open ${request.profileLabel}")
            .setContentText("Tap to decide. Nothing changes unless you say Open.")
            .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(EXPIRES_MS).build())
        // Straight to the question when the phone is in use and unlocked; never over a lock screen.
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        val power = context.getSystemService(android.os.PowerManager::class.java)
        if (power.isInteractive && !keyguard.isKeyguardLocked) runCatching { context.startActivity(intent(context, request.nonce)) }
    }

    fun dismissNotice(context: Context) {
        runCatching { context.getSystemService(android.app.NotificationManager::class.java).cancel(NOTICE) }
    }
}
