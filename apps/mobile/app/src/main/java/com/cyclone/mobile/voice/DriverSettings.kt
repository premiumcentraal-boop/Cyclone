package com.cyclone.mobile.voice

/** The owner's Drive settings (Settings → Driver mode and Settings → Voice). Stored by [DriverMode]; no secrets. */
data class DriverSettings(
    val enabled: Boolean = false,
    /** The AI button's size in dp. */
    val buttonDp: Int = DEFAULT_BUTTON_DP,
    /** Null: picked from the live list. */
    val sttModel: String? = null,
    val ttsModel: String? = null,
    val fastModel: String? = null,
    val voice: String? = null,
    /** Android's own recognizer instead of OpenRouter (offline, private). */
    val onDeviceStt: Boolean = false,
    /** "auto", or a language name the models understand ("English", "Dutch"). */
    val language: String = "auto",
    /** Trailing silence that ends a request. */
    val endSilenceMs: Int = 700,
    /** "Best voice": the most natural voice the live list has, a little slower and dearer; otherwise the fastest. */
    val bestVoice: Boolean = false,
    /** JEV watches each request next to the understanding model (it never decides); its agreement shows in Settings. */
    val jevWatch: Boolean = true,
) {
    companion object {
        const val DEFAULT_BUTTON_DP = 84
        val BUTTON_SIZES = listOf(72, 84, 96)
        val LANGUAGES = listOf("auto", "English", "Dutch", "German", "French", "Spanish")
        val END_SILENCE = listOf(500, 700, 900, 1_200)
    }
}

/**
 * Where the AI button sits, per orientation (plan 32: hold 1 s, drag, snap to an edge, remembered). [yFraction] is
 * the button's centre as a fraction of the usable height, so the spot survives a font or bar change.
 */
data class ButtonSpot(val right: Boolean = true, val yFraction: Float = 0.62f) {
    companion object {
        /** Snap a dropped button: the nearer side, and a height kept clear of the very top and bottom. */
        fun snap(centreX: Float, centreY: Float, width: Float, height: Float): ButtonSpot =
            ButtonSpot(right = centreX >= width / 2f, yFraction = if (height <= 0f) 0.62f else (centreY / height).coerceIn(0.12f, 0.88f))
    }
}
