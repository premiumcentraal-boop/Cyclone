package com.cyclone.mobile.ai

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.cyclone.mobile.CycloneAccessibilityService
import java.util.ArrayDeque

/**
 * V2.9.2 transparent decision HUD.
 *
 * The historical object name is kept to avoid breaking older callers, but the behavior is new:
 * page interpretation -> decision -> verification/recovery -> task result -> compiling results.
 * The HUD shows concise user-facing decision/evidence summaries only. It never exposes model-private
 * hidden chain-of-thought, credentials, typed values or raw screenshots.
 */
object AiTraceOverlayV27Runtime {
    private var controller: AiTraceOverlayV292Controller? = null

    @Synchronized
    fun startTask(service: CycloneAccessibilityService, sessionId: String) {
        if (controller?.sessionId == sessionId) return
        controller?.dismissImmediately()
        controller = AiTraceOverlayV292Controller(service, sessionId) { finished ->
            synchronized(this) { if (controller === finished) controller = null }
        }.also { it.show() }
    }

    @Synchronized
    fun finishTask(sessionId: String, ok: Boolean, message: String) {
        controller?.takeIf { it.sessionId == sessionId }?.finish(ok, message)
    }

    @Synchronized
    fun compilationComplete(sessionId: String, summary: String) {
        controller?.takeIf { it.sessionId == sessionId }?.compilationComplete(summary)
    }

    @Synchronized
    fun disableImmediately() {
        controller?.dismissImmediately()
        controller = null
    }

    fun isVisible(): Boolean = controller != null
}

class AiTraceOverlayV292Controller(
    private val service: CycloneAccessibilityService,
    val sessionId: String,
    private val onDismissed: (AiTraceOverlayV292Controller) -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var orb: TextView? = null
    private var stage: TextView? = null
    private var status: TextView? = null
    private var timeline: LinearLayout? = null
    private var unsubscribe: (() -> Unit)? = null
    private var pulse: ObjectAnimator? = null
    private var finishing = false
    private var compilationDone = false
    private val recent = ArrayDeque<AiTraceEvent>()

    fun show() {
        main.post {
            if (root != null) return@post
            val container = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(12), dp(15), dp(12))
                background = glassBackground(Color.rgb(18, 18, 28), 206, Color.argb(65, 255, 255, 255))
                elevation = dp(16).toFloat()
                alpha = 0f
                translationY = -dp(14).toFloat()
            }

            val header = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            orb = TextView(service).apply {
                text = "✦"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 19f
                background = glassBackground(Color.rgb(105, 91, 255), 228, Color.argb(105, 255, 255, 255), dp(18).toFloat())
            }
            header.addView(orb, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) })
            val titles = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
            stage = TextView(service).apply {
                text = "THINKING"
                setTextColor(Color.rgb(188, 181, 255))
                textSize = 10.5f
                letterSpacing = .16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            titles.addView(stage)
            titles.addView(TextView(service).apply {
                text = "Cyclone AI · live phone agent"
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            container.addView(header)

            status = TextView(service).apply {
                text = "Reading the current UI map and checking learned routes…"
                setTextColor(Color.rgb(236, 235, 244))
                textSize = 12.5f
                maxLines = 4
                setPadding(0, dp(8), 0, dp(7))
            }
            container.addView(status)

            container.addView(View(service).apply { setBackgroundColor(Color.argb(46, 255, 255, 255)) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            timeline = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(5), 0, 0)
            }
            container.addView(timeline)

            val params = WindowManager.LayoutParams(
                dp(354),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(22)
            }
            root = container
            wm.addView(container, params)
            container.animate().alpha(1f).translationY(0f).setDuration(220).start()
            startThinkingAnimation()
            unsubscribe = AiTraceBus.subscribe { event ->
                if (event.sessionId != sessionId) return@subscribe
                main.post { consume(event) }
            }
        }
    }

    private fun consume(event: AiTraceEvent) {
        val visible = event.kind !in setOf("START", "MODEL") || event.kind == "MODEL"
        if (!visible) return
        recent.addLast(event)
        while (recent.size > 4) recent.removeFirst()
        stage?.text = stageFor(event)
        stage?.setTextColor(stageColor(event))
        status?.text = event.displayText
        renderTimeline()
        if (event.ok == false) flashOrb(Color.rgb(224, 76, 93))
        else if (event.ok == true && event.kind in setOf("RESULT", "REPLAY", "PAGE", "LEARNING")) flashOrb(Color.rgb(58, 181, 112))
        else startThinkingAnimation()
    }

    private fun renderTimeline() {
        val target = timeline ?: return
        target.removeAllViews()
        recent.forEach { event ->
            val row = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(0, dp(3), 0, dp(3))
            }
            row.addView(TextView(service).apply {
                text = when (event.ok) { true -> "●"; false -> "●"; null -> "◌" }
                setTextColor(stageColor(event))
                textSize = 10f
            }, LinearLayout.LayoutParams(dp(17), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(service).apply {
                text = timelineText(event)
                setTextColor(Color.rgb(207, 205, 218))
                textSize = 10.8f
                maxLines = 2
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            target.addView(row)
        }
    }

    fun finish(ok: Boolean, message: String) {
        main.post {
            if (finishing) return@post
            finishing = true
            pulse?.cancel()
            stage?.text = if (ok) "TASK COMPLETED" else "TASK FAILED"
            stage?.setTextColor(if (ok) Color.rgb(92, 222, 148) else Color.rgb(255, 105, 120))
            status?.text = TracePrivacy.clean(message).take(220).ifBlank { if (ok) "Task completed." else "Task failed." }
            flashOrb(if (ok) Color.rgb(58, 181, 112) else Color.rgb(224, 76, 93))
            main.postDelayed({
                if (compilationDone || root == null) return@postDelayed
                stage?.text = "COMPILING RESULTS"
                stage?.setTextColor(Color.rgb(188, 181, 255))
                status?.text = "Consolidating verified actions, failures and recovery evidence into Cyclone Brain…"
                startThinkingAnimation()
            }, 650L)
            // Fail-safe: the HUD cannot remain forever if Android kills the background compiler.
            main.postDelayed({ if (!compilationDone) compilationComplete("Result saved locally. Open the notification/history for details.") }, 10_000L)
        }
    }

    fun compilationComplete(summary: String) {
        main.post {
            if (compilationDone) return@post
            compilationDone = true
            pulse?.cancel()
            stage?.text = "RESULTS COMPILED"
            stage?.setTextColor(Color.rgb(92, 222, 148))
            status?.text = TracePrivacy.clean(summary).take(240).ifBlank { "Cyclone Brain updated." }
            flashOrb(Color.rgb(58, 181, 112))
            main.postDelayed({ fadeOut() }, 1650L)
        }
    }

    fun dismissImmediately() {
        main.post {
            pulse?.cancel(); pulse = null
            unsubscribe?.invoke(); unsubscribe = null
            root?.let { runCatching { wm.removeView(it) } }
            root = null; orb = null; stage = null; status = null; timeline = null
            onDismissed(this)
        }
    }

    private fun fadeOut() {
        val view = root ?: return dismissImmediately()
        unsubscribe?.invoke(); unsubscribe = null
        view.animate()
            .alpha(0f)
            .translationY(-dp(30).toFloat())
            .setDuration(380)
            .withEndAction { dismissImmediately() }
            .start()
    }

    private fun startThinkingAnimation() {
        val target = orb ?: return
        if (pulse?.isRunning == true) return
        pulse = ObjectAnimator.ofFloat(target, View.ALPHA, .52f, 1f).apply {
            duration = 680
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun flashOrb(color: Int) {
        pulse?.cancel(); pulse = null
        orb?.background = glassBackground(color, 238, Color.argb(115, 255, 255, 255), dp(18).toFloat())
        orb?.alpha = 1f
        main.postDelayed({
            if (!finishing) {
                orb?.background = glassBackground(Color.rgb(105, 91, 255), 228, Color.argb(105, 255, 255, 255), dp(18).toFloat())
                startThinkingAnimation()
            }
        }, 520L)
    }

    private fun stageFor(event: AiTraceEvent): String = when {
        event.ok == false && event.kind == "BOUNDARY" -> "NEEDS YOU"
        event.ok == false -> "RECOVERING"
        event.kind == "MODEL" -> "THINKING"
        event.kind == "PAGE" -> "UI UNDERSTOOD"
        event.kind == "BRAIN" -> "CHECKING MEMORY"
        event.kind == "DECISION" -> "DECISION"
        event.kind == "REPLAY" -> "LEARNED ROUTE"
        event.kind == "RESULT" -> "VERIFIED"
        event.kind == "VISION" -> "VISUAL CHECK"
        event.kind == "LEARNING" -> "LEARNING"
        event.kind == "DONE" -> "TASK COMPLETED"
        event.kind == "STOPPED" -> "TASK FAILED"
        else -> "WORKING"
    }

    private fun stageColor(event: AiTraceEvent): Int = when (event.ok) {
        true -> Color.rgb(92, 222, 148)
        false -> Color.rgb(255, 105, 120)
        null -> when (event.kind) {
            "MODEL", "DECISION", "BRAIN" -> Color.rgb(188, 181, 255)
            else -> Color.rgb(151, 210, 255)
        }
    }

    private fun timelineText(event: AiTraceEvent): String {
        val prefix = when (event.kind) {
            "PAGE" -> "UI"
            "BRAIN" -> "Brain"
            "REPLAY" -> "Known"
            "DECISION" -> "Do"
            "RESULT" -> "Pass"
            "RECOVERY" -> "Error"
            "BOUNDARY" -> "Stop"
            "VISION" -> "Vision"
            "LEARNING" -> "Learn"
            else -> event.kind.lowercase().replaceFirstChar(Char::uppercase)
        }
        return "$prefix · ${event.displayText}".take(180)
    }

    private fun glassBackground(base: Int, alpha: Int, stroke: Int, radius: Float = dp(21).toFloat()): GradientDrawable = GradientDrawable().apply {
        setColor(Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base)))
        cornerRadius = radius
        setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()
}
