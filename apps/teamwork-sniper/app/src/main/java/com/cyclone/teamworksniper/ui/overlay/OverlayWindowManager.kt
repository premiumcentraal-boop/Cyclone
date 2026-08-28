package com.cyclone.teamworksniper.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.time.LocalDate

class OverlayWindowManager(
    private val context: Context,
    private val windows: WindowManager,
    private val renderer: ScheduleOverlayRenderer = ScheduleOverlayRenderer(context),
) {
    private data class Entry(val view: View, val signature: String)
    private val attached = linkedMapOf<LocalDate, Entry>()

    fun render(days: List<OverlayDay>, onToggle: (OverlayShiftChoice) -> Unit) {
        val desired = days.associateBy { it.date }
        (attached.keys - desired.keys).forEach(::remove)
        desired.forEach { (date, day) ->
            val signature = day.toString()
            if (attached[date]?.signature == signature) return@forEach
            remove(date)
            val layout = layoutFor(day) ?: return@forEach
            val view = renderer.create(day, onToggle)
            runCatching { windows.addView(view, layout) }
                .onSuccess { attached[date] = Entry(view, signature) }
        }
    }

    fun clear() = attached.keys.toList().forEach(::remove)

    fun hasWindows(): Boolean = attached.isNotEmpty()

    private fun remove(date: LocalDate) {
        attached.remove(date)?.let { entry -> runCatching { windows.removeViewImmediate(entry.view) } }
    }

    private fun layoutFor(day: OverlayDay): WindowManager.LayoutParams? {
        val anchor = day.anchorBounds
        val area = day.dayBounds
        if (!anchor.isUsable || !area.isUsable) return null
        val height = area.height
        if (height < context.dp(64)) return null
        val y = (anchor.top - context.dp(18)).coerceIn(area.top, (area.bottom - height).coerceAtLeast(area.top))
        return WindowManager.LayoutParams(
            anchor.width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = anchor.left
            this.y = y
            title = "Teamwork Sniper ${day.date}"
        }
    }
}
