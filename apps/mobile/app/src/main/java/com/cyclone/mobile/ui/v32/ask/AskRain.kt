package com.cyclone.mobile.ui.v32.ask

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.cyclone.mobile.ui.overlay.tracefield.TraceFieldShader
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The AI screen's background (R3): the Cyclone rain. Every cell is one of the Trace Field's own glyphs, lit by a slow
 * scene (a dome of rings and spokes that drifts outward and through copper, violet and blue) with rain streaks
 * falling through it. Drawn by one AGSL shader at about 30 fps while the page is on screen. With Android animations
 * off it is one still frame; if the shader cannot run, a dark gradient stands in.
 */
@Composable
fun AskRainField(modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val context = LocalContext.current
    val rain = remember(density) { AskRain.create(density) }
    DisposableEffect(rain) { onDispose { rain?.release() } }
    val still = remember { AskRain.animationsOff(context) }
    val clock = remember { mutableFloatStateOf(AskRain.START_S) }
    if (rain != null && !still) {
        LaunchedEffect(rain) {
            var start = -1L
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    if (start < 0L) start = now
                    if (now - last >= AskRain.FRAME_NS) {
                        last = now
                        clock.floatValue = AskRain.START_S + ((now - start) / 1_000_000_000f) % AskRain.LOOP_S
                    }
                }
            }
        }
    }
    Canvas(modifier) {
        val drawn = rain?.draw(this, clock.floatValue) ?: false
        if (!drawn) drawRect(AskRain.FALLBACK)
    }
}

/**
 * The shade that keeps words readable on the rain: the top and bottom deepen (header, Ask bar), the whole page a
 * little, and with [greeting] a soft pool behind the greeting.
 */
@Composable
fun AskScrim(modifier: Modifier = Modifier, greeting: Boolean = false) {
    Box(
        modifier.drawBehind {
            drawRect(Color.Black.copy(alpha = 0.12f))
            drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.18f to Color.Transparent))
            drawRect(Brush.verticalGradient(0.72f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.38f)))
            if (greeting) {
                drawRect(
                    Brush.radialGradient(
                        listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.14f),
                        radius = size.width * 0.7f,
                    ),
                )
            }
        },
    )
}

internal class AskRain private constructor(private val shader: RuntimeShader, private val atlas: Bitmap) {
    private val brush = ShaderBrush(shader)

    fun draw(scope: DrawScope, time: Float): Boolean = runCatching {
        shader.setFloatUniform("res", scope.size.width, scope.size.height)
        shader.setFloatUniform("time", time)
        scope.drawRect(brush)
    }.isSuccess

    fun release() {
        runCatching { atlas.recycle() }
    }

    companion object {
        /** A start a little into the loop, so the rain is already falling on the first frame. */
        const val START_S = 40f
        const val LOOP_S = 3_600f
        /** About 30 fps: the rain is calm, so half the display rate is plenty and saves battery. */
        const val FRAME_NS = 32_000_000L
        const val CELL_W_DP = 5f
        const val CELL_H_DP = 7.5f
        const val TEXT_DP = 6.8f
        val FALLBACK = Brush.verticalGradient(listOf(Color(0xFF0B0D12), Color(0xFF15101A), Color(0xFF07080B)))

        fun animationsOff(context: Context): Boolean = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)

        fun create(density: Float): AskRain? = runCatching {
            val cellW = (CELL_W_DP * density).roundToInt().coerceAtLeast(4)
            val cellH = (CELL_H_DP * density).roundToInt().coerceAtLeast(6)
            val glyphs = TraceFieldShader.GLYPHS
            val atlas = Bitmap.createBitmap(cellW * glyphs.length, cellH, Bitmap.Config.ARGB_8888)
            val canvas = AndroidCanvas(atlas)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                textSize = TEXT_DP * density
            }
            val metrics = text.fontMetrics
            val baseline = ceil(cellH / 2f - (metrics.ascent + metrics.descent) / 2f)
            glyphs.forEachIndexed { index, glyph -> canvas.drawText(glyph.toString(), index * cellW + cellW / 2f, baseline, text) }
            val shader = RuntimeShader(SOURCE)
            shader.setInputShader("atlas", BitmapShader(atlas, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            shader.setFloatUniform("cell", cellW.toFloat(), cellH.toFloat())
            shader.setFloatUniform("glyphCount", glyphs.length.toFloat())
            shader.setFloatUniform("res", 1f, 1f)
            shader.setFloatUniform("time", START_S)
            AskRain(shader, atlas)
        }.getOrNull()

        /**
         * One cell = one glyph. The scene gives each cell its light and colour; the rain adds a falling head and a
         * trail that brightens and scrambles the digits it passes. A faint colour tile behind each glyph carries the
         * picture, the glyphs carry the detail.
         */
        const val SOURCE = """
uniform shader atlas;
uniform float2 res;
uniform float2 cell;
uniform float glyphCount;
uniform float time;

float hash1(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }

half3 palette(float t) {
    float3 c = 0.55 + 0.42 * cos(6.2831853 * (t + float3(0.0, 0.18, 0.36)));
    return half3(c);
}

half4 main(float2 p) {
    float2 id = floor(p / cell);
    float2 local = p - id * cell;
    float2 cc = (id + 0.5) * cell;
    float scale = max(res.x, res.y);
    float2 d = (cc - float2(res.x * 0.5, res.y * 0.42)) / scale;
    float r = length(d);
    float a = atan(d.y, d.x);

    float spokes = pow(0.5 + 0.5 * cos(a * 18.0 + time * 0.03), 24.0) * smoothstep(0.02, 0.1, r);
    float rings = pow(0.5 + 0.5 * cos(r * 70.0 - time * 0.5), 14.0);
    float frame = max(spokes, rings);
    float glow = exp(-r * 2.6);
    float lum = clamp((0.16 + 0.84 * glow) * (1.0 - 0.85 * frame), 0.0, 1.0);
    half3 tint = palette(time * 0.012 + r * 0.9);

    float rows = res.y / cell.y;
    float gap = rows * (0.9 + 1.3 * hash1(float2(id.x, 7.7)));
    float speed = 7.0 + 16.0 * hash1(float2(id.x, 3.1));
    float head = mod(hash1(float2(id.x, 1.3)) * gap * 2.0 + time * speed, gap);
    float dist = mod(head - id.y, gap);
    float trail = dist < 26.0 ? exp(-dist / 7.0) : 0.0;
    float isHead = dist < 1.0 ? 1.0 : 0.0;

    float rate = 0.25 + 0.7 * hash1(id + 0.37) + trail * 6.0;
    float tick = floor(time * rate + hash1(id + 1.9) * 10.0);
    float gi = floor(hash1(id + tick * 0.618) * glyphCount);
    float glyph = atlas.eval(float2(gi * cell.x + local.x, local.y)).a;

    float inten = clamp(lum * (0.95 + 0.6 * trail) + 0.12 * trail, 0.0, 1.5);
    half3 c = mix(tint, half3(1.0), half(lum * 0.3));
    c = mix(c, half3(1.0), half(0.8 * isHead));
    half3 outc = c * half(glyph * inten * 1.2) + c * half(inten * 0.16);
    return half4(outc, 1.0);
}
"""
    }
}
