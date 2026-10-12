package com.cyclone.mobile.ui.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the confirmation gate shows the owner: the class of the action, the app it happens in (the main screen's app
 * when the gate opened, null for an action outside any app) and the words on the control Cyclone stopped before
 * ("Send", "Pay €12.99"). Display only: kept in memory, never persisted, logged or sent to a model; the exact-action
 * grant stays [OverlayChromeRuntime]'s signature.
 */
data class GateShown(val gateClass: OverlayGateClass, val app: String?, val control: String?) {
    companion object {
        private val _current = MutableStateFlow<GateShown?>(null)
        val current: StateFlow<GateShown?> = _current

        fun record(gateClass: OverlayGateClass, labels: List<String>, app: String? = WorkingApp.foreground.value) {
            _current.value = GateShown(gateClass, app, control(labels))
        }

        fun clear() { _current.value = null }

        /** The control's words: the first short label with letters; long text or number runs (codes) are left out. */
        internal fun control(labels: List<String>): String? = labels.asSequence()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .firstOrNull { it.length in 1..32 && it.any(Char::isLetter) && it.count(Char::isDigit) <= 6 }
    }
}
