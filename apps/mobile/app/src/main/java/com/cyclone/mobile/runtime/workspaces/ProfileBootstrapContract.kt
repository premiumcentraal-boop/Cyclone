package com.cyclone.mobile.runtime.workspaces

import org.json.JSONObject
import java.io.IOException
import java.security.GeneralSecurityException

/** Only preferences with portable meaning cross users. Never copy task authority or device tokens. */
object ProfileBootstrapContract {
    const val PACKAGE = "com.cyclone.mobile"
    val aiKeys = setOf("openrouter_model", "openrouter_reasoning_effort", "ai_access_profile", "safe_mode")
    val permissions = setOf("android.permission.POST_NOTIFICATIONS", "android.permission.RECORD_AUDIO", "android.permission.READ_CALENDAR")
    const val ACCESSIBILITY = "$PACKAGE/.CycloneAccessibilityService"
    const val LISTENER = "$PACKAGE/.CycloneNotificationListener"

    private val bootstrapStages = setOf(
        "receiver_identity", "keystore", "transfer_read", "identity_validation", "preferences", "profile_registry",
        "credential_restore", "permission_validation", "overlay_validation", "accessibility_validation",
        "listener_validation", "profile_origin", "success_ack", "public_key_publish",
    )
    private val bootstrapFailureCodes = setOf(
        "permission_denied", "crypto_failed", "invalid_transfer", "storage_failed", "validation_failed", "receiver_exception",
    )

    data class BootstrapAcknowledgement(
        val nonce: String,
        val user: Int,
        val ok: Boolean,
        val stage: String,
        val reasonCode: String?,
    )

    /** Reads only bounded, allowlisted diagnostics; acknowledgement payloads never carry exception messages. */
    fun parseBootstrapAcknowledgement(raw: String): BootstrapAcknowledgement? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val user = json.optInt("user", -2)
        if (user < -1) return null
        val ok = json.optBoolean("ok")
        val stageValue = json.optString("stage")
        val stage = stageValue.takeIf { it in bootstrapStages } ?: if (ok) "success_ack" else "unknown"
        val reasonValue = json.optString("reasonCode")
        return BootstrapAcknowledgement(
            nonce = json.optString("nonce").takeUnless { it.isBlank() || it == "null" }.orEmpty(),
            user = user,
            ok = ok,
            stage = stage,
            reasonCode = reasonValue.takeIf { it in bootstrapFailureCodes },
        )
    }

    /** Stable, non-sensitive failure buckets. Never persist exception messages or transfer contents. */
    fun bootstrapFailureCode(error: Exception): String = when (error) {
        is SecurityException -> "permission_denied"
        is GeneralSecurityException -> "crypto_failed"
        is org.json.JSONException -> "invalid_transfer"
        is IOException -> "storage_failed"
        is IllegalArgumentException -> "invalid_transfer"
        is IllegalStateException -> "validation_failed"
        else -> "receiver_exception"
    }

    fun portablePreferences(values: Map<String, *>): Map<String, Any> = values.entries
        .filter { it.key in aiKeys && (it.value is String || it.value is Boolean) }
        .associate { it.key to it.value!! }
    fun userId(uid: Int): Int = uid / 100_000
    fun targetUid(appId: Int, user: Int): Int {
        require(user in 0..21473 && appId in 10000..99999)
        return user * 100_000 + appId
    }
    fun validateTransfer(source: Int, target: Int, current: Int) {
        require(source >= 0 && target > 0 && source != target && target == current) { "Profile transfer identity mismatch" }
    }
    fun mergeServiceList(existing: String, component: String): String {
        require(component in setOf(ACCESSIBILITY, LISTENER))
        return (existing.split(':').filter { it.isNotBlank() && it != "null" } + component).distinct().joinToString(":")
    }
}
