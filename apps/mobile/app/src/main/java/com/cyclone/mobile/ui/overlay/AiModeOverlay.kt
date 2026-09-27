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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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

    fun attach(service: CycloneAccessibilityService) {
        detach()
        this.service = service
        DriverMode.load(service)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            DriverMode.settings.map { it.enabled to it.buttonDp }.distinctUntilChanged().collect { (on, dp) ->
                stop()
                if (on) start(service, dp)
                // The idle bubble gives way to the AI button, or comes back.
                OverlayChromeRuntime.refreshExternalSurface()
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
        val next = VoiceSession(service)
        session = next
        windows = runCatching { Windows(service, next, buttonDp) }.getOrNull()
        val w = windows ?: return
        scope?.launch { next.turn.collect { w.show(VoiceFace.of(it)) } }
    }

    private fun stop() {
        windows?.remove()
        windows = null
        session?.close()
        session = null
    }

    /** The three windows and their Compose content. */
    private class Windows(private val context: CycloneAccessibilityService, private val session: VoiceSession, private val buttonDp: Int) {
        private val wm = context.getSystemService(WindowManager::class.java)
        private val density = context.resources.displayMetrics.density
        private val lifecycles = mutableListOf<OverlayComposeLifecycle>()
        private val buttonPx = ((buttonDp + DRIVER_GLOW.value * 2) * density).toInt()

        private val button = ButtonHost(context).apply { addView(compose { ButtonContent() }) }
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

        init {
            place()
            wm.addView(button, buttonParams)
        }

        fun show(face: VoiceFace) {
            // The orb moves into AI mode while it is up; the button comes back when it collapses.
            val hidden = face.panel
            if ((button.visibility == View.GONE) != hidden) {
                button.visibility = if (hidden) View.GONE else View.VISIBLE
                // A hidden button must not take touches meant for the app underneath.
                buttonParams.flags = if (hidden) buttonParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    else buttonParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                runCatching { wm.updateViewLayout(button, buttonParams) }
            }
            button.contentDescription = face.description
            button.state = face.stateDescription
            if (face.dim != dimShown) {
                dimShown = face.dim
                if (face.dim) runCatching { wm.addView(dim, dimParams) } else runCatching { wm.removeView(dim) }
            }
            if (face.panel != panelShown) {
                panelShown = face.panel
                if (face.panel) runCatching { wm.addView(panel, panelParams) } else runCatching { wm.removeView(panel) }
            }
        }

        fun remove() {
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
            val margin = (6 * density).toInt()
            buttonParams.x = if (spot.right) bounds.width() - buttonPx - margin else margin
            buttonParams.y = (spot.yFraction * bounds.height() - buttonPx / 2f).toInt().coerceIn(0, (bounds.height() - buttonPx).coerceAtLeast(0))
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
            val spot = ButtonSpot.snap(buttonParams.x + buttonPx / 2f, buttonParams.y + buttonPx / 2f, bounds.width().toFloat(), bounds.height().toFloat())
            DriverMode.saveSpot(context, bounds.width() > bounds.height(), spot)
            place()
        }

        private fun compose(content: @Composable () -> Unit): ComposeView {
            val owner = OverlayComposeLifecycle().also { it.start(); lifecycles += it }
            return ComposeView(context).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent { TiltGlassTheme { content() } }
            }
        }

        @Composable
        private fun ButtonContent() {
            val turn by session.turn.collectAsState()
            val level by session.level.collectAsState()
            val face = remember(turn) { VoiceFace.of(turn) }
            DriverButtonFace(face, level, buttonDp.dp)
        }

        @Composable
        private fun DimContent() {
            val shade by animateFloatAsState(0.46f, tween(260), label = "Dim")
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = shade * 0.75f),
                0.7f to Color.Black.copy(alpha = shade),
                1f to Color(0xFF04181D).copy(alpha = shade + 0.2f),
            )))
        }

        @Composable
        private fun PanelContent() {
            val turn by session.turn.collectAsState()
            val level by session.level.collectAsState()
            val face = remember(turn) { VoiceFace.of(turn) }
            val enter = remember { MutableTransitionState(false).apply { targetState = true } }
            AnimatedVisibility(enter, enter = slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(200))) {
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
}

/** AI mode: status, captions on veil pills, the big orb, Stop and "Not now". Glanceable: large type, two lines at most. */
@Composable
internal fun AiModePanel(face: VoiceFace, level: Float, onOrb: () -> Unit, onStop: () -> Unit, onNotNow: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp)
            .tiltGlass(30.dp)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(face.status.uppercase(), color = if (face.warm) GlassWarm else GlassMuted, fontSize = 12.sp, letterSpacing = 1.6.sp,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        if (face.you.isNotBlank()) {
            Caption(face.you, GlassMuted, 20, FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
        }
        if (face.cyclone.isNotBlank()) Caption(face.cyclone, GlassInk, 23, FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier.size(136.dp)
                .clickable(interaction, indication = null, role = Role.Button, onClick = onOrb)
                .semantics {
                    role = Role.Button
                    contentDescription = "Cyclone voice"
                    stateDescription = face.stateDescription
                    onClick(label = "Talk") { onOrb(); true }
                },
            contentAlignment = Alignment.Center,
        ) {
            DriveOrb(face.motion, level, face.warm, Modifier.size(136.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            if (face.notNow) GlassCapsuleButton("Not now", primary = false, onClick = onNotNow, modifier = Modifier.weight(1f).height(56.dp))
            GlassCapsuleButton("Stop", primary = true, onClick = onStop, modifier = Modifier.weight(1f).height(56.dp))
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
