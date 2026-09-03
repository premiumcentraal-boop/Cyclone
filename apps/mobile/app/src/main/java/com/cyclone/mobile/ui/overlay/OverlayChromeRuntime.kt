package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.ai.CycloneAiAccessProfile
import com.cyclone.mobile.ai.CycloneAiAccessProfileStore
import com.cyclone.mobile.ai.OpenRouterAdaptiveAgent
import com.cyclone.mobile.ai.OverlayChromeController
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ai.QuickAgentConfig
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Facade that attaches one Compose overlay window to the existing Accessibility service.
 * Overlay buttons change Cyclone controller state only; they never click host nodes.
 */
object OverlayChromeRuntime {
    private val lock = Any()
    private val aiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cycloneState = object : OverlayCycloneStateEffects {
        override fun pauseAgentForUser() {
            DeviceState.setController(DeviceState.Controller.HUMAN)
        }

        override fun resumeAgent() {
            DeviceState.setController(DeviceState.Controller.AGENT)
        }
    }

    private var machine = OverlayChromeMachine(
        emit = OverlayChromeBus::publish,
        cycloneState = cycloneState,
    )
    private var controller: OverlayChromeController? = null
    private var service: CycloneAccessibilityService? = null
    private var aiJob: Job? = null

    fun isAttached(): Boolean = synchronized(lock) { controller != null }

    fun snapshot(): OverlayChromeSnapshot = synchronized(lock) { machine.snapshot() }

    fun attach(service: CycloneAccessibilityService) {
        synchronized(lock) {
            if (controller != null) return
            this.service = service
            val next = OverlayChromeController(
                service = service,
                onAction = { action -> dispatch(action) },
                onComposerChanged = { text -> updateComposer(text) },
                onRequestSubmitted = { text -> submitRequest(text) },
                onVoiceStateChanged = { listening, transcript, message ->
                    updateVoice(listening, transcript, message)
                },
                getAiSettings = { readAiSettings(service) },
                onAiSettingsChanged = { settings -> saveAiSettings(service, settings) },
            )
            controller = next
            next.show(machine.snapshot())
        }
    }

    fun detach() {
        synchronized(lock) {
            controller?.dismiss()
            controller = null
            service = null
            aiJob?.cancel()
            aiJob = null
            machine = OverlayChromeMachine(
                emit = OverlayChromeBus::publish,
                cycloneState = cycloneState,
            )
        }
    }

    fun startAnalysis(
        sessionId: String,
        bullets: List<String> = emptyList(),
        cta: OverlayAnalysisCta = OverlayAnalysisCta.CONFIRM,
    ) {
        mutate { it.startAnalysis(sessionId, bullets, cta) }
    }

    fun enterWorking(sessionId: String = snapshot().sessionId) {
        mutate { it.enterWorking(sessionId) }
    }

    fun enterLive() {
        mutate { it.enterLive() }
    }

    fun enterGate(gateClass: OverlayGateClass, pcAutoApprove: Boolean = false, sessionId: String = snapshot().sessionId) {
        mutate { it.enterGate(gateClass, pcAutoApprove, sessionId) }
    }

    fun completeDone(sessionId: String = snapshot().sessionId) {
        mutate { it.completeDone(sessionId) }
    }

    fun resetIdle(idleChipVisible: Boolean = true) {
        mutate { it.resetIdle(idleChipVisible) }
    }

    fun dispatch(action: OverlayUserAction) {
        mutate { it.dispatch(action) }
        if (action == OverlayUserAction.EXIT || action == OverlayUserAction.STOP_TASK) {
            synchronized(lock) {
                aiJob?.cancel()
                aiJob = null
            }
        }
    }

    fun updateComposer(text: String) {
        mutate { it.updateComposer(text) }
    }

    fun submitRequest(text: String) {
        val request = text.trim().take(2_000)
        if (request.isBlank()) return
        val accepted = synchronized(lock) {
            val before = machine.snapshot()
            machine.submitRequest(request)
            val changed = before.state == OverlayChromeState.ANALYSIS ||
                before.state == OverlayChromeState.WORKING ||
                before.state == OverlayChromeState.LIVE
            controller?.render(machine.snapshot())
            changed
        }
        if (accepted) runAiRequest(request)
    }

    fun updateVoice(listening: Boolean, transcript: String? = null, message: String? = null) {
        mutate { it.updateVoice(listening, transcript, message) }
    }

    fun beginVoiceInput() {
        synchronized(lock) { controller?.beginVoiceInput() }
    }

    private fun runAiRequest(request: String) {
        val context = synchronized(lock) { service } ?: return
        synchronized(lock) { aiJob?.cancel() }
        val job = aiScope.launch {
            mutate {
                it.enterWorking()
                it.updateStatus("Starting…")
            }
            val settings = readAiSettings(context)
            val accessProfile = CycloneAiAccessProfileStore.read(context)
            val result = OpenRouterAdaptiveAgent(context).execute(
                request,
                QuickAgentConfig(
                    model = OpenRouterModelPresets.byId(settings.modelId).copy(
                        reasoningEffort = settings.reasoningEffort,
                    ),
                    safeMode = accessProfile != CycloneAiAccessProfile.FULL,
                    accessProfile = accessProfile,
                ),
            ) { progress -> mutate { it.updateStatus(progress) } }
            when (snapshot().state) {
                OverlayChromeState.GATE, OverlayChromeState.IDLE -> Unit
                OverlayChromeState.WORKING, OverlayChromeState.LIVE -> if (result.ok) {
                    mutate { it.completeDone() }
                } else {
                    mutate {
                        it.enterLive()
                        it.updateStatus(result.message)
                    }
                }
                else -> Unit
            }
        }
        synchronized(lock) { aiJob = job }
    }

    private fun mutate(block: (OverlayChromeMachine) -> Unit) {
        synchronized(lock) {
            block(machine)
            controller?.render(machine.snapshot())
        }
    }

    private fun readAiSettings(context: Context): OverlayAiSettings {
        val prefs = context.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE)
        val savedModel = prefs.getString(MODEL_KEY, OpenRouterModelPresets.DEFAULT.id).orEmpty()
        val modelId = savedModel.takeIf { id -> OpenRouterModelPresets.all.any { it.id == id } }
            ?: OpenRouterModelPresets.DEFAULT.id
        val effort = prefs.getString(EFFORT_KEY, "medium").orEmpty()
            .takeIf { it in REASONING_LEVELS } ?: "medium"
        return OverlayAiSettings(modelId, effort)
    }

    private fun saveAiSettings(context: Context, settings: OverlayAiSettings) {
        val modelId = settings.modelId.takeIf { id -> OpenRouterModelPresets.all.any { it.id == id } }
            ?: OpenRouterModelPresets.DEFAULT.id
        val effort = settings.reasoningEffort.takeIf { it in REASONING_LEVELS } ?: "medium"
        context.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE).edit()
            .putString(MODEL_KEY, modelId)
            .putString(EFFORT_KEY, effort)
            .apply()
    }

    private const val AI_PREFS = "cyclone_ai"
    private const val MODEL_KEY = "openrouter_model"
    private const val EFFORT_KEY = "openrouter_reasoning_effort"
    private val REASONING_LEVELS = setOf("low", "medium", "high", "max")
}
