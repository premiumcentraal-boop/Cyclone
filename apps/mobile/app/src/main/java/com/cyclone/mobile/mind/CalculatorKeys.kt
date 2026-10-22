package com.cyclone.mobile.mind

/**
 * On a calculator, Enter means "equals". The phone taps the equals key instead of pressing Enter: a tap on "=" is not an
 * approval, while Enter in a field is treated as a send and is declined. Code only; the approval gate is unchanged.
 */
object CalculatorKeys {
    private val EQUALS_LABELS = setOf("=", "equals", "equal", "equal sign")

    fun isCalculator(packageName: String?): Boolean =
        !packageName.isNullOrBlank() && packageName.contains("calculator", ignoreCase = true)

    fun isEquals(label: String): Boolean = label.trim().lowercase() in EQUALS_LABELS
}
