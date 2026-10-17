package com.cyclone.mobile.ui.overlay.cy

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ui.v32.CycloneTaskVisualState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.sin

/**
 * Cy, Cyclone's character (first concept, alpha.139): a frosted orb with two dark eyes, and two small orb hands only
 * when they mean something (busy, waving, celebrating). It lives where the overlay already draws Cyclone (the idle
 * bubble, the island ring, the working card, the "needs you" header); it never adds a window or a panel of its own.
 *
 * Motion follows the glass rules (docs/design/CYCLONE_TILT_GLASS.md, "still unless it means something"):
 * - only real activity loops: listening, thinking, working;
 * - everything else is still between short gestures (a blink every few seconds, a wave, a hop), so an idle or waiting
 *   Cy asks for no frames in between;
 * - Lite ([still]) draws Cy still.
 */
enum class CyMood {
    IDLE, LISTEN, THINK, WORK, ASK, STUCK, DONE, PAUSE;

    /** Hands only when they say something: busy hands, a wave, hands up. */
    val hands: Boolean get() = this == WORK || this == ASK || this == DONE

    /** Moods that loop while they last (real activity); the rest move only in short gestures. */
    val loops: Boolean get() = this == LISTEN || this == THINK || this == WORK

    /** Warm (the glass's "needs you" colour) instead of teal. */
    val warm: Boolean get() = this == ASK || this == STUCK
}

object CyMoods {
    /** The task's state, as Cy shows it. */
    fun of(state: CycloneTaskVisualState, paused: Boolean = false): CyMood = when {
        paused -> CyMood.PAUSE
        state == CycloneTaskVisualState.WORKING -> CyMood.WORK
        state == CycloneTaskVisualState.ACTION_NEEDED -> CyMood.ASK
        state == CycloneTaskVisualState.DONE -> CyMood.DONE
        else -> CyMood.STUCK
    }

    /** Width a Cy of body [size] takes: its hands reach out beside it. */
    fun width(size: Dp, hands: Boolean): Dp = if (hands) size * HANDS_SPAN else size

    const val HANDS_SPAN = 1.95f
}

/** The owner's switch (Settings → Appearance → Working indicator → Cy). On by default; off brings back the mark. */
object CyLook {
    private const val PREFS = "cyclone_ui"
    private const val KEY = "cy_character"
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled
    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        _enabled.value = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
        loaded = true
    }

    fun set(context: Context, on: Boolean) {
        _enabled.value = on
        loaded = true
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}

private val Ink = Color(0xFF0F1315)
private val Teal = Color(0xFF83DBD7)
private val Warm = Color(0xFFE9C78B)
private val CoolBody = listOf(Color(0xFFFFFFFF), Color(0xFFF4F8F8), Color(0xFFDAE6E6), Color(0xFFB9CDCD))
private val WarmBody = listOf(Color(0xFFFFFFFF), Color(0xFFFBF8F2), Color(0xFFE9E1D2), Color(0xFFCDBFA6))
private val CoolHand = listOf(Color(0xFFFFFFFF), Color(0xFFE7EFEF), Color(0xFFC2D3D3))
private val WarmHand = listOf(Color(0xFFFFFFFF), Color(0xFFF3ECE0), Color(0xFFD6C8AE))

/** The master clock's lap (ms): every looping period divides it, so a lap's wrap is seamless. */
private const val LAP_MS = 50_400

/**
 * Cy at body diameter [size]. The layout box is [size] tall and [CyMoods.width] wide; a listening ring, thinking dots
 * or a done spark draw a little outside it. [glow] adds a soft halo (for Cy floating over an app, not on glass).
 */
@Composable
fun Cy(
    mood: CyMood,
    size: Dp,
    modifier: Modifier = Modifier,
    hands: Boolean = mood.hands,
    glow: Boolean = false,
    still: Boolean = com.cyclone.mobile.ui.overlay.OverlayLite.active,
) {
    val clock: State<Float>? = if (mood.loops && !still) rememberInfiniteTransition(label = "Cy")
        .animateFloat(0f, LAP_MS.toFloat(), infiniteRepeatable(tween(LAP_MS, easing = LinearEasing), RepeatMode.Restart), label = "Cy clock")
    else null
    val blink = remember { Animatable(0f) }
    val gesture = remember { Animatable(0f) }
    LaunchedEffect(mood, still) {
        gesture.snapTo(0f)
        if (still) return@LaunchedEffect
        when (mood) {
            // One wave or hop on arrival, then again now and then: still in between.
            CyMood.ASK, CyMood.DONE -> while (true) {
                repeat(if (mood == CyMood.ASK) 2 else 1) {
                    gesture.snapTo(0f)
                    gesture.animateTo(1f, tween(if (mood == CyMood.ASK) 1_300 else 1_100, easing = LinearEasing))
                }
                gesture.snapTo(0f)
                delay(if (mood == CyMood.ASK) 6_000 else 7_000)
            }
            CyMood.STUCK -> gesture.animateTo(1f, tween(1_400, easing = LinearEasing))
            else -> Unit
        }
    }
    LaunchedEffect(mood, still) {
        blink.snapTo(0f)
        if (still || mood == CyMood.STUCK || mood == CyMood.DONE || mood == CyMood.PAUSE) return@LaunchedEffect
        while (true) {
            delay(3_200L + (System.nanoTime() % 2_600L))
            blink.animateTo(1f, tween(80))
            blink.animateTo(0f, tween(130, easing = FastOutSlowInEasing))
        }
    }
    val box = CyMoods.width(size, hands)
    Canvas(modifier.size(box, size)) {
        drawCy(mood, hands, glow, clock?.value ?: 0f, blink.value, gesture.value)
    }
}

/** Cycles of [periodMs] at time [t] (ms), as 0..1. */
private fun phase(t: Float, periodMs: Int): Float = (t % periodMs) / periodMs

private fun wave(p: Float): Float = sin(2f * PI.toFloat() * p)

private fun DrawScope.drawCy(mood: CyMood, hands: Boolean, glow: Boolean, t: Float, blink: Float, gesture: Float) {
    val s = size.height
    val r = s / 2f
    val loops = mood.loops
    // Body motion: a gentle float while active, a hop for done, a short wobble when stuck.
    val hop = if (mood == CyMood.DONE) -0.16f * s * hopCurve(gesture) else 0f
    val float = if (loops) -0.04f * s * (0.5f + 0.5f * wave(phase(t, 4_200))) else 0f
    val c = Offset(size.width / 2f, r + hop + float)
    val wobble = if (mood == CyMood.STUCK) 5f * sin(gesture * 3f * PI.toFloat()) * (1f - gesture) else 0f
    val accent = if (mood.warm) Warm else Teal

    if (glow) drawCircle(
        Brush.radialGradient(listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.07f), Color.Transparent), c, r * 2.1f),
        r * 2.1f, c,
    )
    // A soft contact shadow so the orb floats over any app.
    drawCircle(
        Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.20f), Color.Transparent), c + Offset(0f, s * 0.07f), r * 1.18f),
        r * 1.18f, c + Offset(0f, s * 0.07f),
    )
    if (mood == CyMood.LISTEN) {
        val p = phase(t, 1_800)
        drawCircle(Teal.copy(alpha = 0.75f * (1f - p)), r * (1.18f + 0.3f * p), c, style = Stroke(0.02f * s + 0.6f))
    }
    if (mood == CyMood.DONE && gesture > 0f) {
        val p = gesture
        drawCircle(Teal.copy(alpha = 0.85f * (1f - p) * (if (p < 0.15f) p / 0.15f else 1f)), r * (1.25f + 0.35f * p), c,
            style = Stroke(1.4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(s * 0.05f, s * 0.06f))))
    }
    if (hands) drawHands(mood, c, s, t, gesture)
    rotate(wobble, c) {
        val body = if (mood.warm) WarmBody else CoolBody
        drawCircle(
            Brush.radialGradient(0f to body[0], 0.36f to body[1], 0.70f to body[2], 1f to body[3],
                center = c + Offset(-0.32f * r, -0.44f * r), radius = r * 1.45f),
            r, c,
        )
        // The glass's shine on Cy too: white towards the light (upper left), the state colour opposite.
        rotate(-123f, c) {
            drawCircle(
                Brush.sweepGradient(0f to Color.White.copy(alpha = 0.9f), 0.16f to Color.Transparent, 0.38f to Color.Transparent,
                    0.5f to accent.copy(alpha = 0.75f), 0.62f to Color.Transparent, 0.84f to Color.Transparent, 1f to Color.White.copy(alpha = 0.9f), center = c),
                r - 0.5f, c, style = Stroke(1f),
            )
        }
        drawFace(mood, c, s, t, blink)
    }
    if (mood == CyMood.THINK) {
        for (i in 0..2) {
            val p = phase(t + i * 180f, 1_200)
            val lift = 0.5f + 0.5f * wave(p)
            drawCircle(Teal.copy(alpha = 0.35f + 0.65f * lift), s * 0.035f,
                Offset(c.x + r * 0.75f + i * s * 0.11f, c.y - r * 1.05f - lift * s * 0.05f))
        }
    }
}

/** Up fast, down softly; 0 at both ends. */
private fun hopCurve(p: Float): Float = when {
    p <= 0f || p >= 1f -> 0f
    p < 0.25f -> p / 0.25f
    p < 0.6f -> 1f - (p - 0.25f) / 0.35f
    else -> 0f
}

private fun DrawScope.drawFace(mood: CyMood, c: Offset, s: Float, t: Float, blink: Float) {
    var look = Offset.Zero
    when (mood) {
        CyMood.THINK -> look = Offset(0.07f * s, -0.08f * s)
        CyMood.WORK -> {
            // Glances where the work is: right, across, back.
            val p = phase(t, 3_600)
            look = when {
                p < 0.4f -> Offset(0.07f * s, 0.04f * s)
                p < 0.55f -> Offset((0.07f - 0.13f * (p - 0.4f) / 0.15f) * s, 0.045f * s)
                p < 0.7f -> Offset(-0.06f * s, (0.05f - 0.08f * (p - 0.55f) / 0.15f) * s)
                else -> Offset((-0.06f + 0.13f * (p - 0.7f) / 0.3f) * s, (-0.03f + 0.07f * (p - 0.7f) / 0.3f) * s)
            }
        }
        CyMood.STUCK -> look = Offset(0f, 0.03f * s)
        else -> Unit
    }
    val centre = c + look + Offset(0f, -0.025f * s)
    val (w, h) = when (mood) {
        CyMood.LISTEN -> 0.11f * s to 0.20f * s
        CyMood.ASK -> 0.12f * s to 0.18f * s
        else -> 0.11f * s to 0.15f * s
    }
    val gap = 0.10f * s
    for (side in listOf(-1f, 1f)) {
        val ex = centre.x + side * (gap / 2f + w / 2f)
        when (mood) {
            CyMood.DONE -> drawArc(Ink, 180f, 180f, false, Offset(ex - 0.065f * s, centre.y - 0.02f * s), Size(0.13f * s, 0.13f * s),
                style = Stroke(0.035f * s, cap = StrokeCap.Round))
            CyMood.STUCK -> rotate(side * -14f, Offset(ex, centre.y)) {
                drawRoundRect(Ink, Offset(ex - w / 2f, centre.y - 0.0175f * s), Size(w, 0.035f * s), CornerRadius(0.0175f * s))
            }
            CyMood.PAUSE -> drawRoundRect(Ink, Offset(ex - w / 2f, centre.y + 0.02f * s), Size(w, 0.03f * s), CornerRadius(0.015f * s))
            else -> {
                val eh = h * (1f - 0.88f * blink)
                drawRoundRect(Ink, Offset(ex - w / 2f, centre.y - eh / 2f), Size(w, eh), CornerRadius(0.04f * s))
            }
        }
    }
}

private fun DrawScope.drawHands(mood: CyMood, c: Offset, s: Float, t: Float, gesture: Float) {
    val colors = if (mood.warm) WarmHand else CoolHand
    fun hand(centre: Offset, w: Float, h: Float) {
        withTransform({ scale(1f, h / w, centre) }) {
            drawCircle(Brush.radialGradient(0f to colors[0], 0.58f to colors[1], 1f to colors[2],
                center = centre + Offset(-0.2f * w, -0.24f * w), radius = w * 0.75f), w / 2f, centre)
        }
    }
    val lw = 0.25f * s
    val rw = 0.30f * s
    val rh = 0.23f * s
    var left = Offset(c.x - 0.795f * s, c.y + 0.185f * s)
    var right = Offset(c.x + 0.79f * s, c.y + 0.185f * s)
    when (mood) {
        CyMood.WORK -> {
            left += Offset(0f, -0.045f * s * (0.5f + 0.5f * wave(phase(t, 900))))
            right += Offset(0f, -0.045f * s * (0.5f + 0.5f * wave(phase(t + 450f, 900))))
        }
        CyMood.ASK -> {
            // The right hand up, waving while the gesture plays.
            right = Offset(c.x + 0.79f * s, c.y - 0.345f * s) + Offset(0.025f * s * wave(gesture), -0.02f * s * kotlin.math.abs(wave(gesture)))
        }
        CyMood.DONE -> {
            val up = -0.16f * s * hopCurve(gesture)
            left = Offset(c.x - 0.74f * s, c.y - 0.18f * s + up)
            right = Offset(c.x + 0.74f * s, c.y - 0.18f * s + up)
        }
        else -> Unit
    }
    hand(left, lw, lw)
    hand(right, rw, if (mood == CyMood.DONE) lw else rh)
}
