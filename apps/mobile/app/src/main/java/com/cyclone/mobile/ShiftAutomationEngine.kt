package com.cyclone.mobile

import android.app.PendingIntent
import android.content.Context
import android.service.notification.StatusBarNotification
import java.time.LocalDate
import java.time.ZoneId
import java.util.regex.Pattern

object ShiftAutomationEngine {
    private val timePattern = Pattern.compile("(\\d{1,2})[:.](\\d{2})\\s*(?:-|–|—|to)\\s*(\\d{1,2})[:.](\\d{2})", Pattern.CASE_INSENSITIVE)

    fun onNotification(context: Context, sbn: StatusBarNotification, title: String, text: String) {
        val prefs = context.getSharedPreferences("cyclone", Context.MODE_PRIVATE)
        val packageFilter = prefs.getString("workPackage", "").orEmpty()
        if (packageFilter.isBlank() || !sbn.packageName.contains(packageFilter, ignoreCase = true)) return

        val shift = parseSameDayShift("$title $text") ?: run {
            DeviceState.addLog("Shift notification matched app but time could not be parsed")
            return
        }
        val bufferMinutes = prefs.getInt("calendarBufferMinutes", 30)
        val bufferedStart = shift.first - bufferMinutes * 60_000L
        val bufferedEnd = shift.second + bufferMinutes * 60_000L
        val free = CalendarMatcher.isFree(context, bufferedStart, bufferedEnd)
        DeviceState.addLog("Shift candidate calendarFree=$free")
        if (!free) return

        if (!prefs.getBoolean("autoClaimEnabled", false)) {
            DeviceState.addLog("Eligible shift found; dry-run mode keeps auto-claim disabled")
            return
        }

        openNotification(sbn.notification.contentIntent)
        android.os.Handler(context.mainLooper).postDelayed({
            val claimText = prefs.getString("claimText", "claim") ?: "claim"
            val clicked = CycloneAccessibilityService.instance?.click(
                ElementSelector(textContains = claimText, requireClickable = null)
            ) == true
            DeviceState.addLog("Auto-claim click attempted success=$clicked")
        }, 1500)
    }

    private fun openNotification(intent: PendingIntent?) {
        runCatching { intent?.send() }.onFailure { DeviceState.addLog("Could not open notification: ${it.message}") }
    }

    private fun parseSameDayShift(raw: String): Pair<Long, Long>? {
        val matcher = timePattern.matcher(raw)
        if (!matcher.find()) return null
        val sh = matcher.group(1)?.toIntOrNull() ?: return null
        val sm = matcher.group(2)?.toIntOrNull() ?: return null
        val eh = matcher.group(3)?.toIntOrNull() ?: return null
        val em = matcher.group(4)?.toIntOrNull() ?: return null
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now()
        val start = day.atTime(sh, sm).atZone(zone).toInstant().toEpochMilli()
        var end = day.atTime(eh, em).atZone(zone).toInstant().toEpochMilli()
        if (end <= start) end += 24 * 60 * 60 * 1000L
        return start to end
    }
}
