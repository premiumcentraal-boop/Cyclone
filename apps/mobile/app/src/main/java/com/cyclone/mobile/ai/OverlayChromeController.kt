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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import com.cyclone.mobile.ui.overlay.OverlayExternalInteraction
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

    fun flags(spec: OverlayWindowContract): Int {
        // Keep assistant drafts and approval chrome out of all capture paths, including consent startup.
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SECURE
        if (spec.notTouchModal) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (spec.notFocusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (spec.notTouchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    fun gravity(spec: OverlayWindowContract): Int =
        Gravity.BOTTOM or if (spec.bottomCenter) Gravity.CENTER_HORIZONTAL else Gravity.END
}

/**
 * Sibling of [AiTraceOverlayController]. Hosts the V4 Compose overlay on
 * TYPE_ACCESSIBILITY_OVERLAY so Cyclone chrome is not a new Activity.
 *
 * Expanded chrome is one touchable panel window. Compact mode deliberately splits the logical
 * overlay into a non-touchable 144x72dp visual halo and a centered 48x48dp activation window.
 * The larger decoration never owns input; host taps outside the 48dp hotspot remain host taps.
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
        val attached = listOfNotNull(root, haloRoot, shareRoot, activeBorder)
        if (attached.any { it.visibility == View.VISIBLE }) speechRecognizer?.cancel()
        attached.forEach { it.visibility = View.GONE }
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
    private fun glass() = isCompact(latest) && com.cyclone.mobile.ui.overlay.BackgroundGlassPolicy.visible(backgroundTask)
    private val idleActivation = OverlayIdleActivationTracker()
    private var root: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var activeBorder: View? = null
    private var shareRoot: ComposeView? = null
    private var haloRoot: ComposeView? = null
    private var haloParams: WindowManager.LayoutParams? = null
    private var latest by mutableStateOf(OverlayChromeSnapshot())
    private var aiSettings by mutableStateOf(OverlayAiSettings())
    private var idleVisualState by mutableStateOf(OverlayIdleVisualState())
    private var imeBottomPx by mutableStateOf(0)
    private var navigationBottomPx by mutableStateOf(0)
    private var speechRecognizer: SpeechRecognizer? = null

    fun show(snapshot: OverlayChromeSnapshot) {
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
                    val externalActive by OverlayExternalInteraction.active.collectAsState()
                    LaunchedEffect(externalActive) {
                        aiSettings = getAiSettings()
                        applyLayout(latest)
                    }
                    if (glass()) {
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
                                aiSettings = aiSettings,
                                onAiSettingsChanged = { next ->
                                    aiSettings = next
                                    onAiSettingsChanged(next)
                                },
                                idleVisualState = idleVisualState,
                                onIdleTap = ::recordIdleTap,
                                onIdleSemanticActivate = ::recordSemanticActivation,
                            )
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
                trackIme(view)
            } catch (_: Exception) { dismiss(); return@onMain }
            val shareView = ComposeView(service).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setViewTreeLifecycleOwner(lifecycle)
                setViewTreeViewModelStoreOwner(lifecycle)
                setViewTreeSavedStateRegistryOwner(lifecycle)
                setContent {
                    val sharing by LiveCaptureSessionManager.state.collectAsState()
                    val externalActive by OverlayExternalInteraction.active.collectAsState()
                    if (!externalActive && isCompact(latest) && sharing.active) {
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

    fun render(snapshot: OverlayChromeSnapshot) {
        onMain {
            latest = snapshot
            if (hideLockedWindows()) return@onMain
            if (root != null && root?.isAttachedToWindow != true) {
                dismiss()
                show(snapshot)
                return@onMain
            }
            if (root == null) {
                if (snapshot.state != OverlayChromeState.IDLE || snapshot.idleChipVisible || glass()) show(snapshot)
                return@onMain
            }
            if (!isCompact(snapshot)) resetIdleActivation()
            applyLayout(snapshot)
        }
    }

    fun dismiss() {
        onMain {
            generation++
            if (lockReceiverRegistered) service.unregisterReceiver(lockReceiver)
            lockReceiverRegistered = false
            main.removeCallbacksAndMessages(null)
            latest = OverlayChromeSnapshot(idleChipVisible = false)
            backgroundTask = null
            windows.clear()
            listOfNotNull(root, haloRoot, shareRoot).forEach { it.disposeComposition() }
            shareRoot = null
            activeBorder = null
            root = null
            params = null
            haloRoot = null
            haloParams = null
            resetIdleActivation()
            speechRecognizer?.destroy()
            speechRecognizer = null
            lifecycle.destroy()
        }
    }

    fun keyboardClosed() {
        onMain {
            imeBottomPx = 0
            applyLayout(latest)
        }
    }

    private fun applyLayout(snapshot: OverlayChromeSnapshot) {
        if (hideLockedWindows()) return
        shareRoot?.visibility = View.VISIBLE
        renderActiveBorder(snapshot)
        val view = root ?: return
        val layout = params ?: return
        val compact = isCompact(snapshot)
        val visible = (!compact || snapshot.idleChipVisible || glass()) && !OverlayExternalInteraction.active.value

        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        view.contentDescription =
            if (snapshot.state == OverlayChromeState.GATE && !snapshot.minimized) OverlayCopy.GATE else null
        view.visibility = if (visible) View.VISIBLE else View.GONE

        val spec = if (glass()) OverlayChromeWindowPolicy.glass() else OverlayChromeWindowPolicy.main(compact)
        val changed = applyWindowContract(layout, spec, followKeyboard = !compact && !glass())
        if (changed) runCatching { wm.updateViewLayout(view, layout) }

        haloRoot?.let { halo ->
            halo.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            halo.visibility = if (visible && compact && !glass() && snapshot.idleChipVisible) View.VISIBLE else View.GONE
        }
        haloParams?.let { hp ->
            val haloChanged = applyWindowContract(hp, OverlayChromeWindowPolicy.halo, followKeyboard = false)
            if (haloChanged) haloRoot?.let { halo -> runCatching { wm.updateViewLayout(halo, hp) } }
        }
    }

    /** Decoration never owns touch/focus and yields with every explicit external interaction. */
    private fun renderActiveBorder(snapshot: OverlayChromeSnapshot) {
        val active = !OverlayExternalInteraction.active.value &&
            !snapshot.userPaused &&
            snapshot.state in setOf(OverlayChromeState.WORKING, OverlayChromeState.LIVE)
        if (!active) {
            activeBorder?.let { windows.remove(it) }
            activeBorder = null
            return
        }
        if (activeBorder != null) return
        val border = object : View(service) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f * resources.displayMetrics.density
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val inset = paint.strokeWidth / 2
                paint.shader = android.graphics.LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                    intArrayOf(0xFFA5C8FF.toInt(), 0xFFC4B8FF.toInt(), 0xFF8DE3D2.toInt()), null,
                    android.graphics.Shader.TileMode.CLAMP)
                canvas.drawRoundRect(inset, inset, width - inset, height - inset,
                    24f * resources.displayMetrics.density, 24f * resources.displayMetrics.density, paint)
            }
        }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val layout = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        runCatching { addWindow(border, layout); activeBorder = border }
    }

    private fun overlayParams(snapshot: OverlayChromeSnapshot): WindowManager.LayoutParams =
        windowParams(OverlayChromeWindowPolicy.main(isCompact(snapshot)))

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
            imeBottomPx = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
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
    }

    private fun widthFor(spec: OverlayWindowContract): Int =
        if (spec.matchParentWidth) WindowManager.LayoutParams.MATCH_PARENT else dp(requireNotNull(spec.widthDp))

    private fun heightFor(spec: OverlayWindowContract): Int =
        spec.heightDp?.let(::dp) ?: WindowManager.LayoutParams.WRAP_CONTENT

    private fun gravityFor(spec: OverlayWindowContract): Int = OverlayChromeWindowPolicy.gravity(spec)

    private fun flagsFor(spec: OverlayWindowContract): Int = OverlayChromeWindowPolicy.flags(spec).let {
        if (OverlayExternalInteraction.active.value) it or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else it
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
        snapshot.state == OverlayChromeState.IDLE || snapshot.minimized

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
            val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(service).also {
                it.setRecognitionListener(OverlayRecognitionListener())
                speechRecognizer = it
            }
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

    private inner class OverlayRecognitionListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that. Try speaking again."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Tap the microphone to retry."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is required for voice requests."
                else -> "Voice input paused. Tap the microphone to retry."
            }
            onVoiceStateChanged(false, null, message)
        }

        override fun onResults(results: Bundle?) {
            val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            onVoiceStateChanged(false, transcript, if (transcript.isNullOrBlank()) "I didn't catch that." else null)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val transcript = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!transcript.isNullOrBlank()) onVoiceStateChanged(true, transcript, OverlayCopy.LISTENING)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun onMain(block: () -> Unit) {
        val expectedGeneration = generation
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else main.post { if (generation == expectedGeneration) block() }
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()
}

private class OverlayComposeLifecycle : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
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
