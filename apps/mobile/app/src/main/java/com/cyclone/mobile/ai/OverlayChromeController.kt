package com.cyclone.mobile.ai

import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
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
import com.cyclone.mobile.ui.overlay.OverlayChrome
import com.cyclone.mobile.ui.overlay.OverlayChromeSnapshot
import com.cyclone.mobile.ui.overlay.OverlayChromeState
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
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val lifecycle = OverlayComposeLifecycle()
    private var root: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var latest by mutableStateOf(OverlayChromeSnapshot())

    fun show(snapshot: OverlayChromeSnapshot) {
        onMain {
            latest = snapshot
            if (root != null) {
                applyLayout(snapshot.state)
                return@onMain
            }
            lifecycle.start()
            val view = ComposeView(service).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setViewTreeLifecycleOwner(lifecycle)
                setViewTreeViewModelStoreOwner(lifecycle)
                setViewTreeSavedStateRegistryOwner(lifecycle)
                setContent { OverlayChrome(snapshot = latest, onAction = onAction) }
            }
            val layout = overlayParams(snapshot.state)
            params = layout
            root = view
            wm.addView(view, layout)
        }
    }

    fun render(snapshot: OverlayChromeSnapshot) {
        onMain {
            latest = snapshot
            applyLayout(snapshot.state)
        }
    }

    fun dismiss() {
        onMain {
            root?.let { runCatching { wm.removeView(it) } }
            root = null
            params = null
            lifecycle.destroy()
        }
    }

    private fun applyLayout(state: OverlayChromeState) {
        val view = root ?: return
        val layout = params ?: return
        val wrapChip = state == OverlayChromeState.IDLE
        val width = if (wrapChip) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT
        if (layout.width != width) {
            layout.width = width
            runCatching { wm.updateViewLayout(view, layout) }
        }
    }

    private fun overlayParams(state: OverlayChromeState): WindowManager.LayoutParams {
        val wrapChip = state == OverlayChromeState.IDLE
        return WindowManager.LayoutParams(
            if (wrapChip) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(16)
        }
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
