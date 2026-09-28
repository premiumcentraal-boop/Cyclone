package com.cyclone.mobile.gateway

import com.cyclone.mobile.manual.Anchor
import com.cyclone.mobile.manual.AnchorKind
import com.cyclone.mobile.manual.ChromeProof
import com.cyclone.mobile.manual.CoreKind
import com.cyclone.mobile.manual.dictionary.AppDictionary
import com.cyclone.mobile.manual.dictionary.DictEntry
import com.cyclone.mobile.manual.dictionary.EntryStatus
import com.cyclone.mobile.manual.dictionary.Organizer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayV5ManualAdapterTest {
    private var dict = AppDictionary("com.example")

    @Before
    fun setUp() {
        val anchor = Anchor(AnchorKind.LIST, "screen:list:0123456789abcdef", "abcdef12", screenTitle = "Messages", searchable = true, searchLabel = "Search")
        val messages = DictEntry("set:messages", CoreKind.CONVERSATION, "Messages", ChromeProof.LEXICON, status = EntryStatus.CONFIRMED, anchors = listOf(anchor), observations = 2)
        val primary = DictEntry("set:primary", CoreKind.CONVERSATION, "Primary", ChromeProof.LEXICON, parentId = "set:messages", status = EntryStatus.CONFIRMED,
            anchors = listOf(anchor.copy(kind = AnchorKind.VIEW, position = 0, siblings = listOf("General"))), observations = 2)
        val general = DictEntry("set:general", CoreKind.CONVERSATION, "General", ChromeProof.LEXICON, parentId = "set:messages", anchors = listOf(anchor.copy(kind = AnchorKind.VIEW, position = 1)), observations = 1)
        dict = AppDictionary("com.example", mapOf(messages.id to messages, primary.id to primary, general.id to general))
        GatewayV5ManualAdapter.load = { _ -> dict }
        GatewayV5ManualAdapter.editor = { _, edit -> Organizer.edit(dict, edit, 1_000).also { dict = it } }
        GatewayV5ManualAdapter.label = { _ -> "Example" }
        GatewayV5ManualAdapter.version = { _ -> "1.0" }
        GatewayV5ManualAdapter.models = { Triple("anthropic/claude-fable-5.1", "Claude Fable 5.1", true) to listOf(Triple("anthropic/claude-fable-5.1", "Claude Fable 5.1", true), Triple("z-ai/glm-5.3-flash", "GLM 5.3 Flash", false)) }
    }

    @After
    fun tearDown() {
        GatewayV5ManualAdapter.load = { _ -> AppDictionary("com.example") }
    }

    private fun call(op: String, args: String = "{}") = GatewayV5ManualAdapter.dispatch(op, JSONObject(args))

    @Test
    fun getShowsTheTreeGatesGlossaryAndHealth() {
        val out = call("dictionary.get", """{"placeId":"package:com.example"}""")
        val entries = out.getJSONArray("entries")
        assertEquals(3, entries.length())
        val general = (0 until entries.length()).map { entries.getJSONObject(it) }.single { it.getString("id") == "set:general" }
        assertEquals("candidate", general.getString("status"))
        assertEquals("Conversation › Messages › General", general.getString("path"))
        assertTrue(general.getJSONArray("failedGates").toString().contains("seen_twice"))
        assertTrue(out.getString("glossary").contains("set:primary = Conversation › Messages › Primary"))
        assertEquals("No decisions yet.", out.getJSONObject("jev").getString("summary"))
    }

    @Test
    fun theOwnerEditsThroughTheOrganizerRules() {
        call("dictionary.edit", """{"placeId":"package:com.example","action":"lock","id":"set:messages"}""")
        assertEquals(EntryStatus.LOCKED, dict.entries.getValue("set:messages").status)
        val refused = runCatching { call("dictionary.edit", """{"placeId":"package:com.example","action":"merge","id":"set:messages","into":"set:primary"}""") }
        assertTrue(refused.exceptionOrNull() is GatewayProtocolException)
        val bad = runCatching { call("dictionary.edit", """{"placeId":"package:com.example","action":"drop_table","id":"set:messages"}""") }
        assertTrue(bad.exceptionOrNull() is GatewayProtocolException)
        val extra = runCatching { call("dictionary.get", """{"placeId":"package:com.example","members":true}""") }
        assertTrue(extra.exceptionOrNull() is GatewayProtocolException)
    }

    @Test
    fun modelsListOffersThePhonesModelsWithoutKeys() {
        val out = call("models.list")
        assertEquals("anthropic/claude-fable-5.1", out.getJSONObject("active").getString("id"))
        assertEquals(2, out.getJSONArray("models").length())
        assertFalse(out.toString().contains("sk-or"))
    }
}
