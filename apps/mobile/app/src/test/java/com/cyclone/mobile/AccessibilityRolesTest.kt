package com.cyclone.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AccessibilityRolesTest {
    @Test
    fun inferRoleEmitsTabForTabLayoutAndSelectedClockTab() {
        assertEquals(
            "tab",
            AccessibilityRoles.inferRole(
                className = "com.google.android.material.tabs.TabLayout.TabView",
                clickable = true,
                selected = true,
                text = "Timer",
            ),
        )
        assertEquals(
            "tab",
            AccessibilityRoles.inferRole(
                className = "android.widget.TextView",
                clickable = true,
                selected = true,
                text = "Alarm",
                parentClassName = "com.google.android.material.tabs.TabLayout",
            ),
        )
        assertFalse(
            AccessibilityRoles.inferRole(
                className = "android.widget.TextView",
                text = "Timer",
            ) == "tab",
        )
    }

    @Test
    fun foldTalkBackHostsPromotesDescendantLabelOntoClickableRow() {
        val host = node("host", "0/1", null, listOf("leaf"), "", "generic", clickable = true, className = "androidx.preference.Preference")
        val leaf = node("leaf", "0/1/0", "host", emptyList(), "Network & internet", "text", clickable = false)
        val folded = AccessibilityRoles.foldTalkBackHosts(listOf(host, leaf))
        val promoted = folded.first { it.id == "host" }
        assertEquals("Network & internet", promoted.text)
        assertTrue(promoted.clickable)
        assertEquals("row", promoted.role)
    }

    @Test
    fun emptySelectorIsHardError() {
        val snap = UiSnapshot(
            packageName = "com.test",
            className = "Act",
            screenWidth = 100,
            screenHeight = 100,
            timestampMs = 1,
            fingerprint = "fp",
            controller = "agent",
            windows = emptyList(),
            nodes = listOf(node("top", "0", null, emptyList(), "chrome", "text")),
        )
        val empty = ElementSelector.fromJson(JSONObject())
        assertTrue(empty.isEmpty())
        try {
            SelectorEngine.resolve(snap, empty, 8)
            fail("empty selector must hard-error")
        } catch (error: EmptySelectorException) {
            assertTrue(error.message!!.contains("empty selector"))
        }
        val ignoredId = ElementSelector.fromJson(JSONObject().put("id", "semantic:obs:x"))
        assertTrue(ignoredId.isEmpty())
    }

    private fun node(
        id: String,
        path: String,
        parent: String?,
        children: List<String>,
        text: String,
        role: String,
        clickable: Boolean = false,
        className: String = "android.view.View",
    ) = UiNodeSnapshot(
        id = id,
        path = path,
        parentId = parent,
        childIds = children,
        depth = path.count { it == '/' },
        windowId = 1,
        className = className,
        role = role,
        text = text,
        contentDescription = "",
        resourceId = "",
        bounds = UiBounds(0, 0, 10, 10),
        clickable = clickable,
        longClickable = false,
        editable = false,
        scrollable = false,
        enabled = true,
        selected = false,
        checked = false,
        checkable = false,
        focused = false,
        focusable = clickable,
        visibleToUser = true,
        actions = if (clickable) listOf("ACTION_CLICK") else emptyList(),
    )
}
