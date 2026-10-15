package com.cyclone.mobile.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View

/**
 * Alpha.134: the owner's touch on Cyclone's card. The card itself is drawn in a window that never takes a touch, so
 * the agent's taps and swipes always land on the app under it. This layer, an empty window of exactly the card's size
 * above it, takes the owner's finger and hands each touch to the card.
 *
 * During the agent's own stroke over the card only this layer steps aside. It draws nothing, so the card never blinks.
 */
@SuppressLint("ViewConstructor")
internal class OverlayTouchLayer(context: Context, private val target: () -> View?) : View(context) {
    private val from = IntArray(2)
    private val to = IntArray(2)

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val card = target()?.takeIf { it.isAttachedToWindow && it.visibility == VISIBLE }
        if (OverlayGesturePassthrough.hidden()) {
            if (OverlayTouchForward.stray(true, event.actionMasked == MotionEvent.ACTION_DOWN)) {
                val n = OverlayGesturePassthrough.stray()
                android.util.Log.w("CycloneOverlay", "a touch reached the touch layer before it stepped aside ($n)")
            }
            // A touch of the owner's that was already on the card ends cleanly instead of pressing a button.
            if (forwarding && card != null) forward(card, event, cancel = true)
            forwarding = false
            return true
        }
        if (card == null) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) forwarding = true
        val handled = forward(card, event, cancel = false)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) forwarding = false
        return handled
    }

    private var forwarding = false

    private fun forward(card: View, event: MotionEvent, cancel: Boolean): Boolean {
        getLocationOnScreen(from)
        card.getLocationOnScreen(to)
        val (dx, dy) = OverlayTouchForward.offset(from, to)
        val copy = MotionEvent.obtain(event)
        return try {
            copy.offsetLocation(dx, dy)
            if (cancel) copy.action = MotionEvent.ACTION_CANCEL
            card.dispatchTouchEvent(copy)
        } finally {
            copy.recycle()
        }
    }
}

/** Pure: where a touch on the layer lands on the card (the card may still be gliding with the keyboard). */
internal object OverlayTouchForward {
    fun offset(layerOnScreen: IntArray, cardOnScreen: IntArray): Pair<Float, Float> =
        (layerOnScreen[0] - cardOnScreen[0]).toFloat() to (layerOnScreen[1] - cardOnScreen[1]).toFloat()

    /** A touch starting on the layer while a stroke on the glass is running is that stroke: drop it and count it. */
    fun stray(strokeOnGlass: Boolean, down: Boolean): Boolean = strokeOnGlass && down

    /**
     * Whether the touch layer is up: only over a drawn card (not the idle ball, whose own window is already an empty
     * hotspot), only while the card is on screen and laid out, and never during the agent's stroke on the card.
     */
    fun shown(cardVisible: Boolean, compact: Boolean, strokeOnGlass: Boolean, laidOut: Boolean): Boolean =
        cardVisible && !compact && !strokeOnGlass && laidOut
}
