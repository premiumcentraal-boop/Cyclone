package com.cyclone.mobile.mind.one

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectGateTest {
    private val chat = StepScreen("com.whatsapp", "WhatsApp", "chat", "f1",
        listOf(StepControl("e1", "Message", "edit", editable = true, focused = true, value = "on my way"),
            StepControl("e2", "Send", "button"),
            StepControl("e3", "Delete message", "button")),
        listOf("Anna"))

    private fun step(target: String?) = Step("s1", "tap", target = target)

    @Test fun `typing and moving around are free for every app`() {
        val settings = StepScreen("com.android.settings", "Settings", "display", "f2", emptyList(), emptyList())
        val open = StepControl("e4", "Display", "row")
        assertTrue(EffectGate.decide(step("display"), open, settings).allowed)
        assertTrue(EffectGate.decide(step("message"), chat.controls[0], chat).allowed)
    }

    @Test fun `sending needs the plan to name it, in any app`() {
        val send = chat.controls[1]
        assertFalse(EffectGate.decide(step("reply to Anna"), send, chat).allowed)
        assertTrue(EffectGate.decide(step("send the reply to Anna"), send, chat).allowed)
    }

    @Test fun `paying and publishing follow the same rule`() {
        val checkout = StepControl("e5", "Checkout", "button")
        val shop = StepScreen("com.example.shop", "Shop", "cart", "f3", listOf(checkout), emptyList())
        assertEquals(Effect.OUTWARD, EffectGate.classify(checkout))
        assertFalse(EffectGate.decide(step("look at the cart"), checkout, shop).allowed)
        assertTrue(EffectGate.decide(step("checkout the cart"), checkout, shop).allowed)
    }

    @Test fun `deleting a message the plan does not name needs approval`() {
        val delete = chat.controls[2]
        assertEquals(Effect.DESTRUCTIVE, EffectGate.classify(delete))
        assertFalse(EffectGate.decide(step("reply to Anna"), delete, chat).allowed)
    }

    @Test fun `deleting the message the plan names needs no second question`() {
        val delete = chat.controls[2]
        assertTrue(EffectGate.decide(step("delete the message from Anna"), delete, chat).allowed)
    }

    @Test fun `backspace in the field being typed is a correction, in any app`() {
        val backspace = StepControl("e6", "Delete", "button")
        val withFocus = StepScreen("com.example.notes", "Notes", "edit", "f4",
            listOf(StepControl("e7", "Note", "edit", editable = true, focused = true, value = "hello"), backspace), emptyList())
        assertTrue(EffectGate.decide(step("fix the typo"), backspace, withFocus).allowed)
    }

    @Test fun `the same delete key with no field in focus is not a correction`() {
        val backspace = StepControl("e6", "Delete", "button")
        val noFocus = StepScreen("com.example.notes", "Notes", "list", "f5", listOf(backspace), emptyList())
        assertFalse(EffectGate.decide(step("fix the typo"), backspace, noFocus).allowed)
    }

    @Test fun `the calculator's delete is one case of the same rule, not a special case`() {
        val delete = StepControl("e8", "Delete", "button")
        val display = StepControl("e9", "Display", "text", editable = true, focused = true, value = "756")
        val calc = StepScreen("com.google.android.calculator", "Calculator", "main", "f6", listOf(display, delete), emptyList())
        assertTrue(EffectGate.decide(step("correct the number"), delete, calc).allowed)
    }
}
