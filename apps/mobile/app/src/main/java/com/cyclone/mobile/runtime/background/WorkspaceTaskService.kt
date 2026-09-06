package com.cyclone.mobile.runtime.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.content.res.ColorStateList
import android.widget.ImageView
import android.widget.ProgressBar
import com.cyclone.mobile.ai.vision.live.LiveVisionRuntime
import com.cyclone.mobile.R
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.ai.OpenRouterAdaptiveAgent
import com.cyclone.mobile.ai.QuickAgentConfig
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.runtime.session.ExecutionContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Visible task lifetime. Process restart never restores autonomous input authority. */
class WorkspaceTaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var sessionId: String? = null
    private var agent: OpenRouterAdaptiveAgent? = null
    private var running: Job? = null
    private var sheet: LinearLayout? = null
    private var windowManager: WindowManager? = null
    private var expanded = false
    private var paused = false
    private var handoff = false
    private var label = "your app"
    private var message = "Opening workspace…"
    private var finished = false
    private var previewView: ImageView? = null
    private var previewLabel: TextView? = null
    private var previewBitmap: Bitmap? = null
    private val previewTick = object : Runnable {
        override fun run() {
            if (!expanded || sheet == null) return
            val next = sessionId?.let { runCatching { LiveVisionRuntime.preview(it) }.getOrNull() }
            previewView?.setImageBitmap(next)
            previewBitmap?.recycle(); previewBitmap = next
            previewLabel?.text = if (next != null) "Live workspace · view only" else "Preview unavailable"
            main.postDelayed(this, 750)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "cancel" -> { agent?.cancelActiveTask(); scope.launch { sessionId?.let { WorkspaceRuntime.close(it) }; main.post { stopSelf() } }; return START_NOT_STICKY }
            "view" -> { expanded = true; render(); return START_NOT_STICKY }
            "handoff" -> {
                scope.launch {
                    runCatching { sessionId?.let { WorkspaceRuntime.handoff(it) } }
                        .onSuccess { update("Your app is ready for review. Cyclone has stopped."); main.post { stopSelf() } }
                        .onFailure { update("Open the app to continue. Cyclone has stopped.") }
                }
                return START_NOT_STICKY
            }
            "pause" -> {
                scope.launch { runCatching { sessionId?.let { WorkspaceRuntime.pause(it) } }
                    .onSuccess { paused = true; update("Paused — take your time") }
                    .onFailure { update("The workspace is unavailable.") } }
                return START_NOT_STICKY
            }
            "resume" -> {
                if (running?.isActive == true || handoff) return START_NOT_STICKY
                running = scope.launch {
                    runCatching {
                        WorkspaceRuntime.resume(sessionId ?: error("Workspace unavailable"))
                        paused = false; update("Working in $label…")
                        finishTask(agent!!.resume())
                    }.onFailure { update("This task needs to be restarted.") }
                }
                return START_NOT_STICKY
            }
        }
        if (intent == null || sessionId != null || running?.isActive == true) { if (intent == null) stopSelf(); return START_NOT_STICKY }
        val goal = intent.getStringExtra("goal").orEmpty()
        val target = intent.getStringExtra("package").orEmpty()
        if (goal.isBlank() || target.isBlank()) { stopSelf(); return START_NOT_STICKY }
        label = intent.getStringExtra("label").orEmpty().ifBlank { "your app" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Cyclone tasks", NotificationManager.IMPORTANCE_LOW))
        startForeground(902, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        render()
        running = scope.launch {
            runCatching {
                val session = WorkspaceRuntime.create(applicationContext, target)
                sessionId = session.sessionId
                agent = OpenRouterAdaptiveAgent(applicationContext, ExecutionContext.from(session))
                update("Working in $label…")
                val modelId = getSharedPreferences("cyclone_ai", MODE_PRIVATE).getString("openrouter_model", null)
                val config = QuickAgentConfig(model = modelId?.let(OpenRouterModelPresets::byId) ?: OpenRouterModelPresets.DEFAULT)
                finishTask(agent!!.execute(goal, config) { progress -> update(progress) })
            }.onFailure {
                sessionId?.let { id -> runCatching { WorkspaceRuntime.close(id, WorkspaceState.FAILED) } }
                update("Background work is unavailable on this phone. ${it.message?.take(180).orEmpty()}")
                main.postDelayed({ stopSelf() }, 10_000)
            }
        }
        return START_NOT_STICKY
    }

    private fun finishTask(result: com.cyclone.mobile.ai.QuickAgentResult) {
        when {
            paused -> update("Paused — take your time")
            result.classification == "HUMAN_OR_GATE" -> {
                handoff = true
                sessionId?.let { WorkspaceRuntime.pause(it, WorkspaceState.BACKGROUND_NEEDS_HANDOFF) }
                update("Your review is needed")
            }
            result.ok -> {
                finished = true
                update("Task completed and checked")
                sessionId?.let { WorkspaceRuntime.close(it, WorkspaceState.COMPLETED) }
                sessionId = null
                main.postDelayed({ stopSelf() }, 3_000)
            }
            else -> {
                update("Task stopped. Open the app to continue.")
                sessionId?.let { WorkspaceRuntime.pause(it, WorkspaceState.BACKGROUND_NEEDS_HANDOFF) }
                handoff = true
            }
        }
    }

    private fun update(text: String) { main.post {
        message = text; render()
        getSystemService(NotificationManager::class.java).notify(902, notification())
    } }
    private fun action(name: String) = PendingIntent.getService(this, name.hashCode(),
        Intent(this, WorkspaceTaskService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun notification(): Notification {
        val title = when {
            finished -> "Task completed"
            handoff -> "Ready for your review"
            paused -> "Task paused"
            else -> "Working in $label"
        }
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cyclone_status).setColor(Color.rgb(141, 227, 210))
            .setContentTitle(title).setContentText(message).setSubText("Cyclone")
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setOnlyAlertOnce(true).setShowWhen(false).setOngoing(!finished)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_cyclone_status)
                .setContentTitle("Cyclone task").setContentText("Unlock to view task progress").build())
            .setContentIntent(action("view"))
        if (!finished && !paused && !handoff) builder.setProgress(0, 0, true)
        if (handoff) builder.addAction(Notification.Action.Builder(null, "Review in app", action("handoff")).build())
        else if (!finished) builder.addAction(Notification.Action.Builder(null,
            if (paused) "Resume" else "Pause", action(if (paused) "resume" else "pause")).build())
        builder.addAction(Notification.Action.Builder(null, "View progress", action("view")).build())
        if (!finished) builder.addAction(Notification.Action.Builder(null, "Stop task", action("cancel")).build())
        return builder.build()
    }

    private fun render() {
        val accessibility = CycloneAccessibilityService.instance ?: return
        val manager = accessibility.getSystemService(WindowManager::class.java)
        main.removeCallbacks(previewTick)
        sheet?.let { runCatching { windowManager?.removeView(it) } }
        previewView = null; previewLabel = null
        previewBitmap?.recycle(); previewBitmap = null
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val mint = Color.rgb(141, 227, 210)
        fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radius).toFloat()
            setStroke(dp(1).coerceAtLeast(1), Color.rgb(57, 71, 78))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(14))
            background = rounded(Color.rgb(20, 28, 33), 28); elevation = dp(12).toFloat()
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "CYCLONE"; textSize = 11f; letterSpacing = .14f; setTextColor(mint)
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(Button(this).apply {
            text = if (expanded) "Hide" else "View"; textSize = 12f; isAllCaps = false
            setTextColor(mint); background = rounded(Color.rgb(29, 41, 47), 24)
            contentDescription = if (expanded) "Hide progress. Task keeps running." else "View task progress"
            setOnClickListener { expanded = !expanded; render() }
        }, LinearLayout.LayoutParams(dp(72), dp(48)))
        card.addView(header)
        card.addView(TextView(this).apply {
            text = when { finished -> "Task completed"; handoff -> "Over to you"; paused -> "Paused"; else -> "Working in $label" }
            textSize = 20f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(6))
        })
        card.addView(TextView(this).apply {
            text = message; textSize = 14f; setTextColor(Color.rgb(188, 203, 211))
            maxLines = if (expanded) 4 else 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, dp(12))
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        if (!paused && !handoff && !finished) card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(mint)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
        if (expanded && !finished) {
            previewLabel = TextView(this).apply {
                text = "Connecting preview…"; textSize = 11f; setTextColor(mint); setPadding(0, dp(12), 0, dp(8))
            }.also(card::addView)
            previewView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "View-only preview of the task workspace"
                background = rounded(Color.rgb(10, 16, 20), 16)
                clipToOutline = true
            }.also { card.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                minOf(dp(210), resources.displayMetrics.heightPixels / 4))) }
        }
        val actions = LinearLayout(this).apply { setPadding(0, dp(14), 0, 0) }
        fun control(title: String, command: String, primary: Boolean) {
            actions.addView(Button(this).apply {
                text = title; textSize = 13f; isAllCaps = false; minHeight = dp(48)
                setTextColor(if (primary) Color.rgb(13, 49, 41) else mint)
                background = rounded(if (primary) mint else Color.rgb(26, 38, 43), 24)
                setOnClickListener { startService(Intent(this@WorkspaceTaskService, WorkspaceTaskService::class.java).setAction(command)) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        }
        if (!finished) {
            control("Stop task", "cancel", false)
            if (handoff) control("Review in app", "handoff", true)
            else control(if (paused) "Resume" else "Pause", if (paused) "resume" else "pause", true)
            card.addView(actions)
        }
        val width = minOf(dp(360), resources.displayMetrics.widthPixels - dp(24))
        val params = WindowManager.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(44) }
        runCatching {
            manager.addView(card, params); windowManager = manager; sheet = card
            if (expanded) main.post(previewTick)
        }
    }

    override fun onDestroy() {
        agent?.cancelActiveTask()
        val id = sessionId
        scope.cancel()
        // Revocation happens even if the model/network call outlives the UI coroutine.
        if (id != null) Thread { runCatching { WorkspaceRuntime.close(id) } }.start()
        main.removeCallbacksAndMessages(null)
        previewView?.setImageDrawable(null)
        previewBitmap?.recycle(); previewBitmap = null
        sheet?.let { runCatching { windowManager?.removeView(it) } }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { private const val CHANNEL = "cyclone-workspace-task" }
}
