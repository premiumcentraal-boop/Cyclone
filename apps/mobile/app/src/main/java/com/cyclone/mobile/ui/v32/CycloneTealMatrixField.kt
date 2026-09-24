package com.cyclone.mobile.ui.v32

import android.graphics.RuntimeShader
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity

/**
 * The living Teal Matrix canvas: one AGSL pass behind every in-app screen.
 *
 * Deep teal base, two slow aurora ribbons (domain-warped noise, 40-60 s drift), breathing corner
 * blooms, and a fixed dot matrix whose dots only brighten where a ribbon passes. Dots never move or
 * swap, so it reads as alive without flicker. Measured offline at 30 fps: max per-frame change
 * 2/255 per channel (below visible), yet ~45 % of the screen evolves over 10 s. A 1/255 dither keeps
 * 8-bit panels free of gradient banding.
 *
 * It is the source layer for real glass (Kyant drawBackdrop), so the Ask bar and tab bar refract a
 * moving scene. Motion is capped at 30 fps and freezes when the system "Remove animations" setting
 * is on (animator scale 0) or the window stops drawing.
 */
internal object TealMatrixField {
    const val FPS_CAP = 30f
    /** Start mid-cycle so the first frame already has a ribbon in view. */
    const val START_S = 30f

    val SOURCE = """
uniform float2 res;
uniform float t;
uniform float density;

float h(float2 p) {
    p = fract(p * float2(233.34, 851.73));
    p += dot(p, p + 23.45);
    return fract(p.x * p.y);
}
float vn(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(h(i), h(i + float2(1.0, 0.0)), f.x), mix(h(i + float2(0.0, 1.0)), h(i + float2(1.0, 1.0)), f.x), f.y);
}
// Two slow aurora ribbons: domain-warped noise shaped into curtains that drift diagonally.
float ribbon(float2 uv, float s, float phase) {
    float2 w = float2(vn(uv * 1.1 + float2(s * 0.020, -s * 0.013) + phase), vn(uv * 1.1 + float2(-s * 0.017, s * 0.021) + phase + 5.2));
    float n = vn(uv * float2(1.4, 2.4) + w * 1.6 + float2(0.0, s * 0.03));
    float band = 0.5 + 0.5 * sin((uv.x * 1.3 - uv.y * 2.2) * 2.4 + n * 3.2 + s * 0.09 + phase);
    return smoothstep(0.45, 0.95, band) * smoothstep(0.30, 0.75, n);
}
half4 main(float2 xy) {
    float2 uv = xy / res.y;
    float y = xy.y / res.y;
    // Deep base: teal-black with a lifted middle, never flat.
    float3 top = float3(0.016, 0.082, 0.098);
    float3 mid = float3(0.027, 0.150, 0.172);
    float3 low = float3(0.018, 0.098, 0.118);
    float3 col = mix(mix(top, mid, smoothstep(0.0, 0.45, y)), low, smoothstep(0.45, 1.0, y));
    float a = ribbon(uv, t, 0.0);
    float b = ribbon(uv * 0.8 + 3.7, t * 0.7, 2.1);
    float glow = a * 0.9 + b * 0.55;
    col += float3(0.10, 0.36, 0.34) * glow * 0.32;
    // Corner blooms that breathe over ~40 s.
    float breathe = 0.5 + 0.5 * sin(t * 0.157);
    col += float3(0.12, 0.42, 0.40) * 0.18 * exp(-3.0 * dot(uv - float2(res.x / res.y * 0.85, 0.05), uv - float2(res.x / res.y * 0.85, 0.05))) * (0.7 + 0.3 * breathe);
    // Dot matrix: fixed grid, dots only light up where a ribbon passes (never move, never flicker).
    float cellPx = 11.0 * density;
    float2 cell = floor(xy / cellPx);
    float2 f = xy - (cell + 0.5) * cellPx;
    float lit = clamp(glow * 1.2, 0.0, 1.0) * (0.55 + 0.45 * vn(cell * 0.35 + t * 0.05));
    float r = (0.35 + 1.05 * lit) * density;
    float dotMask = 1.0 - smoothstep(r - 0.6 * density, r + 0.6 * density, length(f));
    col += float3(0.51, 0.86, 0.84) * dotMask * (0.05 + 0.34 * lit);
    // Soft vignette + 1-bit dither: no banding on 8-bit panels.
    float2 v = xy / res - 0.5;
    col *= 1.0 - 0.28 * dot(v, v);
    col += (h(xy + t) - 0.5) / 255.0;
    return half4(half3(col), 1.0);
}
""".trimIndent()

    /** Advances the field clock only when a full 1/[FPS_CAP] interval has elapsed; dt is clamped so a resume never jumps. */
    fun advance(clock: Float, lastFrameNanos: Long, nowNanos: Long): Pair<Float, Long> {
        if (lastFrameNanos == 0L) return clock to nowNanos
        val dt = (nowNanos - lastFrameNanos) / 1_000_000_000f
        // 10 % tolerance: two 60 Hz vsyncs land a nanosecond short of 1/30 s and must still count.
        if (dt < 0.9f / FPS_CAP) return clock to lastFrameNanos
        return clock + dt.coerceAtMost(0.1f) to nowNanos
    }

    fun motionReduced(animatorScale: Float): Boolean = animatorScale == 0f
}

@Composable
internal fun TealMatrixBackdrop(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val shader = remember { runCatching { RuntimeShader(TealMatrixField.SOURCE) }.getOrNull() }
    if (shader == null) {
        TealMatrixStaticBackdrop(modifier)
        return
    }
    val reduced = remember {
        TealMatrixField.motionReduced(
            runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f),
        )
    }
    var clock by remember { mutableFloatStateOf(TealMatrixField.START_S) }
    if (!reduced) {
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    val (next, stamp) = TealMatrixField.advance(clock, last, now)
                    last = stamp
                    if (next != clock) clock = next
                }
            }
        }
    }
    val brush = remember(shader) { ShaderBrush(shader) }
    Box(
        modifier.drawBehind {
            shader.setFloatUniform("res", size.width, size.height)
            shader.setFloatUniform("t", clock)
            shader.setFloatUniform("density", density)
            drawRect(brush)
        },
    )
}
