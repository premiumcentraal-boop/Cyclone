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
    private val benignNavigation = setOf(
        "home", "orders", "order", "invoice", "invoices", "tracking", "track", "search", "filter",
        "back", "next", "previous", "details", "help", "support", "settings", "account", "profile",
        "library", "history", "delivery", "deliveries", "battery", "display", "network", "about",
    )

    fun classify(label: String, resourceId: String = "", contentDescription: String = ""): ActionRisk {
        val text = listOf(label, resourceId.substringAfterLast('/'), contentDescription)
            .joinToString(" ").lowercase().replace(Regex("[_-]+"), " ")
        if (authentication.any { token -> text.contains(token) }) return ActionRisk.AUTHENTICATION
        if (consequential.any { token -> text.contains(token) }) return ActionRisk.CONSEQUENTIAL
        if (benignNavigation.any { token -> text.contains(token) }) return ActionRisk.SAFE
        return ActionRisk.UNKNOWN
    }

    fun isExplorable(risk: ActionRisk): Boolean = risk == ActionRisk.SAFE || risk == ActionRisk.UNKNOWN

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
}
