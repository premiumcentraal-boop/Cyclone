package com.cyclone.mobile.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * The microphone for one request (plan 24 §5.3): 16 kHz mono from the `VOICE_RECOGNITION` source, with echo and
 * noise suppression when the phone has them, fed to [VoiceActivity] until the owner stops talking.
 *
 * The clip stays in this object's memory: it is handed to the transcriber as WAV bytes and then dropped. Nothing is
 * written anywhere.
 */
class VoiceCapture(private val context: Context) {

    sealed interface Outcome {
        /** Speech, trimmed to the part worth sending. */
        class Clip(val samples: ShortArray, val voicedMs: Int) : Outcome
        data object NothingHeard : Outcome
        data class Failed(val failure: VoiceFailure) : Outcome
    }

    @Volatile private var finishEarly = false

    /** The owner tapped while talking: end now and keep what was said. */
    fun finish() { finishEarly = true }

    /** Records until the detector decides; [onLevel] gets the voice level (0..1) about 50 times a second. */
    @SuppressLint("MissingPermission")
    suspend fun record(tuning: VoiceActivity.Tuning, onLevel: (Float) -> Unit): Outcome = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@withContext Outcome.Failed(VoiceFailure.NO_MIC)
        finishEarly = false
        val rate = VoiceActivity.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return@withContext Outcome.Failed(VoiceFailure.MIC_BUSY)
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, rate / 5 * 2))
        }.getOrNull() ?: return@withContext Outcome.Failed(VoiceFailure.MIC_BUSY)
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return@withContext Outcome.Failed(VoiceFailure.MIC_BUSY) }
        val echo = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val noise = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val detector = VoiceActivity(tuning)
        val all = ShortArray(rate * tuning.maxClipMs / 1000 + rate)
        var count = 0
        val chunk = ShortArray(rate / 50)
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@withContext Outcome.Failed(VoiceFailure.MIC_BUSY)
            while (true) {
                coroutineContext.ensureActive()
                val n = record.read(chunk, 0, chunk.size)
                if (n < 0) return@withContext Outcome.Failed(VoiceFailure.MIC_BUSY)
                if (n == 0) continue
                val room = minOf(n, all.size - count)
                System.arraycopy(chunk, 0, all, count, room)
                count += room
                when (val result = detector.feed(chunk, n)) {
                    is VoiceActivity.Result.Listening -> {
                        onLevel(result.level)
                        if (finishEarly) return@withContext early(all, count, detector)
                    }
                    is VoiceActivity.Result.Heard ->
                        return@withContext Outcome.Clip(Wav.trim(all, count, result.startMs, result.endMs), result.voicedMs)
                    VoiceActivity.Result.NoSpeech, is VoiceActivity.Result.Blip -> return@withContext Outcome.NothingHeard
                }
            }
            @Suppress("UNREACHABLE_CODE") Outcome.NothingHeard
        } finally {
            runCatching { record.stop() }
            record.release()
            echo?.release()
            noise?.release()
            onLevel(0f)
        }
    }

    /** A tap ends the request: send what was said, if it was speech at all. */
    private fun early(all: ShortArray, count: Int, detector: VoiceActivity): Outcome {
        val final = detector.feed(ShortArray(VoiceActivity.SAMPLE_RATE * 2))
        return when (final) {
            is VoiceActivity.Result.Heard -> Outcome.Clip(Wav.trim(all, count, final.startMs, minOf(final.endMs, count * 1000 / VoiceActivity.SAMPLE_RATE)), final.voicedMs)
            else -> Outcome.NothingHeard
        }
    }
}
