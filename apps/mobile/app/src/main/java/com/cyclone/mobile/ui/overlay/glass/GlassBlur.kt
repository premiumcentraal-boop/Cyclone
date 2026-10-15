package com.cyclone.mobile.ui.overlay.glass

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the overlay's glass blurs the app behind it (B) or stays darkened smoke (A). Only Android's own signals
 * decide, so the check itself costs nothing: nothing polls and nothing measures frames.
 * - Android's cross-window blur switch (one listener): off on phones that cannot blur, in battery saver and when the
 *   owner turns window blurs off.
 * - The phone's heat (one listener): from "moderate" up, blur steps aside until it cools.
 * Blur needs Android 12. A blur window that fails to open turns it off for the rest of the session.
 */
object GlassBlur {
    /** The blur behind a panel (dp). */
    const val RADIUS_DP = 24
    /** How much lighter the smoke gets while the app behind is blurred (body alpha 0.86 → 0.74). */
    const val VEIL_LIFT = 0.12f
    /** The switch between A and B fades over this long, so it never jumps. */
    const val FADE_MS = 320

    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on

    private var allowed = false
    private var thermal = 0
    private var failed = false
    private var started = false
    private var blurListener: java.util.function.Consumer<Boolean>? = null
    private var heatListener: PowerManager.OnThermalStatusChangedListener? = null

    /** The rule, kept pure: Android 12+, Android allows blur now, the phone is not hot, and no blur window failed. */
    fun decide(sdk: Int, allowed: Boolean, thermalStatus: Int, failed: Boolean): Boolean =
        sdk >= Build.VERSION_CODES.S && allowed && thermalStatus < PowerManager.THERMAL_STATUS_MODERATE && !failed

    /** Starts listening (main thread, idempotent). */
    fun start(context: Context) {
        if (started || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        started = true
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val pm = context.getSystemService(PowerManager::class.java)
        val executor = context.mainExecutor
        runCatching {
            val listener = java.util.function.Consumer<Boolean> { allowed = it; update() }
            blurListener = listener
            wm.addCrossWindowBlurEnabledListener(executor, listener)
        }
        if (pm != null) runCatching {
            thermal = pm.currentThermalStatus
            val listener = PowerManager.OnThermalStatusChangedListener { thermal = it; update() }
            heatListener = listener
            pm.addThermalStatusListener(executor, listener)
        }
        allowed = runCatching { wm.isCrossWindowBlurEnabled }.getOrDefault(false)
        update()
    }

    fun stop(context: Context) {
        if (!started) return
        started = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurListener?.let { l -> runCatching { context.getSystemService(WindowManager::class.java)?.removeCrossWindowBlurEnabledListener(l) } }
            heatListener?.let { l -> runCatching { context.getSystemService(PowerManager::class.java)?.removeThermalStatusListener(l) } }
        }
        blurListener = null
        heatListener = null
        _on.value = false
    }

    /** A blur window could not open: stay with smoke for this session. */
    fun failed() { failed = true; update() }

    private fun update() { _on.value = decide(Build.VERSION.SDK_INT, allowed, thermal, failed) }
}

/** One panel that wants the app behind it blurred: where it is (window pixels) and its corner radius (px). */
data class BlurRegion(val bounds: Rect, val cornerRadiusPx: Float)

/**
 * The overlay window's blur bookkeeping: the panels on screen, which of them have a blur window behind them, and
 * which are moving. A blur window can only follow a panel a frame or two late, so it never tries: while a panel moves
 * (appears, slides, resizes, goes) its blur steps aside and the panel shows smoke; once it has been still for
 * [SETTLE_MS] the blur comes back and its smoke lightens. [schedule] runs a callback later on the main thread;
 * [onChanged] runs whenever the windows should be synced.
 */
class BlurHost(
    private val slots: Int,
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit,
    /**
     * Alpha.131: a change no edge of which moves further than this (px) is a resize in place, not a move. The blur
     * follows it at once instead of stepping aside, so a work card that gains or loses a line of text doesn't pulse
     * dark and light on every step. 0 keeps the old rule (every change steps aside).
     */
    private val smallChangePx: Float = 0f,
    private val onChanged: () -> Unit,
) {
    private val order = ArrayList<Any>()
    private val regions = HashMap<Any, BlurRegion>()
    private val moving = HashSet<Any>()
    private var moveGeneration = 0
    private var blurred by mutableStateOf<Set<Any>>(emptySet())

    fun place(key: Any, region: BlurRegion) {
        val before = regions[key]
        if (before == region) return
        if (key !in order) order += key
        regions[key] = region
        if (before != null && key !in moving && small(before, region)) {
            onChanged()
            return
        }
        moved(key)
    }

    private fun small(a: BlurRegion, b: BlurRegion): Boolean {
        if (smallChangePx <= 0f || a.cornerRadiusPx != b.cornerRadiusPx) return false
        val edges = listOf(a.bounds.left - b.bounds.left, a.bounds.top - b.bounds.top, a.bounds.right - b.bounds.right, a.bounds.bottom - b.bounds.bottom)
        return edges.all { kotlin.math.abs(it) <= smallChangePx }
    }

    fun remove(key: Any) {
        if (regions.remove(key) == null) return
        order -= key
        moving -= key
        publish()
        onChanged()
    }

    /** The whole overlay window moved: every panel is in motion. */
    fun jolt() {
        if (order.isEmpty()) return
        order.forEach { moving += it }
        settleLater()
    }

    /** One entry per blur window, in order: the panel to blur behind, or null while it moves or is not there. */
    fun shown(): List<BlurRegion?> = order.take(slots).map { if (it in moving) null else regions[it] }

    /** Whether this panel has its blur behind it right now (a state: the panel's smoke follows it). */
    fun isBlurred(key: Any): Boolean = key in blurred

    private fun moved(key: Any) {
        moving += key
        settleLater()
    }

    private fun settleLater() {
        val generation = ++moveGeneration
        publish()
        onChanged()
        schedule(SETTLE_MS) {
            if (generation != moveGeneration) return@schedule
            moving.clear()
            publish()
            onChanged()
        }
    }

    private fun publish() { blurred = order.take(slots).filter { it !in moving }.toSet() }

    companion object {
        /** How long a panel stays still before its blur returns. */
        const val SETTLE_MS = 160L
        /** A resize up to about two lines of card text is followed in place. */
        const val SMALL_CHANGE_DP = 48f
    }
}

/** The overlay window's [BlurHost]; null everywhere else (the app's own screens have their own blur). */
val LocalBlurHost = staticCompositionLocalOf<BlurHost?> { null }

/** A panel's hook into the blur: [modifier] reports where it is, [lift] lightens its smoke while it is blurred. */
class BlurPanel(val modifier: Modifier, val lift: () -> Float)

private val NoBlur = BlurPanel(Modifier) { 0f }

/** For a top-level overlay panel with [cornerRadius]: outside the overlay window it does nothing. */
@Composable
fun rememberBlurPanel(cornerRadius: Dp): BlurPanel {
    val host = LocalBlurHost.current ?: return NoBlur
    val key = remember { Any() }
    val radiusPx = with(LocalDensity.current) { cornerRadius.toPx() }
    DisposableEffect(host, key) { onDispose { host.remove(key) } }
    val on by GlassBlur.on.collectAsState()
    // This panel's smoke lightens only while its own blur is behind it: in as the blur settles, out quickly when the
    // panel starts to move (the blur has already stepped aside).
    val blurred = on && host.isBlurred(key)
    val lift = animateFloatAsState(if (blurred) GlassBlur.VEIL_LIFT else 0f,
        tween(if (blurred) GlassBlur.FADE_MS else 90), label = "Glass smoke")
    return remember(host, key, radiusPx) {
        BlurPanel(Modifier.onGloballyPositioned { host.place(key, BlurRegion(it.boundsInWindow(), radiusPx)) }) { lift.value }
    }
}
