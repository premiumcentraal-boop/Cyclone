package com.cyclone.mobile.voice

/**
 * Plan 65 R1, deciding while they speak: one piece of work started early for [K] (a clip's span, an understanding
 * question), handed over only to someone asking for exactly the same [K]. Anything else cancels it: a head start is
 * never used for words it wasn't started on. Pure: [H] is whatever handle the work has (a coroutine Deferred).
 */
class HeadStart<K : Any, H : Any>(private val cancel: (H) -> Unit) {
    private var key: K? = null
    private var handle: H? = null

    /** Starts the work for [key] unless it is already running for it; replaces (and cancels) work for another key. */
    @Synchronized
    fun start(key: K, begin: () -> H): Boolean {
        if (handle != null && this.key == key) return false
        handle?.let(cancel)
        this.key = key
        handle = runCatching(begin).getOrNull()
        return handle != null
    }

    /** The work started for exactly [key], or null; either way the slot is empty after (other work is cancelled). */
    @Synchronized
    fun take(key: K): H? {
        val h = handle
        val matches = h != null && this.key == key
        this.key = null
        handle = null
        if (!matches) h?.let(cancel)
        return if (matches) h else null
    }

    /** Cancels whatever is waiting. */
    @Synchronized
    fun clear() {
        handle?.let(cancel)
        key = null
        handle = null
    }
}
