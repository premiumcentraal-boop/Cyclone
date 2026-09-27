package com.cyclone.mobile.voice

import android.content.Context
import android.os.SystemClock
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.owner.OwnerMomentsRuntime
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.task.TaskCommand
import com.cyclone.mobile.task.TaskCommands
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call

/**
 * One Drive session (plan 24 §6): carries out what [VoiceTurn] decides. It records, transcribes, understands and
 * speaks; it watches the running task and its Owner Moments; and it hands work to Cyclone in exactly two ways:
 * [OverlayChromeRuntime.submitRequest] for a new request and [TaskCommands.send] for everything else (plan 32:
 * voice never acts).
 *
 * All state changes happen on the main thread, one event at a time.
 */
class VoiceSession(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val speech = SpeechOut(app)
    private val capture = VoiceCapture(app)
    private val onDevice = OnDeviceStt(app)

    private val _turn = MutableStateFlow(VoiceTurn(language = DriverMode.load(app).language))
    val turn: StateFlow<VoiceTurn> = _turn
    private val micLevel = MutableStateFlow(0f)
    private val _level = MutableStateFlow(0f)
    /** The orb's level: the owner's voice while listening, Cyclone's while speaking. */
    val level: StateFlow<Float> = _level

    private val _timings = MutableStateFlow(VoiceTimings())
    /** How fast the last requests were, for Settings → Voice. */
    val timings: StateFlow<VoiceTimings> = _timings

    private var earconJob: Job? = null
    private var listenJob: Job? = null
    private var speakJob: Job? = null
    private var workJob: Job? = null
    private var stillJob: Job? = null
    private val calls = mutableListOf<Call>()
    private var clip: ShortArray? = null
    private var heardText: String? = null
    private var stoppedTalkingAt = 0L
    private var awaitingAckSound = false
    private var liveTaskId: String? = null

    init {
        speech.prepare(DriverMode.load(app).language)
        scope.launch { combine(micLevel, speech.level) { mic, out -> maxOf(mic, out) }.collect { _level.value = it } }
        scope.launch { OwnerMomentsRuntime.task.collect(::onTask) }
        scope.launch { OwnerMomentsRuntime.moments().collect { moment ->
            val voice = VoiceMomentSource.of(moment)
            if (voice != null) dispatch(VoiceEvent.MomentOpened(voice)) else if (_turn.value.moment != null) dispatch(VoiceEvent.MomentClosed)
        } }
        // The live model lists: fetched once per session in the background, so the first request does not wait.
        scope.launch(Dispatchers.IO) { runCatching { VoiceCatalog.refresh(OpenRouterSecretStore.read(app)) } }
    }

    fun tap() {
        if (_turn.value.phase == VoicePhase.LISTENING) { capture.finish(); return }
        dispatch(VoiceEvent.Tap)
    }

    fun stop() {
        workJob?.cancel()
        cancelCalls()
        dispatch(VoiceEvent.Stop)
    }
    fun notNow() = dispatch(VoiceEvent.NotNow)

    fun close() {
        stopListening()
        speech.release()
        cancelCalls()
        scope.cancel()
    }

    // ---- the loop ---------------------------------------------------------------------------------------------------

    private fun dispatch(event: VoiceEvent) {
        val step = _turn.value.on(event)
        _turn.value = step.turn.copy(language = DriverMode.settings.value.language)
        step.effects.forEach(::run)
        if (step.turn.phase == VoicePhase.WORKING) armStillWorking() else if (!step.turn.taskLive) stillJob?.cancel()
    }

    private fun run(effect: VoiceEffect) {
        when (effect) {
            is VoiceEffect.Play -> earconJob = scope.launch { speech.earcon(effect.earcon) }
            VoiceEffect.Listen -> listen()
            VoiceEffect.StopListening -> stopListening()
            VoiceEffect.Transcribe -> transcribe()
            is VoiceEffect.Understand -> understand(effect.transcript, effect.context)
            is VoiceEffect.Say -> say(effect.line)
            VoiceEffect.StopSpeaking -> { speakJob?.cancel(); speech.stop() }
            is VoiceEffect.Submit -> OverlayChromeRuntime.submitRequest(effect.goal, driving = true)
            is VoiceEffect.Send -> send(effect.answer)
        }
    }

    private fun listen() {
        listenJob?.cancel()
        clip = null
        heardText = null
        val settings = DriverMode.settings.value
        listenJob = scope.launch {
            // The earcon first: the detector learns the room's noise, not our own sound.
            earconJob?.join()
            VoiceService.start(app)
            try {
                if (settings.onDeviceStt) {
                    when (val result = onDevice.listen(settings.language) { micLevel.value = it }) {
                        is OnDeviceStt.Result.Text -> { heardText = result.text; heard(); dispatch(VoiceEvent.Heard) }
                        OnDeviceStt.Result.NothingHeard -> dispatch(VoiceEvent.NothingHeard)
                        is OnDeviceStt.Result.Failed -> dispatch(VoiceEvent.Failed(result.failure))
                    }
                } else {
                    val tuning = VoiceActivity.Tuning(endSilenceMs = settings.endSilenceMs)
                    when (val result = capture.record(tuning) { micLevel.value = it }) {
                        is VoiceCapture.Outcome.Clip -> { clip = result.samples; heard(); dispatch(VoiceEvent.Heard) }
                        VoiceCapture.Outcome.NothingHeard -> dispatch(VoiceEvent.NothingHeard)
                        is VoiceCapture.Outcome.Failed -> dispatch(VoiceEvent.Failed(result.failure))
                    }
                }
            } finally {
                micLevel.value = 0f
                VoiceService.stop(app)
            }
        }
    }

    private fun heard() {
        stoppedTalkingAt = SystemClock.elapsedRealtime()
        awaitingAckSound = true
    }

    private fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        clip = null
        micLevel.value = 0f
        VoiceService.stop(app)
    }

    private fun transcribe() {
        heardText?.let { text -> heardText = null; dispatch(VoiceEvent.Transcript(text)); return }
        val samples = clip ?: return dispatch(VoiceEvent.Failed(VoiceFailure.NOT_HEARD))
        clip = null
        workJob = scope.launch {
            val started = SystemClock.elapsedRealtime()
            val event = withContext(Dispatchers.IO) {
                try {
                    val key = OpenRouterSecretStore.read(app)
                    if (key.isBlank()) return@withContext VoiceEvent.Failed(VoiceFailure.NO_KEY)
                    val model = model(key) { it.stt } ?: return@withContext VoiceEvent.Failed(VoiceFailure.OFFLINE)
                    VoiceEvent.Transcript(OpenRouterStt(OpenRouterVoice(key), model).transcribe(samples, DriverMode.settings.value.language, ::track))
                } catch (error: VoiceCallException) {
                    VoiceEvent.Failed(error.failure)
                } finally {
                    samples.fill(0)
                }
            }
            _timings.value = _timings.value.copy(transcribeMs = SystemClock.elapsedRealtime() - started)
            dispatch(event)
        }
    }

    private fun understand(transcript: String, context: VoiceContext) {
        workJob = scope.launch {
            val started = SystemClock.elapsedRealtime()
            val event = withContext(Dispatchers.IO) {
                try {
                    val key = OpenRouterSecretStore.read(app)
                    if (key.isBlank()) return@withContext VoiceEvent.Failed(VoiceFailure.NO_KEY)
                    val model = model(key) { it.fast } ?: return@withContext VoiceEvent.Failed(VoiceFailure.OFFLINE)
                    VoiceEvent.Understood(OpenRouterVoice(key).understand(model, transcript, context, ::track))
                } catch (error: VoiceCallException) {
                    VoiceEvent.Failed(error.failure)
                }
            }
            _timings.value = _timings.value.copy(understandMs = SystemClock.elapsedRealtime() - started)
            dispatch(event)
        }
    }

    private fun say(line: String) {
        speakJob?.cancel()
        val job = scope.launch {
            earconJob?.join()
            val key = OpenRouterSecretStore.read(app)
            val settings = DriverMode.settings.value
            val choice = VoiceCatalog.choice(settings)
            val api = key.takeIf { it.isNotBlank() }?.let { OpenRouterVoice(it) }
            val started = SystemClock.elapsedRealtime()
            val spoken = speech.say(line, api, choice.tts, choice.voice, settings.language)
            if (awaitingAckSound) {
                awaitingAckSound = false
                // End of speech to the first sound of the answer: the number plan 24 §7 sets (p50 2.0 s, p90 3.0 s).
                val confirm = started + spoken.firstSoundMs - stoppedTalkingAt
                _timings.value = _timings.value.record(firstSoundMs = spoken.firstSoundMs, confirmMs = confirm, engine = spoken.engine)
            }
        }
        speakJob = job
        job.invokeOnCompletion { cause -> if (cause == null) scope.launch { if (speakJob === job) dispatch(VoiceEvent.SpeechEnded) } }
    }

    private fun send(answer: VoiceAnswer) {
        val taskId = liveTaskId ?: OwnerMomentsRuntime.task.value?.taskId ?: return
        val command = when (answer) {
            is VoiceAnswer.Reply -> TaskCommand.Reply(answer.text)
            // Values said by voice are for this task only: never remembered (plan 32).
            is VoiceAnswer.Fill -> TaskCommand.Fill(answer.values, remember = false)
            VoiceAnswer.Approve -> TaskCommand.Approve
            VoiceAnswer.Decline -> TaskCommand.Decline
            VoiceAnswer.Stop -> TaskCommand.Stop
        }
        TaskCommands.send(app, taskId, command)
    }

    // ---- the task ---------------------------------------------------------------------------------------------------

    private fun onTask(task: com.cyclone.mobile.runtime.background.WorkspaceTaskUi?) {
        val live = task != null && task.phase !in TERMINAL
        val wasLive = liveTaskId != null
        when {
            live && liveTaskId != task!!.taskId -> { liveTaskId = task.taskId; dispatch(VoiceEvent.TaskStarted) }
            !live && wasLive -> {
                liveTaskId = null
                val outcome = when (task?.phase) {
                    TaskPhase.DONE -> TaskOutcome.DONE
                    TaskPhase.FAILED -> TaskOutcome.FAILED
                    else -> TaskOutcome.STOPPED
                }
                dispatch(VoiceEvent.TaskEnded(outcome, task?.outcome ?: task?.message.orEmpty()))
            }
        }
    }

    private fun armStillWorking() {
        if (stillJob?.isActive == true) return
        stillJob = scope.launch {
            delay(STILL_WORKING_MS)
            dispatch(VoiceEvent.StillWorking)
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** The model for one stage from the live list, fetching the list first if this session has none yet. */
    private fun model(key: String, pick: (VoiceChoice) -> String?): String? {
        val settings = DriverMode.settings.value
        pick(VoiceCatalog.choice(settings))?.let { return it }
        VoiceCatalog.refresh(key, maxAgeMs = 0)
        return pick(VoiceCatalog.choice(settings))
    }

    private fun track(call: Call) = synchronized(calls) {
        calls += call
        if (calls.size > 8) calls.removeAt(0)
    }

    private fun cancelCalls() = synchronized(calls) { calls.forEach { it.cancel() }; calls.clear() }

    companion object {
        const val STILL_WORKING_MS = 60_000L
        private val TERMINAL = setOf(TaskPhase.DONE, TaskPhase.FAILED, TaskPhase.STOPPED)
    }
}
