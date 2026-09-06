package com.cyclone.mobile.agent.contract

import java.net.URI

/** Narrow intent captured from the user's task, never from model-provided authorization flags. */
data class NavigationIntent(val target: String, val chrome: Boolean) {
    fun accepts(value: String): Boolean {
        val uri = runCatching { URI(if ("://" in value) value else "https://$value") }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1 || uri.query != null || uri.fragment != null) return false
        if (!uri.path.isNullOrBlank() && uri.path != "/") return false
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return false
        return if ('.' in target) host == target.removePrefix("https://").removePrefix("www.").trimEnd('/')
        else host.split('.').let { it.size == 2 && it.first() == target }
    }
    companion object {
        fun parse(goal: String): NavigationIntent? {
            val match = Regex("(?i)^(?:please\\s+)?(?:open|go to|navigate to)\\s+(?:the\\s+)?([a-z0-9./:-]+)(?:\\s+(?:website|site))?(?:\\s+(?:in|using|with)\\s+(?:google\\s+)?(chrome))?[.!]?$").matchEntire(goal.trim()) ?: return null
            return NavigationIntent(match.groupValues[1].lowercase().trimEnd('.'), match.groupValues[2].isNotBlank())
        }
    }
}
