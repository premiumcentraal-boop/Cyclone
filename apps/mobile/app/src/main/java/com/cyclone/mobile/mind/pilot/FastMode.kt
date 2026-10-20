package com.cyclone.mobile.mind.pilot

import android.content.Context

/** How sure the router's models must be before Cyclone acts on their guess (plan 41's setting, kept by plan 75). */
enum class FastSureness(val wire: String, val label: String, val bar: Double) {
    CAREFUL("careful", "Careful", 0.9), BALANCED("balanced", "Balanced", 0.8), QUICK("quick", "Quick", 0.7);
    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: CAREFUL }
}

/**
 * What is left of plan 41's Fast mode: the sureness bar the router and the phone model's learning read. The Pilot, its
 * fast model, route and look-ahead were retired by plan 69 and removed in alpha 145 (plan 75); OneMind's Flash profile
 * does that work. Stored on this phone only, under the same preferences as before.
 */
object FastMode {
    private const val PREFS = "cyclone_fast"

    fun sureness(context: Context): FastSureness =
        FastSureness.of(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("sureness", null))
}
