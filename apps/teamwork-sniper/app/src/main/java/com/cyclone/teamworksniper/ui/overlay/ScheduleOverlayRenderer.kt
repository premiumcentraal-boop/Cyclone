package com.cyclone.teamworksniper.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.format.DateTimeFormatter

class ScheduleOverlayRenderer(private val context: Context) {
    fun create(day: OverlayDay, onToggle: (OverlayShiftChoice) -> Unit): View {
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, context.dp(3))
            contentDescription = "Teamwork Sniper choices for ${day.date}"
        }
        day.choices.forEach { choice -> rows.addView(choiceRow(choice, onToggle)) }
        return ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.TRANSPARENT)
            addView(rows)
        }
    }

    private fun choiceRow(
        choice: OverlayShiftChoice,
        onToggle: (OverlayShiftChoice) -> Unit,
    ): View {
        var selected = choice.selected
        val glyph = SniperGlyphView(context)
        val time = TextView(context).apply {
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val state = TextView(context).apply {
            textSize = 10.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val code = TextView(context).apply {
            text = choice.code.name
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val center = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(time, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(state, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(62)
            setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
            elevation = context.dp(1).toFloat()
            addView(glyph, LinearLayout.LayoutParams(context.dp(42), LinearLayout.LayoutParams.MATCH_PARENT))
            addView(center, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = context.dp(8)
            })
            addView(code, LinearLayout.LayoutParams(context.dp(48), LinearLayout.LayoutParams.MATCH_PARENT))
            renderChoice(this, glyph, time, state, code, choice, selected)
            isEnabled = !choice.claimed
            setOnClickListener {
                if (choice.claimed) return@setOnClickListener
                selected = !selected
                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                animateSelection(this, selected)
                renderChoice(this, glyph, time, state, code, choice, selected, updateBackground = false)
                onToggle(choice.copy(selected = selected))
            }
        }.also { row ->
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(64),
            ).apply { bottomMargin = context.dp(6) }
        }
    }

    private fun renderChoice(
        row: LinearLayout,
        glyph: SniperGlyphView,
        time: TextView,
        state: TextView,
        code: TextView,
        choice: OverlayShiftChoice,
        selected: Boolean,
        updateBackground: Boolean = true,
    ) {
        time.text = if (choice.start != null && choice.end != null) {
            choice.start.format(TIME) + " – " + choice.end.format(TIME)
        } else {
            ""
        }
        state.text = when {
            choice.claimed -> "CLAIMED ✓"
            choice.openNow && selected -> "OPEN · SNIPING ✓"
            choice.openNow -> "OPEN TO TAKE"
            selected -> "SNIPING ✓"
            else -> "SNIPE"
        }
        val filled = selected || choice.claimed
        val foreground = if (filled) Color.WHITE else OverlayTheme.ORANGE
        time.setTextColor(foreground)
        state.setTextColor(foreground)
        code.setTextColor(foreground)
        glyph.setSelected(filled, choice.claimed)
        if (updateBackground) row.background = OverlayTheme.choice(context, selected, choice.claimed)
        row.contentDescription = buildString {
            append(choice.code.name).append(". ").append(state.text).append(". ")
            if (choice.start != null && choice.end != null) append(time.text).append(". ")
            append(if (choice.claimed) "Claimed" else if (selected) "Double tap to unselect" else "Double tap to snipe")
        }
    }

    private fun animateSelection(view: View, selected: Boolean) {
        val from = if (selected) Color.TRANSPARENT else OverlayTheme.ORANGE
        val to = if (selected) OverlayTheme.ORANGE else Color.TRANSPARENT
        ValueAnimator.ofArgb(from, to).apply {
            duration = 180
            addUpdateListener { animator ->
                view.background = OverlayTheme.choice(context, selected, false).apply {
                    setColor(animator.animatedValue as Int)
                }
            }
            start()
        }
    }

    private class SniperGlyphView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private var color = OverlayTheme.ORANGE

        fun setSelected(selected: Boolean, claimed: Boolean) {
            color = if (selected || claimed) Color.WHITE else OverlayTheme.ORANGE
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = color
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) * 0.23f
            canvas.drawCircle(cx, cy, radius, paint)
            canvas.drawCircle(cx, cy, radius * 0.38f, paint)
            canvas.drawLine(cx - radius * 1.45f, cy, cx - radius * 0.65f, cy, paint)
            canvas.drawLine(cx + radius * 0.65f, cy, cx + radius * 1.45f, cy, paint)
            canvas.drawLine(cx, cy - radius * 1.45f, cx, cy - radius * 0.65f, paint)
            canvas.drawLine(cx, cy + radius * 0.65f, cx, cy + radius * 1.45f, paint)
        }
    }

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    }
}
