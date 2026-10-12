package com.cyclone.mobile.gesture

import android.content.Context

/** Plan 52: Settings › Hands, stored on this phone only and applied to [Hands] for the whole process. */
data class HandsPreferences(
    val style: HandsStyle = HandsStyle.NATURAL,
    val handedness: Handedness = Handedness.RIGHT,
    val typos: Boolean = false,
)

object HandsSettings {
    private const val PREFS = "cyclone_hands"

    fun read(context: Context): HandsPreferences {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return HandsPreferences(
            style = HandsStyle.parse(p.getString("style", null)),
            handedness = Handedness.parse(p.getString("hand", null)),
            typos = p.getBoolean("typos", false),
        )
    }

    fun save(context: Context, preferences: HandsPreferences) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("style", preferences.style.name.lowercase())
            .putString("hand", preferences.handedness.name.lowercase())
            .putBoolean("typos", preferences.typos)
            .apply()
        apply(preferences)
    }

    /** Loads the stored choice into [Hands]; called when the Accessibility service connects and on every change. */
    fun apply(context: Context) = apply(read(context))

    private fun apply(preferences: HandsPreferences) {
        Hands.style = preferences.style
        Hands.handedness = preferences.handedness
        Hands.typos = preferences.typos
    }
}
