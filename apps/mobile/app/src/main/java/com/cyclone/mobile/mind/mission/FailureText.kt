package com.cyclone.mobile.mind.mission

/**
 * Alpha 141 follow-up: a failure that names itself. Alpha 138–140 showed owners "Cyclone could not run this mission:
 * com.cyclone.mobile.mind.one.OneMindPrompt", because on Android a class whose initializer failed throws
 * NoClassDefFoundError whose message is only the class name; the real cause (a PatternSyntaxException from Android's
 * ICU regex) was in the cause chain and never shown.
 *
 * [describe] gives the failure's own type and message plus the root cause's type and message, scrubbed of anything
 * that looks like a secret, in one bounded line. Pure.
 */
object FailureText {
    const val MAX = 400

    fun describe(error: Throwable): String {
        val top = one(error)
        val root = rootOf(error)
        val text = if (root === error) top else "$top (caused by ${one(root)})"
        return MindRedaction.scrubText(text).take(MAX)
    }

    /** The deepest cause, guarding against cycles. */
    fun rootOf(error: Throwable): Throwable {
        var current = error
        val seen = HashSet<Throwable>()
        while (seen.add(current)) current = current.cause ?: break
        return current
    }

    private fun one(error: Throwable): String {
        val name = error.javaClass.simpleName.ifBlank { error.javaClass.name }
        val message = error.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return if (message.isBlank()) name else "$name: ${message.take(200)}"
    }
}
