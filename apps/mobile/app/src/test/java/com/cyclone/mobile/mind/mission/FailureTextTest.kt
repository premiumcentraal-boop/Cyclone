package com.cyclone.mobile.mind.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureTextTest {
    @Test fun `a class that failed to load names its root cause`() {
        // What ART threw in alpha 138: the class name as the message, the regex error as the cause.
        val regex = java.util.regex.PatternSyntaxException("Syntax error U_REGEX_RULE_SYNTAX", "\\{([a-z]+)}", 13)
        val init = ExceptionInInitializerError(regex)
        val noClass = NoClassDefFoundError("com.cyclone.mobile.mind.one.OneMindPrompt").apply { initCause(init) }
        val text = FailureText.describe(noClass)
        assertTrue(text, text.startsWith("NoClassDefFoundError: com.cyclone.mobile.mind.one.OneMindPrompt (caused by PatternSyntaxException: "))
        assertTrue(text, text.contains("U_REGEX_RULE_SYNTAX"))
    }

    @Test fun `a plain failure stays short, and secrets never reach the text`() {
        assertEquals("IllegalArgumentException: Add your OpenRouter API key first.",
            FailureText.describe(IllegalArgumentException("Add your OpenRouter API key first.")))
        assertEquals("IllegalStateException", FailureText.describe(IllegalStateException()))
        val leaky = RuntimeException("upstream", IllegalStateException("token=sk-abcdefghijklmnop failed"))
        val text = FailureText.describe(leaky)
        assertFalse(text, text.contains("sk-abcdefghijklmnop"))
        assertTrue(text.length <= FailureText.MAX)
    }
}
