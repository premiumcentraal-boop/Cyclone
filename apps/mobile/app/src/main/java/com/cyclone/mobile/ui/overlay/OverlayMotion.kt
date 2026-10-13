package com.cyclone.mobile.ui.overlay

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntSize

/**
 * The one way Cyclone's overlay moves. Opening, folding, the Ask bar becoming the island and back, a card's section
 * unfolding: all ride the same critically damped spring, so every part of the stack starts, travels and lands together
 * (no overshoot, no part arriving before another, no slide from below). Fades use the same spring, so nothing pops.
 * A spring also picks up mid-way from where a motion is when the owner reverses it, instead of jumping.
 */
object OverlayMotion {
    /** Critically damped: the fastest settle with no bounce. */
    const val DAMPING = Spring.DampingRatioNoBouncy
    /** About 320 ms to settle: quick, but slow enough to read as one gesture. */
    const val STIFFNESS = 420f

    val float: FiniteAnimationSpec<Float> = spring(DAMPING, STIFFNESS, visibilityThreshold = 0.001f)
    val size: FiniteAnimationSpec<IntSize> = spring(DAMPING, STIFFNESS, visibilityThreshold = IntSize.VisibilityThreshold)
    private val fade: FiniteAnimationSpec<Float> = spring(DAMPING, STIFFNESS)

    /**
     * One surface handing over to another in the same place (Ask bar and island, composer and gate): the old one fades
     * out quickly before the new one fades in, so their words never overlap, while the size rides the one spring.
     */
    fun swap(): ContentTransform = ContentTransform(
        fadeIn(tween(SWAP_IN_MS, delayMillis = SWAP_OUT_MS / 2)), fadeOut(tween(SWAP_OUT_MS)),
        sizeTransform = SizeTransform(clip = false) { _, _ -> size },
    )

    private const val SWAP_OUT_MS = 90
    private const val SWAP_IN_MS = 200

    /** A part of a card unfolding below what is already there. */
    fun unfold(): EnterTransition = fadeIn(fade) + expandVertically(size, expandFrom = Alignment.Top)

    /** And folding back up. */
    fun fold(): ExitTransition = fadeOut(fade) + shrinkVertically(size, shrinkTowards = Alignment.Top)
}
