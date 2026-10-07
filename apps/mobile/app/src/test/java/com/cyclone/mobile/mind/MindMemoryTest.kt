package com.cyclone.mobile.mind

import com.cyclone.mobile.UiBounds
import com.cyclone.mobile.UiNodeSnapshot
import com.cyclone.mobile.ui.overlay.ClickGateIntercept
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MindMemoryTest {
    private fun memory(clock: () -> Long = { 1_000 }) = MindMemory(Files.createTempFile("mind", ".json").toFile(), clock, limit = 3)

    @Test fun storesDeduplicatesAndPersists() {
        val file = Files.createTempFile("mind", ".json").toFile()
        val a = MindMemory(file)
        assertTrue(a.remember("Owner prefers Dutch replies") is MindMemory.Saved.Stored)
        assertTrue(a.remember("owner prefers dutch replies") is MindMemory.Saved.Updated)
        assertEquals(1, MindMemory(file).all().size)
    }

    @Test fun refusesSecrets() {
        val m = memory()
        listOf("wachtwoord is Zomer2024", "card 4111 1111 1111 1111", "the verification code is 123456",
            "api key sk-or-v1-abcdefghijk", "IBAN NL91ABNA0417164300").forEach {
            assertTrue(it, m.remember(it) is MindMemory.Saved.Refused)
        }
        assertTrue(m.all().isEmpty())
    }

    @Test fun keepsTheNewestWithinTheLimit() {
        var now = 0L
        val m = memory { now }
        (1..5).forEach { now = it * 10L; m.remember("fact number $it about apps") }
        assertEquals(3, m.all().size)
        assertTrue(m.all().none { it.text.endsWith("1 about apps") })
    }

    @Test fun searchRanksByOverlap() {
        val m = memory()
        m.remember("Timer app is Google Clock")
        m.remember("The owner's Gmail is jan@example.com")
        assertEquals("The owner's Gmail is jan@example.com", m.search("which gmail account").first().text)
        assertTrue(m.search("zzz").isEmpty())
    }
}

class PromptCachingTest {
    @Test fun anthropicRoutesGetTwoBreakpoints() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "sys"))
            .put(JSONObject().put("role", "user").put("content", "goal"))
            .put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "c").put("content", "result"))
            .put(JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", "shot"))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:x")))))
        assertTrue(OpenRouterMindModel.promptCaching("anthropic/claude-sonnet-5"))
        assertFalse(OpenRouterMindModel.promptCaching("openai/gpt-6"))
        val cached = OpenRouterMindModel.withCacheBreakpoints(messages)
        assertEquals(2, Regex("cache_control").findAll(cached.toString()).count())
        assertTrue(cached.getJSONObject(0).get("content") is JSONArray)
        assertEquals("goal", cached.getJSONObject(1).getString("content"))
        assertTrue(cached.getJSONObject(4).getJSONArray("content").getJSONObject(0).has("cache_control"))
        assertFalse("input is not mutated", messages.toString().contains("cache_control"))
    }
}

class PointLabelsTest {
    private fun node(id: String, path: String, depth: Int, bounds: UiBounds, text: String = "", clickable: Boolean = false) = UiNodeSnapshot(
        id = id, path = path, parentId = null, childIds = emptyList(), depth = depth, windowId = 1, className = "View",
        role = if (clickable) "button" else "text", text = text, contentDescription = "", resourceId = "", bounds = bounds,
        clickable = clickable, longClickable = false, editable = false, scrollable = false, enabled = true, selected = false,
        checked = false, checkable = false, focused = false, focusable = false, visibleToUser = true)

    @Test fun aTapOnAButtonsChildCarriesTheButtonsWords() {
        val nodes = listOf(
            node("root", "0", 0, UiBounds(0, 0, 1080, 2400)),
            node("send", "0/1", 1, UiBounds(900, 2200, 1060, 2300), clickable = true),
            node("icon", "0/1/0", 2, UiBounds(920, 2210, 980, 2290)),
            node("label", "0/1/1", 2, UiBounds(980, 2210, 1050, 2290), text = "Send"),
        )
        assertTrue("Send" in ClickGateIntercept.labelsAtPoint(nodes, 950, 2250))
        assertTrue(ClickGateIntercept.labelsAtPoint(nodes, 10, 10).isEmpty())
    }

    @Test fun aFloatingButtonSpeaksForTheTapNotTheDeeperRowUnderIt() {
        // Alpha 109, Gmail: the inbox list is deep in the tree; the Compose button floats over it as a later sibling.
        val nodes = listOf(
            node("root", "0", 0, UiBounds(0, 0, 1080, 2400)),
            node("list", "0/1", 1, UiBounds(0, 300, 1080, 2300)),
            node("row", "0/1/4", 2, UiBounds(0, 1900, 1080, 2150), clickable = true),
            node("subject", "0/1/4/2", 3, UiBounds(200, 1950, 1000, 2010), text = "Your parcel was posted"),
            node("snippet", "0/1/4/3", 3, UiBounds(200, 2010, 1000, 2100), text = "Send us your feedback"),
            node("compose", "0/3", 1, UiBounds(700, 1980, 1040, 2120), text = "Compose", clickable = true),
        )
        val labels = ClickGateIntercept.labelsAtPoint(nodes, 850, 2050)
        assertEquals("Compose", labels.first())
        assertFalse(labels.any { it.contains("posted") || it.contains("Send us") })
        assertEquals(null, com.cyclone.mobile.policy.GateClassifier.classify("phone.tap_point", labels))
        // The row itself, away from the button, still carries its own words.
        assertTrue(ClickGateIntercept.labelsAtPoint(nodes, 300, 1980).any { it.contains("posted") })
    }
}

/** Alpha 109: the review only judges what a tap does on the page it lands on. */
class CurrentPageReviewTest {
    private fun node(id: String, path: String, window: Int, bounds: UiBounds, text: String = "", clickable: Boolean = false) = UiNodeSnapshot(
        id = id, path = path, parentId = null, childIds = emptyList(), depth = path.count { it == '/' }, windowId = window,
        className = "View", role = if (clickable) "button" else "text", text = text, contentDescription = "", resourceId = "",
        bounds = bounds, clickable = clickable, longClickable = false, editable = false, scrollable = false, enabled = true,
        selected = false, checked = false, checkable = false, focused = false, focusable = false, visibleToUser = true)

    private fun window(id: Int, layer: Int, bounds: UiBounds) =
        com.cyclone.mobile.UiWindowSnapshot(id, "", 1, layer, active = false, focused = false, bounds = bounds)

    @Test fun aDialogOnTopIsThePageAndTheButtonBehindItIsNotReviewed() {
        // The app (window 900, low id but HIGH path number) shows "Delete all" behind a dialog (window 5) that is on top.
        val nodes = listOf(
            node("app", "w900/0", 900, UiBounds(0, 0, 1080, 2400)),
            node("del", "w900/0/7", 900, UiBounds(100, 1100, 980, 1300), text = "Delete all", clickable = true),
            node("dlg", "w5/0", 5, UiBounds(100, 900, 980, 1500)),
            node("ok", "w5/0/1", 5, UiBounds(150, 1150, 900, 1250), text = "Got it", clickable = true),
        )
        val windows = listOf(window(900, 1, UiBounds(0, 0, 1080, 2400)), window(5, 2, UiBounds(100, 900, 980, 1500)))
        val labels = ClickGateIntercept.labelsAtPoint(nodes, 500, 1200, windows)
        assertEquals(listOf("Got it"), labels)
        assertEquals(null, com.cyclone.mobile.policy.GateClassifier.classify("phone.tap_point", labels))
        // Outside the dialog the app is the page again, and its own action is reviewed as before.
        val windows2 = listOf(window(900, 1, UiBounds(0, 0, 1080, 2400)), window(5, 2, UiBounds(100, 900, 980, 1100)))
        assertTrue("Delete all" in ClickGateIntercept.labelsAtPoint(nodes, 500, 1200, windows2))
    }

    @Test fun aButtonOnlyCarriesItsOwnChildrenNotANeighbourWithALongerNumber() {
        val nodes = listOf(
            node("root", "0", 1, UiBounds(0, 0, 1080, 2400)),
            node("open", "0/1", 1, UiBounds(0, 100, 1080, 300), clickable = true),
            node("openLabel", "0/1/0", 1, UiBounds(0, 100, 500, 300), text = "Open"),
            node("neighbour", "0/10", 1, UiBounds(0, 1000, 1080, 1200), text = "Delete forever", clickable = true),
        )
        val labels = ClickGateIntercept.labelsAtPoint(nodes, 700, 200)
        assertEquals(listOf("Open"), labels)
    }

    @Test fun whenThePageIsUnknownTheReviewStillSeesEverythingUnderTheFinger() {
        // No window list (or a window with nothing under the point): fall back to all nodes, so the review gates more, never less.
        val nodes = listOf(
            node("root", "0", 1, UiBounds(0, 0, 1080, 2400)),
            node("pay", "0/2", 1, UiBounds(0, 2000, 1080, 2200), text = "Pay now", clickable = true),
        )
        assertTrue("Pay now" in ClickGateIntercept.labelsAtPoint(nodes, 500, 2100))
        val emptyTop = listOf(window(1, 1, UiBounds(0, 0, 1080, 2400)), window(77, 9, UiBounds(0, 1900, 1080, 2400)))
        assertTrue("Pay now" in ClickGateIntercept.labelsAtPoint(nodes, 500, 2100, emptyTop))
    }

    @Test fun theReviewNeverChangesWhatTheModelSees() {
        val nodes = listOf(
            node("root", "0", 1, UiBounds(0, 0, 1080, 2400)),
            node("row", "0/1", 1, UiBounds(0, 1900, 1080, 2150), clickable = true),
            node("snippet", "0/1/3", 1, UiBounds(200, 2010, 1000, 2100), text = "Send us your feedback"),
            node("compose", "0/3", 1, UiBounds(700, 1980, 1040, 2120), text = "Compose", clickable = true),
        )
        val before = nodes.toList()
        ClickGateIntercept.labelsAtPoint(nodes, 850, 2050)
        assertEquals(before, nodes)
        assertTrue(nodes.any { it.text == "Send us your feedback" })
    }
}
