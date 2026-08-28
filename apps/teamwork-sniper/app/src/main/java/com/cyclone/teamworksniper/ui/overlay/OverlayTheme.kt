package com.cyclone.teamworksniper.ui.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

object OverlayTheme {
    const val ORANGE = 0xFFFF6A00.toInt()
    const val CLAIMED = 0xFF14804A.toInt()

    fun choice(context: Context, selected: Boolean, claimed: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dp(12).toFloat()
        when {
            claimed -> setColor(CLAIMED)
            selected -> setColor(ORANGE)
            else -> {
                setColor(Color.TRANSPARENT)
                setStroke(context.dp(2), ORANGE)
            }
        }
    }

}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
