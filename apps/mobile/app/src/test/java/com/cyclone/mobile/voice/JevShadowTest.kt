package com.cyclone.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JevShadowTest {
    @Test fun `the request asks one typed choice over every kind`() {
        val body = JevShadow.request("reply to Louella", VoiceContext(open = VoiceContext.OpenAsk("question", "Which Louella?"), taskLive = true))
        assertEquals("~typesafe/jev-latest", body.getString("model"))
        val state = body.getJSONArray("state")
        val text = (0 until state.length()).joinToString("\n") { state.getString(it) }
        assertTrue(text.contains("Spoken: reply to Louella"))
        assertTrue(text.contains("Open question: Which Louella?"))
        val kind = body.getJSONObject("questions").getJSONObject("kind")
        assertEquals("choice", kind.getString("type"))
        assertEquals(VoiceKind.entries.size, kind.getJSONObject("criteria").length())
    }

    @Test fun `answers are read exactly`() {
        assertEquals(JevShadow.Decision(VoiceKind.REPLY, 0.93),
            JevShadow.parse("""{"answers":{"kind":{"type":"choice","choice":"reply","confidence":0.93}}}"""))
        assertNull(JevShadow.parse("""{"decisions":{"kind":{"value":"task","probability":0.7}}}"""))
        assertNull(JevShadow.parse("""{"kind":"none"}"""))
        assertNull(JevShadow.parse("""{"answers":{"kind":{"type":"choice","choice":"launch","confidence":0.9}}}"""))
        assertNull(JevShadow.parse("""{"answers":{"kind":{"type":"refusal"}}}"""))
        assertNull(JevShadow.parse("not json"))
        assertNull(JevShadow.parse(null))
        assertNull(JevShadow.parse("""{"error":{"message":"beta"}}"""))
    }

    @Test fun `the tally shows agreement, speed and agreement when sure`() {
        var t = JevShadow.Tally()
        assertEquals("No requests yet.", t.summary())
        t = t.failed("HTTP 404")
        assertEquals("No answer yet: HTTP 404", t.summary())
        t = t.add(JevShadow.Sample(VoiceKind.TASK, VoiceKind.TASK, 180, 0.9))
            .add(JevShadow.Sample(VoiceKind.REPLY, VoiceKind.TASK, 220, 0.6))
            .add(JevShadow.Sample(VoiceKind.TASK, VoiceKind.TASK, 150, 0.95))
            .add(JevShadow.Sample(VoiceKind.TASK, null, 900))
        assertEquals(2.0 / 3, t.agreement!!, 1e-9)
        assertEquals(180L, t.medianMs)
        assertEquals(1.0, t.confidentAgreement!!, 1e-9)
        assertTrue(t.summary(), t.summary().startsWith("Agreed 2 of 3 · 0.2 s · 100% when sure"))
        repeat(80) { t = t.add(JevShadow.Sample(VoiceKind.TASK, VoiceKind.TASK, 100)) }
        assertEquals(JevShadow.KEEP, t.samples.size)
    }
}
