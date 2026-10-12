package com.cyclone.mobile.decisions

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan 58: the Decisions wire against the documented contract. The fixture under `resources/decisions/` is rebuilt from
 * the OpenRouter API reference's example answer (three questions about a checkout bug, with its documented values).
 * Real recorded answers from `scripts/dev/decisions_probe.py` join it once the owner has run the probe.
 */
class DecisionsWireTest {
    private val questions = linkedMapOf(
        "is_bug" to DQuestion.Noul("Is this a software defect?", "A defect in the product.", "Not a defect."),
        "team" to DQuestion.Choice("Which team owns it?", linkedMapOf("payments" to "Checkout and billing.", "frontend" to "", "account" to "")),
        "urgency" to DQuestion.Score("How urgent?", listOf("Can wait.", "This week.", "Blocking revenue right now.")),
    )

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("decisions/$name")) { "fixture $name" }.readText()

    @Test fun `the request has the documented shape`() {
        val body = DecisionsWire.body("~typesafe/jev-latest",
            listOf(DPart.Text("Ticket: checkout fails"), DPart.Image("data:image/webp;base64,UklGRg=="), DPart.Text("  ")), questions)
        assertEquals("~typesafe/jev-latest", body.getString("model"))
        val state = body.getJSONArray("state")
        assertEquals(2, state.length())
        assertEquals("Ticket: checkout fails", state.getString(0))
        assertEquals("image_url", state.getJSONObject(1).getString("type"))
        val q = body.getJSONObject("questions")
        assertEquals("noul", q.getJSONObject("is_bug").getString("type"))
        assertEquals(setOf("true", "false"), q.getJSONObject("is_bug").getJSONObject("criteria").keys().asSequence().toSet())
        // A blank guidance repeats the option, so every criterion says something.
        assertEquals("frontend", q.getJSONObject("team").getJSONObject("criteria").getString("frontend"))
        assertEquals(3, q.getJSONObject("urgency").getJSONArray("criteria").length())
        assertEquals("latency", body.getJSONObject("provider").getString("sort"))
    }

    @Test fun `bad requests are refused before they leave the phone`() {
        fun fails(block: () -> Unit) = assertTrue(runCatching(block).isFailure)
        fails { DecisionsWire.body("m", listOf(DPart.Text("x")), emptyMap()) }
        fails { DecisionsWire.body("m", listOf(DPart.Text("x")), mapOf("Bad Name" to DQuestion.choice("q", listOf("a")))) }
        fails { DecisionsWire.body("m", listOf(DPart.Text(" ")), questions) }
        fails { DecisionsWire.body("", listOf(DPart.Text("x")), questions) }
        fails { DPart.Image("https://example.com/a.png") }
        fails { DPart.Image("data:image/gif;base64,AAAA") }
        fails { DQuestion.Choice("q", emptyMap()) }
        assertFalse(DecisionsWire.isImageDataUrl("data:image/png;base64,"))
    }

    @Test fun `the documented example answer is read exactly`() {
        val reply = DecisionsWire.parse(fixture("openrouter_reference_answer.json"), questions)!!
        assertEquals(0.96, reply.noul("is_bug")!!.yes, 1e-9)
        val team = reply.choice("team")!!
        assertEquals("payments", team.choice)
        assertEquals(0.75, team.confidence, 1e-9)
        assertEquals(0.16, team.probabilities.getValue("frontend"), 1e-9)
        assertEquals("frontend" to 0.16, team.runnerUp())
        assertEquals(0.84 - 0.16, team.margin(), 1e-9)
        val urgency = reply.score("urgency")!!
        assertEquals(1.99, urgency.score, 1e-9)
        assertEquals(0.99, urgency.probabilities.getValue(2), 1e-9)
        assertEquals("typesafe/jev-1.13-20260917", reply.model)
        assertEquals("TypeSafe", reply.provider)
        assertEquals(476, reply.inputTokens)
    }

    @Test fun `anything outside the contract is no answer`() {
        fun parse(answers: JSONObject) = DecisionsWire.parse(JSONObject().put("answers", answers).toString(), questions)!!
        // An option that wasn't offered, the wrong type, a score out of range.
        val wrong = parse(JSONObject()
            .put("team", JSONObject().put("type", "choice").put("choice", "legal").put("confidence", 0.9))
            .put("is_bug", JSONObject().put("type", "choice").put("choice", "yes"))
            .put("urgency", JSONObject().put("type", "score").put("score", 7)))
        assertTrue(wrong.answers.isEmpty())
        // A refusal is kept as a refusal, never read as "no".
        val refused = parse(JSONObject().put("is_bug", JSONObject().put("type", "refusal")))
        assertTrue(refused.refused("is_bug"))
        assertNull(refused.noul("is_bug"))
        // Probabilities fill in a missing confidence; neither means no answer.
        val probs = parse(JSONObject().put("team", JSONObject().put("type", "choice").put("choice", "frontend")
            .put("probabilities", JSONObject().put("frontend", 0.6).put("payments", 0.4).put("legal", 0.9))))
        assertEquals(0.6, probs.choice("team")!!.confidence, 1e-9)
        assertFalse(probs.choice("team")!!.probabilities.containsKey("legal"))
        assertNull(parse(JSONObject().put("team", JSONObject().put("type", "choice").put("choice", "frontend"))).choice("team"))
        // Values are kept inside 0..1.
        assertEquals(1.0, parse(JSONObject().put("is_bug", JSONObject().put("type", "noul").put("noul", 3.2))).noul("is_bug")!!.yes, 0.0)
        // Not an answer at all.
        assertNull(DecisionsWire.parse(null, questions))
        assertNull(DecisionsWire.parse("not json", questions))
        assertNull(DecisionsWire.parse("""{"error":{"code":400,"message":"criteria is required"}}""", questions))
        assertNull(DecisionsWire.parse(JSONArray().toString(), questions))
    }

    @Test fun `the breaker pauses after three failures in a minute or one rate limit`() {
        var now = 0L
        val breaker = DecisionBreaker(clock = { now })
        val timeout = DecisionsResult.Failed(DecisionsFailure.TIMEOUT, null, 1200)
        breaker.record(timeout); now += 10_000; breaker.record(timeout)
        assertFalse(breaker.open())
        now += 70_000 // the first failure is older than a minute now
        breaker.record(timeout)
        assertFalse(breaker.open())
        now += 1_000; breaker.record(timeout)
        assertFalse("two in the last minute", breaker.open())
        now += 1_000; breaker.record(timeout)
        assertTrue(breaker.open())
        assertEquals(DecisionsFailure.TIMEOUT, breaker.reason())
        now += 120_001
        assertFalse(breaker.open())
        assertNull(breaker.reason())
        breaker.record(DecisionsResult.Failed(DecisionsFailure.RATE_LIMITED, 429, 50))
        assertTrue(breaker.open())
        now += 120_001
        breaker.record(timeout); breaker.record(DecisionsResult.Ok("{}", 300)); breaker.record(timeout); breaker.record(timeout)
        assertFalse("a success clears the count", breaker.open())
    }

    @Test fun `http failures are told apart`() {
        assertEquals(DecisionsFailure.REJECTED, DecisionsFailure.ofStatus(400))
        assertEquals(DecisionsFailure.REJECTED, DecisionsFailure.ofStatus(413))
        assertEquals(DecisionsFailure.KEY, DecisionsFailure.ofStatus(402))
        assertEquals(DecisionsFailure.RATE_LIMITED, DecisionsFailure.ofStatus(429))
        assertEquals(DecisionsFailure.SERVER, DecisionsFailure.ofStatus(529))
        assertEquals(DecisionsFailure.SERVER, DecisionsFailure.ofStatus(524))
    }
}
