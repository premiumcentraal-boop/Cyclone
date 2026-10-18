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

    /** Alpha 144: an Instagram-like feed: a fixed tab bar and a scrolling list of posts that change by themselves. */
    private fun feed(posts: List<String>, profileSelected: Boolean): JSONObject {
        val nodes = JSONArray()
            .put(node("com.instagram.android:id/action_bar_title", "Instagram", "text", false, "0/0"))
            .put(node("com.instagram.android:id/feed_list", "", "list", false, "0/1").put("scrollable", true))
        posts.forEachIndexed { i, p -> nodes.put(node("com.instagram.android:id/row_user", p, "button", true, "0/1/$i")) }
        listOf("Home", "Reels", "Profile").forEachIndexed { i, tab ->
            nodes.put(node("com.instagram.android:id/${tab.lowercase()}_tab", tab, "tab", true, "0/2/$i")
                .put("selected", if (tab == "Profile") profileSelected else tab == "Home"))
        }
        return JSONObject().put("package", "com.instagram.android").put("class", "com.instagram.mainactivity.MainActivity").put("nodes", nodes)
    }

    @Test
    fun aFeedThatMovesByItselfKeepsItsScreenIdentityAnotherTabDoesNot() {
        val before = PageSignatureEngine.fromSnapshot(feed(listOf("anna liked this", "ben · 3 min"), profileSelected = false))
        val moved = PageSignatureEngine.fromSnapshot(feed(listOf("carl posted", "dina · 4 min", "sponsored"), profileSelected = false))
        val profile = PageSignatureEngine.fromSnapshot(feed(listOf("anna liked this"), profileSelected = true))
        assertTrue(before.screenIdentity.isNotBlank())
        // The page key moved with the feed: it could never prove a tap (field test 143).
        assertNotEquals(before.pageKey, moved.pageKey)
        assertEquals(before.screenIdentity, moved.screenIdentity)
        assertNotEquals(before.screenIdentity, profile.screenIdentity)
        // Any script counts, and a counter doesn't move it.
        val arabic = PageSignatureEngine.fromSnapshot(feed(listOf("x"), false).also { it.getJSONArray("nodes").getJSONObject(0).put("text", "الملف 3") })
        val arabic2 = PageSignatureEngine.fromSnapshot(feed(listOf("y"), false).also { it.getJSONArray("nodes").getJSONObject(0).put("text", "الملف 4") })
        val other = PageSignatureEngine.fromSnapshot(feed(listOf("x"), false).also { it.getJSONArray("nodes").getJSONObject(0).put("text", "الإعدادات") })
        assertEquals(arabic.screenIdentity, arabic2.screenIdentity)
        assertNotEquals(arabic.screenIdentity, other.screenIdentity)
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

    @Test
    fun unlabeledActionableContainerInheritsDescendantLabel() {
        val parent = node("", "", "button", true, "0/1")
        val child = node("android:id/title", "Apps", "text", false, "0/1/0")
        val page = PageSignatureEngine.fromSnapshot(JSONObject()
            .put("package", "com.android.settings")
            .put("class", "com.android.settings.Settings")
            .put("nodes", JSONArray().put(parent).put(child)))

        val apps = page.controls.single { it.label == "Apps" }
        assertEquals("Apps", apps.selector.getString("descendantText"))
        assertEquals(true, apps.selector.getBoolean("clickable"))
    }


    @Test
    fun calculatorDigitsIncludingSevenSurviveHiddenVisibleFlagAndStayUnique() {
        val nodes = JSONArray()
        for (digit in 0..9) {
            nodes.put(
                node("com.google.android.calculator:id/digit_$digit", digit.toString(), "button", true, "0/1/2/3/4/$digit")
                    .put("id", "digit-$digit")
                    .put("visibleToUser", false)
                    .put("contentDescription", digit.toString())
                    .put("bounds", JSONObject().put("left", digit * 80).put("top", 800).put("right", digit * 80 + 70).put("bottom", 900)),
            )
        }
        val page = PageSignatureEngine.fromSnapshot(
            JSONObject()
                .put("package", "com.google.android.calculator")
                .put("class", "com.android.calculator2.Calculator")
                .put("nodes", nodes),
        )
        val sevens = page.controls.filter { it.label == "7" }
        assertEquals(1, sevens.size)
        assertTrue(page.controls.any { it.label == "0" })
        assertTrue(page.controls.any { it.label == "9" })
        assertEquals(10, page.controls.map { it.label }.distinct().size)
    }

    @Test
    fun clockTimerVersusAlarmHaveDifferentPageKeys() {
        val timer = PageSignatureEngine.fromSnapshot(clockSnapshot("Timer"))
        val alarm = PageSignatureEngine.fromSnapshot(clockSnapshot("Alarm"))
        assertNotEquals(timer.pageKey, alarm.pageKey)
    }

    private fun clockSnapshot(selectedTab: String): JSONObject = JSONObject()
        .put("package", "com.google.android.deskclock")
        .put("class", "com.android.deskclock.DeskClock")
        .put("nodes", JSONArray()
            .put(node("android:id/tab", "Alarm", "tab", true, "0/0").put("selected", selectedTab == "Alarm"))
            .put(node("android:id/tab", "Clock", "tab", true, "0/1").put("selected", selectedTab == "Clock"))
            .put(node("android:id/tab", "Timer", "tab", true, "0/2").put("selected", selectedTab == "Timer"))
            .put(node("android:id/chrome", "More options", "button", true, "0/3")))

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
