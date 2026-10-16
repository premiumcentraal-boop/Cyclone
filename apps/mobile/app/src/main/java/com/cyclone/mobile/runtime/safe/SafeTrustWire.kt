package com.cyclone.mobile.runtime.safe

import org.json.JSONArray

/** Old authorization never survives recovery; only the explicitly re-paired fresh installation stays active. */
internal object SafeTrustWire {
    fun restore(saved: JSONArray, current: JSONArray, phone: String, now: Long): JSONArray {
        val active = (0 until current.length()).map { current.getJSONObject(it) }
            .filter { it.optString("phoneId") == phone && it.isNull("revokedAtMs") }
            .associateBy { it.getString("trustId") }
        val result = JSONArray()
        for (i in 0 until saved.length()) {
            val row = saved.getJSONObject(i)
            if (row.getString("trustId") !in active) result.put(row.put("revokedAtMs", now))
        }
        active.values.forEach(result::put)
        return result
    }
}
