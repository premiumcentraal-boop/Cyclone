package com.cyclone.mobile.ui.overlay

/**
 * JVM-testable overlay state machine. Buttons change Cyclone state only.
 * Host taps are not dispatched from here — PhoneToolExecutor remains the only mutation engine.
 */
class OverlayChromeMachine(
    private val emit: (OverlayChromeEvent) -> Unit = OverlayChromeBus::publish,
    private val cycloneState: OverlayCycloneStateEffects = OverlayCycloneStateEffects.NoOp,
) {
    @Volatile
    private var snapshot: OverlayChromeSnapshot = OverlayChromeSnapshot()

    fun snapshot(): OverlayChromeSnapshot = snapshot

    fun state(): OverlayChromeState = snapshot.state

    fun startAnalysis(
        sessionId: String,
        bullets: List<String> = emptyList(),
        cta: OverlayAnalysisCta = OverlayAnalysisCta.CONFIRM,
    ) {
        if (snapshot.state != OverlayChromeState.IDLE && snapshot.state != OverlayChromeState.DONE) return
        snapshot = OverlayChromeSnapshot(
            state = OverlayChromeState.ANALYSIS,
            sessionId = sessionId,
            bullets = bullets,
            analysisCta = cta,
            idleChipVisible = false,
        )
    }

    fun enterWorking(sessionId: String = snapshot.sessionId) {
        if (snapshot.state != OverlayChromeState.IDLE &&
            snapshot.state != OverlayChromeState.ANALYSIS &&
            snapshot.state != OverlayChromeState.LIVE
        ) return
        snapshot = snapshot.copy(
            state = OverlayChromeState.WORKING,
            sessionId = sessionId.ifBlank { snapshot.sessionId },
            idleChipVisible = false,
            minimized = false,
            userPaused = false,
        )
        cycloneState.resumeAgent()
    }

    fun enterLive() {
        if (snapshot.state != OverlayChromeState.WORKING) return
        snapshot = snapshot.copy(state = OverlayChromeState.LIVE, idleChipVisible = false, minimized = false)
    }

    fun enterGate(gateClass: OverlayGateClass, pcAutoApprove: Boolean = false, sessionId: String = snapshot.sessionId) {
        if (snapshot.state == OverlayChromeState.GATE) return
        snapshot = snapshot.copy(
            state = OverlayChromeState.GATE,
            sessionId = sessionId.ifBlank { snapshot.sessionId },
            gateClass = gateClass,
            idleChipVisible = false,
            pcAutoApproveIgnored = pcAutoApprove,
            minimized = false,
            userPaused = true,
        )
        cycloneState.pauseAgentForUser()
        emit(
            OverlayChromeEvent(
                kind = OverlayChromeEventKind.GATE,
                state = OverlayChromeState.GATE,
                sessionId = snapshot.sessionId,
                gateClass = gateClass,
                pcAutoApproveIgnored = pcAutoApprove,
            ),
        )
    }

    fun completeDone(sessionId: String = snapshot.sessionId) {
        if (snapshot.state != OverlayChromeState.WORKING && snapshot.state != OverlayChromeState.LIVE) return
        snapshot = snapshot.copy(
            state = OverlayChromeState.DONE,
            sessionId = sessionId.ifBlank { snapshot.sessionId },
            idleChipVisible = false,
            minimized = false,
            userPaused = false,
        )
        emit(
            OverlayChromeEvent(
                kind = OverlayChromeEventKind.DONE,
                state = OverlayChromeState.DONE,
                sessionId = snapshot.sessionId,
                gateClass = snapshot.gateClass,
            ),
        )
    }

    fun resetIdle(idleChipVisible: Boolean = true) {
        snapshot = OverlayChromeSnapshot(idleChipVisible = idleChipVisible)
    }

    fun dispatch(action: OverlayUserAction) {
        when (action) {
            OverlayUserAction.ASK_CYCLONE -> askCyclone()
            OverlayUserAction.CONFIRM -> confirm()
            OverlayUserAction.COMMERCE -> commerce()
            OverlayUserAction.VIEW_PROGRESS -> viewProgress()
            OverlayUserAction.STOP_TASK -> stopTask()
            OverlayUserAction.TAKE_CONTROL -> takeControl()
            OverlayUserAction.GATE_CONFIRM -> gateConfirm()
            OverlayUserAction.MINIMIZE -> minimize()
            OverlayUserAction.EXIT -> exitAiMode()
        }
    }

    private fun askCyclone() {
        if (snapshot.minimized) {
            snapshot = snapshot.copy(minimized = false, idleChipVisible = false)
            return
        }
        if (snapshot.state != OverlayChromeState.IDLE) return
        snapshot = snapshot.copy(
            state = OverlayChromeState.ANALYSIS,
            idleChipVisible = false,
            minimized = false,
            userPaused = false,
        )
        emitChrome(OverlayChromeEventKind.ASK_CYCLONE)
    }

    fun updateComposer(text: String) {
        if (snapshot.state != OverlayChromeState.ANALYSIS &&
            snapshot.state != OverlayChromeState.WORKING &&
            snapshot.state != OverlayChromeState.LIVE
        ) return
        snapshot = snapshot.copy(composerText = text.take(2_000), voiceMessage = null)
    }

    fun submitRequest(text: String = snapshot.composerText) {
        val request = text.trim().take(2_000)
        if (request.isBlank() ||
            (snapshot.state != OverlayChromeState.ANALYSIS &&
                snapshot.state != OverlayChromeState.WORKING &&
                snapshot.state != OverlayChromeState.LIVE)
        ) return
        snapshot = snapshot.copy(
            composerText = "",
            bullets = listOf(request),
            voiceMessage = null,
        )
        emitChrome(OverlayChromeEventKind.ASK_CYCLONE, requestText = request)
    }

    fun updateVoice(listening: Boolean, transcript: String? = null, message: String? = null) {
        if (snapshot.state != OverlayChromeState.ANALYSIS &&
            snapshot.state != OverlayChromeState.WORKING &&
            snapshot.state != OverlayChromeState.LIVE
        ) return
        snapshot = snapshot.copy(
            voiceListening = listening,
            composerText = transcript?.take(2_000) ?: snapshot.composerText,
            voiceMessage = message,
        )
    }

    fun updateStatus(message: String?) {
        if (snapshot.state == OverlayChromeState.IDLE || snapshot.state == OverlayChromeState.GATE) return
        snapshot = snapshot.copy(statusMessage = message?.take(500))
    }

    private fun confirm() {
        if (snapshot.state != OverlayChromeState.ANALYSIS) return
        if (snapshot.analysisCta != OverlayAnalysisCta.CONFIRM) return
        snapshot = snapshot.copy(state = OverlayChromeState.WORKING, idleChipVisible = false, userPaused = false)
        cycloneState.resumeAgent()
        emitChrome(OverlayChromeEventKind.CONFIRM)
    }

    private fun commerce() {
        if (snapshot.state != OverlayChromeState.ANALYSIS) return
        if (snapshot.analysisCta != OverlayAnalysisCta.COMMERCE) return
        snapshot = snapshot.copy(state = OverlayChromeState.WORKING, idleChipVisible = false, userPaused = false)
        cycloneState.resumeAgent()
        emitChrome(OverlayChromeEventKind.COMMERCE)
    }

    private fun viewProgress() {
        if (snapshot.state != OverlayChromeState.WORKING) return
        snapshot = snapshot.copy(state = OverlayChromeState.LIVE, idleChipVisible = false, minimized = false)
        emitChrome(OverlayChromeEventKind.VIEW_PROGRESS)
    }

    private fun stopTask() {
        if (snapshot.state != OverlayChromeState.WORKING && snapshot.state != OverlayChromeState.LIVE) return
        val from = snapshot.state
        cycloneState.pauseAgentForUser()
        snapshot = OverlayChromeSnapshot(sessionId = snapshot.sessionId, idleChipVisible = true)
        emit(
            OverlayChromeEvent(
                kind = OverlayChromeEventKind.STOP_TASK,
                state = from,
                sessionId = snapshot.sessionId,
            ),
        )
    }

    private fun takeControl() {
        if (snapshot.state != OverlayChromeState.ANALYSIS &&
            snapshot.state != OverlayChromeState.WORKING &&
            snapshot.state != OverlayChromeState.LIVE
        ) return
        if (snapshot.userPaused) cycloneState.resumeAgent() else cycloneState.pauseAgentForUser()
        snapshot = snapshot.copy(userPaused = !snapshot.userPaused)
        emitChrome(OverlayChromeEventKind.TAKE_CONTROL)
    }

    private fun minimize() {
        if (snapshot.state == OverlayChromeState.IDLE) return
        snapshot = snapshot.copy(minimized = true, idleChipVisible = true, voiceListening = false)
    }

    private fun exitAiMode() {
        val from = snapshot.state
        if (from == OverlayChromeState.IDLE) {
            resetIdle(idleChipVisible = false)
            return
        }
        if (from != OverlayChromeState.DONE) {
            cycloneState.pauseAgentForUser()
            emit(
                OverlayChromeEvent(
                    kind = OverlayChromeEventKind.STOP_TASK,
                    state = from,
                    sessionId = snapshot.sessionId,
                    gateClass = snapshot.gateClass,
                ),
            )
        }
        resetIdle()
    }

    private fun gateConfirm() {
        if (snapshot.state != OverlayChromeState.GATE) return
        val gateClass = snapshot.gateClass
        cycloneState.resumeAgent()
        emitChrome(OverlayChromeEventKind.GATE_CONFIRM, gateClass = gateClass)
        snapshot = snapshot.copy(
            state = OverlayChromeState.DONE,
            idleChipVisible = false,
            minimized = false,
            userPaused = false,
        )
        emit(
            OverlayChromeEvent(
                kind = OverlayChromeEventKind.DONE,
                state = OverlayChromeState.DONE,
                sessionId = snapshot.sessionId,
                gateClass = gateClass,
            ),
        )
    }

    private fun emitChrome(
        kind: OverlayChromeEventKind,
        gateClass: OverlayGateClass? = snapshot.gateClass,
        requestText: String? = null,
    ) {
        emit(
            OverlayChromeEvent(
                kind = kind,
                state = snapshot.state,
                sessionId = snapshot.sessionId,
                gateClass = gateClass,
                pcAutoApproveIgnored = snapshot.pcAutoApproveIgnored,
                requestText = requestText,
                userPaused = snapshot.userPaused,
            ),
        )
    }
}

interface OverlayCycloneStateEffects {
    fun pauseAgentForUser()
    fun resumeAgent()

    object NoOp : OverlayCycloneStateEffects {
        override fun pauseAgentForUser() = Unit
        override fun resumeAgent() = Unit
    }
}
