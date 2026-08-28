package com.cyclone.teamworksniper.ui.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cyclone.teamworksniper.data.ActivityLogStore
import com.cyclone.teamworksniper.data.RuleStore
import com.cyclone.teamworksniper.rules.TargetSelectionRules
import com.cyclone.teamworksniper.runtime.TeamworkLauncher
import com.cyclone.teamworksniper.teamwork.TeamworkScheduleOverlayMapper

class TeamworkOverlayController(
    private val service: AccessibilityService,
    private val rootProvider: () -> AccessibilityNodeInfo?,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val rules = RuleStore(service)
    private val activity = ActivityLogStore(service)
    private val modelBuilder = OverlayModelBuilder()
    private val windowManager = OverlayWindowManager(
        service,
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager,
    )
    private val refresh = Runnable(::refreshNow)
    private val foregroundWatch = Runnable(::watchForeground)

    fun start() = scheduleRefresh(0)

    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() == TeamworkLauncher.PACKAGE || windowManager.hasWindows()) {
            scheduleRefresh(EVENT_DEBOUNCE_MS)
        }
    }

    fun dispose() {
        handler.removeCallbacksAndMessages(null)
        windowManager.clear()
    }

    private fun scheduleRefresh(delayMs: Long) {
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, delayMs)
    }

    private fun refreshNow() {
        val root = rootProvider()
        if (root == null) {
            windowManager.clear()
            return
        }
        val packageName = root.packageName?.toString()
        if (packageName != TeamworkLauncher.PACKAGE) {
            root.recycle()
            windowManager.clear()
            return
        }
        val snapshot = try {
            AccessibilityOverlaySnapshot.capture(root)
        } finally {
            root.recycle()
        }
        val schedule = TeamworkScheduleOverlayMapper.map(snapshot)
        if (schedule == null) {
            windowManager.clear()
        } else {
            val days = modelBuilder.build(schedule, rules.load(), activity.load())
            windowManager.render(days) { choice ->
                rules.save(TargetSelectionRules.toggle(rules.load(), choice.date, choice.code))
                scheduleRefresh(0)
            }
        }
        handler.removeCallbacks(foregroundWatch)
        if (windowManager.hasWindows()) handler.postDelayed(foregroundWatch, FOREGROUND_POLL_MS)
    }

    private fun watchForeground() {
        val root = rootProvider()
        val isTeamwork = try {
            root?.packageName?.toString() == TeamworkLauncher.PACKAGE
        } finally {
            root?.recycle()
        }
        if (!isTeamwork) windowManager.clear() else scheduleRefresh(0)
    }

    companion object {
        private const val EVENT_DEBOUNCE_MS = 70L
        private const val FOREGROUND_POLL_MS = 350L
    }
}
