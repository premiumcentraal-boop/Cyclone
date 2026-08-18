package com.cyclone.mobile

import org.json.JSONArray
import org.json.JSONObject

data class PhoneToolDefinition(
    val name: String,
    val mutating: Boolean,
    val requiredCapability: String? = null,
    val description: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("mutating", mutating)
        .put("requiredCapability", requiredCapability ?: JSONObject.NULL)
        .put("description", description)
}

object PhoneToolRegistry {
    val definitions: List<PhoneToolDefinition> = listOf(
        PhoneToolDefinition("phone.observe", false, "accessibility", "Return normalized current UI state and clear the post-takeover fresh-observation gate"),
        PhoneToolDefinition("phone.screenshot", false, "screenshot", "Capture the screen or a cropped region; base64 is opt-in"),
        PhoneToolDefinition("phone.find", false, "accessibility", "Resolve stable selectors against the current normalized UI snapshot"),
        PhoneToolDefinition("phone.click", true, "accessibility", "Click a selector using accessibility action with gesture fallback"),
        PhoneToolDefinition("phone.long_press", true, "accessibility", "Long-press the center of a selected element"),
        PhoneToolDefinition("phone.tap", true, "accessibility", "Tap screen coordinates"),
        PhoneToolDefinition("phone.type", true, "accessibility", "Set text on a selected or focused editable element"),
        PhoneToolDefinition("phone.replace_text", true, "accessibility", "Replace text on a selected or focused editable element"),
        PhoneToolDefinition("phone.scroll", true, "accessibility", "Scroll a selected or first scrollable container"),
        PhoneToolDefinition("phone.swipe", true, "accessibility", "Dispatch a coordinate swipe gesture"),
        PhoneToolDefinition("phone.back", true, "accessibility", "Perform Android Back"),
        PhoneToolDefinition("phone.home", true, "accessibility", "Perform Android Home"),
        PhoneToolDefinition("phone.open_app", true, "app_launch", "Launch an installed package through its normal launcher intent"),
        PhoneToolDefinition("phone.open_notification", true, "notification_listener", "Open a retained notification through its content PendingIntent"),
        PhoneToolDefinition("phone.wait_for", false, "accessibility", "Wait locally for a selector/package/text/fingerprint condition without LLM polling"),
        PhoneToolDefinition("phone.assert", false, "accessibility", "Assert a current UI condition once"),
        PhoneToolDefinition("phone.get_notifications", false, "notification_listener", "Return retained notification metadata and action titles"),
        PhoneToolDefinition("phone.get_current_app", false, null, "Return last observed package/class/controller state"),
        PhoneToolDefinition("phone.get_clipboard", false, "clipboard", "Read clipboard text when Android permits it"),
        PhoneToolDefinition("phone.set_clipboard", true, "clipboard", "Write clipboard text"),
        PhoneToolDefinition("phone.share", true, "intent_launch", "Open Android ACTION_SEND for text, optionally scoped to a package"),
        PhoneToolDefinition("phone.launch_intent", true, "intent_launch", "Open an allowlisted URI scheme with ACTION_VIEW"),
        PhoneToolDefinition("phone.capabilities", false, null, "Return runtime capability availability and missing-permission states"),
    )

    private val byName = definitions.associateBy { it.name }

    fun definition(name: String): PhoneToolDefinition? = byName[name]
    fun isMutating(name: String): Boolean = byName[name]?.mutating == true
    fun toJson(): JSONArray = JSONArray().also { array -> definitions.forEach { array.put(it.toJson()) } }
}
