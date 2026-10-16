package com.cyclone.mobile.ai

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.PowerManager
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import com.cyclone.mobile.ui.overlay.OverlayExternalInteraction
import com.cyclone.mobile.ui.overlay.OverlayGesturePassthrough
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.capture.LiveCaptureSessionManager
import com.cyclone.mobile.capture.LiveCaptureService
import com.cyclone.mobile.ui.overlay.ScreenSharePill
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.MainActivity
import com.cyclone.mobile.ui.overlay.LocalOverlayImeBottomPx
import com.cyclone.mobile.ui.overlay.OverlayChrome
import com.cyclone.mobile.ui.overlay.OverlayChromeContract
import com.cyclone.mobile.ui.overlay.OverlayImeLift
import com.cyclone.mobile.ui.overlay.OverlayAiSettings
import com.cyclone.mobile.ui.overlay.OverlayIdleActivationTracker
import com.cyclone.mobile.ui.overlay.OverlayIdleHalo
import com.cyclone.mobile.ui.overlay.OverlayIdleTapResult
import com.cyclone.mobile.ui.overlay.OverlayIdleVisualState
import com.cyclone.mobile.ui.overlay.OverlayChromeSnapshot
import com.cyclone.mobile.ui.overlay.OverlayChromeState
import com.cyclone.mobile.ui.overlay.OverlayCopy
import com.cyclone.mobile.ui.overlay.OverlayUserAction

internal object OverlayLockScreenPolicy {
    fun blocked(screenOff: Boolean, keyguardLocked: Boolean, interactive: Boolean): Boolean =
        screenOff || keyguardLocked || !interactive
}

internal data class OverlayWindowContract(
    val matchParentWidth: Boolean,
    val widthDp: Int?,
    val heightDp: Int?,
    val bottomCenter: Boolean,
    val notFocusable: Boolean,
    val notTouchModal: Boolean,
    val notTouchable: Boolean,
    val bottomMarginDp: Int,
)

internal object OverlayChromeWindowPolicy {
    fun main(compact: Boolean): OverlayWindowContract = if (compact) {
        OverlayWindowContract(
            matchParentWidth = false,
            widthDp = OverlayChromeContract.IDLE_TOUCH_SIZE_DP,
            heightDp = OverlayChromeContract.IDLE_TOUCH_SIZE_DP,
            bottomCenter = true,
            notFocusable = true,
            notTouchModal = true,
            notTouchable = false,
            bottomMarginDp = OverlayChromeContract.IDLE_TOUCH_BOTTOM_MARGIN_DP,
        )
    } else {
        OverlayWindowContract(
            matchParentWidth = true,
            widthDp = null,
            heightDp = null,
            bottomCenter = true,
            notFocusable = false,
            notTouchModal = true,
            notTouchable = false,
            bottomMarginDp = 0,
        )
    }

    fun minimizedComposer(): OverlayWindowContract = OverlayWindowContract(
        matchParentWidth = true,
        widthDp = null,
        heightDp = null,
        bottomCenter = true,
        notFocusable = false,
        notTouchModal = true,
        notTouchable = false,
        bottomMarginDp = 0,
    )

    fun glass(): OverlayWindowContract = main(compact = false).copy(notFocusable = true)

    val halo: OverlayWindowContract = OverlayWindowContract(
        matchParentWidth = false,
        widthDp = OverlayChromeContract.IDLE_VISUAL_WIDTH_DP,
        heightDp = OverlayChromeContract.IDLE_VISUAL_HEIGHT_DP,
        bottomCenter = true,
        notFocusable = true,
        notTouchModal = true,
        notTouchable = true,
        bottomMarginDp = OverlayChromeContract.IDLE_VISUAL_BOTTOM_MARGIN_DP,
    )

    fun flags(spec: OverlayWindowContract, secretVisible: Boolean = false): Int {
        // Android replaces an entire secure window with black pixels in USB screen capture.
        // The ordinary Ask/task surface is part of the phone UI and must mirror normally.
        // Keep the Secrets Card protected; it is never exposed to the desktop stream.
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (secretVisible) flags = flags or WindowManager.LayoutParams.FLAG_SECURE
        if (spec.notTouchModal) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (spec.notFocusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (spec.notTouchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    /**
     * Alpha.134: the drawn card never takes a touch, so the agent's strokes always land on the app under it, with no
     * flag that has to change in time. The owner's touch reaches the card through its [touchLayerFlags] window.
     */
    fun drawn(spec: OverlayWindowContract): OverlayWindowContract = spec.copy(notTouchable = true)

    /** The empty window over the card that takes the owner's touch: never focus, never the touches around it. */
    fun touchLayerFlags(): Int = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

    fun withHostGesturePassthrough(flags: Int): Int =
        flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

    /**
     * Alpha.131: a host action's step back. The window hands focus to the app (so the app keeps its keyboard and Back
     * reaches it) but still takes the owner's touch, so Stop works while Cyclone acts.
     */
    fun withHostFocus(flags: Int): Int = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    fun gravity(spec: OverlayWindowContract): Int =
        Gravity.BOTTOM or if (spec.bottomCenter) Gravity.CENTER_HORIZONTAL else Gravity.END
}

/**
 * Sibling of [AiTraceOverlayController]. Hosts the V4 Compose overlay on
 * TYPE_ACCESSIBILITY_OVERLAY so Cyclone chrome is not a new Activity.
 *
 * Expanded chrome is one touchable drawer window. Idle mode deliberately splits the logical
 * overlay into a non-touchable 144x72dp visual halo and a centered 48x48dp activation window.
 * A minimized current run uses a separate content-height Ask Cyclone pill; it never turns the
 * rest of the host app into a touch target.
 */
class OverlayChromeController(
    private val service: CycloneAccessibilityService,
    private val onAction: (OverlayUserAction) -> Unit,
    private val onComposerChanged: (String) -> Unit,
    private val onRequestSubmitted: (String) -> Unit,
    private val onVoiceStateChanged: (Boolean, String?, String?) -> Unit,
    private val getAiSettings: () -> OverlayAiSettings,
    private val onAiSettingsChanged: (OverlayAiSettings) -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val keyguard = service.getSystemService(KeyguardManager::class.java)
    private val power = service.getSystemService(PowerManager::class.java)
    private var screenOff = false
    private var lockReceiverRegistered = false
    private var lastLockBlocked: Boolean? = null
    private fun lockScreenBlocked() = OverlayLockScreenPolicy.blocked(screenOff, keyguard.isKeyguardLocked, power.isInteractive)
    private fun hideLockedWindows(): Boolean {
        if (!lockScreenBlocked()) return false
        val attached = listOfNotNull(root, haloRoot, shareRoot, sheetRoot, touchLayer)
        if (attached.any { it.visibility == View.VISIBLE }) { speechRecognizer?.cancel(); voiceSession = false }
        attached.forEach { it.visibility = View.GONE }
        blurWindows.sync(emptyList(), 0, 0, visible = false)
        return true
    }
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOff = when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> true
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> false
                else -> screenOff
            }
            render(latest)
        }
    }
    private val lockCheck = object : Runnable {
        override fun run() {
            if (!lockReceiverRegistered) return
            val blocked = lockScreenBlocked()
            if (blocked != lastLockBlocked) {
                lastLockBlocked = blocked
                render(latest)
            }
            main.postDelayed(this, 500)
        }
    }
    private fun watchLockScreen() {
        if (lockReceiverRegistered) return
        ContextCompat.registerReceiver(service, lockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        lockReceiverRegistered = true
        main.postDelayed(lockCheck, 500)
    }
    private var lifecycle = OverlayComposeLifecycle()
    @Volatile private var generation = 0L
    private var backgroundTask by mutableStateOf<com.cyclone.mobile.runtime.background.WorkspaceTaskUi?>(null)
    private val windows = com.cyclone.mobile.ui.overlay.OverlayWindowRegistry<View> { view ->
        try { wm.removeViewImmediate(view) } catch (_: IllegalArgumentException) { /* Already removed by Android. */ }
        if (view is ComposeView) view.disposeComposition()
    }
    /** Instrumentation hook: service detach has zero windows; task cleanup retains chrome. */
    fun attachedWindowCount(): Int = windows.size
    fun attachedLauncherCount(): Int = if (root?.isAttachedToWindow == true) 1 else 0
    private fun addWindow(view: View, layout: WindowManager.LayoutParams) {
        if (lockScreenBlocked()) view.visibility = View.GONE
        wm.addView(view, layout)
        windows.attached(view)
    }
    fun background(task: com.cyclone.mobile.runtime.background.WorkspaceTaskUi?) {
        onMain {
            backgroundTask = task
            if (com.cyclone.mobile.ui.overlay.BackgroundGlassPolicy.tearDown(task)) {
                backgroundTask = null
                render(latest.copy(idleChipVisible = true))
            }
            else if (com.cyclone.mobile.ui.overlay.BackgroundGlassPolicy.visible(task)) {
                if (root == null) show(latest) else applyLayout(latest)
            }
        }
    }
    private fun glass() =
        latest.state == OverlayChromeState.IDLE &&
            com.cyclone.mobile.ui.overlay.BackgroundGlassPolicy.visible(backgroundTask)
    private val idleActivation = OverlayIdleActivationTracker()
    // Blur behind the glass (GlassBlur): small blur windows under the overlay window, one per panel.
    private val blurWindows = OverlayBlurWindows(service)
    private val blurHost = com.cyclone.mobile.ui.overlay.glass.BlurHost(OverlayBlurWindows.SLOTS,
        schedule = { ms, block -> main.postDelayed(block, ms) },
        // Alpha.131: a card growing or shrinking by a line keeps its blur (it follows in place); only a real move
        // (appearing, sliding, going) makes the blur step aside.
        smallChangePx = service.resources.displayMetrics.density * com.cyclone.mobile.ui.overlay.glass.BlurHost.SMALL_CHANGE_DP) { syncBlur() }
    private var blurOrigin: IntArray? = null
    private val smallBlurMovePx = service.resources.displayMetrics.density * com.cyclone.mobile.ui.overlay.glass.BlurHost.SMALL_CHANGE_DP
    private var root: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var shareRoot: ComposeView? = null
    private var haloRoot: ComposeView? = null
    private var haloParams: WindowManager.LayoutParams? = null
    /** Alpha.134: the empty window over the card that takes the owner's touch (the card itself never does). */
    private var touchLayer: com.cyclone.mobile.ui.overlay.OverlayTouchLayer? = null
    private var touchParams: WindowManager.LayoutParams? = null
    /** The last layout wanted the touch layer up; it comes up once the card is laid out. */
    private var touchWanted = false
    private var sheetRoot: android.widget.FrameLayout? = null
    private var sheetCompose: ComposeView? = null
    private var sheetParams: WindowManager.LayoutParams? = null
    private var sheetCloseRequests by mutableStateOf(0)
    private var sheetLastPage = com.cyclone.mobile.ui.overlay.ComposerAccessory.ATTACHMENTS
    private var latest by mutableStateOf(OverlayChromeSnapshot())
    private var aiSettings by mutableStateOf(OverlayAiSettings())
    private var idleVisualState by mutableStateOf(OverlayIdleVisualState())
    private var imeBottomPx by mutableStateOf(0)
    private var navigationBottomPx by mutableStateOf(0)
    private var reportedImeBottomPx = 0
    private var speechRecognizer: SpeechRecognizer? = null
    /** A recognition session is running; a second start must not restart it (a restart ends it with an error). */
    private var voiceSession = false
    private var voiceSessionAt = 0L
    /** The last layout pass changed a window's params or visibility (alpha.134): only then must a yield wait. */
    private var windowsTouched = false

    /** The main window has been on screen at least once since it was added (alpha.131). */
    private var rootAttachedOnce = false

    /** 0: on screen as usual; 1: stepped back for a host action; 2: hidden for a stroke on Cyclone's glass. */
    @Volatile private var hostGestureYielded = 0

    fun show(snapshot: OverlayChromeSnapshot) {
        runCatching { com.cyclone.mobile.ui.v32.ask.VisualQuality.load(service) }
        onMain {
            latest = snapshot
            watchLockScreen()
            if (hideLockedWindows()) return@onMain
            aiSettings = getAiSettings()
            if (root != null) {
                applyLayout(snapshot)
                return@onMain
            }
            lifecycle = OverlayComposeLifecycle()
            lifecycle.start()
            com.cyclone.mobile.ui.overlay.glass.GlassBlur.start(service)
            // Opened before the overlay window so the blur always sits under the glass, never over it.
            blurWindows.prepare()
            val halo = ComposeView(service).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setViewTreeLifecycleOwner(lifecycle)
                setViewTreeViewModelStoreOwner(lifecycle)
                setViewTreeSavedStateRegistryOwner(lifecycle)
                setContent {
                    OverlayIdleHalo(state = idleVisualState)
                }
            }
            val view = ComposeView(service).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setViewTreeLifecycleOwner(lifecycle)
                setViewTreeViewModelStoreOwner(lifecycle)
                setViewTreeSavedStateRegistryOwner(lifecycle)
                setContent {
                    // Alpha.134 (plan 60): the overlay follows the owner's Visual quality; Lite drops the per-frame work.
                    val quality by com.cyclone.mobile.ui.v32.ask.VisualQuality.resolved.collectAsState()
                    val lite = quality == com.cyclone.mobile.ui.v32.ask.GlassQuality.LITE
                    LaunchedEffect(lite) { com.cyclone.mobile.ui.overlay.OverlayLite.set(lite) }
                    val blurOn by com.cyclone.mobile.ui.overlay.glass.GlassBlur.on.collectAsState()
                    // Each panel fades its own smoke with its blur (rememberBlurPanel); here the windows follow the switch.
                    LaunchedEffect(blurOn) { syncBlur() }
                    CompositionLocalProvider(com.cyclone.mobile.ui.overlay.glass.LocalBlurHost provides blurHost) {
                    val externalActive by OverlayExternalInteraction.active.collectAsState()
                    val secretCardState by com.cyclone.mobile.secrets.SecretsCardRuntime.state.collectAsState()
                    val ownerMoment = com.cyclone.mobile.owner.rememberOwnerMoment()
                    val sheetPage by com.cyclone.mobile.ui.overlay.OverlayToolsSheetState.page.collectAsState()
                    LaunchedEffect(sheetPage) { syncToolsSheet(sheetPage) }
                    LaunchedEffect(externalActive, secretCardState?.visible, ownerMoment?.kind, ownerMoment?.requestId) {
                        aiSettings = getAiSettings()
                        applyLayout(latest)
                    }
                    if (glass() && secretCardState?.visible != true && !com.cyclone.mobile.owner.OwnerMoments.overlayCard(ownerMoment)) {
                        com.cyclone.mobile.ui.v32.CycloneV32Theme(drawBackground = false) {
                            com.cyclone.mobile.ui.overlay.BackgroundTaskGlass(backgroundTask!!) { onAction(OverlayUserAction.ASK_CYCLONE) }
                        }
                    } else {
                        CompositionLocalProvider(LocalOverlayImeBottomPx provides imeBottomPx) {
                            OverlayChrome(
                                snapshot = latest,
                                onAction = onAction,
                                onComposerChanged = onComposerChanged,
                                onRequestSubmitted = onRequestSubmitted,
                                onVoiceInput = ::beginVoiceInput,
                                onVoiceStop = ::stopVoiceInput,
                                onLiveVoice = ::beginLiveVoice,
                                aiSettings = aiSettings,
                                onAiSettingsChanged = { next ->
                                    aiSettings = next
                                    onAiSettingsChanged(next)
                                },
                                idleVisualState = idleVisualState,
                                secretCardState = secretCardState,
                                ownerMoment = ownerMoment,
                                onIdleTap = ::recordIdleTap,
                                onIdleSemanticActivate = ::recordSemanticActivation,
                            )
                        }
                    }
                    }
                }
            }
            val haloLayout = windowParams(OverlayChromeWindowPolicy.halo)
            val layout = overlayParams(snapshot)
            haloParams = haloLayout
            params = layout
            haloRoot = halo
            root = view
            applyLayout(snapshot)
            // Add decoration first so the small semantic/touch hotspot stays above it.
            try {
                addWindow(halo, haloLayout)
                addWindow(view, layout)
                // Above the card: the owner's touch goes in here and on to the card.
                val touch = com.cyclone.mobile.ui.overlay.OverlayTouchLayer(service) { root }.apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    visibility = View.GONE
                    // The background glass is never focused, so screen readers reach its one action here.
                    setOnClickListener { if (glass()) onAction(OverlayUserAction.ASK_CYCLONE) }
                }
                val touchLayout = WindowManager.LayoutParams(
                    1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    OverlayChromeWindowPolicy.touchLayerFlags(), PixelFormat.TRANSPARENT,
                ).apply {
                    gravity = layout.gravity
                    softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                    fitInsetsTypes = 0
                    title = "Cyclone touch"
                }
                touchLayer = touch
                touchParams = touchLayout
                addWindow(touch, touchLayout)
                trackIme(view)
                view.viewTreeObserver.addOnGlobalLayoutListener { if (root === view) { refreshRegions(); syncTouchLayer() } }
                view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) { if (root === view) rootAttachedOnce = true }
                    override fun onViewDetachedFromWindow(v: View) = Unit
                })
            } catch (_: Exception) { dismiss(); return@onMain }
            val shareView = ComposeView(service).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setViewTreeLifecycleOwner(lifecycle)
                setViewTreeViewModelStoreOwner(lifecycle)
                setViewTreeSavedStateRegistryOwner(lifecycle)
                setContent {
                    val sharing by LiveCaptureSessionManager.state.collectAsState()
                    val externalActive by OverlayExternalInteraction.active.collectAsState()
                    if (!externalActive && isCompact(latest) && sharing.active &&
                        sharing.phase != com.cyclone.mobile.capture.ScreenSharePhase.REQUESTING_PERMISSION
                    ) {
                        com.cyclone.mobile.ui.v32.CycloneV32Theme {
                            androidx.compose.foundation.layout.Box(Modifier.padding(8.dp)) {
                                ScreenSharePill(sharing) { LiveCaptureService.stop(service) }
                            }
                        }
                    }
                }
            }
            val shareLayout = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_SECURE, PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
            shareRoot = shareView
            try { addWindow(shareView, shareLayout) } catch (_: Exception) { dismiss() }
        }
    }

    /** A card that needs a full, focusable window: the Secrets Card or a mission's check-in card. */
    private fun cardVisible(): Boolean = com.cyclone.mobile.secrets.SecretsCardRuntime.state.value?.visible == true ||
        com.cyclone.mobile.owner.OwnerMoments.overlayCard(com.cyclone.mobile.owner.OwnerMomentsRuntime.current())

    fun render(snapshot: OverlayChromeSnapshot) {
        onMain {
            latest = snapshot
            if (hideLockedWindows()) return@onMain
            // Alpha.131: re-create only a window Android took away after it was on screen. One that was just added
            // isn't attached until its first traversal; tearing it down then made every window blink.
            if (root != null && rootAttachedOnce && root?.isAttachedToWindow != true) {
                dismiss()
                show(snapshot)
                return@onMain
            }
            if (root == null) {
                val secretVisible = cardVisible()
                if (snapshot.state != OverlayChromeState.IDLE || snapshot.idleChipVisible || glass() || secretVisible) show(snapshot)
                return@onMain
            }
            if (!isCompact(snapshot)) resetIdleActivation()
            applyLayout(snapshot, lockChecked = true)
        }
    }

    fun dismiss() {
        onMain {
            generation++
            if (lockReceiverRegistered) service.unregisterReceiver(lockReceiver)
            lockReceiverRegistered = false
            main.removeCallbacksAndMessages(null)
            blurSyncPending = false
            latest = OverlayChromeSnapshot(idleChipVisible = false)
            backgroundTask = null
            windows.clear()
            liftAnimator?.cancel()
            liftAnimator = null
            liftTarget = null
            blurWindows.clear()
            com.cyclone.mobile.ui.overlay.glass.GlassBlur.stop(service)
            listOfNotNull(root, haloRoot, shareRoot, sheetCompose).forEach { it.disposeComposition() }
            sheetCompose = null
            com.cyclone.mobile.ui.overlay.OverlayToolsSheetState.close()
            sheetRoot = null
            sheetParams = null
            shareRoot = null
            root = null
            params = null
            haloRoot = null
            haloParams = null
            touchLayer = null
            touchParams = null
            touchWanted = false
            hostGestureYielded = 0
            rootAttachedOnce = false
            resetIdleActivation()
            speechRecognizer?.destroy()
            voiceSession = false
            speechRecognizer = null
            lifecycle.destroy()
        }
    }

    private var liftAnimator: android.animation.ValueAnimator? = null
    private var liftTarget: Int? = null

    /**
     * Moves the overlay window to [targetY] over the keyboard's own time and curve, one window update per frame, so the
     * Ask bar rides up and down with the keyboard. A new target picks up from where the window is.
     */
    private fun glideTo(view: View, layout: WindowManager.LayoutParams, targetY: Int) {
        val running = liftAnimator
        if (running != null && running.isRunning && liftTarget == targetY) return
        running?.cancel()
        val from = layout.y
        liftTarget = targetY
        liftAnimator = android.animation.ValueAnimator.ofInt(from, targetY).apply {
            duration = com.cyclone.mobile.ui.overlay.OverlayImeLift.GLIDE_MS
            // The keyboard's own curve (fast out, slow in), so the bar and the keyboard move as one.
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { animator ->
                if (root !== view || !view.isAttachedToWindow) { animator.cancel(); return@addUpdateListener }
                layout.y = animator.animatedValue as Int
                runCatching { wm.updateViewLayout(view, layout) }
                syncTouchLayer()
            }
            start()
        }
    }

    /** Moves the blur windows to the panels; all hidden when the overlay is hidden or blur is off. */
    private fun syncBlur() {
        val view = root
        val visible = view != null && view.isAttachedToWindow && view.visibility == View.VISIBLE && view.alpha > 0f &&
            com.cyclone.mobile.ui.overlay.glass.GlassBlur.on.value
        val origin = IntArray(2)
        if (visible) view!!.getLocationOnScreen(origin)
        // The overlay window itself moved (keyboard, a new layout): the panels are in motion, so the blur steps aside
        // until they settle; the host calls back here.
        val before = blurOrigin
        blurOrigin = origin
        // Alpha.131: a card a line taller (its bottom-anchored window's top moves a little) keeps its blur, which
        // follows in place; a glide with the keyboard or a real move still steps aside.
        val far = before != null && (kotlin.math.abs(before[0] - origin[0]) > smallBlurMovePx || kotlin.math.abs(before[1] - origin[1]) > smallBlurMovePx)
        if (visible && before != null && !before.contentEquals(origin) && (far || liftAnimator?.isRunning == true)) { blurHost.jolt(); return }
        blurWindows.sync(blurHost.shown(), origin[0], origin[1], visible)
    }

    fun keyboardClosed() {
        onMain {
            reportedImeBottomPx = 0
            imeBottomPx = 0
            applyLayout(latest)
        }
    }

    /** One blur sync waits at a time (alpha.134): every layout used to post its own. */
    private var blurSyncPending = false
    private val blurSync = Runnable { blurSyncPending = false; syncBlur() }

    private fun applyLayout(snapshot: OverlayChromeSnapshot, lockChecked: Boolean = false) {
        if (!lockChecked && hideLockedWindows()) return
        // The window may move or hide below; the blur follows once it has.
        if (!blurSyncPending) { blurSyncPending = true; main.postDelayed(blurSync, 32) }
        // Alpha.131: a host action only steps back (no touch, no focus, out of the accessibility tree; still on
        // screen). The windows go GONE only while a stroke lands on Cyclone's own glass.
        val yieldHost = OverlayGesturePassthrough.active()
        val hideHost = OverlayGesturePassthrough.hidden()
        shareRoot?.let { share ->
            share.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            share.visibility = if (hideHost || OverlayExternalInteraction.active.value) View.GONE else View.VISIBLE
        }
        sheetRoot?.visibility = if (hideHost) View.GONE else View.VISIBLE
        val view = root ?: return
        val layout = params ?: return
        val secretVisible = cardVisible()
        val compact = !secretVisible && isCompact(snapshot)
        val minimizedComposer = !secretVisible && isMinimizedComposer(snapshot)
        // Driver mode (plan 32): the AI button replaces the idle bubble; everything else (task, cards) stays.
        val driverIdle = com.cyclone.mobile.voice.DriverMode.settings.value.enabled && compact && !minimizedComposer &&
            (snapshot.state == OverlayChromeState.IDLE || snapshot.launcherCollapsed) && !glass()
        val shown = (secretVisible || !compact || snapshot.idleChipVisible || glass()) &&
            !OverlayExternalInteraction.active.value && !driverIdle &&
            // Live voice's panel has the bottom of the screen; the Ask overlay comes back as it was when it closes.
            !com.cyclone.mobile.ui.overlay.DriverOverlay.panelOpen.value
        // Alpha.134: a stroke on Cyclone's glass never hides what the owner sees. The drawn card never takes a touch
        // (only its empty touch layer steps aside); in idle the window is the empty hotspot and its halo stays.
        val visible = shown && !(hideHost && compact)

        view.importantForAccessibility =
            if (yieldHost) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_YES
        view.contentDescription = when {
            secretVisible -> "Secret needed"
            snapshot.state == OverlayChromeState.GATE && !snapshot.minimized -> OverlayCopy.GATE
            else -> null
        }
        // Shown before this layout: only a window already on screen glides to a new height.
        val wasShown = view.isAttachedToWindow && view.visibility == View.VISIBLE
        view.visibility = if (visible) View.VISIBLE else View.GONE
        // The blur windows leave with the glass in the same frame (not 32 ms later, which showed bare blur).
        if (wasShown && !visible) syncBlur()

        val spec = when {
            secretVisible -> OverlayChromeWindowPolicy.main(compact = false)
            glass() -> OverlayChromeWindowPolicy.glass()
            minimizedComposer -> OverlayChromeWindowPolicy.minimizedComposer()
            else -> OverlayChromeWindowPolicy.main(compact)
        }
        val fromY = layout.y
        val changed = applyWindowContract(
            layout,
            if (compact) spec else OverlayChromeWindowPolicy.drawn(spec),
            followKeyboard = secretVisible || (!compact && !glass()),
        )
        // A new height above the keyboard glides there with the keyboard instead of jumping (the window moves by y).
        val targetY = layout.y
        // Alpha.134: Lite moves the card above the keyboard in one step (one window call instead of one per frame).
        if (wasShown && visible && targetY != fromY && !com.cyclone.mobile.ui.overlay.OverlayLite.active) {
            layout.y = liftAnimator?.let { (it.animatedValue as? Int) } ?: fromY
            glideTo(view, layout, targetY)
        } else {
            liftAnimator?.cancel()
            liftAnimator = null
            liftTarget = null
        }
        val yieldState = if (hideHost) 2 else if (yieldHost) 1 else 0
        // Alpha.134: a window call only when its params really changed (flags are part of them). A step back on a
        // window that is already not focusable, the idle ball or background glass, costs no IPC at all.
        if (changed) {
            runCatching { wm.updateViewLayout(view, layout) }
        }
        // Hiding for a stroke (or coming back) flips every window's visibility, the halo, pill and sheet too.
        windowsTouched = changed || wasShown != (view.visibility == View.VISIBLE) || (yieldState == 2) != (hostGestureYielded == 2)

        haloRoot?.let { halo ->
            halo.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // The halo never takes a touch: it stays on screen through a stroke, only its hotspot steps aside.
            halo.visibility = if (
                shown &&
                !secretVisible &&
                (snapshot.state == OverlayChromeState.IDLE || snapshot.launcherCollapsed) &&
                !glass() &&
                snapshot.idleChipVisible
            ) View.VISIBLE else View.GONE
        }
        haloParams?.let { hp ->
            val haloChanged = applyWindowContract(hp, OverlayChromeWindowPolicy.halo, followKeyboard = false)
            if (haloChanged) {
                haloRoot?.let { halo -> runCatching { wm.updateViewLayout(halo, hp) } }
                windowsTouched = true
            }
        }
        hostGestureYielded = yieldState
        touchWanted = com.cyclone.mobile.ui.overlay.OverlayTouchForward.shown(visible, compact, strokeOnGlass = hideHost, laidOut = true)
        touchLayer?.let { touch ->
            // Screen readers see the card itself while it has focus; the never-focused background glass speaks here.
            touch.importantForAccessibility = if (glass() && !yieldHost) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
            touch.contentDescription = if (glass()) "Cyclone task. Open Cyclone" else null
        }
        syncTouchLayer()
        refreshRegions()
    }

    /**
     * Alpha.134: the touch layer covers exactly the card (same gravity, offset and laid-out size). It comes up once the
     * card is laid out and leaves only for a stroke on the glass, which is invisible: it draws nothing.
     */
    private fun syncTouchLayer() {
        val touch = touchLayer ?: return
        val tp = touchParams ?: return
        val view = root ?: return
        val layout = params ?: return
        val laidOut = view.isAttachedToWindow && view.visibility == View.VISIBLE && view.width > 0 && view.height > 0
        val on = touchWanted && laidOut
        if (on) {
            var changed = false
            if (tp.width != view.width) { tp.width = view.width; changed = true }
            if (tp.height != view.height) { tp.height = view.height; changed = true }
            if (tp.gravity != layout.gravity) { tp.gravity = layout.gravity; changed = true }
            if (tp.x != layout.x) { tp.x = layout.x; changed = true }
            if (tp.y != layout.y) { tp.y = layout.y; changed = true }
            if (changed && touch.isAttachedToWindow) runCatching { wm.updateViewLayout(touch, tp) }
        }
        val next = if (on) View.VISIBLE else View.GONE
        if (touch.visibility != next) {
            touch.visibility = next
            windowsTouched = true
        }
    }

    /** Alpha.131: where Cyclone's windows are on screen, kept for a stroke deciding whether to hide them. */
    @Volatile private var onScreen: List<IntArray> = emptyList()

    private fun refreshRegions() {
        val margin = dp(8)
        // The halo never takes a touch, so a stroke near it needs nothing from Cyclone (its hotspot is the root).
        onScreen = listOfNotNull(root, shareRoot, sheetRoot).filter { it.isAttachedToWindow && it.visibility == View.VISIBLE && it.width > 0 }
            .map { v ->
                val at = IntArray(2).also(v::getLocationOnScreen)
                intArrayOf(at[0] - margin, at[1] - margin, at[0] + v.width + margin, at[1] + v.height + margin)
            }
    }

    /** What strokes check (any thread). A window that is mid-glide still counts by where it was last laid out. */
    fun regions(): List<IntArray> = onScreen

    /**
     * The + tools drawer: a full-screen window above the Ask overlay. It blurs everything behind it
     * (cross-window blur, animated with the sheet) and owns focus only so Back closes it.
     */
    private fun syncToolsSheet(page: com.cyclone.mobile.ui.overlay.ComposerAccessory) {
        if (page == com.cyclone.mobile.ui.overlay.ComposerAccessory.NONE) {
            sheetRoot?.let { windows.remove(it) }
            sheetCompose?.disposeComposition()
            sheetCompose = null
            sheetRoot = null
            sheetParams = null
            return
        }
        sheetLastPage = page
        if (sheetRoot != null || root == null || lockScreenBlocked()) return
        // Alpha.134: Lite opens the tools sheet without the full-screen blur (and its per-frame radius updates).
        val blurAvailable = runCatching { wm.isCrossWindowBlurEnabled }.getOrDefault(false) && !com.cyclone.mobile.ui.overlay.OverlayLite.active
        val maxBlurPx = com.cyclone.mobile.ui.overlay.OverlayToolsSheetPhysics.BLUR_DP * service.resources.displayMetrics.density
        val content = ComposeView(service).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val current by com.cyclone.mobile.ui.overlay.OverlayToolsSheetState.page.collectAsState()
                val sharing by LiveCaptureSessionManager.state.collectAsState()
                com.cyclone.mobile.ui.v32.CycloneV32Theme(drawBackground = false) {
                    com.cyclone.mobile.ui.overlay.OverlayToolsSheet(
                        page = if (current == com.cyclone.mobile.ui.overlay.ComposerAccessory.NONE) sheetLastPage else current,
                        sharingActive = sharing.active,
                        aiSettings = aiSettings,
                        onAiSettingsChanged = { next ->
                            aiSettings = next
                            onAiSettingsChanged(next)
                        },
                        actions = com.cyclone.mobile.ui.overlay.OverlayToolsSheetActions(
                            onCamera = { launchFromSheet(Intent(service, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java).putExtra("camera", true)) },
                            onPhotos = { launchFromSheet(Intent(service, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java).putExtra("photos", true)) },
                            onFiles = { launchFromSheet(Intent(service, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java)) },
                            onShareScreen = { launchFromSheet(Intent(service, com.cyclone.mobile.capture.LiveCaptureConsentActivity::class.java).putExtra("wholeDisplay", true)) },
                            onCrossAppShare = { launchFromSheet(Intent(service, com.cyclone.mobile.capture.LiveCaptureConsentActivity::class.java).putExtra("wholeDisplay", true)) },
                        ),
                        blurAvailable = blurAvailable,
                        onBlurFraction = { fraction -> setSheetBlur((fraction * maxBlurPx).toInt(), blurAvailable) },
                        closeRequests = sheetCloseRequests,
                        onClosed = { com.cyclone.mobile.ui.overlay.OverlayToolsSheetState.close() },
                    )
                }
            }
        }
        // ComposeView is final; a thin host catches Back so it runs the same smooth close as a drag.
        val sheet = object : android.widget.FrameLayout(service) {
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    if (event.action == android.view.KeyEvent.ACTION_UP) sheetCloseRequests++
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setViewTreeLifecycleOwner(lifecycle)
            setViewTreeViewModelStoreOwner(lifecycle)
            setViewTreeSavedStateRegistryOwner(lifecycle)
            addView(content)
        }
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (blurAvailable) WindowManager.LayoutParams.FLAG_BLUR_BEHIND else 0),
            PixelFormat.TRANSLUCENT,
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            fitInsetsTypes = 0
            if (blurAvailable) blurBehindRadius = 0
        }
        sheetCloseRequests = 0
        sheetParams = layout
        sheetRoot = sheet
        sheetCompose = content
        runCatching { addWindow(sheet, layout) }.onFailure {
            content.disposeComposition()
            sheetCompose = null
            sheetRoot = null
            sheetParams = null
            com.cyclone.mobile.ui.overlay.OverlayToolsSheetState.close()
        }
    }

    private fun setSheetBlur(radiusPx: Int, blurAvailable: Boolean) {
        if (!blurAvailable) return
        val view = sheetRoot ?: return
        val layout = sheetParams ?: return
        if (kotlin.math.abs(layout.blurBehindRadius - radiusPx) < 2 && radiusPx != 0) return
        layout.blurBehindRadius = radiusPx.coerceAtLeast(0)
        runCatching { wm.updateViewLayout(view, layout) }
    }

    /** Opening a picker/consent screen closes the drawer at once and yields the Ask overlay. */
    private fun launchFromSheet(intent: Intent) {
        com.cyclone.mobile.ui.overlay.OverlayToolsSheetState.close()
        OverlayExternalInteraction.active.value = true
        runCatching { service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure {
                OverlayExternalInteraction.active.value = false
                Toast.makeText(service, "This action is unavailable.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun overlayParams(snapshot: OverlayChromeSnapshot): WindowManager.LayoutParams =
        windowParams(
            when {
                cardVisible() ->
                    OverlayChromeWindowPolicy.main(compact = false)
                glass() -> OverlayChromeWindowPolicy.glass()
                isMinimizedComposer(snapshot) -> OverlayChromeWindowPolicy.minimizedComposer()
                else -> OverlayChromeWindowPolicy.main(isCompact(snapshot))
            },
        )

    private fun windowParams(spec: OverlayWindowContract): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            widthFor(spec),
            heightFor(spec),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flagsFor(spec),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = gravityFor(spec)
            y = liftedY(spec, followKeyboard = spec.heightDp == null && !spec.notFocusable)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            fitInsetsTypes = 0
        }

    private fun applyWindowContract(
        layout: WindowManager.LayoutParams,
        spec: OverlayWindowContract,
        followKeyboard: Boolean,
    ): Boolean {
        var changed = false
        val width = widthFor(spec)
        val height = heightFor(spec)
        val flags = flagsFor(spec)
        val gravity = gravityFor(spec)
        val y = liftedY(spec, followKeyboard)
        if (layout.width != width) {
            layout.width = width
            changed = true
        }
        if (layout.height != height) {
            layout.height = height
            changed = true
        }
        if (layout.flags != flags) {
            layout.flags = flags
            changed = true
        }
        if (layout.gravity != gravity) {
            layout.gravity = gravity
            changed = true
        }
        if (layout.y != y) {
            layout.y = y
            changed = true
        }
        if (layout.softInputMode != WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING) {
            layout.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            changed = true
        }
        if (layout.fitInsetsTypes != 0) {
            layout.fitInsetsTypes = 0
            changed = true
        }
        return changed
    }

    private fun liftedY(spec: OverlayWindowContract, followKeyboard: Boolean): Int =
        OverlayImeLift.windowY(
            followKeyboard = followKeyboard,
            specBottomMarginPx = dp(spec.bottomMarginDp),
            imeBottomPx = imeBottomPx,
            navigationBottomPx = navigationBottomPx,
            restGapPx = dp(OverlayChromeContract.COMPOSER_BOTTOM_GAP_DP),
            keyboardGapPx = dp(OverlayImeLift.KEYBOARD_GAP_DP),
        )

    private fun trackIme(view: View) {
        val apply = { insets: WindowInsetsCompat ->
            // Alpha.131: while a host action runs the window has handed focus to the app, so the keyboard insets it
            // sees are the app's; the card keeps its place until the action is over.
            if (!OverlayGesturePassthrough.active()) {
                reportedImeBottomPx = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                imeBottomPx = reportedImeBottomPx
            }
            navigationBottomPx = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            applyLayout(latest)
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            apply(insets)
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            view,
            object : WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE,
            ) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    apply(insets)
                    return insets
                }
            },
        )
        ViewCompat.requestApplyInsets(view)
        // Accessibility overlays can receive frame-relative (zero) IME insets even with a docked
        // keyboard. Resolve its absolute window bounds; never inspect keyboard nodes or text.
        val keyboardTracker = object : Runnable {
            override fun run() {
                if (root !== view || !view.isAttachedToWindow) return
                if (view.visibility == View.VISIBLE && !isCompact(latest) && !glass() && !OverlayGesturePassthrough.active()) {
                    val screen = wm.maximumWindowMetrics.bounds
                    val docked = if (view.hasWindowFocus()) runCatching {
                        service.windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                            .maxOfOrNull { window ->
                                val bounds = android.graphics.Rect()
                                window.getBoundsInScreen(bounds)
                                OverlayImeLift.dockedHeight(screen.width(), screen.bottom, bounds.left,
                                    bounds.top, bounds.right, bounds.bottom, navigationBottomPx)
                            } ?: 0
                    }.getOrDefault(0) else 0
                    val next = OverlayImeLift.resolvedHeight(reportedImeBottomPx, docked)
                    if (imeBottomPx != next) {
                        imeBottomPx = next
                        applyLayout(latest)
                    }
                }
                // Alpha.134: the tracker only needs to be quick while Cyclone has the keyboard (the owner is typing).
                main.postDelayed(this, if (view.hasWindowFocus()) 80L else 500L)
            }
        }
        main.post(keyboardTracker)
        // Gaining focus (the owner tapped the Ask field) checks the keyboard at once instead of on the slow beat.
        view.viewTreeObserver.addOnWindowFocusChangeListener { focused ->
            if (focused && root === view) { main.removeCallbacks(keyboardTracker); main.post(keyboardTracker) }
        }
    }

    private fun widthFor(spec: OverlayWindowContract): Int =
        if (spec.matchParentWidth) WindowManager.LayoutParams.MATCH_PARENT else dp(requireNotNull(spec.widthDp))

    private fun heightFor(spec: OverlayWindowContract): Int =
        spec.heightDp?.let(::dp) ?: WindowManager.LayoutParams.WRAP_CONTENT

    private fun gravityFor(spec: OverlayWindowContract): Int = OverlayChromeWindowPolicy.gravity(spec)

    private fun flagsFor(spec: OverlayWindowContract): Int = OverlayChromeWindowPolicy.flags(
        spec,
        secretVisible = com.cyclone.mobile.secrets.SecretsCardRuntime.state.value?.visible == true,
    ).let {
        when {
            OverlayExternalInteraction.active.value || OverlayGesturePassthrough.hidden() -> OverlayChromeWindowPolicy.withHostGesturePassthrough(it)
            OverlayGesturePassthrough.active() -> OverlayChromeWindowPolicy.withHostFocus(it)
            else -> it
        }
    }

    /**
     * Commit overlay yield before dispatchGesture. FLAG_NOT_TOUCHABLE is not enough:
     * the idle ball and Ask card must be GONE and not important-for-accessibility, then
     * WindowManager must process that frame or the stroke still hits Cyclone chrome.
     */
    fun syncHostGesturePassthrough() {
        val latch = CountDownLatch(1)
        // Alpha.134: only a yield that changed a window waits for Android to commit it. Coming back never waits (the
        // next action doesn't depend on Cyclone's card being back), and a step back that changed nothing costs nothing.
        val entering = OverlayGesturePassthrough.active()
        val task = Runnable {
            try {
                windowsTouched = false
                if (root != null) applyLayout(latest)
                val attached = sequenceOf(root, haloRoot, shareRoot).firstOrNull { it?.isAttachedToWindow == true }
                if (attached != null && entering && windowsTouched) {
                    Choreographer.getInstance().postFrameCallback {
                        Choreographer.getInstance().postFrameCallback { latch.countDown() }
                    }
                } else {
                    latch.countDown()
                }
            } catch (_: RuntimeException) {
                latch.countDown()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run()
            return
        }
        if (!entering) {
            main.post(task)
            return
        }
        if (!main.post(task)) {
            latch.countDown()
            return
        }
        // Alpha 91: a busy main thread (0.5 s stalls were measured) must not let a stroke land on Cyclone's own card.
        latch.await(1_200, TimeUnit.MILLISECONDS)
    }

    private fun recordIdleTap() {
        applyIdleTapResult(idleActivation.onTap(SystemClock.elapsedRealtime()))
    }

    private fun recordSemanticActivation() {
        applyIdleTapResult(idleActivation.semanticActivate())
    }

    private fun applyIdleTapResult(result: OverlayIdleTapResult) {
        if (result.ignored) return
        idleVisualState = OverlayIdleVisualState(
            pulseSerial = result.pulseSerial,
            pulseLevel = result.pulseLevel,
            activating = result.activate,
        )
        if (!result.activate) return
        main.postDelayed(
            {
                if (isCompact(latest) && latest.idleChipVisible && idleVisualState.activating) {
                    onAction(OverlayUserAction.ASK_CYCLONE)
                }
            },
            OverlayChromeContract.IDLE_ACTIVATION_DELAY_MS,
        )
    }

    private fun resetIdleActivation() {
        idleActivation.reset()
        idleVisualState = OverlayIdleVisualState()
    }

    private fun isCompact(snapshot: OverlayChromeSnapshot): Boolean =
        snapshot.state == OverlayChromeState.IDLE || snapshot.launcherCollapsed

    private fun isMinimizedComposer(snapshot: OverlayChromeSnapshot): Boolean =
        snapshot.state != OverlayChromeState.IDLE && snapshot.minimized && !snapshot.launcherCollapsed

    fun beginVoiceInput() {
        onMain {
            if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                onVoiceStateChanged(false, null, "Allow microphone access once, then tap the voice button again.")
                Toast.makeText(service, "Cyclone needs microphone access for voice requests.", Toast.LENGTH_LONG).show()
                service.startActivity(
                    Intent(service, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_REQUEST_OVERLAY_VOICE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
                return@onMain
            }
            if (!SpeechRecognizer.isRecognitionAvailable(service)) {
                onVoiceStateChanged(false, null, "Voice recognition is not available on this phone.")
                return@onMain
            }
            val now = android.os.SystemClock.uptimeMillis()
            if (voiceSession && now - voiceSessionAt < 60_000L) return@onMain
            val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(service).also {
                it.setRecognitionListener(OverlayRecognitionListener())
                speechRecognizer = it
            }
            // Clear any session the recognizer still holds, so starting never fails as busy.
            recognizer.cancel()
            voiceSession = true
            voiceSessionAt = now
            onVoiceStateChanged(true, null, OverlayCopy.LISTENING)
            recognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                },
            )
        }
    }

    /**
     * Live voice (the empty Ask bar's button): a spoken conversation with Cyclone, the one Driver mode's button opens.
     * The Ask overlay steps aside while the voice panel is up ([applyLayout]) and comes back as it was.
     */
    fun beginLiveVoice() {
        onMain {
            if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                onVoiceStateChanged(false, null, "Allow microphone access once, then tap Live voice again.")
                service.startActivity(
                    Intent(service, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_REQUEST_OVERLAY_VOICE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
                return@onMain
            }
            if (voiceSession) { speechRecognizer?.cancel(); voiceSession = false; onVoiceStateChanged(false, null, null) }
            // The keyboard leaves with the Ask bar.
            root?.let { v -> runCatching { service.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0) } }
            com.cyclone.mobile.ui.overlay.DriverOverlay.live(service)
        }
    }

    /**
     * The owner stopped talking (tap on the orb, or let go after holding it). The recognizer finishes what it heard,
     * so the words land in the Ask field instead of being thrown away.
     */
    fun stopVoiceInput() {
        onMain {
            if (!voiceSession) {
                // Nothing is running (it was cancelled elsewhere): just put the button back.
                onVoiceStateChanged(false, null, null)
                return@onMain
            }
            speechRecognizer?.stopListening() ?: run {
                voiceSession = false
                onVoiceStateChanged(false, null, null)
            }
        }
    }

    private inner class OverlayRecognitionListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            voiceSession = false
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that. Try speaking again."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Tap the microphone to retry."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is required for voice requests."
                else -> "Voice input paused. Tap the microphone to retry."
            }
            onVoiceStateChanged(false, null, message)
        }

        override fun onResults(results: Bundle?) {
            voiceSession = false
            val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            // Plan 65 R1: the words wait in the field for Send; their decision can be ready by then.
            if (!transcript.isNullOrBlank()) com.cyclone.mobile.mind.modes.CycloneModes.prime(service, transcript)
            onVoiceStateChanged(false, transcript, if (transcript.isNullOrBlank()) "I didn't catch that." else null)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val transcript = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!transcript.isNullOrBlank()) {
                // Plan 65 R1: decide while they speak, once the words have held still for a moment.
                com.cyclone.mobile.mind.modes.CycloneModes.prime(service, transcript, afterMs = PRIME_SETTLE_MS)
                onVoiceStateChanged(true, transcript, OverlayCopy.LISTENING)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun onMain(block: () -> Unit) {
        val expectedGeneration = generation
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else main.post { if (generation == expectedGeneration) block() }
    }

    private companion object {
        /** Dictation's words must hold still this long before their decision starts early. */
        const val PRIME_SETTLE_MS = 250L
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()
}

internal class OverlayComposeLifecycle : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun start() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
        store.clear()
    }
}
