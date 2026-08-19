package com.cyclone.mobile.applearner

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageAwarenessEngineTest {
    @Test
    fun dynamicValuesDoNotCreateNewPageIdentity() {
        val first = PageSignatureEngine.fromSnapshot(settingsSnapshot("Battery 83%", "10:31", "Order 918281"))
        val second = PageSignatureEngine.fromSnapshot(settingsSnapshot("Battery 82%", "10:32", "Order 392918"))

        assertEquals(first.pageKey, second.pageKey)
        assertEquals(first.structuralKey, second.structuralKey)
        assertNotEquals(first.contentKey, "")
        assertTrue(first.controls.any { it.label == "Battery" })
    }

    @Test
    fun meaningfulMenuStructureCreatesDifferentPageIdentity() {
        val settings = PageSignatureEngine.fromSnapshot(settingsSnapshot("Battery 83%", "10:31", "Order 918281"))
        val details = PageSignatureEngine.fromSnapshot(JSONObject()
            .put("package", "com.android.settings")
            .put("class", "com.android.settings.Settings\$BatteryDashboardActivity")
            .put("nodes", JSONArray()
                .put(node("android:id/title", "Battery usage", "text", false, "0/0"))
                .put(node("com.android.settings:id/battery_usage", "Battery usage", "button", true, "0/1"))
                .put(node("com.android.settings:id/battery_saver", "Battery Saver", "switch", true, "0/2"))))

        assertNotEquals(settings.pageKey, details.pageKey)
    }

    @Test
    fun sensitiveActionParamsAreRedacted() {
        val raw = JSONObject()
            .put("selector", JSONObject().put("resourceId", "id/password"))
            .put("value", "super-secret")
        val safe = PageSignatureEngine.safeParams("phone.type", raw)
        assertEquals(true, safe.getBoolean("redacted"))
        assertEquals(1, safe.length())
    }

    private fun settingsSnapshot(dynamicBattery: String, time: String, order: String): JSONObject = JSONObject()
        .put("package", "com.android.settings")
        .put("class", "com.android.settings.Settings")
        .put("nodes", JSONArray()
            .put(node("android:id/title", "Settings", "text", false, "0/0"))
            .put(node("com.android.settings:id/battery", "Battery", "button", true, "0/1"))
            .put(node("com.android.settings:id/network", "Network & internet", "button", true, "0/2"))
            .put(node("com.example:id/dynamic", dynamicBattery, "text", false, "0/3"))
            .put(node("com.example:id/time", time, "text", false, "0/4"))
            .put(node("com.example:id/order", order, "text", false, "0/5")))

    private fun node(resource: String, text: String, role: String, clickable: Boolean, path: String): JSONObject = JSONObject()
        .put("resourceId", resource)
        .put("text", text)
        .put("contentDescription", "")
        .put("role", role)
        .put("class", "android.widget.TextView")
        .put("path", path)
        .put("depth", path.count { it == '/' })
        .put("visibleToUser", true)
        .put("clickable", clickable)
        .put("editable", false)
        .put("scrollable", false)
        .put("longClickable", false)
        .put("checkable", false)
        .put("actions", JSONArray(if (clickable) listOf("ACTION_CLICK") else emptyList<String>()))
}
