package com.cyclone.mobile.voice

import android.os.SystemClock
import java.io.ByteArrayOutputStream

/**
 * Test voice (plan 24 §5.7): measures, on this phone and network, each stage of a request against its target. Cyclone
 * says a sample request with the chosen voice, transcribes that audio back with the chosen model, and has the fast
 * model understand it; no microphone and nothing stored. The owner picks a fast voice from real numbers.
 */
object VoiceProbe {
    const val SAMPLE = "Set a timer for ten minutes."

    data class Result(
        val firstSoundMs: Long? = null,
        val transcribeMs: Long? = null,
        val understandMs: Long? = null,
        val transcript: String = "",
        val kind: VoiceKind? = null,
        val goal: String = "",
        val error: String? = null,
    ) {
        /** What the owner would wait between stopping and hearing the confirmation start. */
        val confirmMs: Long? get() = if (transcribeMs != null && understandMs != null && firstSoundMs != null) transcribeMs + understandMs + firstSoundMs else null
        val understood: Boolean get() = kind == VoiceKind.TASK && goal.contains("timer", ignoreCase = true)
    }

    /** Network: call off the main thread. */
    fun run(key: String, choice: VoiceChoice, language: String): Result {
        if (key.isBlank()) return Result(error = VoiceCopy.NO_KEY)
        val tts = choice.tts ?: return Result(error = "OpenRouter lists no speech model right now.")
        val api = OpenRouterVoice(key)
        return try {
            val pcm = ByteArrayOutputStream()
            var rate = 24_000
            var first = -1L
            val started = SystemClock.elapsedRealtime()
            api.speak(tts, choice.voice, SAMPLE, onStart = { rate = it }, onPcm = { bytes, n ->
                if (first < 0) first = SystemClock.elapsedRealtime() - started
                pcm.write(bytes, 0, n)
            })
            var result = Result(firstSoundMs = first.takeIf { it >= 0 })
            val stt = choice.stt ?: return result.copy(error = "Recognition is on the phone: only speech is measured.")
            val clip = Wav.resample(Wav.samples(pcm.toByteArray()), rate, VoiceActivity.SAMPLE_RATE)
            val t0 = SystemClock.elapsedRealtime()
            val text = api.transcribe(stt, Wav.encode(clip), language)
            result = result.copy(transcribeMs = SystemClock.elapsedRealtime() - t0, transcript = text)
            val fast = choice.fast ?: return result.copy(error = "OpenRouter lists no fast text model right now.")
            val t1 = SystemClock.elapsedRealtime()
            val u = api.understand(fast, text, VoiceContext(language = language))
            result.copy(understandMs = SystemClock.elapsedRealtime() - t1, kind = u.kind, goal = u.goal)
        } catch (error: VoiceCallException) {
            Result(error = error.message)
        }
    }
}
