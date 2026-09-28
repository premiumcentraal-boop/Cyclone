package com.cyclone.mobile.ui.v32.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.capsule.ContinuousRoundedRectangle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** R3 smoked glass tokens (docs/design/redesign/rounds/R3-ai-screen.md). */
object AskGlass {
    /** How much black the glass adds over the blurred rain: clearly darker than the scene, still its colour. */
    const val SMOKE = 0.52f
    /** Selected rows and the user's own bubble: a lighter smoke. */
    const val LIGHT_SMOKE = 0.34f
    /** Sheets over a dimmed page sit a little lighter so they lift off it. */
    const val SHEET_SMOKE = 0.44f
    const val BLUR_DP = 20f
    const val LENS_HEIGHT_DP = 12f
    const val LENS_AMOUNT_DP = 20f
    /** One shine band crosses each surface every [SHINE_MS]. */
    const val SHINE_MS = 6_000
    val Ink = Color(0xF5FFFFFF)
    val Muted = Color(0xA8FFFFFF)
    val Faint = Color(0x70FFFFFF)
    val Hairline = Color(0x2EFFFFFF)
    /** The words pill inside the Ask bar and the search field: a shade darker than the glass. */
    val Veil = Color(0x47000000)
    /** Without a backdrop (no layer yet) the glass is painted in this graphite. */
    val Painted = Color(0xE0121418)
    val Done = Color(0xFF34C759)
    val Waiting = Color(0xFFFFB340)
    val Problem = Color(0xFFFF6B5E)
}

/** The rain the page records for its glass; null outside the AI screen, where glass is painted. */
val LocalAskBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** The shared shine phase (0..1, one sweep per [AskGlass.SHINE_MS]); read only while drawing. */
val LocalAskShine = staticCompositionLocalOf<State<Float>?> { null }

/**
 * Smoked glass (R3): a live blur of the rain beneath, lensed at the rim like thick glass, then clearly darkened so text
 * reads on it. It keeps the rain's colours. A thin white highlight runs along the rim; [shine] draws one soft diagonal
 * band that crosses the surface every six seconds, offset by [shineOffset] so neighbours do not flash together.
 */
fun Modifier.smokedGlass(
    backdrop: Backdrop?,
    radius: Dp,
    smoke: Float = AskGlass.SMOKE,
    shine: State<Float>? = null,
    shineOffset: Float = 0f,
    shape: CornerBasedShape = ContinuousRoundedRectangle(radius),
): Modifier {
    if (backdrop == null) {
        return this
            .background(AskGlass.Painted, shape)
            .border(0.8.dp, AskGlass.Hairline, shape)
            .drawWithCache { onDrawBehind { drawShine(shine, shineOffset) } }
    }
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(AskGlass.BLUR_DP.dp.toPx())
            lens(AskGlass.LENS_HEIGHT_DP.dp.toPx(), AskGlass.LENS_AMOUNT_DP.dp.toPx())
        },
        highlight = { Highlight(width = 0.9.dp, alpha = 0.85f) },
        shadow = { Shadow(radius = 18.dp, offset = DpOffset(0.dp, 6.dp), color = Color.Black.copy(alpha = 0.32f)) },
        onDrawSurface = {
            drawRect(Color.Black.copy(alpha = smoke))
            // A faint top sheen: glass catches the light from above.
            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.07f), 0.45f to Color.Transparent))
            drawShine(shine, shineOffset)
        },
    )
}

private fun DrawScope.drawShine(shine: State<Float>?, offset: Float) {
    val phase = shine?.value ?: return
    if (size.width <= 0f || size.height <= 0f) return
    val p = (phase + offset) % 1f
    // The band travels from just off the left edge to just off the right, leaning like light through a window.
    val centre = (p * 1.8f - 0.4f) * size.width
    val half = size.width * 0.09f
    val lean = size.height * 0.35f
    drawRect(
        Brush.linearGradient(
            0f to Color.Transparent,
            0.5f to Color.White.copy(alpha = 0.07f),
            1f to Color.Transparent,
            start = Offset(centre - half - lean, 0f),
            end = Offset(centre + half - lean, size.height * 0.2f),
        ),
    )
}

/**
 * The Ask bar's fingerprint dots (kept from the signature capsule, silver for R3): concentric dotted ridges bend into
 * both ends of the bar and fade before the words. Geometry is in dp and cached per size.
 */
fun Modifier.askWhorls(color: Color = Color(0xFFE6EBF2)): Modifier = drawWithCache {
    data class Dot(val center: Offset, val radius: Float, val alpha: Float)
    val unit = 1.dp.toPx()
    val w = size.width
    val h = size.height
    val reach = min(w * .28f, 100f * unit)
    val dots = ArrayList<Dot>()
    if (w > 0f && h > 0f) {
        for (side in 0..1) {
            val originX = if (side == 0) -15f * unit else w + 12f * unit
            val originY = h * if (side == 0) .95f else .72f
            for (ridge in 1..25) {
                val r = ridge * 4.1f * unit
                val count = (2 * PI * r / (3.8f * unit)).toInt().coerceAtLeast(12)
                for (i in 0 until count) {
                    val angle = i * 2.0 * PI / count + ridge * .037
                    val x = originX + cos(angle).toFloat() * r
                    val y = originY + sin(angle).toFloat() * r * .83f
                    val edge = if (side == 0) x else w - x
                    if (x < 0 || x > w || y < 0 || y > h || edge !in 0f..reach) continue
                    val fade = (1f - edge / reach).coerceIn(0f, 1f)
                    val lower = .24f + .76f * y / h
                    dots += Dot(Offset(x, y), (.35f + .40f * fade) * unit, (fade * lower * .62f).coerceAtMost(.8f))
                }
            }
        }
    }
    onDrawWithContent {
        dots.forEach { drawCircle(color.copy(alpha = it.alpha), it.radius, it.center) }
        drawContent()
    }
}
