package com.cyclone.mobile.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import kotlin.coroutines.resume

/**
 * Speech to text (plan 24 §5.3), two ways:
 * - [OpenRouterStt]: the clip Drive recorded, as WAV in memory, to the owner's transcription model;
 * - [OnDeviceStt]: Android's own recognizer (offline, private). It owns the microphone, so it listens and
 *   transcribes in one go instead of taking Drive's clip.
 */
class OpenRouterStt(private val api: OpenRouterVoice, private val model: String) {
    suspend fun transcribe(clip: ShortArray, language: String, track: (Call) -> Unit): String = withContext(Dispatchers.IO) {
        val wav = Wav.encode(clip)
        try {
            api.transcribe(model, wav, language, track)
        } finally {
            // The clip is not needed any more; drop the only copy of the audio.
            wav.fill(0)
        }
    }
}

class OnDeviceStt(private val context: Context) {
    sealed interface Result {
        data class Text(val text: String) : Result
        data object NothingHeard : Result
        data class Failed(val failure: VoiceFailure) : Result
    }

    /** Listens with Android's recognizer until the owner stops; [onLevel] follows the voice. */
    suspend fun listen(language: String, onLevel: (Float) -> Unit): Result = withContext<Result>(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return@withContext Result.Failed(VoiceFailure.NOT_HEARD)
        val recognizer = if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        try {
            suspendCancellableCoroutine<Result> { continuation ->
                fun finish(result: Result) { if (continuation.isActive) continuation.resume(result) }
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = onLevel(0f)
                    override fun onError(error: Int) = finish(when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Result.NothingHeard
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Result.Failed(VoiceFailure.NO_MIC)
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_AUDIO -> Result.Failed(VoiceFailure.MIC_BUSY)
                        else -> Result.Failed(VoiceFailure.NOT_HEARD)
                    })
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        finish(if (text.isBlank()) Result.NothingHeard else Result.Text(text))
                    }
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                OpenRouterVoice.languageCode(language)?.let { intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
                continuation.invokeOnCancellation { runCatching { recognizer.cancel() } }
                recognizer.startListening(intent)
            }
        } finally {
            runCatching { recognizer.destroy() }
            onLevel(0f)
        }
    }
}
