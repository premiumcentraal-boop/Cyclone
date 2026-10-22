package com.cyclone.mobile.mind

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorKeysTest {
    @Test fun `the stock calculator is a calculator`() {
        assertTrue(CalculatorKeys.isCalculator("com.google.android.calculator"))
        assertTrue(CalculatorKeys.isCalculator("com.digitalchemy.calculator.freedecimal"))
    }

    @Test fun `messaging and blank packages are not calculators`() {
        assertFalse(CalculatorKeys.isCalculator("com.whatsapp"))
        assertFalse(CalculatorKeys.isCalculator("com.google.android.gm"))
        assertFalse(CalculatorKeys.isCalculator(null))
        assertFalse(CalculatorKeys.isCalculator("  "))
    }

    @Test fun `equals is recognised by its common labels, whatever the case or spacing`() {
        assertTrue(CalculatorKeys.isEquals("="))
        assertTrue(CalculatorKeys.isEquals("equals"))
        assertTrue(CalculatorKeys.isEquals("  Equals "))
    }

    @Test fun `other keys are not equals`() {
        assertFalse(CalculatorKeys.isEquals("Enter"))
        assertFalse(CalculatorKeys.isEquals("7"))
        assertFalse(CalculatorKeys.isEquals("multiply"))
        assertFalse(CalculatorKeys.isEquals(""))
    }
}
