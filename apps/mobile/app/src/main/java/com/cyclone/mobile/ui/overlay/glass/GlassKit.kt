package com.cyclone.mobile.ui.overlay.glass

import android.content.Context
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

private val Teal = Color(0xFF83DBD7)
private val Ink = Color(0xFFE0F5F3)

/**
 * A thin rim whose brightest point faces the light (and a weaker one opposite), for round controls; with
 * [cornerRadius] it follows a capsule instead of a circle.
 */
fun Modifier.litRim(width: Dp = 1.1.dp, cornerRadius: Dp? = null): Modifier = drawWithCache {
    val stroke = Stroke(width.toPx())
    val inset = width.toPx() / 2f
    val radius = size.minDimension / 2f - inset
    val circleBrush = Brush.sweepGradient(
        0f to Color(0xF2F2FFFD), 0.16f to Color.Transparent, 0.34f to Color.Transparent,
        0.5f to Color(0x99BEF5F0), 0.66f to Color.Transparent, 0.84f to Color.Transparent, 1f to Color(0xF2F2FFFD),
    )
    onDrawWithContent {
        drawContent()
        val degrees = GlassOptics.rimRotationDegrees(GlassLight.x, GlassLight.y)
        if (cornerRadius == null) {
            rotate(degrees) { drawCircle(circleBrush, radius = radius, style = stroke) }
        } else {
            // A capsule cannot be rotated, so the gradient's bright points move to the light instead.
            val a = (((degrees / 360f) % 1f) + 1f) % 1f
            val stops = listOf(a to Color(0xF2F2FFFD), (a + 0.5f) % 1f to Color(0x99BEF5F0))
            val colors = buildList {
                stops.forEach { (at, color) ->
                    add((at - 0.16f + 1f) % 1f to Color.Transparent); add(at to color); add((at + 0.16f) % 1f to Color.Transparent)
                }
            }.sortedBy { it.first }.toTypedArray()
            drawRoundRect(Brush.sweepGradient(*colors), topLeft = Offset(inset, inset),
                size = Size(size.width - 2 * inset, size.height - 2 * inset),
                cornerRadius = CornerRadius(cornerRadius.toPx() - inset), style = stroke)
        }
    }
}

/**
 * Every press lights the control up: the rim flashes fully bright and a soft teal glow blooms and fades (about
 * 0.7 s). [cornerRadius] null means a circle.
 */
@Composable
fun Modifier.pressGlow(interaction: MutableInteractionSource, cornerRadius: Dp? = null): Modifier {
    val flash = remember { Animatable(0f) }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { event ->
            if (event is PressInteraction.Press) launch {
                flash.snapTo(0f)
                flash.animateTo(1f, tween(130))
                flash.animateTo(0f, tween(620))
            }
        }
    }
    return drawWithContent {
        drawContent()
        val f = flash.value
        if (f <= 0f) return@drawWithContent
        val glow = 10.dp.toPx()
        if (cornerRadius == null) {
            val r = size.minDimension / 2f
            drawCircle(Brush.radialGradient(
                listOf(Teal.copy(alpha = 0.55f * f), Teal.copy(alpha = 0f)), center = center, radius = r + glow,
            ), radius = r + glow)
            drawCircle(Color(0xFFF2FFFD).copy(alpha = 0.95f * f), radius = r - 0.6.dp.toPx(), style = Stroke(1.3.dp.toPx()))
        } else {
            val cr = CornerRadius(cornerRadius.toPx())
            drawRoundRect(Teal.copy(alpha = 0.28f * f), Offset(-glow / 2, -glow / 2),
                Size(size.width + glow, size.height + glow), CornerRadius(cr.x + glow / 2))
            drawRoundRect(Color(0xFFF2FFFD).copy(alpha = 0.95f * f), cornerRadius = cr, style = Stroke(1.3.dp.toPx()))
        }
    }
}

/** A soft round control: dim glass, a lit rim and the press glow. */
@Composable
fun GlassRoundButton(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 46.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.size(size)
            .pressGlow(interaction)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.02f))))
            .litRim()
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** The soft pill text sits on: no edge, fully rounded, so the dots show faintly through it. */
fun Modifier.veilPill(): Modifier = background(Color(0x4D04181D), RoundedCornerShape(percent = 50))

/**
 * The voice button (plan 27): like the other round buttons until the owner talks, then a living teal orb whose light
 * rises upwards inside the bar (tight below). The parent clips the rising light to the bar.
 */
@Composable
fun VoiceOrbButton(listening: Boolean, enabled: Boolean, description: String, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    if (!listening) {
        GlassRoundButton(description, onClick, enabled = enabled, content = content)
        return
    }
    val motion = rememberInfiniteTransition(label = "Voice orb")
    val swirl by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(7_000, easing = LinearEasing)), label = "Swirl")
    val rise by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(2_400), RepeatMode.Reverse), label = "Rise")
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.size(46.dp)
            .pressGlow(interaction)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(46.dp)) {
            val c = center
            val r = size.minDimension / 2f
            // Light rising into the bar above the orb.
            val h = r * (2.3f + 0.25f * rise)
            drawOval(Brush.radialGradient(
                listOf(Color(0xE66EF0E4), Color(0x6641D7CB), Color(0x0041D7CB)),
                center = Offset(c.x, c.y - r * 0.2f), radius = h,
            ), topLeft = Offset(c.x - r * 1.25f, c.y - h - r * 0.1f), size = Size(r * 2.5f, h + r * 0.5f))
            drawCircle(Brush.radialGradient(
                listOf(Color(0xFF6FE8DC), Color(0xFF35C2B6), Color(0xFF1F9C93), Color(0xFF17827B)),
                center = Offset(c.x, c.y - r * 0.24f), radius = r * 1.1f,
            ), radius = r)
            rotate(swirl) {
                drawCircle(Brush.radialGradient(listOf(Color(0x73D2FFFA), Color(0x00D2FFFA)),
                    center = Offset(c.x - r * 0.3f, c.y - r * 0.35f), radius = r * 0.7f), radius = r)
                drawCircle(Brush.radialGradient(listOf(Color(0x8C085A56), Color(0x00085A56)),
                    center = Offset(c.x + r * 0.35f, c.y + r * 0.45f), radius = r * 0.8f), radius = r)
            }
        }
        content()
    }
}

private val iconCache = LruCache<String, ImageBitmap>(24)

/** The app's real launcher icon, cached; null when the app is unknown. */
fun appIconBitmap(context: Context, packageName: String?): ImageBitmap? {
    if (packageName.isNullOrBlank()) return null
    iconCache.get(packageName)?.let { return it }
    return runCatching {
        val size = (48 * context.resources.displayMetrics.density).toInt().coerceIn(64, 192)
        context.packageManager.getApplicationIcon(packageName).toBitmap(size, size).asImageBitmap()
    }.getOrNull()?.also { iconCache.put(packageName, it) }
}

/** One app logo: round, with a thin dark ring so it sits cleanly on the glass. [current] adds the teal ring. */
@Composable
fun AppLogo(packageName: String?, size: Dp, current: Boolean = false, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(packageName) { appIconBitmap(context, packageName) } ?: return
    Box(modifier.size(size)
        .drawWithContent {
            drawContent()
            val r = this.size.minDimension / 2f
            drawCircle(Color(0xF2062026), radius = r + 1.dp.toPx(), style = Stroke(2.dp.toPx()))
            if (current) drawCircle(Teal.copy(alpha = 0.55f), radius = r + 2.6.dp.toPx(), style = Stroke(1.4.dp.toPx()))
        }) {
        Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    }
}

/**
 * The apps a task has worked in, the current one in front and larger, earlier ones tucked behind it, slightly smaller
 * and quieter (plan 27, the card header).
 */
@Composable
fun AppLogoStack(packages: List<String>, modifier: Modifier = Modifier) {
    if (packages.isEmpty()) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy((-9).dp)) {
        packages.forEachIndexed { index, pkg ->
            val current = index == packages.lastIndex
            AppLogo(
                pkg, if (current) 34.dp else 28.dp, current = current,
                modifier = Modifier.graphicsLayer { alpha = if (current) 1f else 0.82f }
                    .zIndex(if (current) 1f else 0f),
            )
        }
    }
}
