package com.cyclone.mobile.ai

/**
 * Narrow V2.8 helper: trace detail is optional, so a blank String can legitimately map to null.
 * This overload is selected only when the fallback lambda itself returns String?.
 */
internal fun String.ifBlank(defaultValue: () -> String?): String? = if (isBlank()) defaultValue() else this
