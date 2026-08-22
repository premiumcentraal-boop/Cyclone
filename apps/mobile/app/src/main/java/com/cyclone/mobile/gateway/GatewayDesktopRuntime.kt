package com.cyclone.mobile.gateway

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.PhoneToolRequest
import org.json.JSONObject

internal object GatewayDesktopPreferences {
    private const val PREFS = "cyclone_desktop_gateway_v1"
    private const val CLIPBOARD_ENABLED = "clipboard_enabled"

    fun clipboardEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(CLIPBOARD_ENABLED, false)

    fun setClipboardEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(CLIPBOARD_ENABLED, enabled).apply()
    }
}

internal object GatewayDesktopPairingManager {
    private val engine = DesktopPairingEngine()

    fun begin(context: Context, args: JSONObject): JSONObject {
        val challenge = try {
            engine.begin(args.optString("usbSessionId"), args.optString("pcNonce"))
        } catch (_: IllegalArgumentException) {
            throw GatewayProtocolException("PROTOCOL_MISMATCH", "Pairing request is invalid")
        }

        // Pairing is protocol state first and UI second. Never let a Toast/window lifecycle problem
        // crash the Gateway process or invalidate a perfectly good pairing challenge. Use only the
        // application context and one best-effort notification; the Gateway control center polls
        // the same engine and will render the code normally when it is open.
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            runCatching {
                Toast.makeText(appContext, "Cyclone pairing code: ${challenge.code}", Toast.LENGTH_LONG).show()
            }
        }

        return JSONObject()
            .put("challengeId", challenge.challengeId)
            .put("expiresAtMs", challenge.expiresAtMs)
            .put("attemptsAllowed", DesktopPairingEngine.MAX_ATTEMPTS)
            .put("credentialAuthority", false)
    }

    fun complete(context: Context, args: JSONObject): JSONObject {
        val completion = engine.complete(
            challengeId = args.optString("challengeId"),
            usbSessionId = args.optString("usbSessionId"),
            pcNonce = args.optString("pcNonce"),
            code = args.optString("code"),
        )
        if (completion is DesktopPairingCompletion.Failure) {
            val code = when (completion.reason) {
                DesktopPairingFailure.EXPIRED -> "PAIRING_EXPIRED"
                DesktopPairingFailure.REPLAY -> "PAIRING_REPLAY"
                DesktopPairingFailure.CODE_REJECTED -> "PAIRING_CODE_REJECTED"
                DesktopPairingFailure.ATTEMPTS_EXCEEDED -> "PAIRING_ATTEMPTS_EXCEEDED"
                DesktopPairingFailure.SESSION_MISMATCH -> "PAIRING_SESSION_MISMATCH"
                DesktopPairingFailure.INVALID_REQUEST -> "PROTOCOL_MISMATCH"
            }
            throw GatewayProtocolException(code, "Pairing could not be completed")
        }
        val credential = if (GatewaySessionStore.enabled(context)) {
            GatewaySessionStore.rotate(context)
        } else {
            GatewaySessionStore.enable(context)
        }
        return JSONObject()
            .put("paired", true)
            .put("credential", credential)
            .put("credentialBits", 256)
    }

    fun revoke(context: Context): JSONObject {
        engine.clear()
        if (GatewaySessionStore.enabled(context)) GatewaySessionStore.rotate(context)
        return JSONObject().put("revoked", true)
    }

    fun codeForUser(): String? = engine.activeForUser()?.code
    fun expiresAtForUser(): Long? = engine.activeForUser()?.expiresAtMs
    fun active(): Boolean = engine.activeForUser() != null
}

internal object GatewayManualDesktopAdapter {
    fun execute(context: Context, requestId: String, args: JSONObject): JSONObject {
        val kind = args.optString("kind")
        if (kind !in DesktopManualControlContract.allowedKinds) {
            throw GatewayProtocolException("PROTOCOL_MISMATCH", "Unsupported manual control kind", requestId)
        }
        val snapshot = runCatching { CycloneAccessibilityService.instance?.observe(markFresh = true) }.getOrNull()
        val request = when (kind) {
            "tap" -> {
                val width = snapshot?.screenWidth ?: 0
                val height = snapshot?.screenHeight ?: 0
                val x = try {
                    DesktopManualControlContract.normalizedPixel(args.optDouble("x", Double.NaN), width)
                } catch (_: IllegalArgumentException) {
                    throw GatewayProtocolException("PROTOCOL_MISMATCH", "tap x is invalid", requestId)
                }
                val y = try {
                    DesktopManualControlContract.normalizedPixel(args.optDouble("y", Double.NaN), height)
                } catch (_: IllegalArgumentException) {
                    throw GatewayProtocolException("PROTOCOL_MISMATCH", "tap y is invalid", requestId)
                }
                PhoneToolRequest(requestId, "phone.tap", JSONObject().put("x", x).put("y", y).put("waitForChangeMs", 0))
            }
            "back" -> PhoneToolRequest(requestId, "phone.back", JSONObject().put("waitForChangeMs", 0))
            "home" -> PhoneToolRequest(requestId, "phone.home", JSONObject().put("waitForChangeMs", 0))
            "scroll_up" -> PhoneToolRequest(requestId, "phone.scroll", JSONObject().put("direction", "backward").put("waitForChangeMs", 0))
            "scroll_down" -> PhoneToolRequest(requestId, "phone.scroll", JSONObject().put("direction", "forward").put("waitForChangeMs", 0))
            "text" -> {
                val value = args.optString("text")
                if (value.isBlank() || value.length > 4096) {
                    throw GatewayProtocolException("PROTOCOL_MISMATCH", "text batch is invalid", requestId)
                }
                PhoneToolRequest(requestId, "phone.type", JSONObject().put("value", value).put("waitForChangeMs", 0))
            }
            "wake" -> {
                val power = context.getSystemService(PowerManager::class.java)
                if (power?.isInteractive == true) {
                    return JSONObject().put("ok", true).put("kind", "wake").put("status", "ALREADY_AWAKE")
                }
                throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "Safe wake is unavailable on this build", requestId)
            }
            else -> throw GatewayProtocolException("PROTOCOL_MISMATCH", "Unsupported manual control kind", requestId)
        }
        val result = PhoneToolExecutor.execute(context, request)
        return JSONObject()
            .put("ok", result.ok)
            .put("kind", kind)
            .put("error", result.error?.toJson() ?: JSONObject.NULL)
            .put("beforeFingerprint", result.beforeFingerprint ?: JSONObject.NULL)
            .put("afterFingerprint", result.afterFingerprint ?: JSONObject.NULL)
            .put("typedValueRedacted", kind == "text")
    }
}

internal object GatewayClipboardAdapter {
    fun capability(context: Context): JSONObject = JSONObject()
        .put("mode", "PC_TO_PHONE")
        .put("enabled", GatewayDesktopPreferences.clipboardEnabled(context))
        .put("reverseSync", "UNAVAILABLE")

    fun set(context: Context, requestId: String, args: JSONObject): JSONObject {
        if (!GatewayDesktopPreferences.clipboardEnabled(context)) {
            throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "Clipboard sync is disabled on the phone", requestId)
        }
        val value = args.optString("text")
        if (value.isBlank() || value.length > 16_384 || DesktopClipboardPolicy.looksSensitive(value)) {
            throw GatewayProtocolException("POLICY_DENIED", "Clipboard content was rejected by the privacy filter", requestId)
        }
        runCatching { CycloneAccessibilityService.instance?.observe(markFresh = true) }
        val result = PhoneToolExecutor.execute(
            context,
            PhoneToolRequest(requestId, "phone.set_clipboard", JSONObject().put("text", value).put("waitForChangeMs", 0)),
        )
        return JSONObject()
            .put("updated", result.ok)
            .put("mode", "PC_TO_PHONE")
            .put("contentRedacted", true)
            .put("error", result.error?.toJson() ?: JSONObject.NULL)
    }
}
