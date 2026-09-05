package com.cyclone.mobile.policy

/** Semantic intent of the selected control. Context can refine an action, but cannot replace it. */
enum class ActionIntent {
    PAY,
    SEND_EXTERNAL,
    DELETE,
    GRANT_PERMISSION,
    DENY_SITE_NOTIFICATION,
    ALLOW_SITE_NOTIFICATION,
    DISMISS_NUISANCE,
    SAFE_OTHER,
}

object ActionIntentClassifier {
    private val denyNotification = setOf("block", "deny", "don't allow", "do not allow", "not now", "no thanks")
    private val allowNotification = setOf("allow", "enable notifications", "turn on notifications")
    private val nuisanceDismiss = setOf("close", "dismiss", "maybe later", "no thanks", "not now")
    private val pay = setOf("pay", "payment", "checkout", "place order", "buy now", "purchase", "confirm order", "complete purchase")
    private val delete = setOf("delete", "remove", "erase", "factory reset", "wipe data", "move to bin", "move to trash", "send to bin", "send to trash", "throw away")
    private val deleteMoveToBinOrTrash = Regex("""(?:move|send)(?:\s+\S+){0,6}\s+to\s+(?:bin|trash)""")
    private val send = setOf("send", "send message", "send email", "post", "publish")
    private val grant = setOf("grant", "allow access", "give permission", "authorize app", "trust this", "enable access")

    fun classify(action: String, labels: List<String> = emptyList()): ActionIntent {
        val actionText = normalize(action)
        val labelText = labels.map(::normalize).filter(String::isNotBlank)
        val selected = labelText.firstOrNull().orEmpty()
        val context = labelText.drop(1).joinToString(" ")
        val combined = listOf(actionText, selected, context).joinToString(" ")
        val notificationContext = listOf(selected, context).joinToString(" ").let { text ->
            "notification" in text || "notifications" in text || "wants to send you" in text
        }

        // The selected control has priority. A surrounding sentence such as "wants to send you
        // notifications" must never turn a Block button into an external SEND action.
        if (notificationContext && denyNotification.any { selected == it || selected.startsWith("$it ") }) {
            return ActionIntent.DENY_SITE_NOTIFICATION
        }
        if (notificationContext && allowNotification.any { selected == it || selected.startsWith("$it ") }) {
            return ActionIntent.ALLOW_SITE_NOTIFICATION
        }

        if (pay.any { selected.contains(it) || actionText.contains(it) }) return ActionIntent.PAY
        if (delete.any { selected.contains(it) || actionText.contains(it) } || deleteMoveToBinOrTrash.containsMatchIn(selected)) {
            return ActionIntent.DELETE
        }
        if (send.any { selected == it || selected.startsWith("$it ") || actionText == it }) return ActionIntent.SEND_EXTERNAL
        if (grant.any { selected.contains(it) || actionText.contains(it) }) return ActionIntent.GRANT_PERMISSION

        // Fall back to full context only for phrases whose semantic target is intrinsically
        // consequential. Generic words inside explanatory prose are deliberately ignored.
        if (pay.any(combined::contains)) return ActionIntent.PAY
        if (delete.any(combined::contains) || deleteMoveToBinOrTrash.containsMatchIn(combined)) return ActionIntent.DELETE
        if (grant.any(combined::contains)) return ActionIntent.GRANT_PERMISSION
        if (nuisanceDismiss.any { selected == it || selected.startsWith("$it ") }) return ActionIntent.DISMISS_NUISANCE
        return ActionIntent.SAFE_OTHER
    }

    fun gateClass(intent: ActionIntent): GateClass? = when (intent) {
        ActionIntent.PAY -> GateClass.PAY
        ActionIntent.SEND_EXTERNAL -> GateClass.SEND
        ActionIntent.DELETE -> GateClass.DELETE
        ActionIntent.GRANT_PERMISSION, ActionIntent.ALLOW_SITE_NOTIFICATION -> GateClass.GRANT
        ActionIntent.DENY_SITE_NOTIFICATION, ActionIntent.DISMISS_NUISANCE, ActionIntent.SAFE_OTHER -> null
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[_-]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
