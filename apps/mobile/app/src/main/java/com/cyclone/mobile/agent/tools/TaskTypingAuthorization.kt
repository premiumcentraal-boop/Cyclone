package com.cyclone.mobile.agent.tools

import com.cyclone.mobile.agent.contract.NavigationIntent
import org.json.JSONObject

internal object TaskTypingAuthorization {
    fun allows(userGoal: String?, packageName: String, evidence: JSONObject, value: String): Boolean {
        if (userGoal.isNullOrBlank() || value.isBlank() || value.length > 4096) return false
        if (evidence.optBoolean("password") || !evidence.optBoolean("enabled", true)) return false
        val id = evidence.optString("resourceId").lowercase()
        if (listOf("url_bar", "urlbar", "location_bar", "address_bar", "search_box").none(id::contains)) return false
        if (!evidence.optBoolean("editable") && evidence.optString("role") !in setOf("textbox", "edittext", "text_field")) return false
        if (listOf("chrome", "browser", "firefox", "edge").none(packageName.lowercase()::contains)) return false
        val navigation = NavigationIntent.parse(userGoal)
        if (navigation != null) return (!navigation.chrome || packageName == "com.android.chrome") && navigation.accepts(value)
        val query = Regex("(?i)^search(?: for)? (.+?)(?: in (?:google )?chrome)?$").matchEntire(userGoal.trim())?.groupValues?.get(1)
        return query != null && value == query
    }
}
