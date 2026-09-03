package com.cyclone.mobile.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
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
import com.cyclone.mobile.ui.overlay.OverlayChrome
import com.cyclone.mobile.ui.overlay.OverlayChromeSnapshot
import com.cyclone.mobile.ui.overlay.OverlayChromeState
import com.cyclone.mobile.ui.overlay.OverlayCopy
import com.cyclone.mobile.ui.overlay.OverlayUserAction

/**
 * Sibling of [AiTraceOverlayController]. Hosts the V4 Compose overlay on
 * TYPE_ACCESSIBILITY_OVERLAY so Cyclone chrome is not a new Activity.
 *
 * Overlay buttons are touchable. The window is WRAP_CONTENT, so host taps outside the chrome
 * reach the current app and must go through PhoneToolExecutor.
 */
class OverlayChromeController(
    private val service: CycloneAccessibilityService,
    private val onAction: (OverlayUserAction) -> Unit,
    private val onComposerChanged: (String) -> Unit,
    private val onRequestSubmitted: (String) -> Unit,
    private val onVoiceStateChanged: (Boolean, String?, String?) -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val lifecycle = OverlayComposeLifecycle()
    private var root: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var latest by mutableStateOf(OverlayChromeSnapshot())
    private var speechRecognizer: SpeechRecognizer? = null

    fun show(snapshot: OverlayChromeSnapshot) {
        onMain {
            latest = snapshot
            if (root != null) {
                applyLayout(snapshot)
                return@onMain
            }
            lifecycle.start()
            val view = ComposeView(service).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setViewTreeLifecycleOwner(lifecycle)
                setViewTreeViewModelStoreOwner(lifecycle)
                setViewTreeSavedStateRegistryOwner(lifecycle)
                setContent {
                    OverlayChrome(
                        snapshot = latest,
                        onAction = onAction,
                        onComposerChanged = onComposerChanged,
                        onRequestSubmitted = onRequestSubmitted,
                        onVoiceInput = ::beginVoiceInput,
                    )
                }
            }
            val layout = overlayParams(snapshot)
            params = layout
            root = view
            applyLayout(snapshot)
            wm.addView(view, layout)
        }
    }

    fun render(snapshot: OverlayChromeSnapshot) {
        onMain {
            latest = snapshot
            applyLayout(snapshot)
        }
    }

    fun dismiss() {
        onMain {
            root?.let { runCatching { wm.removeView(it) } }
            root = null
            params = null
            speechRecognizer?.destroy()
            speechRecognizer = null
            lifecycle.destroy()
        }
    }

    private fun applyLayout(snapshot: OverlayChromeSnapshot) {
        val view = root ?: return
        val layout = params ?: return
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        view.contentDescription = if (snapshot.state == OverlayChromeState.GATE && !snapshot.minimized) OverlayCopy.GATE else null
        val compact = snapshot.state == OverlayChromeState.IDLE || snapshot.minimized
        val width = if (compact) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            if (compact) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else 0
        val gravity = if (compact) Gravity.BOTTOM or Gravity.END else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        var changed = false
        if (layout.width != width) {
            layout.width = width
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
        val y = if (compact) dp(4) else 0
        if (layout.y != y) {
            layout.y = y
            changed = true
        }
        if (changed) runCatching { wm.updateViewLayout(view, layout) }
    }

    private fun overlayParams(snapshot: OverlayChromeSnapshot): WindowManager.LayoutParams {
        val compact = snapshot.state == OverlayChromeState.IDLE || snapshot.minimized
        return WindowManager.LayoutParams(
            if (compact) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                if (compact) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else 0,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = if (compact) Gravity.BOTTOM or Gravity.END else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = if (compact) dp(4) else 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }

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
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
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
