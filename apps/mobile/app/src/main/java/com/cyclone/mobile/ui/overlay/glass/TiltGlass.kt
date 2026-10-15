package com.cyclone.mobile.ui.overlay.glass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Where the light comes from, shared by every glass surface. Reads happen in the draw phase, so a new light only
 * redraws the glass; nothing recomposes.
 */
object GlassLight {
    var x by mutableFloatStateOf(GlassOptics.REST_X)
        private set
    var y by mutableFloatStateOf(GlassOptics.REST_Y)
        private set

    /** The glass's clock (seconds), for the dots' slow breathing; stays 0 with animations off. */
    var time by mutableFloatStateOf(0f)
        internal set

    // Motion energy: set when the phone moves, then dies away on its own (read against [time], so any number of
    // clocks can run without decaying it twice).
    private var kick = 0f
    private var kickAt = 0f

    /** How much the phone has just moved, 0..1, fading over about a second. */
    fun energyAt(t: Float): Float = GlassOptics.energyAfter(kick, t - kickAt)

    /** Moves the light [amount] of the way to (tx, ty); [dt] is the seconds since the last move. */
    internal fun ease(tx: Float, ty: Float, amount: Float = 0.22f, dt: Float = 0.06f) {
        val dx = (tx - x) * amount
        val dy = (ty - y) * amount
        x += dx
        y += dy
        // A real tilt excites the dots; tremor and holding still let them settle.
        val now = time
        kick = max(energyAt(now), GlassOptics.motionEnergy(kotlin.math.hypot(dx, dy) / max(dt, 0.008f)))
        kickAt = now
    }

    internal fun rest() {
        x = GlassOptics.REST_X
        y = GlassOptics.REST_Y
        kick = 0f
    }
}

/**
 * While Cyclone's glass is on screen, the light follows how the owner tilts the phone (gravity sensor). The grip they
 * start with is the baseline, and it slowly re-centres, so the light answers movement, not posture. With animations
 * switched off in Android settings the light stays still.
 */
@Composable
fun FollowPhoneLight() {
    val context = LocalContext.current
    // Alpha.134: Lite keeps the light still (no gravity sensor, no glass clock, no redraw while the phone moves).
    if (com.cyclone.mobile.ui.overlay.OverlayLite.active) {
        DisposableEffect(Unit) { GlassLight.rest(); onDispose { } }
        return
    }
    DisposableEffect(Unit) {
        val still = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (still || sensors == null || sensor == null) {
            GlassLight.rest()
            return@DisposableEffect onDispose { }
        }
        var baseX = Float.NaN
        var baseY = Float.NaN
        var smoothX = 0f
        var smoothY = 0f
        var lastNs = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val gx = event.values[0]
                val gy = event.values[1]
                val dt = if (lastNs == 0L) 0.06f else ((event.timestamp - lastNs) / 1e9f).coerceIn(0.005f, 0.25f)
                lastNs = event.timestamp
                if (baseX.isNaN()) { baseX = gx; baseY = gy; smoothX = gx; smoothY = gy }
                // Gravity is smoothed first (a low-pass of about a third of a second), so shaking the phone left and
                // right averages out towards the middle instead of whipping the light about; a real tilt still
                // arrives, just softly.
                val k = GlassOptics.smoothing(dt, GlassOptics.TILT_SMOOTHING_S)
                smoothX += (gx - smoothX) * k
                smoothY += (gy - smoothY) * k
                baseX += (smoothX - baseX) * 0.004f
                baseY += (smoothY - baseY) * 0.004f
                val (tx, ty) = GlassOptics.lightFromGravity(smoothX, smoothY, baseX, baseY)
                GlassLight.ease(tx, ty, GlassOptics.smoothing(dt, GlassOptics.LIGHT_EASE_S), dt)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensors.unregisterListener(listener) }
    }
    GlassClock(context)
}

/**
 * Runs the glass clock while glass is on screen. Every frame while the dots ripple after a movement; otherwise about
 * 20 times a second, which is plenty for the slow breathing and spares the battery. Off with animations off.
 */
@Composable
private fun GlassClock(context: Context) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val still = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
        if (still) { GlassLight.time = 0f; return@LaunchedEffect }
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { nanos -> (nanos / 1_000_000L % CLOCK_WRAP_MS) / 1000f }
            GlassLight.time = now
            // Calm glass asks for a frame only every 50 ms; a frame callback every vsync would keep the display
            // pipeline busy the whole time the overlay shows.
            if (GlassLight.energyAt(now) <= 0.02f) kotlinx.coroutines.delay(CALM_TICK_MS)
        }
    }
}

/** The clock wraps every hour so it keeps float precision; the breathing is periodic, so the wrap is one soft step. */
private const val CLOCK_WRAP_MS = 3_600_000L

/** Between ticks of the calm breathing (no ripple): about 20 a second. */
private const val CALM_TICK_MS = 50L

private val Teal = Color(0xFF83DBD7)

/**
 * The glass's palette. [TEAL] is Tilt Glass as the overlay has it. [SMOKE] is the AI screen's neutral glass (R3): the
 * same optics in graphite and white, with silver dots, so the cards sitting on the rain carry no teal.
 */
enum class GlassPalette(
    internal val body: Triple<Color, Color, Color>,
    internal val sheen: Color,
    internal val dotRgb: Int,
    internal val hairline: Color,
    internal val glow: Color,
    internal val rim: Color,
    /** How much of the app underneath the body hides: 1 is solid, lower lets it show through, darkened. */
    internal val bodyAlpha: Float = 0.95f,
) {
    TEAL(Triple(Color(0xFF10383E), Color(0xFF062228), Color(0xFF08282E)), Teal, 0x83DBD7, Color(0xFFA0E2DE), Color(0xFF83E6DE), Color(0xFFF0FFFD)),
    SMOKE(Triple(Color(0xFF1C1E23), Color(0xFF0F1013), Color(0xFF14161A)), Color(0xFFFFFFFF), 0xD6DCE4, Color(0xFFFFFFFF), Color(0xFFE8ECF2), Color(0xFFFFFFFF)),
    /**
     * The overlay's glass, matched to the in-app Ask bar: no teal, a darkened see-through veil over whatever app is
     * underneath ([bodyAlpha] below 1), light grey dots and a white shine.
     */
    VEIL(Triple(Color(0xFF16181C), Color(0xFF08090B), Color(0xFF0C0D10)), Color(0xFFFFFFFF), 0xD6DCE4, Color(0xFFFFFFFF), Color(0xFFE8ECF2), Color(0xFFFFFFFF),
        bodyAlpha = 0.86f),
}

/** The palette glass under this point uses; surfaces that call [tiltGlass] pass it on. The overlay never sets it. */
val LocalGlassPalette = androidx.compose.runtime.staticCompositionLocalOf { GlassPalette.VEIL }

/**
 * The glass itself (plan 27): dark teal body with a sheen leaning towards the light, a crisp hairline, a sharp shine
 * on the rim sections facing the light (and a weaker one opposite), and optional halftone fingerprint dots along the
 * edge. [thin] is the fine line for small pills; [seeThrough] below 1 lets the app show through (the idle bubble).
 */
fun Modifier.tiltGlass(
    cornerRadius: Dp,
    dots: Boolean = true,
    thin: Boolean = false,
    seeThrough: Float = 1f,
    palette: GlassPalette = GlassPalette.VEIL,
    /** How much lighter the smoke is right now (the app behind is blurred); read while drawing. */
    lift: () -> Float = NoLift,
): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    val r = min(cornerRadius.toPx(), min(w, h) / 2f)
    // Continuous (squircle) corners, like the in-app Ask bar: the curve eases into the straight edges.
    val outline = continuousOutline(0f, w, h, r)
    // The dots stop just inside the lit rim (its line and glow), so none show between the shine and the edge.
    val dotInset = 1.5.dp.toPx()
    val dotOutline = continuousOutline(dotInset, w, h, max(0f, r - dotInset))
    val body = Brush.verticalGradient(
        0f to palette.body.first.copy(alpha = (palette.bodyAlpha - 0.01f) * seeThrough),
        0.55f to palette.body.second.copy(alpha = palette.bodyAlpha * seeThrough),
        1f to palette.body.third.copy(alpha = palette.bodyAlpha * seeThrough),
    )
    // Halftone band: dot centres and their place relative to the rim, computed once per size.
    val step = 4.3.dp.toPx()
    val band = min(30.dp.toPx(), h * 0.46f)
    val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val falls = ArrayList<Float>()
    val rooms = ArrayList<Float>()
    // Alpha.134: Lite draws no halftone dots (thousands of points re-evaluated on every light change).
    if (dots && !com.cyclone.mobile.ui.overlay.OverlayLite.active && w > 0f && h > 0f) {
        // The dots sit in rows that follow the panel's own outline: each row is the continuous outline set in a
        // little further, sampled at even steps along its length. Straight edges get straight rows, and in the
        // corners every row bends with the curve, so the corners fill evenly instead of breaking a grid apart.
        // Alternate rows are shifted half a step, which keeps the honeycomb look of a halftone.
        val rowGap = step * 0.87f
        val minR = 3.dp.toPx()
        val pos = FloatArray(2)
        var row = 0
        var d = dotInset + step * 0.5f
        while (d < dotInset + band) {
            val iw = w - 2 * d
            val ih = h - 2 * d
            if (iw <= step || ih <= step) break
            val ring = continuousOutline(d, w, h, min(max(minR, r - d), min(iw, ih) / 2f))
            val measure = android.graphics.PathMeasure(ring.asAndroidPath(), true)
            val length = measure.length
            val n = (length / step).roundToInt()
            if (n > 0) {
                val gap = length / n
                val shift = if (row and 1 == 1) gap / 2f else 0f
                val fall = (1f - (d - dotInset) / band).coerceIn(0f, 1f)
                for (k in 0 until n) {
                    measure.getPosTan(shift + k * gap, pos, null)
                    xs += pos[0]; ys += pos[1]; falls += fall; rooms += d - dotInset
                }
            }
            d += rowGap
            row++
        }
    }
    val dotCount = xs.size
    // Each dot's place around the panel (radians, in the panel's own proportions, so a corner is 45°).
    val phis = FloatArray(dotCount) { GlassOptics.angle((xs[it] - w / 2f) / (w / 2f), (ys[it] - h / 2f) / (h / 2f)) }
    val fallArr = FloatArray(dotCount) { falls[it] }
    val roomArr = FloatArray(dotCount) { rooms[it] }
    val keys = IntArray(dotCount)
    val counts = IntArray(ALPHA_LEVELS * RADIUS_LEVELS)
    val starts = IntArray(ALPHA_LEVELS * RADIUS_LEVELS)
    val points = FloatArray(dotCount * 2)
    val lit = FloatArray(2)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    val dpPx = 1.dp.toPx()
    val maxRadiusPx = MAX_DOT_RADIUS_DP * dpPx
    val minRadiusPx = 0.32f * dpPx
    // The shine traces the same continuous outline, set in by 0.6 px.
    val rim = pathSegments(continuousOutline(0.6f, w, h, max(0f, r - 0.6f)), w / 2f, h / 2f, 260)
    val hairline = Stroke(0.8.dp.toPx())
    // A tight glow under a crisp line: the shine reads as one sharp edge.
    val glowWidth = 2.dp.toPx()
    val lineWidth = (if (thin) 0.6.dp else 1.15.dp).toPx()
    val rimRun = Path()

    onDrawBehind {
        val lx = GlassLight.x
        val ly = GlassLight.y
        val theta = GlassOptics.angle(lx, ly)
        val power = GlassOptics.power(lx, ly)
        clipPath(outline) {
            val l = lift()
            drawRect(if (l <= 0f) body else Brush.verticalGradient(
                0f to palette.body.first.copy(alpha = (palette.bodyAlpha - 0.01f - l) * seeThrough),
                0.55f to palette.body.second.copy(alpha = (palette.bodyAlpha - l) * seeThrough),
                1f to palette.body.third.copy(alpha = (palette.bodyAlpha - l) * seeThrough),
            ))
            // The sheen is a soft pool of light entering from the lit edge, round rather than a band, so no angle of
            // the phone shows a line across the glass; a faint answer glows at the opposite edge.
            val len = max(0.001f, kotlin.math.hypot(lx, ly))
            val ux = lx / len
            val uy = ly / len
            val reach = max(w, h) * 0.85f
            drawRect(Brush.radialGradient(
                0f to palette.sheen.copy(alpha = 0.11f * power * seeThrough),
                0.35f to palette.sheen.copy(alpha = 0.05f * power * seeThrough),
                0.7f to palette.sheen.copy(alpha = 0.012f * power * seeThrough), 1f to Color.Transparent,
                center = Offset(w / 2f + ux * w / 2f, h / 2f + uy * h / 2f), radius = reach,
            ))
            drawRect(Brush.radialGradient(
                0f to palette.sheen.copy(alpha = 0.035f * power * seeThrough), 1f to Color.Transparent,
                center = Offset(w / 2f - ux * w / 2f, h / 2f - uy * h / 2f), radius = reach * 0.6f,
            ))
            if (dotCount > 0) clipPath(dotOutline) {
                // Living halftone: every frame each dot is lit by where the phone leans (a highlight that slides
                // around the rim), breathes with a slow wave travelling round the panel, and ripples outward from the
                // highlight when the phone moves. Dots are sorted into alpha × size buckets (counting sort, no
                // allocation) and each bucket is one drawPoints call.
                val t = GlassLight.time
                val energy = GlassLight.energyAt(t)
                counts.fill(0)
                for (k in 0 until dotCount) {
                    GlassOptics.liveDot(phis[k], fallArr[k], theta, power, t, energy, lit)
                    val alpha = lit[0] * DOT_OPACITY
                    val radiusPx = min(lit[1] * dpPx, roomArr[k] - 0.25f * dpPx)
                    if (alpha < 0.012f || radiusPx < minRadiusPx) { keys[k] = -1; continue }
                    val a = (alpha / DOT_ALPHA_TOP * (ALPHA_LEVELS - 1)).roundToInt().coerceIn(0, ALPHA_LEVELS - 1)
                    val rq = (radiusPx / maxRadiusPx * (RADIUS_LEVELS - 1)).roundToInt().coerceIn(1, RADIUS_LEVELS - 1)
                    val key = a * RADIUS_LEVELS + rq
                    keys[k] = key
                    counts[key]++
                }
                var run = 0
                for (b in counts.indices) { starts[b] = run; run += counts[b] }
                for (k in 0 until dotCount) {
                    val key = keys[k]
                    if (key < 0) continue
                    val at = starts[key] * 2
                    points[at] = xs[k]; points[at + 1] = ys[k]
                    starts[key]++
                }
                drawIntoCanvas { canvas ->
                    var offset = 0
                    for (b in counts.indices) {
                        val n = counts[b]
                        if (n == 0) continue
                        val alpha = (b / RADIUS_LEVELS) * DOT_ALPHA_TOP / (ALPHA_LEVELS - 1)
                        paint.color = android.graphics.Color.argb((alpha * 255).roundToInt(), (palette.dotRgb shr 16) and 0xFF, (palette.dotRgb shr 8) and 0xFF, palette.dotRgb and 0xFF)
                        paint.strokeWidth = (b % RADIUS_LEVELS) * maxRadiusPx / (RADIUS_LEVELS - 1) * 2f
                        canvas.nativeCanvas.drawPoints(points, offset * 2, n * 2, paint)
                        offset += n
                    }
                }
            }
        }
        drawPath(outline, palette.hairline.copy(alpha = 0.16f), style = hairline)
        // The shine is drawn as continuous runs: neighbouring segments of the same brightness become one path, stroked
        // once with flat ends and round joins. Separate round-capped segments overlapped at every joint and doubled
        // the light there, which beaded the shine into a dotted line; one stroke per run keeps it a single sharp edge.
        for (pass in 0..1) {
            if (pass == 0 && thin) continue
            val width = if (pass == 0) glowWidth else lineWidth
            val stroke = Stroke(width, cap = StrokeCap.Butt, join = StrokeJoin.Round)
            var runLevel = -1
            var i = 0
            fun flush() {
                if (runLevel >= 0) {
                    val alpha = runLevel / RIM_LEVELS.toFloat()
                    drawPath(rimRun, (if (pass == 0) palette.glow else palette.rim).copy(alpha = alpha), style = stroke)
                }
                rimRun.reset()
                runLevel = -1
            }
            while (i < rim.size) {
                val intensity = GlassOptics.rimIntensity(rim[i + 4], theta, thin)
                val alpha = if (pass == 0) (0.17f * intensity * power).coerceIn(0f, 1f)
                    else min(if (thin) 0.72f else 0.9f, (if (thin) 0.72f else 1f) * intensity * power)
                val level = if (intensity < 0.02f) -1 else (alpha * RIM_LEVELS).roundToInt().coerceIn(1, RIM_LEVELS)
                if (level != runLevel) {
                    flush()
                    if (level > 0) { rimRun.moveTo(rim[i], rim[i + 1]); runLevel = level }
                }
                if (level > 0) rimRun.lineTo(rim[i + 2], rim[i + 3])
                i += 5
            }
            flush()
        }
    }
}

private const val ALPHA_LEVELS = 32
private val NoLift: () -> Float = { 0f }
/** Brightness steps of the shine: neighbouring segments in the same step are drawn as one stroke. */
private const val RIM_LEVELS = 48
/** The halftone sits quietly in the glass: a little under full strength. */
internal const val DOT_OPACITY = 0.30f

/** The brightest a drawn dot gets ([GlassOptics.liveDot]'s cap times [DOT_OPACITY]); the alpha steps span up to it. */
private const val DOT_ALPHA_TOP = GlassOptics.DOT_ALPHA_CAP * DOT_OPACITY
private const val RADIUS_LEVELS = 24
/** The largest a dot is drawn (dp): [GlassOptics.liveDot]'s biggest radius. */
private const val MAX_DOT_RADIUS_DP = 2.3f

/** A continuous-corner (squircle) rounded rectangle, inset by [inset] on every side, as a path. */
private fun androidx.compose.ui.draw.CacheDrawScope.continuousOutline(inset: Float, w: Float, h: Float, radius: Float): Path {
    val iw = max(0f, w - 2 * inset)
    val ih = max(0f, h - 2 * inset)
    val path = Path()
    when (val o = com.kyant.capsule.ContinuousRoundedRectangle(radius.toDp())
        .createOutline(androidx.compose.ui.geometry.Size(iw, ih), layoutDirection, this)) {
        is androidx.compose.ui.graphics.Outline.Generic -> path.addPath(o.path)
        is androidx.compose.ui.graphics.Outline.Rounded -> path.addRoundRect(o.roundRect)
        is androidx.compose.ui.graphics.Outline.Rectangle -> path.addRect(o.rect)
    }
    if (inset != 0f) path.translate(Offset(inset, inset))
    return path
}

/**
 * Segments along any closed outline: x0, y0, x1, y1 and the outward normal angle, five floats each. The normal is the
 * tangent turned to face away from the centre ([cx], [cy]).
 */
internal fun pathSegments(path: Path, cx: Float, cy: Float, n: Int): FloatArray {
    val measure = android.graphics.PathMeasure(path.asAndroidPath(), true)
    val length = measure.length
    if (length <= 0f || n <= 0) return FloatArray(0)
    val pos = FloatArray(2); val tan = FloatArray(2)
    val xs = FloatArray(n + 1); val ys = FloatArray(n + 1); val normals = FloatArray(n + 1)
    for (i in 0..n) {
        measure.getPosTan(length * (i % n) / n, pos, tan)
        xs[i] = pos[0]; ys[i] = pos[1]
        var nx = tan[1]; var ny = -tan[0]
        if (nx * (pos[0] - cx) + ny * (pos[1] - cy) < 0f) { nx = -nx; ny = -ny }
        normals[i] = kotlin.math.atan2(ny, nx)
    }
    val out = FloatArray(n * 5)
    for (i in 0 until n) {
        out[i * 5] = xs[i]; out[i * 5 + 1] = ys[i]; out[i * 5 + 2] = xs[i + 1]; out[i * 5 + 3] = ys[i + 1]; out[i * 5 + 4] = normals[i]
    }
    return out
}

/** Segments around a rounded rectangle: x0, y0, x1, y1, outward normal angle — five floats each. */
internal fun rimSegments(w: Float, h: Float, radius: Float, n: Int): FloatArray {
    if (w <= 0f || h <= 0f) return FloatArray(0)
    val r = min(radius, min(w, h) / 2f)
    val sx = w - 2 * r
    val sy = h - 2 * r
    val arc = (PI * r / 2).toFloat()
    val per = 2 * sx + 2 * sy + 4 * arc
    fun point(u: Float): FloatArray {
        var d = u * per
        if (d < sx) return floatArrayOf(r + d, 0f, (-PI / 2).toFloat()); d -= sx
        if (d < arc) { val a = (-PI / 2).toFloat() + d / max(r, 0.001f); return floatArrayOf(w - r + cos(a) * r, r + sin(a) * r, a) }; d -= arc
        if (d < sy) return floatArrayOf(w, r + d, 0f); d -= sy
        if (d < arc) { val a = d / max(r, 0.001f); return floatArrayOf(w - r + cos(a) * r, h - r + sin(a) * r, a) }; d -= arc
        if (d < sx) return floatArrayOf(w - r - d, h, (PI / 2).toFloat()); d -= sx
        if (d < arc) { val a = (PI / 2).toFloat() + d / max(r, 0.001f); return floatArrayOf(r + cos(a) * r, h - r + sin(a) * r, a) }; d -= arc
        if (d < sy) return floatArrayOf(0f, h - r - d, PI.toFloat()); d -= sy
        val a = PI.toFloat() + d / max(r, 0.001f)
        return floatArrayOf(r + cos(a) * r, r + sin(a) * r, a)
    }
    val out = FloatArray(n * 5)
    for (i in 0 until n) {
        val p0 = point(i.toFloat() / n)
        val p1 = point(((i + 1) % n).toFloat() / n)
        out[i * 5] = p0[0]; out[i * 5 + 1] = p0[1]; out[i * 5 + 2] = p1[0]; out[i * 5 + 3] = p1[1]; out[i * 5 + 4] = p0[2]
    }
    return out
}
