package com.cyclone.teamworksniper.teamwork

import com.cyclone.teamworksniper.data.OpenShift
import com.cyclone.teamworksniper.data.ShiftCode
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import org.json.JSONObject

object TeamworkNativeShiftId {
    const val PREFIX = "tech.picnic.workapp:id/SG"

    fun decode(resourceId: String?, zone: ZoneId = ZoneId.systemDefault()): OpenShift? {
        val encoded = resourceId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
        val json = runCatching {
            val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
            JSONObject(String(Base64.getDecoder().decode(padded), Charsets.UTF_8))
        }.getOrNull() ?: return null
        if (!json.optString("p").equals("OPEN", ignoreCase = true)) return null

        val label = json.optJSONArray("l")?.let { labels ->
            (0 until labels.length())
                .map(labels::optString)
                .firstOrNull { it.startsWith("TRIP_BASE_SHIFTS|") }
                ?.substringAfter('|')
        } ?: return null
        val codes = label.split('-').mapNotNull(ShiftCode::fromRaw)
        if (codes.isEmpty() || codes.joinToString("-") { it.name } != label.uppercase()) return null

        val start = runCatching { Instant.parse(json.getString("s")).atZone(zone) }.getOrNull() ?: return null
        val end = runCatching { Instant.parse(json.getString("e")).atZone(zone) }.getOrNull() ?: return null
        val shiftId = json.optString("c").takeIf { it.isNotBlank() } ?: return null
        return OpenShift(
            date = start.toLocalDate(),
            code = codes.first(),
            codes = codes,
            startTime = start.toLocalTime(),
            endTime = end.toLocalTime(),
            semanticIdentity = resourceId,
            teamworkShiftId = shiftId,
        )
    }
}
