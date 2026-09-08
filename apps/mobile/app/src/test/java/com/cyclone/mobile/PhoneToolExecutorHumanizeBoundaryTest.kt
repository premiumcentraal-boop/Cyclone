package com.cyclone.mobile

import com.cyclone.mobile.gesture.HumanizePreference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFailsWith
import org.junit.Test

class PhoneToolExecutorHumanizeBoundaryTest {
    @Test
    fun `omitted humanize remains backward compatible auto`() {
        val params = JSONObject()
        val raw = params.optString("humanize").takeIf { params.has("humanize") }
        assertEquals(HumanizePreference.AUTO, HumanizePreference.parse(raw))
    }

    @Test
    fun `explicit valid values remain bounded`() {
        listOf("auto", "off", "light", "normal").forEach { value ->
            val params = JSONObject().put("humanize", value)
            assertEquals(value.uppercase(), HumanizePreference.parse(params.optString("humanize")).name)
        }
    }

    @Test
    fun `explicit malformed strings fail closed`() {
        listOf("", "natural", "max", "arbitrary-path").forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                HumanizePreference.parse(JSONObject().put("humanize", value).optString("humanize"))
            }
        }
    }

    @Test
    fun `wrong json types cannot become valid profile values`() {
        listOf<Any>(true, 1, 2.5, JSONObject().put("mode", "normal"), org.json.JSONArray().put("light"))
            .forEach { value ->
                val coerced = JSONObject().put("humanize", value).optString("humanize")
                assertFailsWith<IllegalArgumentException> { HumanizePreference.parse(coerced) }
            }
    }
}
