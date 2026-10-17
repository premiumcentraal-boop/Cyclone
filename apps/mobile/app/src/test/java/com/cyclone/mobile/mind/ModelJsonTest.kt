package com.cyclone.mobile.mind

import com.cyclone.mobile.mind.one.PlanLines
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs against Android's own org.json (src/testAndroidJson), where optString on a JSON null is "null". */
class ModelJsonTest {
    @Test fun `on Android a JSON null reads as the word null, which is why model JSON drops nulls`() {
        val raw = JSONObject("""{"app":"WhatsApp","package":null}""")
        assertEquals("null", raw.optString("package"))           // Android's behaviour, and the bug
        assertEquals("null", raw.optString("package", "fallback"))
        val clean = ModelJson.parse("""{"app":"WhatsApp","package":null,"nested":{"a":null,"b":[null,"x"]}}""")
        assertEquals("", clean.optString("package"))
        assertFalse(clean.has("package"))
        assertFalse(clean.getJSONObject("nested").has("a"))
        assertEquals(1, clean.getJSONObject("nested").getJSONArray("b").length())
        assertEquals("WhatsApp", clean.optString("app"))
    }

    @Test fun `a model's optional tool arguments sent as null are simply not given`() {
        val call = MindToolCall("c1", "screen_find", """{"query":"Anna","expect":null,"why":null}""")
        val args = call.argumentsJson()!!
        assertEquals("", args.optString("expect"))
        assertEquals("", args.optString("why"))
        assertEquals("Anna", args.optString("query"))
    }

    @Test fun `a plan step with null fields never targets a control called null`() {
        val item = PlanLines.parse("""{"step":"tap","target":null,"note":null,"expect":null,"args":{"text":null}}""") { "s1" }
        val step = (item as PlanLines.Item.StepLine).step
        assertNull(step.target)
        assertNull(step.note)
        assertNull(step.expect)
        assertTrue(step.args.isEmpty())
    }
}
