package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.UiBounds
import com.cyclone.mobile.UiNodeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickGateLabelPriorityTest {
    @Test
    fun activatedBlockControlOutranksNotificationModalProse() {
        val chosen = node("modal", "www.ad.nl wants to send you notifications", clickable = false)
        val activation = node("block", "Block", clickable = true)
        val labels = ClickGateIntercept.labelsFor(chosen, activation)
        assertEquals("Block", labels.first())
        val decision = ClickGateIntercept.decide("phone.click", labels, OverlayChromeState.LIVE, useRuntimeApproval = false)
        assertTrue(decision.performClick)
        assertFalse(decision.enterGate)
        assertNull(decision.gateClass)
    }

    @Test
    fun activatedAllowControlStillRequiresGrant() {
        val chosen = node("modal", "www.ad.nl wants to send you notifications", clickable = false)
        val activation = node("allow", "Allow", clickable = true)
        val labels = ClickGateIntercept.labelsFor(chosen, activation)
        assertEquals("Allow", labels.first())
        val decision = ClickGateIntercept.decide("phone.click", labels, OverlayChromeState.LIVE, useRuntimeApproval = false)
        assertFalse(decision.performClick)
        assertEquals(OverlayGateClass.GRANT, decision.gateClass)
    }

    private fun node(id: String, text: String, clickable: Boolean) = UiNodeSnapshot(
        id = id,
        path = "0/$id",
        parentId = "0",
        childIds = emptyList(),
        depth = 1,
        windowId = 1,
        className = "android.view.View",
        role = if (clickable) "button" else "generic",
        text = text,
        contentDescription = "",
        resourceId = "",
        bounds = UiBounds(0, 0, 100, 40),
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
