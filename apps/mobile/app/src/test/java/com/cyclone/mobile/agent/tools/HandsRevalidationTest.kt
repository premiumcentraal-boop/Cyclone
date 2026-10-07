package com.cyclone.mobile.agent.tools

import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.gateway.GatewayElement
import com.cyclone.mobile.gateway.GatewayObservation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan 21 (Hands). A synthetic fixture shaped like the failed ChatGPT run: the "Reply to ChatGPT" composer is an
 * EditText inside a clickable input container, its label is its hint and changes once it has focus or text, and the
 * answer above repeats long labels. Replace with the real captured tree (Phase 0.1) when the owner records it.
 */
class HandsRevalidationTest {
    private val composerBox = box(40, 2000, 1000, 2140)

    @Test fun composerInsideAClickableContainerIsTheSameControl() {
        val before = screen("a", composer(label = "Reply to ChatGPT", obs = "a"), container("a"))
        val after = screen("b", composer(label = "Reply to ChatGPT", obs = "b"), container("b"))
        val report = CurrentTargetRevalidation.resolve(before, after, "semantic:a:composer")
        assertEquals(TargetDrift.MATCHED, report.status)
        assertEquals("semantic:b:composer", report.elementId)
    }

    @Test fun composerWhoseLabelChangedOnFocusStillMatchesByIdentity() {
        val before = screen("a", composer(label = "Reply to ChatGPT", obs = "a"), container("a"))
        val after = screen("b", composer(label = "Message", obs = "b", focused = true), container("b"))
        val report = CurrentTargetRevalidation.resolve(before, after, "semantic:a:composer")
        assertEquals(TargetDrift.MATCHED, report.status)
        assertEquals("semantic:b:composer", report.elementId)
    }

    @Test fun rawMirrorOfTheComposerWithItsTextAsLabelIsNotAmbiguous() {
        val before = screen("a", composer(label = "Reply to ChatGPT", obs = "a"), container("a"))
        val raw = composer(label = "What you're describing is", obs = "b", source = "raw_accessibility", id = "raw:b:n42")
        val after = screen("b", composer(label = "What you're describing is", obs = "b"), raw, container("b"),
            answer("b", 1), answer("b", 2))
        assertEquals(TargetDrift.MATCHED, CurrentTargetRevalidation.resolve(before, after, "semantic:a:composer").status)
    }

    @Test fun twoDistinctFieldsWithTheSameIdStayAmbiguous() {
        // The list re-rendered: neither field is where the old one was, so identity by id alone cannot pick one.
        val before = screen("a", composer(label = "Amount", obs = "a", resource = "id/amount", path = "/0/2", node = "a1"))
        val after = screen("b",
            composer(label = "", obs = "b", resource = "id/amount", path = "/0/3", node = "b1"),
            composer(label = "", obs = "b", resource = "id/amount", path = "/0/4", node = "b2", id = "semantic:b:second"))
        assertEquals(TargetDrift.AMBIGUOUS, CurrentTargetRevalidation.resolve(before, after, "semantic:a:composer").status)
    }

    @Test fun anOverlappingSiblingButtonStillFailsClosed() {
        val before = screen("a", composer(label = "Reply to ChatGPT", obs = "a"))
        val overlay = element("semantic:b:popup", "Allow", "button", "/0/9", "n90", composerBox, clickable = true)
        val after = screen("b", composer(label = "Reply to ChatGPT", obs = "b"), overlay)
        assertEquals(TargetDrift.AMBIGUOUS, CurrentTargetRevalidation.resolve(before, after, "semantic:a:composer").status)
    }

    @Test fun directTextReplacementIsNotInterceptedByASelectionToolbar() {
        val before = screen("a", composer(label = "Month", obs = "a"))
        val toolbar = element("semantic:b:popup", "Cut", "button", "/9/0", "n90", composerBox, clickable = true)
        val after = screen("b", composer(label = "Month", obs = "b"), toolbar)
        assertEquals(TargetDrift.MATCHED, CurrentTargetRevalidation.resolve(before, after, "semantic:a:composer",
            requiresTouchClearance = false).status)
        assertEquals(TargetDrift.AMBIGUOUS, CurrentTargetRevalidation.resolve(before, after, "semantic:a:composer").status)
    }

    @Test fun directTextReplacementStillRequiresUniqueIdentityAndScope() {
        val before = screen("a", composer(label = "Month", obs = "a", path = "/0/2", node = "a1"))
        val after = screen("b",
            composer(label = "Month", obs = "b", path = "/0/3", node = "b1"),
            composer(label = "Month", obs = "b", path = "/0/4", node = "b2", id = "semantic:b:second"))
        assertEquals(TargetDrift.AMBIGUOUS, CurrentTargetRevalidation.resolve(before, after, "semantic:a:composer",
            requiresTouchClearance = false).status)
        val otherApp = screen("b", composer(label = "Month", obs = "b")).let {
            it.copy(page = it.page.copy(packageName = "com.other.app")) }
        assertEquals(TargetDrift.SCOPE_MISMATCH, CurrentTargetRevalidation.resolve(before, otherApp, "semantic:a:composer",
            requiresTouchClearance = false).status)
    }

    @Test fun aSettingsRowAndItsSwitchAreOneControlNotAmbiguous() {
        // Alpha 91: Settings › Display › Auto-rotate screen: a clickable row with a Switch inside. Either one does the same.
        fun row(obs: String) = element("semantic:$obs:row", "Auto-rotate screen", "button", "/0/7", "n70", box(0, 1800, 1080, 1960), clickable = true)
        fun switch(obs: String) = element("semantic:$obs:switch", "Auto-rotate screen switch", "switch", "/0/7/2", "n72",
            box(900, 1840, 1040, 1920), clickable = true).also { it.evidence.put("checkable", true) }
        assertEquals(TargetDrift.MATCHED, CurrentTargetRevalidation.resolve(screen("a", row("a"), switch("a")),
            screen("b", row("b"), switch("b")), "semantic:a:row").status)
        assertEquals(TargetDrift.MATCHED, CurrentTargetRevalidation.resolve(screen("a", row("a"), switch("a")),
            screen("b", row("b"), switch("b")), "semantic:a:switch").status)
    }

    @Test fun aNestedButtonUnderThePressPointStillFailsClosed() {
        // A list item whose centre is covered by a different inner button ("Delete") stays ambiguous.
        fun item(obs: String) = element("semantic:$obs:item", "Note 1", "button", "/0/3", "n30", box(0, 400, 1080, 560), clickable = true)
        fun delete(obs: String) = element("semantic:$obs:delete", "Delete", "button", "/0/3/1", "n31", box(440, 420, 640, 540), clickable = true)
        assertEquals(TargetDrift.AMBIGUOUS, CurrentTargetRevalidation.resolve(screen("a", item("a"), delete("a")),
            screen("b", item("b"), delete("b")), "semantic:a:item").status)
    }

    @Test fun aClickablePageContainerAroundARowIsNotACompetingControl() {
        // Alpha 108, captured on a Pixel 8 (Android 16), Settings › Display and touch: `content_parent` is clickable and
        // scrollable over the whole page, and the Screen timeout row sits inside it. The press goes to the row.
        fun page(obs: String) = element("semantic:$obs:page", "Display and touch", "button", "0/0", "p0", box(0, 132, 1080, 2337),
            clickable = true, resource = "com.android.settings:id/content_parent")
        fun row(obs: String) = element("semantic:$obs:row", "After 30 minutes of inactivity Screen timeout", "button", "0/0/1/0", "r0",
            box(0, 279, 1080, 451), clickable = true)
        assertEquals(TargetDrift.MATCHED, CurrentTargetRevalidation.resolve(screen("a", page("a"), row("a")),
            screen("b", page("b"), row("b")), "semantic:a:row").status)
    }

    @Test fun aPageContainerDoesNotHideAnOverlappingSibling() {
        // The container exemption is for ancestors only: a different control over the row still fails closed.
        fun page(obs: String) = element("semantic:$obs:page", "Display and touch", "button", "0/0", "p0", box(0, 132, 1080, 2337), clickable = true)
        fun row(obs: String) = element("semantic:$obs:row", "Screen timeout", "button", "0/0/1/0", "r0", box(0, 279, 1080, 451), clickable = true)
        val popup = element("semantic:b:popup", "Allow", "button", "0/0/2", "q0", box(0, 300, 1080, 420), clickable = true)
        assertEquals(TargetDrift.AMBIGUOUS, CurrentTargetRevalidation.resolve(screen("a", page("a"), row("a")),
            screen("b", page("b"), row("b"), popup), "semantic:a:row").status)
    }

    @Test fun anInnerButtonIsStillNotHiddenByTheContainerRule() {
        // Pressing the outer item while a different inner button covers its centre stays ambiguous, even inside a page container.
        fun page(obs: String) = element("semantic:$obs:page", "Notes", "button", "/0", "p0", box(0, 132, 1080, 2337), clickable = true)
        fun item(obs: String) = element("semantic:$obs:item", "Note 1", "button", "/0/3", "n30", box(0, 400, 1080, 560), clickable = true)
        fun delete(obs: String) = element("semantic:$obs:delete", "Delete", "button", "/0/3/1", "n31", box(440, 420, 640, 540), clickable = true)
        assertEquals(TargetDrift.AMBIGUOUS, CurrentTargetRevalidation.resolve(screen("a", page("a"), item("a"), delete("a")),
            screen("b", page("b"), item("b"), delete("b")), "semantic:a:item").status)
        assertEquals(TargetDrift.MATCHED, CurrentTargetRevalidation.resolve(screen("a", page("a"), item("a"), delete("a")),
            screen("b", page("b"), item("b"), delete("b")), "semantic:a:delete").status)
    }

    @Test fun cyclonesOwnChromeNeverCompetesWithTheAppsControl() {
        val chrome = element("semantic:b:chrome", "Confirm", "button", "overlay/0/1", "c1", composerBox, clickable = true,
            resource = "com.cyclone.mobile:id/overlay_chrome_1")
        val after = screen("b", composer(label = "Reply to ChatGPT", obs = "b"), chrome)
        assertEquals(TargetDrift.MATCHED, CurrentTargetRevalidation.resolve(screen("a", composer(label = "Reply to ChatGPT", obs = "a")),
            after, "semantic:a:composer").status)
    }

    @Test fun aButtonWhoseLabelChangedIsStillGone() {
        // Only text boxes match through a changed label; buttons keep the strict rule.
        val button = element("semantic:a:send", "Send", "button", "/0/5/2", "n43", box(1000, 2000, 1060, 2140), clickable = true)
        val renamed = element("semantic:b:send", "Stop", "button", "/0/5/2", "n43", box(1000, 2000, 1060, 2140), clickable = true)
        assertEquals(TargetDrift.DISAPPEARED,
            CurrentTargetRevalidation.resolve(screen("a", button), screen("b", renamed), "semantic:a:send").status)
    }

    @Test fun ownerMissionTypingNeverCoversSecrets() {
        val field = JSONObject().put("editable", true).put("enabled", true).put("label", "Reply to ChatGPT")
        assertTrue(OwnerMissionTyping.allows(field, "Find better examples"))
        assertFalse(OwnerMissionTyping.allows(JSONObject(field.toString()).put("password", true), "x"))
        assertFalse(OwnerMissionTyping.allows(JSONObject(field.toString()).put("label", "Verification code"), "123456"))
        assertFalse(OwnerMissionTyping.allows(JSONObject(field.toString()).put("resourceId", "id/card_number"), "4111"))
        assertFalse(OwnerMissionTyping.allows(JSONObject().put("editable", false).put("label", "Send"), "x"))
        assertFalse(OwnerMissionTyping.allows(field, ""))
    }

    // ---- fixture -------------------------------------------------------------------------------------------------

    private fun composer(
        label: String,
        obs: String,
        focused: Boolean = false,
        source: String = "semantic",
        id: String = "semantic:$obs:composer",
        resource: String = "com.openai.chatgpt:id/composer",
        path: String = "/0/5/1",
        node: String = "n42",
    ) = element(id, label, "textbox", path, node, composerBox, editable = true, focused = focused, source = source, resource = resource)

    private fun container(obs: String) =
        element("semantic:$obs:container", "Message input", "button", "/0/5", "n41", box(20, 1980, 1060, 2160), clickable = true)

    private fun answer(obs: String, n: Int) =
        element("semantic:$obs:answer$n", "What you're describing is", "text", "/0/2/$n", "n2$n", box(40, 400 + n * 200, 1000, 580 + n * 200))

    private fun element(
        id: String,
        label: String,
        role: String,
        path: String,
        node: String,
        bounds: JSONObject,
        clickable: Boolean = false,
        editable: Boolean = false,
        focused: Boolean = false,
        source: String = "semantic",
        resource: String = "",
    ): GatewayElement {
        val evidence = JSONObject()
            .put("elementId", id).put("source", source).put("label", label).put("role", role)
            .put("semanticName", label.lowercase().replace(' ', '_')).put("resourceId", resource)
            .put("bounds", JSONObject(bounds.toString())).put("clickable", clickable).put("editable", editable)
            .put("focused", focused).put("enabled", true).put("visibleToUser", true)
        if (source == "raw_accessibility") evidence.put("id", node).put("path", path)
        else evidence.put("rawNodeId", node).put("rawPath", path)
        return GatewayElement(id, source, label, label.lowercase().replace(' ', '_'), role, evidence)
    }

    private fun box(l: Int, t: Int, r: Int, b: Int) = JSONObject().put("left", l).put("top", t).put("right", r).put("bottom", b)

    private fun screen(id: String, vararg elements: GatewayElement): GatewayObservation {
        val page = PageContext(
            pageKey = "chat", packageName = "com.openai.chatgpt", className = "com.openai.chatgpt.Main", title = "ChatGPT",
            structuralKey = "s", contentKey = "c", controls = emptyList(), observationCount = 1, firstSeenAt = 1, lastSeenAt = 1,
        )
        return GatewayObservation(id, 1, page, JSONObject().put("activity", "com.openai.chatgpt.Main"),
            elements.associateBy { it.id })
    }
}
