package com.cyclone.mobile.ui.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the confirmation gate shows the owner: the class of the action, the app it happens in (the main screen's app
 * when the gate opened, null for an action outside any app) and the model's own reason for the step
 * ([com.cyclone.mobile.policy.ActionReason]). Display only: kept in memory, never persisted, logged or sent to a model;
 * the exact-action grant stays [OverlayChromeRuntime]'s signature.
 */
data class GateShown(val gateClass: OverlayGateClass, val app: String?, val why: String? = null) {
    companion object {
        private val _current = MutableStateFlow<GateShown?>(null)
        val current: StateFlow<GateShown?> = _current

        fun record(
            gateClass: OverlayGateClass,
            app: String? = WorkingApp.foreground.value,
            why: String? = com.cyclone.mobile.policy.ActionReason.current(),
        ) {
            _current.value = GateShown(gateClass, app, why)
        }

        fun clear() { _current.value = null }
    }
}
