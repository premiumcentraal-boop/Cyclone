package com.cyclone.mobile

import com.cyclone.mobile.gesture.HandsStyle
import com.cyclone.mobile.gesture.SeededGestureRng
import com.cyclone.mobile.gesture.typing.Keystroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 52 run 3: the keyboard opens and text goes in key by key, with set-text and paste behind it. */
class PhoneTypeEngineKeysTest {
    private class Field(var text: String = "", var focused: Boolean = false)

    private class Host(
        val field: Field,
        /** null: cannot touch the field here (a background display). */
        private val keyboardAppears: Boolean? = true,
        private val keys: PhoneTypeEngine.KeysOutcome = PhoneTypeEngine.KeysOutcome.DONE,
        /** The app drops every other character (an input filter): only set-text can put the exact value in. */
        private val keysLoseText: Boolean = false,
    ) : PhoneTypeEngine.LiveHost {
        val calls = mutableListOf<String>()
        var strokes: List<Keystroke> = emptyList()

        override fun resolve(plan: PhoneTypeEngine.ExecutePlan): Any = field
        override fun view(handle: Any, redactText: Boolean) = PhoneTypeEngine.LiveView(
            rawNodeId = "n1", path = "w1/0/1", editable = true, focused = field.focused, enabled = true,
            textLength = field.text.length, textDigest = PhoneTypeEngine.digest(field.text), actions = emptyList(),
        )
        override fun focus(handle: Any): Boolean { calls += "focus"; field.focused = true; return true }
        override fun click(handle: Any): Boolean { calls += "click"; field.focused = true; return true }
        override fun setText(handle: Any, value: CharSequence): Boolean { calls += "set_text"; field.text = value.toString(); return true }
        override fun paste(handle: Any, value: CharSequence): Boolean { calls += "paste"; field.text = value.toString(); return true }
        override fun refresh(handle: Any): Any = field
        override fun readText(handle: Any): CharSequence = field.text
        override fun raiseKeyboard(handle: Any): Boolean? {
            calls += "touch"
            if (keyboardAppears == null) return null
            field.focused = true
            return keyboardAppears
        }
        override fun typeKeys(handle: Any, strokes: List<Keystroke>): PhoneTypeEngine.KeysOutcome {
            calls += "keys"
            this.strokes = strokes
            if (keys == PhoneTypeEngine.KeysOutcome.DONE) {
                val typed = com.cyclone.mobile.gesture.typing.KeystrokePlanner.replay(strokes)
                field.text = if (keysLoseText) typed.filterIndexed { index, _ -> index % 2 == 0 } else typed
            }
            return keys
        }
    }

    private fun plan(value: String) = PhoneTypeEngine.ExecutePlan(
        elementId = "e1", rawNodeId = "n1", path = "w1/0/1", needsFocus = true,
        valueLength = value.length, valueDigest = PhoneTypeEngine.digest(value),
    )

    private fun type(value: String, host: Host, style: HandsStyle = HandsStyle.NATURAL, redact: Boolean = false) =
        PhoneTypeEngine.perform(plan(value), value, host, redactObservedText = redact, style = style, rng = SeededGestureRng(3L))

    @Test fun naturalHandsTouchTheFieldOpenTheKeyboardAndTypeKeyByKey() {
        val host = Host(Field("old text"))
        val result = type("sam.jones92", host)
        assertTrue(result.ok)
        assertEquals("keys", result.method)
        assertEquals("shown", result.keyboard)
        assertTrue(result.textVerified)
        assertEquals("sam.jones92", host.field.text)
        assertEquals(listOf("touch", "keys"), host.calls)
        assertTrue("one stroke per character", host.strokes.size >= "sam.jones92".length)
        val payload = result.toPayload()
        assertEquals("IME_COMMIT", payload.getString("action"))
        assertEquals("shown", payload.getString("keyboard"))
        assertFalse("the value never appears in the result", payload.toString().contains("sam.jones92"))
    }

    @Test fun whenTheKeyboardDoesNotOpenSetTextFillsTheField() {
        val host = Host(Field(), keyboardAppears = false)
        val result = type("hello", host)
        assertTrue(result.ok)
        assertEquals("set_text", result.method)
        assertEquals("not_shown", result.keyboard)
        assertFalse("keys" in host.calls)
    }

    @Test fun whenKeysAreUnavailableOrLoseTextSetTextMakesItExact() {
        val unavailable = Host(Field(), keys = PhoneTypeEngine.KeysOutcome.UNAVAILABLE)
        val first = type("hello there", unavailable)
        assertTrue(first.ok)
        assertEquals("set_text", first.method)
        assertEquals("hello there", unavailable.field.text)

        val lossy = Host(Field(), keysLoseText = true)
        val second = type("hello there", lossy)
        assertTrue(second.ok)
        assertEquals("set_text", second.method)
        assertEquals("hello there", lossy.field.text)
        assertEquals(listOf("touch", "keys", "set_text"), lossy.calls)
    }

    @Test fun anOwnerTakeoverStopsTypingAndNothingElseIsWritten() {
        val host = Host(Field("draft"), keys = PhoneTypeEngine.KeysOutcome.STOPPED)
        val result = type("hello", host)
        assertFalse(result.ok)
        assertEquals(PhoneToolErrorCode.HUMAN_HAS_CONTROL, result.error!!.code)
        assertFalse("set_text" in host.calls || "paste" in host.calls)
    }

    @Test fun preciseHandsSecretsAndBackgroundDisplaysKeepTheOldPath() {
        val precise = Host(Field())
        assertEquals("set_text", type("hello", precise, style = HandsStyle.PRECISE).method)
        assertFalse("touch" in precise.calls)

        val secret = Host(Field())
        val filled = type("hunter2", secret, redact = true)
        assertFalse("a secret is never typed key by key", "keys" in secret.calls || "touch" in secret.calls)
        assertNull(filled.keyboard)

        val background = Host(Field(), keyboardAppears = null)
        val result = type("hello", background)
        assertEquals("set_text", result.method)
        assertNull(result.keyboard)
        assertFalse("keys" in background.calls)
    }

    @Test fun multiLineTextIsNotKeyed() {
        val host = Host(Field())
        val result = type("line one\nline two", host)
        assertTrue(result.ok)
        assertFalse("touch" in host.calls)
        assertEquals("set_text", result.method)
    }
}
