package com.cyclone.mobile.runtime.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
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
                finishTask(agent!!.execute(goal, config))
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
    private fun notification(): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Cyclone · $label")
        .setContentText(message).setOngoing(true).setContentIntent(action("view"))
        .addAction(Notification.Action.Builder(null, "View", action("view")).build())
        .addAction(Notification.Action.Builder(null, "Cancel", action("cancel")).build()).build()

    private fun render() {
        val accessibility = CycloneAccessibilityService.instance ?: return
        val manager = accessibility.getSystemService(WindowManager::class.java)
        sheet?.let { runCatching { windowManager?.removeView(it) } }
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply { setColor(Color.rgb(28, 30, 40)); cornerRadius = dp(26).toFloat() }
            elevation = dp(10).toFloat()
        }
        card.addView(TextView(this).apply {
            text = "Cyclone · $label"; textSize = 13f; setTextColor(Color.rgb(168, 177, 210))
            setTypeface(null, Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = message; textSize = 16f; setTextColor(Color.WHITE); minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { expanded = !expanded; render() }
            contentDescription = "$message. Tap to ${if (expanded) "collapse" else "expand"} task controls"
        })
        if (expanded) {
            fun control(title: String, command: String) {
                card.addView(Button(this).apply {
                    text = title; minHeight = dp(48)
                    setOnClickListener { startService(Intent(this@WorkspaceTaskService, WorkspaceTaskService::class.java).setAction(command)) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            if (handoff) control("Review in app", "handoff")
            else control(if (paused) "Resume" else "Pause", if (paused) "resume" else "pause")
            control("Cancel task", "cancel")
        }
        val params = WindowManager.LayoutParams(dp(if (expanded) 320 else 270), ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(48) }
        runCatching { manager.addView(card, params); windowManager = manager; sheet = card }
    }

    override fun onDestroy() {
        agent?.cancelActiveTask()
        val id = sessionId
        scope.cancel()
        // Revocation happens even if the model/network call outlives the UI coroutine.
        if (id != null) Thread { runCatching { WorkspaceRuntime.close(id) } }.start()
        main.removeCallbacksAndMessages(null)
        sheet?.let { runCatching { windowManager?.removeView(it) } }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { private const val CHANNEL = "cyclone-workspace-task" }
}
