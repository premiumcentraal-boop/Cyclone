package com.cyclone.mobile.policy

/**
 * The model's own short, user-facing reason for what it is doing now: the "why" of the Mind's current plan step, or
 * the classic agent's displaySummary. Written to be shown to the owner (never hidden chain-of-thought); the
 * confirmation gate shows it behind an info icon. In memory only, never persisted or logged, and dropped when stale.
 */
object ActionReason {
    private const val FRESH_MS = 10 * 60_000L
    private const val MAX = 200

    @Volatile private var latest: Pair<String, Long>? = null

    fun set(reason: String?, nowMs: Long = System.currentTimeMillis()) {
        val clean = reason?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX)?.takeIf { it.isNotEmpty() } ?: return
        latest = clean to nowMs
    }

    fun clear() { latest = null }

    /** The reason when it was given in the last few minutes, else null. */
    fun current(nowMs: Long = System.currentTimeMillis()): String? =
        latest?.takeIf { nowMs - it.second in 0..FRESH_MS }?.first
}
