package com.cyclone.mobile.applearner

import org.json.JSONObject

object ActionSafetyPolicy {
    private val consequential = setOf(
        "buy", "purchase", "checkout", "pay", "payment", "transfer", "send", "post", "publish",
        "delete", "remove", "cancel", "submit", "confirm order", "place order", "change password",
        "change account", "logout", "log out", "install", "uninstall", "permission", "factory reset",
        "subscribe", "unsubscribe", "book", "reserve", "accept", "decline", "sign", "approve",
    )
    private val authentication = setOf(
        "captcha", "verify identity", "identity verification", "2fa", "two-factor", "two factor",
        "otp", "one-time code", "one time code", "passkey", "security key", "sign in", "log in",
        "login", "password", "authentication", "biometric", "fingerprint", "face id",
    )
    private val notificationDenyLabels = setOf("block", "deny", "don't allow", "do not allow", "not now", "no thanks")
    private val notificationAllowLabels = setOf("allow", "enable", "turn on")

    fun classify(label: String, resourceId: String = "", contentDescription: String = ""): ActionRisk {
        val selected = normalize(label)
        val context = normalize(contentDescription)
        val resource = normalize(resourceId.substringAfterLast('/'))
        val notificationContext = listOf(selected, context, resource).joinToString(" ").let { text ->
            "notification" in text || "notifications" in text || "wants to send you" in text
        }

        // The selected action is authoritative. Explanatory modal prose cannot turn Block/Deny into
        // a SEND action merely because the sentence says "wants to send you notifications".
        if (notificationContext && notificationDenyLabels.any { selected == it || selected.startsWith("$it ") }) {
            return ActionRisk.SAFE
        }
        if (notificationContext && notificationAllowLabels.any { selected == it || selected.startsWith("$it ") }) {
            return ActionRisk.CONSEQUENTIAL
        }

        val text = listOf(selected, resource, context).joinToString(" ").trim()
        if (authentication.any { token -> text.contains(token) }) return ActionRisk.AUTHENTICATION
        if (consequential.any { token -> text.contains(token) }) return ActionRisk.CONSEQUENTIAL
        // A visible, labelled navigation/action control with no high-risk semantics is safe to inspect.
        // Unlabelled controls are mapped but not automatically explored.
        return if (text.isNotBlank()) ActionRisk.SAFE else ActionRisk.UNKNOWN
    }

    fun isExplorable(risk: ActionRisk): Boolean = risk == ActionRisk.SAFE

    fun looksSensitiveField(node: JSONObject): Boolean {
        val text = listOf(
            node.optString("text"),
            node.optString("contentDescription"),
            node.optString("resourceId"),
        ).joinToString(" ").lowercase()
        return listOf("password", "passcode", "pin", "cvv", "card number", "token", "secret", "otp", "2fa")
            .any(text::contains)
    }

    fun redactedNodeCopy(node: JSONObject): JSONObject {
        val copy = JSONObject(node.toString())
        if (looksSensitiveField(copy) || copy.optBoolean("password", false)) {
            copy.put("text", "<redacted>")
            copy.put("contentDescription", "<redacted>")
        } else {
            copy.put("text", copy.optString("text").take(180))
            copy.put("contentDescription", copy.optString("contentDescription").take(180))
        }
        return copy
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[_-]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
