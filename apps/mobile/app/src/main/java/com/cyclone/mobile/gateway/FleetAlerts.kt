package com.cyclone.mobile.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * Roadmap run 14 (plan 33 §11): a fleet alert from the owner's PC, shown as an ordinary notification (`cc.alert`).
 * "Work Phone is offline", "Logging in to @shop failed twice". Plain words only: tapping it opens Cyclone, and it
 * carries no action, approval or command. Same-alert repeats replace the earlier notification.
 */
internal object FleetAlerts {
    const val CHANNEL = "cyclone-fleet-alerts"
    private val ALERT_ID = Regex("^alr_[A-Za-z0-9_-]{6,40}$")
    private val LEVELS = setOf("critical", "warning", "info")
    private val INLINE_SECRET = Regex("(?i)(password|passcode|passwd|pin|otp|token|secret|api[_-]?key|authorization|cookie|cvv|credential)\\s*[:=]")

    data class Alert(val id: String, val level: String, val title: String, val text: String)

    /** Seam for JVM tests; production posts the notification. */
    internal var post: (Context, Alert) -> Boolean = ::show

    /** The checked alert, or a protocol error naming what is wrong. Pure. */
    fun parse(args: JSONObject): Alert {
        if (args.keys().asSequence().any { it !in setOf("alertId", "level", "title", "text") }) throw invalid("Unexpected alert field.")
        val id = (args.opt("alertId") as? String).orEmpty()
        val level = (args.opt("level") as? String).orEmpty()
        val title = (args.opt("title") as? String)?.trim().orEmpty()
        val text = (args.opt("text") as? String)?.trim().orEmpty()
        if (!ALERT_ID.matches(id)) throw invalid("alertId is malformed.")
        if (level !in LEVELS) throw invalid("level is critical, warning or info.")
        if (title.isEmpty() || title.length > 120 || text.length > 400) throw invalid("An alert is a title of 1..120 and a text of up to 400 characters.")
        if (INLINE_SECRET.containsMatchIn("$title $text")) throw invalid("An alert never carries a secret.")
        return Alert(id, level, title, text)
    }

    fun receive(context: Context, args: JSONObject): JSONObject {
        val alert = parse(args)
        return JSONObject().put("shown", runCatching { post(context, alert) }.getOrDefault(false))
    }

    /** A stable notification id per alert, so a repeat of the same alert replaces it. */
    fun noticeId(alertId: String): Int = 0x4C00_0000 or (alertId.hashCode() and 0x00FF_FFFF)

    private fun show(context: Context, alert: Alert): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Fleet alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Your phones going offline, failing logins and routines that keep failing, from your PC's Command Center."
        })
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(alert.title)
            .setContentText(alert.text.ifEmpty { "From your PC's Command Center." })
            .setStyle(Notification.BigTextStyle().bigText(alert.text.ifEmpty { "From your PC's Command Center." }))
            .setCategory(if (alert.level == "critical") Notification.CATEGORY_ERROR else Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
        launch?.let {
            builder.setContentIntent(PendingIntent.getActivity(context, noticeId(alert.id), it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        manager.notify(noticeId(alert.id), builder.build())
        return true
    }

    private fun invalid(message: String) = GatewayProtocolException("INVALID_REQUEST", message)
}

/**
 * Roadmap run 14: the heartbeat's heat and storage facts, named the way the PC reads them. Pure, for JVM tests.
 */
internal object FleetHealthFacts {
    private val THERMAL = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")

    /** Android's PowerManager thermal status (0..6) as a word, or null when unknown. */
    fun thermal(status: Int): String? = THERMAL.getOrNull(status)

    /** The battery temperature extra (tenths of a degree) in °C, or null when missing or implausible. */
    fun temperatureC(tenths: Int): Double? = if (tenths in -400..1200) tenths / 10.0 else null

    fun freeMb(bytes: Long): Long? = if (bytes >= 0) bytes / (1024L * 1024L) else null
}
