package com.cyclone.mobile.ui.overlay

import java.util.concurrent.atomic.AtomicInteger

/**
 * Yield Cyclone overlay windows for an already-authorized host action.
 *
 * Two levels, so the overlay stays on screen (the owner's "always stable" ask, alpha.131):
 * - **Step back** ([withHostFocus]): the windows stay visible but take no focus, and leave the
 *   accessibility tree, so the host keeps focus, Back reaches it and its tree reads clean. Every host action and its
 *   result settling runs like this.
 * - **Hide** ([withHostPassthrough], [withHostStroke]): GONE + not important, only for that stroke, and only when it
 *   touches the overlay's bounds ([overlaps]); a stroke elsewhere on the screen only steps back. 4.8.0 found strokes
 *   landing on Cyclone because its windows were touchable and turned untouchable just before the stroke, which a busy
 *   main thread could miss. Alpha.134: what hides now draws nothing. The card is drawn in a window that never takes a
 *   touch (like the Trace Field and the halo, which strokes have always passed), and only its empty touch layer and
 *   the empty idle hotspot step aside, so the owner never sees a blink.
 *
 * Overlay buttons still never click host nodes. While stepped back the glass still takes the owner's touch (Stop works);
 * it is unusable only for a stroke on it, and just after (the echo window).
 */
object OverlayGesturePassthrough {
    private val depth = AtomicInteger(0)
    private val hideDepth = AtomicInteger(0)
    @Volatile private var enabled = false
    @Volatile private var hidden = false
    @Volatile private var applyFlags: ((Boolean) -> Unit)? = null

    /** A host action runs: Cyclone's windows take no focus and leave the accessibility tree (still on screen). */
    fun active(): Boolean = enabled

    /** A stroke lands on Cyclone's glass right now: its windows are GONE for that stroke. */
    fun hidden(): Boolean = hidden

    /** Where Cyclone's windows are on screen (left, top, right, bottom in pixels), one provider per window owner. */
    private val regionProviders = java.util.concurrent.CopyOnWriteArrayList<() -> List<IntArray>>()

    fun regions(provider: () -> List<IntArray>): () -> Unit {
        regionProviders += provider
        return { regionProviders -= provider }
    }

    /**
     * Whether a stroke over [left, top, right, bottom] (screen pixels) comes within [marginPx] of a Cyclone window.
     * Unknown means yes: a provider that fails, or a stroke with no bounds, hides the overlay as before.
     */
    fun overlaps(bounds: FloatArray?, marginPx: Float = OVERLAP_MARGIN_PX): Boolean {
        if (bounds == null || bounds.size != 4) return true
        return regionProviders.any { provider ->
            val rects = runCatching { provider() }.getOrNull() ?: return true
            rects.any { r ->
                r.size == 4 && bounds[0] <= r[2] + marginPx && bounds[2] >= r[0] - marginPx &&
                    bounds[1] <= r[3] + marginPx && bounds[3] >= r[1] - marginPx
            }
        }
    }

    /** Room around Cyclone's glass a stroke must keep for the overlay to stay on screen. */
    const val OVERLAP_MARGIN_PX = 48f

    /**
     * Alpha 91: when Cyclone's own injected gesture last ended. A touch on Cyclone's buttons during a gesture or just
     * after it is that gesture landing on Cyclone's glass (the panel could not step aside in time), never the owner:
     * a Mind run paused itself that way and then sat idle until it timed out.
     */
    @Volatile private var lastEndedAtMs = 0L

    fun injectedRecently(windowMs: Long = INJECTED_ECHO_MS, nowMs: Long = System.nanoTime() / 1_000_000): Boolean =
        hidden || nowMs - lastEndedAtMs in 0..windowMs

    const val INJECTED_ECHO_MS = 700L

    /**
     * Alpha.134, the built-in check: a touch that reaches Cyclone's touch layer while it should have stepped aside is
     * the agent's own stroke arriving before Android applied the step. It is dropped (never a Cyclone button) and
     * counted, so a phone that behaves differently shows up as a number, not a guess.
     */
    private val strays = AtomicInteger(0)

    fun stray(): Int = strays.incrementAndGet()

    fun strayCount(): Int = strays.get()

    @Volatile private var straysAtHide = 0
    @Volatile private var strayed = false

    /** Whether a touch reached the touch layer during the last stroke on the glass; reading it clears it. */
    fun consumeStrayed(): Boolean = strayed.also { strayed = false }

    /**
     * Alpha.78: Cyclone's other windows (Drive's voice panel and button) yield to an injected gesture too, so a swipe or
     * tap Cyclone makes lands on the app, never on its own glass. A follower returns once its windows let touches through.
     */
    private val followers = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun follow(listener: (Boolean) -> Unit): () -> Unit {
        followers += listener
        return { followers -= listener }
    }

    fun bind(applyFlags: (Boolean) -> Unit) {
        this.applyFlags = applyFlags
    }

    fun unbind() {
        applyFlags = null
    }

    /** A host action and its settling: the overlay steps back but stays on screen. */
    fun <T> withHostFocus(block: () -> T): T = yielding(hide = false, block)

    /** Hides the overlay for [block]: a stroke whose bounds aren't known, or that lands on Cyclone's glass. */
    fun <T> withHostPassthrough(block: () -> T): T = yielding(hide = true, block)

    /** A stroke over [bounds] (screen pixels: left, top, right, bottom): hidden only when it touches the overlay. */
    fun <T> withHostStroke(bounds: FloatArray?, block: () -> T): T = yielding(hide = overlaps(bounds), block)

    private fun <T> yielding(hide: Boolean, block: () -> T): T {
        val entered = depth.getAndIncrement() == 0
        val hiding = hide && hideDepth.getAndIncrement() == 0
        if (entered || hiding) {
            enabled = true
            if (hiding) { hidden = true; straysAtHide = strays.get(); strayed = false }
            applyFlags?.invoke(true)
            // Drive's voice windows have no step back of their own: they leave only for a stroke that lands on glass.
            if (hiding) followers.forEach { runCatching { it(true) } }
        }
        try {
            return block()
        } finally {
            val unhide = hide && hideDepth.decrementAndGet() == 0
            val left = depth.decrementAndGet() == 0
            if (unhide || left) {
                // Alpha.131: the echo window follows a stroke on Cyclone's glass, not every host action, so the owner's
                // Stop works while Cyclone acts elsewhere on the screen.
                if (unhide) {
                    hidden = false
                    strayed = strays.get() != straysAtHide
                    lastEndedAtMs = System.nanoTime() / 1_000_000
                }
                if (left) enabled = false
                applyFlags?.invoke(enabled)
                if (unhide) followers.forEach { runCatching { it(false) } }
            }
        }
    }

    internal fun resetForTests() {
        lastEndedAtMs = 0L
        depth.set(0)
        hideDepth.set(0)
        hidden = false
        regionProviders.clear()
        strays.set(0)
        straysAtHide = 0
        strayed = false
        enabled = false
        applyFlags = null
        followers.clear()
    }
}
