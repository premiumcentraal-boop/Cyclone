package com.cyclone.mobile.runtime.safe

/** Process-local barrier: pairing/restore may run, but ordinary runtime stores must wait for recovery. */
internal object SafeStartupGate {
    @Volatile var blocked = false
        private set
    private val waiting = mutableListOf<() -> Unit>()
    @Synchronized fun initialize(restoreRequired: Boolean) { blocked = restoreRequired }
    fun whenReady(action: () -> Unit) {
        synchronized(this) { if (blocked) { waiting += action; return } }
        action()
    }
    fun complete() {
        val actions = synchronized(this) { blocked = false; waiting.toList().also { waiting.clear() } }
        actions.forEach { runCatching(it) }
    }
}
