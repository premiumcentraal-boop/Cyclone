package com.cyclone.mobile.fastpath

import com.cyclone.mobile.agent.contract.NavigationIntent
import org.json.JSONObject

data class FastPathLandingHint(
    val tool: String,
    val packageName: String? = null,
    val uri: String? = null,
    val reason: String,
    val workspaceNamedApp: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("tool", tool)
        .put("package", packageName ?: JSONObject.NULL)
        .put("uri", uri ?: JSONObject.NULL)
        .put("reason", reason)
        .put("preferBeforeIconHunt", true)
        .put("workspaceNamedApp", workspaceNamedApp)
}

/**
 * Prefer intent / deep-link / open_app before hunting launcher icons.
 * Named-app matches are the same signal 3.9.12 Ask→workspace routing already uses.
 */
object FastPathLanding {
    val APP_PACKAGE_ALIASES = linkedMapOf(
        "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "youtube" to "com.google.android.youtube",
        "google maps" to "com.google.android.apps.maps",
        "maps" to "com.google.android.apps.maps",
        "play store" to "com.android.vending",
        "settings" to "com.android.settings",
        "photos" to "com.google.android.apps.photos",
        "camera" to "com.android.camera2",
        "clock" to "com.google.android.deskclock",
        "files" to "com.google.android.documentsui",
        "messages" to "com.google.android.apps.messaging",
        "phone" to "com.google.android.dialer",
        "contacts" to "com.google.android.contacts",
    )

    private val URL = Regex("(?i)https?://[^\\s]+")
    private val HOST = Regex("(?i)\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}\\b")

    fun resolve(goal: String): FastPathLandingHint? {
        val trimmed = goal.trim()
        if (trimmed.isBlank()) return null

        NavigationIntent.parse(trimmed)?.let { intent ->
            val host = intent.target.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            val uri = if ('.' in host) "https://$host" else "https://$host.com"
            return FastPathLandingHint(
                tool = "phone.launch_intent",
                uri = sanitizeUri(uri),
                reason = "Goal names a website. Prefer phone.launch_intent over hunting a browser icon.",
            )
        }

        URL.find(trimmed)?.value?.let { raw ->
            val uri = sanitizeUri(raw) ?: return@let
            return FastPathLandingHint(
                tool = "phone.launch_intent",
                uri = uri,
                reason = "Goal contains an http(s) URL. Prefer phone.launch_intent over icon hunting.",
            )
        }

        namedApp(trimmed)?.let { (alias, packageName) ->
            return FastPathLandingHint(
                tool = "phone.open_app",
                packageName = packageName,
                reason = "Goal names $alias. Prefer phone.open_app before hunting a launcher icon. 3.9.12 Ask→workspace uses the same named-app match.",
                workspaceNamedApp = true,
            )
        }

        HOST.find(trimmed)?.value?.let { host ->
            if (host.equals("android.com", ignoreCase = true)) return@let
            val uri = sanitizeUri("https://${host.removePrefix("www.")}") ?: return@let
            return FastPathLandingHint(
                tool = "phone.launch_intent",
                uri = uri,
                reason = "Goal names a web host. Prefer phone.launch_intent over hunting a browser icon.",
            )
        }
        return null
    }

    fun namedApp(goal: String): Pair<String, String>? {
        val lower = goal.lowercase()
        return APP_PACKAGE_ALIASES.entries.firstOrNull { (alias, _) ->
            Regex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(alias) + "(?![\\p{L}\\p{N}])").containsMatchIn(lower)
        }?.toPair()
    }

    fun sanitizeUri(raw: String): String? {
        val clean = raw.trim().substringBefore('#').substringBefore('?').trimEnd('/')
        if (!(clean.startsWith("https://", ignoreCase = true) || clean.startsWith("http://", ignoreCase = true))) {
            return null
        }
        if (':' in clean.substringAfter("://").substringBefore('/')) return null
        return clean.take(240)
    }
}
