package com.cyclone.mobile.ai

/**
 * Compatibility shim for one trace call that historically used `ifBlank { null }`.
 *
 * The lambda type is deliberately Nothing? so normal `ifBlank { "fallback" }` calls continue to
 * resolve to Kotlin's standard non-null overload. Blank optional trace detail becomes an empty
 * detail string rather than widening every String.ifBlank call in this package to nullable.
 */
internal fun String.ifBlank(defaultValue: () -> Nothing?): String = if (isBlank()) {
    defaultValue()
    ""
} else this
