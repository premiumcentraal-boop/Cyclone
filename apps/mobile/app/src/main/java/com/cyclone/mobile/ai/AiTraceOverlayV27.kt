package com.cyclone.mobile.ai

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.cyclone.mobile.CycloneAccessibilityService

/** V2.7 overlay lifecycle: one overlay per active AI task, then a brief result and animated exit. */
object AiTraceOverlayV27Runtime {
    private var controller: AiTraceOverlayV27Controller? = null

    @Synchronized
    fun startTask(service: CycloneAccessibilityService, sessionId: String) {
        controller?.dismissImmediately()
        controller = AiTraceOverlayV27Controller(service, sessionId) { finished ->
            synchronized(this) {
                if (controller === finished) controller = null
            }
        }.also { it.show() }
    }

    @Synchronized
    fun finishTask(sessionId: String, ok: Boolean, message: String) {
        controller?.takeIf { it.sessionId == sessionId }?.finish(ok, message)
    }

    @Synchronized
    fun disableImmediately() {
        controller?.dismissImmediately()
        controller = null
    }

    fun isVisible(): Boolean = controller != null
}

class AiTraceOverlayV27Controller(
    private val service: CycloneAccessibilityService,
    val sessionId: String,
    private val onDismissed: (AiTraceOverlayV27Controller) -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var title: TextView? = null
    private var status: TextView? = null
    private var unsubscribe: (() -> Unit)? = null
    private var finishing = false

    fun show() {
        main.post {
            if (root != null) return@post
            val container = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(11), dp(15), dp(11))
                background = background(false)
                elevation = dp(12).toFloat()
                alpha = 0f
                translationY = -dp(12).toFloat()
            }
            title = TextView(service).apply {
                text = "✦ Cyclone AI · working"
                setTextColor(Color.WHITE)
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            status = TextView(service).apply {
                text = "Understanding the current phone state…"
                setTextColor(Color.rgb(230, 230, 238))
                textSize = 12.5f
                maxLines = 4
                setPadding(0, dp(4), 0, 0)
            }
            container.addView(title)
            container.addView(status)
            val params = WindowManager.LayoutParams(
                dp(338),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(24)
            }
            root = container
            wm.addView(container, params)
            container.animate().alpha(1f).translationY(0f).setDuration(180).start()
            unsubscribe = AiTraceBus.subscribe { event ->
                if (event.sessionId != sessionId || finishing) return@subscribe
                main.post { status?.text = event.displayText }
            }
        }
    }

    fun finish(ok: Boolean, message: String) {
        main.post {
            if (finishing) return@post
            finishing = true
            unsubscribe?.invoke()
            unsubscribe = null
            val view = root ?: run { onDismissed(this); return@post }
            view.background = background(ok)
            title?.text = if (ok) "✓ Cyclone AI · task complete" else "✕ Cyclone AI · task stopped"
            status?.text = message.take(180).ifBlank { if (ok) "Done." else "Stopped." }
            main.postDelayed({
                root?.animate()
                    ?.alpha(0f)
                    ?.translationY(-dp(28).toFloat())
                    ?.setDuration(360)
                    ?.withEndAction { dismissImmediately() }
                    ?.start()
            }, if (ok) 1050L else 1450L)
        }
    }

    fun dismissImmediately() {
        main.post {
            unsubscribe?.invoke()
            unsubscribe = null
            root?.let { runCatching { wm.removeView(it) } }
            root = null
            title = null
            status = null
            onDismissed(this)
        }
    }

    private fun background(success: Boolean): GradientDrawable = GradientDrawable().apply {
        val base = if (success) Color.rgb(23, 92, 66) else Color.rgb(24, 24, 32)
        setColor(Color.argb(238, Color.red(base), Color.green(base), Color.blue(base)))
        cornerRadius = dp(19).toFloat()
        setStroke(dp(1), Color.argb(72, 255, 255, 255))
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()
}
