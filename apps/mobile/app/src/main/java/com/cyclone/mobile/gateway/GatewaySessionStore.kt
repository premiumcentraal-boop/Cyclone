package com.cyclone.mobile.gateway

import android.content.Context
import java.security.SecureRandom
import java.util.Base64

internal object GatewaySessionStore {
    private const val PREFS = "cyclone_pc_gateway_v293"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TOKEN = "session_token"
    private const val KEY_ROTATED_AT = "rotated_at"
    private val random = SecureRandom()

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun token(context: Context): String? = prefs(context).getString(KEY_TOKEN, null)?.takeIf(String::isNotBlank)

    fun enable(context: Context): String {
        val token = newToken()
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_TOKEN, token)
            .putLong(KEY_ROTATED_AT, System.currentTimeMillis())
            .apply()
        return token
    }

    fun disable(context: Context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, false).remove(KEY_TOKEN).apply()
    }

    fun rotate(context: Context): String {
        require(enabled(context)) { "PC Gateway is disabled" }
        val token = newToken()
        prefs(context).edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_ROTATED_AT, System.currentTimeMillis())
            .apply()
        return token
    }

    fun rotatedAt(context: Context): Long = prefs(context).getLong(KEY_ROTATED_AT, 0L)

    fun authenticate(context: Context, supplied: String): Boolean = GatewayAuth.matches(token(context), supplied)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun newToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
