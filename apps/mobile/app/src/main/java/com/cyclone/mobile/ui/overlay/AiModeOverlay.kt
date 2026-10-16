package com.cyclone.mobile.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.ai.OverlayComposeLifecycle
import com.cyclone.mobile.ui.overlay.glass.TiltGlassTheme
import com.cyclone.mobile.ui.overlay.glass.tiltGlass
import com.cyclone.mobile.ui.overlay.glass.veilPill
import com.cyclone.mobile.voice.ButtonSpot
import com.cyclone.mobile.voice.DriverMode
import com.cyclone.mobile.voice.VoiceFace
import com.cyclone.mobile.voice.VoiceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Driver mode's windows (plan 32), next to the normal chrome:
 * - the AI button: the orb on idle-bubble glass, where the idle bubble was. Tap to talk; hold one second, then drag
 *   it to an edge, where it stays (per orientation);
 * - AI mode: a Tilt Glass panel rising from the bottom while a request is live, with the owner's words, Cyclone's line,
 *   the big orb, one big Stop, and "Not now" when Cyclone asks;
 * - the dim: the screen darkens only while a request is live; it never takes a touch.
 *
 * The button and panel only call the [VoiceSession]; the session hands work to Cyclone through the overlay runtime
 * and Task Kit.
 */
object DriverOverlay {
    private var service: CycloneAccessibilityService? = null
    private var scope: CoroutineScope? = null
    private var session: VoiceSession? = null
    private var windows: Windows? = null

    /** Alpha 95: the keeper's state, see [heal]. */
    private var failures = 0
    private var lastRepairMs = 0L
    private var buttonDp = 0
    private var faceJob: kotlinx.coroutines.Job? = null
    /** Whether the voice panel is up: the Ask overlay steps aside for it, and comes back as it was after. */
    val panelOpen = kotlinx.coroutines.flow.MutableStateFlow(false)

    private fun showFace(w: Windows, face: VoiceFace) {
        w.show(face)
        if (panelOpen.value != face.panel) {
            panelOpen.value = face.panel
            OverlayChromeRuntime.refreshExternalSurface()
        }
    }

    /** Live voice started from the Ask bar, without the car button: it ends when the conversation does. */
    private var liveOnly = false
    private var endJob: kotlinx.coroutines.Job? = null

    /**
     * Live voice: the Ask bar's empty-field button. Talks now, in the same conversation Driver mode's button opens:
     * with the car button on, that session listens; otherwise a session without the button opens for this
     * conversation and closes once it, and any task it started, is over.
     */
    fun live(service: CycloneAccessibilityService) {
        attach(service)
        session?.let { s -> guarded("live.tap") { s.tap() }; return }
        guarded("live.start") { startLive(service) }
    }

    private fun startLive(service: CycloneAccessibilityService) {
        val next = VoiceSession(service, VoiceTraceSink(service))
        session = next
        liveOnly = true
        val w = runCatching { Windows(service, next, DriverMode.settings.value.buttonDp, withButton = false) }
            .onFailure { record("live.windows", it) }
            .getOrNull()
        windows = w
        if (w == null) { stop(); return }
        var opened = false
        faceJob = scope?.launch {
            next.turn.collect { turn ->
                guarded("live.face") { showFace(w, VoiceFace.of(turn)) }
                if (busy(turn)) { opened = true; endJob?.cancel(); endJob = null }
                else if (opened && endJob == null) endJob = scope?.launch {
                    // A finished line stays readable a moment; "keep listening" reopens the mic within this grace.
                    kotlinx.coroutines.delay(LIVE_CLOSE_AFTER_MS)
                    if (session === next && !busy(next.turn.value)) stop()
                }
            }
        }
        next.tap()
    }

    private fun busy(turn: com.cyclone.mobile.voice.VoiceTurn): Boolean =
        turn.phase !in setOf(com.cyclone.mobile.voice.VoicePhase.IDLE, com.cyclone.mobile.voice.VoicePhase.CLOSED) ||
            turn.taskLive || turn.speaking || turn.quickLive

    /**
     * Attaches the Drive windows to [service]. Called by the accessibility service itself (alpha 95), so the orb no
     * longer depends on the main chrome attaching first, and safe to call again: the same live service is kept.
     */
    fun attach(service: CycloneAccessibilityService) {
        if (this.service === service && scope != null) {
            heal()
            return
        }
        detach()
        this.service = service
        DriverMode.load(service)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            DriverMode.settings.map { it.enabled to it.buttonDp }.distinctUntilChanged().collect { (on, dp) ->
                // Alpha 95: one failure here used to end this watcher, and with it every later toggle or size change.
                guarded("drive.orb.settings") {
                    stop()
                    buttonDp = dp
                    failures = 0
                    if (on) start(service, dp)
                }
                // The idle bubble gives way to the AI button, or comes back.
                guarded("drive.orb.chrome") { OverlayChromeRuntime.refreshExternalSurface() }
            }
        }
        // Alpha 95: while Driver mode is on, the orb is always there. Whatever took it away, the keeper brings it back.
        s.launch {
            while (true) {
                kotlinx.coroutines.delay(com.cyclone.mobile.voice.OrbKeeper.CHECK_EVERY_MS)
                guarded("drive.orb.keeper") { heal() }
            }
        }
    }

    fun detach() {
        stop()
        scope?.cancel()
        scope = null
        service = null
    }

    private fun start(service: CycloneAccessibilityService, buttonDp: Int) {
        // Alpha.78: every voice run is written to the run history (Glass → Runs, diagnostics).
        val next = VoiceSession(service, VoiceTraceSink(service))
        session = next
        windows = runCatching { Windows(service, next, buttonDp) }
            .onFailure { record("drive.orb.windows", it) }
            .getOrNull()
        val w = windows ?: return
        faceJob = scope?.launch { next.turn.collect { turn -> guarded("drive.orb.face") { showFace(w, VoiceFace.of(turn)) } } }
    }

    private fun stop() {
        liveOnly = false
        endJob?.cancel()
        endJob = null
        faceJob?.cancel()
        faceJob = null
        windows?.let { w -> guarded("drive.orb.remove") { w.remove() } }
        windows = null
        session?.let { s -> guarded("drive.orb.session") { s.close() } }
        session = null
        if (panelOpen.value) { panelOpen.value = false; guarded("drive.orb.chrome") { OverlayChromeRuntime.refreshExternalSurface() } }
    }

    /** One look at the orb: repairs whatever is missing while Driver mode is on ([com.cyclone.mobile.voice.OrbKeeper]). */
    private fun heal() {
        val svc = service ?: return
        // Live voice without the car button has no orb to keep.
        if (liveOnly) return
        val settings = DriverMode.settings.value
        val live = CycloneAccessibilityService.instance
        val w = windows
        val look = com.cyclone.mobile.voice.OrbKeeper.Look(
            enabled = settings.enabled,
            serviceCurrent = live == null || live === svc,
            windowsPresent = w != null,
            buttonAttached = w?.buttonAttached == true,
            buttonVisible = w?.buttonVisible == true,
            panelWanted = w?.panelWanted == true,
            panelAttached = w?.panelAttached == true,
            buttonOnScreen = w?.buttonOnScreen() != false,
        )
        val action = com.cyclone.mobile.voice.OrbKeeper.decide(look)
        if (action == com.cyclone.mobile.voice.OrbKeeper.Action.NONE) {
            if (settings.enabled && w != null) failures = 0
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (!com.cyclone.mobile.voice.OrbKeeper.allowed(action, failures, lastRepairMs, now)) return
        com.cyclone.mobile.DeviceState.addLog("Drive orb: ${action.name.lowercase()}")
        when (action) {
            com.cyclone.mobile.voice.OrbKeeper.Action.REATTACH -> {
                lastRepairMs = now; failures++
                // Not from inside this scope's own coroutine: attach() cancels it.
                live?.let { next -> android.os.Handler(android.os.Looper.getMainLooper()).post { guarded("drive.orb.reattach") { attach(next) } } }
            }
            com.cyclone.mobile.voice.OrbKeeper.Action.REBUILD -> {
                lastRepairMs = now
                stop()
                start(svc, if (buttonDp > 0) buttonDp else settings.buttonDp)
                if (windows == null) failures++ else failures = 0
                guarded("drive.orb.chrome") { OverlayChromeRuntime.refreshExternalSurface() }
            }
            com.cyclone.mobile.voice.OrbKeeper.Action.SHOW_BUTTON -> w?.showButton()
            com.cyclone.mobile.voice.OrbKeeper.Action.PLACE -> w?.place()
            com.cyclone.mobile.voice.OrbKeeper.Action.NONE -> Unit
        }
    }

    private inline fun guarded(stage: String, block: () -> Unit) {
        try { block() } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: Throwable) { record(stage, error) }
    }

    private fun record(stage: String, error: Throwable) {
        val context = service ?: return
        runCatching { com.mobilerun.portal.diagnostics.CycloneProcessDiagnostics.recordNonFatal(context, stage, error) }
    }

    /** The three windows and their Compose content. */
    private class Windows(private val context: CycloneAccessibilityService, private val session: VoiceSession, private val buttonDp: Int,
                          private val withButton: Boolean = true) {
        private val wm = context.getSystemService(WindowManager::class.java)
        private val density = context.resources.displayMetrics.density
        private val lifecycles = mutableListOf<OverlayComposeLifecycle>()
        private val buttonPx = ((buttonDp + DRIVER_GLOW.value * 2) * density).toInt()

        private val button = ButtonHost(context).also { host -> host.addView(compose(host) { ButtonContent() }) }
        private val buttonParams = WindowManager.LayoutParams(buttonPx, buttonPx, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.START }
        private val dim = compose { DimContent() }
        private val dimParams = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { fitInsetsTypes = 0 }
        private val panel = compose { PanelContent() }
        private val panelParams = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.BOTTOM }
        private var dimShown = false
        private var panelShown = false
        /** A gesture Cyclone injects is in progress: the voice windows let touches through (alpha.78). */
        private val visibility = DriverOverlayVisibility()
        private val main = android.os.Handler(android.os.Looper.getMainLooper())
        private val unfollow = OverlayGesturePassthrough.follow { yieldNow -> onMainAndWait { visibility.yielding = yieldNow; applyTouch() } }
        /** Alpha.131: where the button and panel are, so a stroke elsewhere leaves them on screen. The dim takes no touch. */
        private val unregion = OverlayGesturePassthrough.regions {
            buildList {
                if (button.isAttachedToWindow && button.visibility == View.VISIBLE) {
                    add(intArrayOf(buttonParams.x, buttonParams.y, buttonParams.x + buttonPx, buttonParams.y + buttonPx))
                }
                if (panelShown && panel.isAttachedToWindow && panel.visibility == View.VISIBLE) {
                    val at = IntArray(2).also(panel::getLocationOnScreen)
                    add(intArrayOf(at[0], at[1], at[0] + panel.width, at[1] + panel.height))
                }
            }
        }

        init {
            place()
            if (withButton) wm.addView(button, buttonParams)
        }

        /** Touchable unless hidden or yielding to a gesture Cyclone is making. */
        private fun applyTouch() {
            // Accessibility overlays can receive injected gestures despite NOT_TOUCHABLE. Remove all voice
            // windows from the visible/accessibility hit tree for the stroke, as the main Ask overlay does.
            button.visibility = if (visibility.buttonVisible) View.VISIBLE else View.GONE
            panel.visibility = if (visibility.panelVisible) View.VISIBLE else View.GONE
            dim.visibility = if (visibility.dimVisible) View.VISIBLE else View.GONE
            button.importantForAccessibility = if (visibility.yielding) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            panel.importantForAccessibility = if (visibility.yielding) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            dim.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            val buttonOff = !visibility.buttonVisible
            val buttonFlags = if (buttonOff) buttonParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                else buttonParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            if (buttonFlags != buttonParams.flags) { buttonParams.flags = buttonFlags; runCatching { wm.updateViewLayout(button, buttonParams) } }
            val panelFlags = if (visibility.yielding) panelParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                else panelParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            if (panelFlags != panelParams.flags) { panelParams.flags = panelFlags; if (panelShown) runCatching { wm.updateViewLayout(panel, panelParams) } }
        }

        /** Runs [block] on the main thread and waits (at most 300 ms) until it ran and a frame passed. */
        private fun onMainAndWait(block: () -> Unit) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { block(); return }
            val latch = java.util.concurrent.CountDownLatch(1)
            val posted = main.post {
                try { block() } finally {
                    // Frame callbacks precede traversal. Wait until the following frame so WindowManager
                    // has processed the hidden views and input flags before dispatchGesture starts.
                    android.view.Choreographer.getInstance().postFrameCallback {
                        android.view.Choreographer.getInstance().postFrameCallback { latch.countDown() }
                    }
                }
            }
            if (posted) latch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        }

        /** The last face shown: whether AI mode (the panel) is meant to be open. */
        val panelWanted get() = visibility.panelWanted
        val buttonAttached: Boolean get() = button.isAttachedToWindow
        val buttonVisible: Boolean get() = button.visibility == View.VISIBLE
        val panelAttached: Boolean get() = panelShown && panel.isAttachedToWindow

        fun buttonOnScreen(): Boolean {
            val bounds = wm.currentWindowMetrics.bounds
            return com.cyclone.mobile.voice.OrbKeeper.onScreen(buttonParams.x, buttonParams.y, buttonPx, bounds.width(), bounds.height())
        }

        /** The keeper found the button hidden while AI mode is closed. */
        fun showButton() {
            visibility.showButton()
            applyTouch()
        }

        fun show(face: VoiceFace) {
            visibility.show(face.panel, face.dim)
            button.contentDescription = face.description
            button.state = face.stateDescription
            if (face.dim != dimShown) {
                dimShown = face.dim
                if (face.dim) runCatching { wm.addView(dim, dimParams) } else runCatching { wm.removeView(dim) }
            }
            if (face.panel != panelShown) {
                // Alpha 95: shown only when the window really went up, so a failed add is noticed and repaired.
                panelShown = if (face.panel) runCatching { wm.addView(panel, panelParams) }.isSuccess
                    else { runCatching { wm.removeView(panel) }; false }
            }
            applyTouch()
        }

        fun remove() {
            unfollow()
            unregion()
            runCatching { wm.removeView(button) }
            if (dimShown) runCatching { wm.removeView(dim) }
            if (panelShown) runCatching { wm.removeView(panel) }
            lifecycles.forEach { it.destroy() }
        }

        /** Puts the button where the owner left it for this orientation. */
        fun place() {
            val bounds = wm.currentWindowMetrics.bounds
            val landscape = bounds.width() > bounds.height()
            val spot = DriverMode.spot(context, landscape)
            val (x, y) = spot.corner(bounds.width(), bounds.height(), buttonPx, (6 * density).toInt())
            buttonParams.x = x
            buttonParams.y = y
            if (button.isAttachedToWindow) runCatching { wm.updateViewLayout(button, buttonParams) }
        }

        fun moveBy(dx: Float, dy: Float, fromX: Int, fromY: Int) {
            val bounds = wm.currentWindowMetrics.bounds
            buttonParams.x = (fromX + dx).toInt().coerceIn(0, (bounds.width() - buttonPx).coerceAtLeast(0))
            buttonParams.y = (fromY + dy).toInt().coerceIn(0, (bounds.height() - buttonPx).coerceAtLeast(0))
            runCatching { wm.updateViewLayout(button, buttonParams) }
        }

        fun drop() {
            val bounds = wm.currentWindowMetrics.bounds
            val spot = ButtonSpot.at(buttonParams.x + buttonPx / 2f, buttonParams.y + buttonPx / 2f, bounds.width().toFloat(), bounds.height().toFloat())
            DriverMode.saveSpot(context, bounds.width() > bounds.height(), spot)
            place()
        }

        /**
         * A ComposeView for one overlay window. Compose looks up the lifecycle from the window's root view, so when the
         * ComposeView sits inside a host (the button's touch frame), the host carries the owners too; without them
         * the first frame throws and takes the whole app down.
         */
        private fun compose(host: View? = null, content: @Composable () -> Unit): ComposeView {
            val owner = OverlayComposeLifecycle().also { it.start(); lifecycles += it }
            host?.apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
            }
            return ComposeView(context).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                // The Ask overlay's look: its forced-dark scheme and glass, so the voice windows read as part of it.
                setContent { com.cyclone.mobile.ui.v32.CycloneSignatureTheme { TiltGlassTheme { content() } } }
            }
        }

        @Composable
        private fun ButtonContent() {
            com.cyclone.mobile.ui.overlay.glass.FollowPhoneLight()
            val turn by session.turn.collectAsState()
            val level by session.level.collectAsState()
            val face = remember(turn) { VoiceFace.of(turn) }
            DriverButtonFace(face, level, buttonDp.dp)
        }

        /** The dim: the overlay's smoke (graphite, no teal), deepest behind the panel; it fades in on the overlay's spring. */
        @Composable
        private fun DimContent() {
            val shade = remember { androidx.compose.animation.core.Animatable(if (OverlayLite.active) DIM else 0f) }
            androidx.compose.runtime.LaunchedEffect(Unit) { if (!OverlayLite.active) shade.animateTo(DIM, OverlayMotion.float) }
            val s = shade.value
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = s * 0.6f),
                0.65f to Color.Black.copy(alpha = s * 0.9f),
                1f to SMOKE.copy(alpha = (s + 0.22f).coerceAtMost(1f)),
            )))
        }

        @Composable
        private fun PanelContent() {
            val turn by session.turn.collectAsState()
            val level by session.level.collectAsState()
            val face = remember(turn) { VoiceFace.of(turn) }
            com.cyclone.mobile.ui.overlay.glass.FollowPhoneLight()
            val enter = remember { MutableTransitionState(false).apply { targetState = true } }
            // The overlay's one spring (OverlayMotion), growing up from the bottom edge; Lite only fades.
            val rise = if (OverlayLite.active) fadeIn(OverlayMotion.float)
                else fadeIn(OverlayMotion.float) + androidx.compose.animation.expandVertically(OverlayMotion.size, expandFrom = Alignment.Bottom)
            AnimatedVisibility(enter, enter = rise) {
                AiModePanel(face, level, onOrb = session::tap, onStop = session::stop, onNotNow = session::notNow)
            }
        }

        /** The button's touch: tap talks; a one-second hold picks it up to move. Raw screen positions, so it follows the finger. */
        @SuppressLint("ViewConstructor")
        private inner class ButtonHost(context: Context) : FrameLayout(context) {
            var state: String = ""
            private val slop = ViewConfiguration.get(context).scaledTouchSlop
            private var downX = 0f
            private var downY = 0f
            private var fromX = 0
            private var fromY = 0
            private var held = false
            private var moved = false
            private val hold = Runnable {
                if (!moved) {
                    held = true
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start()
                }
            }

            init {
                isClickable = true
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            }

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY
                        fromX = buttonParams.x; fromY = buttonParams.y
                        held = false; moved = false
                        postDelayed(hold, HOLD_TO_MOVE_MS)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (held) moveBy(dx, dy, fromX, fromY)
                        else if (kotlin.math.hypot(dx, dy) > slop) { moved = true; removeCallbacks(hold) }
                    }
                    MotionEvent.ACTION_UP -> {
                        removeCallbacks(hold)
                        if (held) { animate().scaleX(1f).scaleY(1f).setDuration(120).start(); drop() }
                        else if (!moved) performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        removeCallbacks(hold)
                        if (held) { animate().scaleX(1f).scaleY(1f).setDuration(120).start(); drop() }
                    }
                }
                return true
            }

            override fun performClick(): Boolean {
                super.performClick()
                session.tap()
                return true
            }

            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                info.className = "android.widget.Button"
                info.stateDescription = state
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, "Talk to Cyclone"))
            }

            override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
                if (action == AccessibilityNodeInfo.ACTION_CLICK) performClick() else super.performAccessibilityAction(action, arguments)

            override fun onConfigurationChanged(newConfig: Configuration?) {
                super.onConfigurationChanged(newConfig)
                place()
            }
        }
    }

    const val HOLD_TO_MOVE_MS = 1_000L
    /** How dark the dim gets; [SMOKE] is the overlay's graphite (the VEIL glass body). */
    private const val DIM = 0.46f
    private val SMOKE = Color(0xFF08090B)
    /** Live voice from the Ask bar closes this long after the conversation (and its task) is over. */
    const val LIVE_CLOSE_AFTER_MS = 2_500L
}

/**
 * Live voice's panel: the Ask overlay's card (its glass, 30 dp corners, 16 dp side margins, grabber and padding), with
 * the status, the captions on veil pills, the orb, Stop and "Not now". Glanceable: large type, two lines at most. It
 * grows and shrinks with its words on the overlay's spring (still in Lite).
 */
@Composable
internal fun AiModePanel(face: VoiceFace, level: Float, onOrb: () -> Unit, onStop: () -> Unit, onNotNow: () -> Unit) {
    val radius = OverlayStackGeometry.CARD_RADIUS_DP.dp
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            .tiltGlass(radius, palette = com.cyclone.mobile.ui.overlay.glass.LocalGlassPalette.current)
            .then(if (OverlayLite.active) Modifier else Modifier.animateContentSize(OverlayMotion.size))
            .padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.padding(bottom = 14.dp).size(32.dp, 3.dp).background(GlassMuted.copy(alpha = 0.38f), androidx.compose.foundation.shape.CircleShape))
        // The small status word shows only when no caption already says it (never "LISTENING" over "Listening…").
        if (face.showStatus) {
            Text(face.status.uppercase(), color = if (face.warm) GlassWarm else GlassMuted, fontSize = 12.sp, letterSpacing = 1.6.sp,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
        }
        // The owner's words in the Ask field's type; Cyclone's line in the overlay's question type, a step larger.
        if (face.you.isNotBlank()) {
            Caption(face.you, GlassMuted, 17, FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
        }
        if (face.cyclone.isNotBlank()) Caption(face.cyclone, GlassInk, 21, FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier.size(128.dp)
                .clickable(interaction, indication = null, role = Role.Button, onClick = onOrb)
                .semantics {
                    role = Role.Button
                    contentDescription = "Cyclone voice"
                    stateDescription = face.stateDescription
                    onClick(label = "Talk") { onOrb(); true }
                },
            contentAlignment = Alignment.Center,
        ) {
            DriveOrb(face.motion, level, face.warm, Modifier.size(128.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            if (face.notNow) GlassCapsuleButton("Not now", primary = false, onClick = onNotNow, modifier = Modifier.weight(1f).height(52.dp))
            GlassCapsuleButton("Stop", primary = true, onClick = onStop, modifier = Modifier.weight(1f).height(52.dp))
        }
    }
}

@Composable
private fun Caption(text: String, color: Color, sizeSp: Int, weight: FontWeight) {
    Box(Modifier.widthIn(max = 560.dp).veilPill().padding(horizontal = 18.dp, vertical = 8.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(text, color = color, fontSize = sizeSp.sp, lineHeight = (sizeSp + 6).sp, fontWeight = weight, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
