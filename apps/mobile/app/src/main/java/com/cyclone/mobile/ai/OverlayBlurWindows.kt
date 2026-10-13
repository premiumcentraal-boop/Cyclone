package com.cyclone.mobile.ai

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.cyclone.mobile.ui.overlay.glass.BlurRegion
import com.cyclone.mobile.ui.overlay.glass.GlassBlur
import kotlin.math.roundToInt

/**
 * The blur behind the overlay's glass (Android 12+). Android blurs whole windows, so each blurred panel gets a small,
 * empty, untouchable window of exactly its size and corner radius under the overlay window, with the window's
 * background blur turned on. They are opened once, hidden, before the overlay window, so they always sit below it;
 * afterwards they only move, resize, show and hide. With [GlassBlur] off they stay hidden and cost nothing.
 */
internal class OverlayBlurWindows(private val context: Context, private val slots: Int = SLOTS) {
    private val pool = ArrayList<Dialog>()
    private val shown = BooleanArray(slots)
    private val corners = FloatArray(slots) { -1f }

    /** Opens the hidden pool. Call before the overlay window is added. */
    fun prepare() {
        if (pool.isNotEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        repeat(slots) {
            val dialog = runCatching { open() }.getOrNull()
            if (dialog == null) { GlassBlur.failed(); clear(); return }
            pool += dialog
        }
    }

    /**
     * Puts one blur window behind each region (window pixels of the overlay window at [originX], [originY] on screen)
     * and hides the rest; all hidden when [visible] is false.
     */
    fun sync(regions: List<BlurRegion?>, originX: Int, originY: Int, visible: Boolean) {
        if (pool.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val radius = (GlassBlur.RADIUS_DP * context.resources.displayMetrics.density).roundToInt()
        pool.forEachIndexed { i, dialog ->
            // A panel in motion has no blur (null): hiding is immediate, a frozen blurred frame never lingers.
            val region = regions.getOrNull(i)?.takeIf { visible && it.bounds.width > 1f && it.bounds.height > 1f }
            val window = dialog.window ?: return@forEachIndexed
            if (region == null) {
                if (shown[i]) { dialog.hide(); shown[i] = false }
                return@forEachIndexed
            }
            val attrs = window.attributes
            val x = originX + region.bounds.left.roundToInt()
            val y = originY + region.bounds.top.roundToInt()
            val w = region.bounds.width.roundToInt()
            val h = region.bounds.height.roundToInt()
            if (attrs.x != x || attrs.y != y || attrs.width != w || attrs.height != h) {
                attrs.x = x; attrs.y = y; attrs.width = w; attrs.height = h
                window.attributes = attrs
            }
            // Android reads the blur's corners from the window background when it is set, so a new radius gets a new
            // background rather than a changed one.
            if (corners[i] != region.cornerRadiusPx) {
                window.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = region.cornerRadiusPx })
                corners[i] = region.cornerRadiusPx
            }
            if (!shown[i]) {
                window.setBackgroundBlurRadius(radius)
                runCatching { dialog.show() }.onFailure { GlassBlur.failed() }
                shown[i] = true
            }
        }
    }

    fun clear() {
        pool.forEach { runCatching { it.dismiss() } }
        pool.clear()
        shown.fill(false)
        corners.fill(-1f)
    }

    private fun open(): Dialog {
        val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setCancelable(false)
        dialog.setContentView(View(context))
        val window = requireNotNull(dialog.window)
        window.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        window.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        window.setWindowAnimations(0)
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.START
            width = 1; height = 1; x = 0; y = 0
            fitInsetsTypes = 0
            title = "Cyclone glass blur"
        }
        dialog.show()
        dialog.hide()
        return dialog
    }

    companion object {
        /** The card, the island or Ask bar, and two companion panels (options, plane pill). */
        const val SLOTS = 4
    }
}
