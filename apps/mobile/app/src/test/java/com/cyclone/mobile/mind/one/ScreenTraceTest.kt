package com.cyclone.mobile.mind.one

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTraceTest {
    private val screen = StepScreen(
        packageName = "com.google.android.calculator",
        appLabel = "Calculator",
        pageKey = "main",
        fingerprint = "abc123",
        controls = listOf(
            StepControl("e1", "7", "button"),
            StepControl("e2", "Display", "text", editable = true, focused = true, value = "756"),
        ),
        texts = listOf("756", "Show more history"),
        weak = false,
        sensitive = false,
        identity = "main-screen",
    )

    @Test fun `a screen survives the round trip exactly`() {
        val back = ScreenCodec.decode(ScreenCodec.encode(screen))
        assertEquals(screen, back)
    }

    @Test fun `an empty value and an absent value are kept apart`() {
        val json = ScreenCodec.encode(screen)
        assertTrue(json.getJSONArray("controls").getJSONObject(0).isNull("value"))
        assertEquals("756", json.getJSONArray("controls").getJSONObject(1).getString("value"))
    }

    @Test fun `a password typed into the screen is scrubbed before it is recorded`() {
        val leaky = screen.copy(texts = listOf("my password: hunter2"))
        val text = ScreenCodec.encode(leaky).getJSONArray("texts").getString(0)
        assertFalse(text.contains("hunter2"))
    }

    @Test fun `the recorder hands the crew the same screen it was given`() {
        val recorded = mutableListOf<JSONObject>()
        val eyes = RecordingEyes(StepEyes { screen }) { recorded += it }
        val seen = eyes.look(false)
        assertSame(screen, seen)
        assertEquals(1, recorded.size)
        assertEquals("screen", recorded.single().getString("kind"))
        assertEquals(screen, ScreenCodec.decode(recorded.single().getJSONObject("screen")))
    }

    @Test fun `a sink that fails never changes what the crew sees`() {
        val eyes = RecordingEyes(StepEyes { screen }) { throw IllegalStateException("disk full") }
        assertSame(screen, eyes.look(false))
    }

    @Test fun `an unreadable capture is passed on as unreadable and records nothing`() {
        val recorded = mutableListOf<JSONObject>()
        val eyes = RecordingEyes(StepEyes { null }) { recorded += it }
        assertNull(eyes.look(false))
        assertTrue(recorded.isEmpty())
    }

    @Test fun `a sensitive screen is never recorded, and the crew still gets it`() {
        val recorded = mutableListOf<JSONObject>()
        val secret = screen.copy(sensitive = true)
        val eyes = RecordingEyes(StepEyes { secret }) { recorded += it }
        assertSame(secret, eyes.look(false))
        assertTrue(recorded.isEmpty())
    }

    @Test fun `a trace file name keeps only letters, digits, dashes and underscores`() {
        assertEquals("run-1ab", LabTrace.safeName("run-1/../ab"))
    }
}
