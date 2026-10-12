package com.cyclone.mobile.gesture

import com.cyclone.mobile.UiNodeSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 52 run 6: the typed requests behind `phone.double_tap`, `phone.drag`, `phone.pinch` and `phone.draw`.
 *
 * Every request names controls (a selector or a current elementId) and plain intents (a direction, a scale, a shape).
 * None carries screen coordinates: the phone plans every point. The one exception, by design, is `phone.draw`'s
 * `strokes`: at most 4 polylines of at most 64 points, normalised to 0..1 inside a grounded drawing canvas, so a
 * caller can draw something specific without ever addressing the rest of the screen.
 */
sealed class HandGestureRequest {
    abstract val kind: TouchGestureKind

    object DoubleTap : HandGestureRequest() {
        override val kind = TouchGestureKind.DOUBLE_TAP
    }

    data class Drag(
        /** The control to drop on (`toSelector`, or `toElementId` mapped by the executor); null for a drag by direction. */
        val toSelector: JSONObject?,
        val toElementId: String?,
        val direction: SwipeDirection?,
        val amount: SwipeAmount,
        val holdMs: Long?,
    ) : HandGestureRequest() {
        override val kind = TouchGestureKind.DRAG
    }

    data class Pinch(val scale: Double, val focus: GesturePoint?) : HandGestureRequest() {
        override val kind = TouchGestureKind.PINCH
    }

    data class Draw(val shape: DrawShape?, val strokes: List<List<GesturePoint>>?) : HandGestureRequest() {
        override val kind = TouchGestureKind.DRAW
    }

    companion object {
        val TOOLS: Map<String, TouchGestureKind> = linkedMapOf(
            "phone.double_tap" to TouchGestureKind.DOUBLE_TAP,
            "phone.drag" to TouchGestureKind.DRAG,
            "phone.pinch" to TouchGestureKind.PINCH,
            "phone.draw" to TouchGestureKind.DRAW,
        )

        /** Keys that would smuggle raw screen points in; refused on every gesture tool. */
        private val RAW_POINT_KEYS = setOf("x1", "y1", "x2", "y2", "points", "path", "fromX", "fromY", "toX", "toY")

        /** The request, or a reason it is malformed (nothing is dispatched for a malformed request). */
        fun parse(tool: String, params: JSONObject): Pair<HandGestureRequest?, String?> {
            if (tool !in TOOLS) return null to "unknown gesture tool $tool"
            RAW_POINT_KEYS.firstOrNull { params.has(it) }?.let {
                return null to "$tool takes controls and intents, not screen coordinates ($it)"
            }
            return when (tool) {
                "phone.double_tap" -> DoubleTap to null
                "phone.drag" -> drag(params)
                "phone.pinch" -> pinch(params)
                else -> draw(params)
            }
        }

        private fun drag(params: JSONObject): Pair<HandGestureRequest?, String?> {
            val toSelector = params.optJSONObject("toSelector")?.takeIf { it.length() > 0 }
            val toElementId = params.optString("toElementId").takeIf { it.isNotBlank() }
            val hasDirection = params.has("direction")
            if ((toSelector != null || toElementId != null) == hasDirection) {
                return null to "drag needs either a control to drop on (toElementId) or a direction, not both"
            }
            val direction = if (hasDirection) SwipeDirection.parse(params.optString("direction"))
                ?: return null to "direction must be up, down, left or right" else null
            val amount = if (params.has("amount")) SwipeAmount.parse(params.optString("amount"))
                ?: return null to "amount must be peek, half, page or far" else SwipeAmount.HALF
            val hold = if (params.has("holdMs")) params.optLong("holdMs", -1L) else if (params.has("hold")) params.optLong("hold", -1L) else null
            if (hold != null && hold !in HandGestures.DRAG_PICKUP_MIN_MS..HandGestures.DRAG_PICKUP_MAX_MS) {
                return null to "holdMs must be ${HandGestures.DRAG_PICKUP_MIN_MS} to ${HandGestures.DRAG_PICKUP_MAX_MS}"
            }
            return Drag(toSelector, toElementId, direction, amount, hold) to null
        }

        private fun pinch(params: JSONObject): Pair<HandGestureRequest?, String?> {
            val scale = when {
                params.has("scale") -> params.optDouble("scale", Double.NaN)
                params.optString("zoom").equals("in", ignoreCase = true) -> 2.0
                params.optString("zoom").equals("out", ignoreCase = true) -> 0.5
                else -> return null to "pinch needs scale (0.25 to 4; above 1 zooms in) or zoom: in|out"
            }
            if (!scale.isFinite() || scale < HandGestures.PINCH_SCALE_MIN || scale > HandGestures.PINCH_SCALE_MAX) {
                return null to "scale must be ${HandGestures.PINCH_SCALE_MIN} to ${HandGestures.PINCH_SCALE_MAX}"
            }
            if (kotlin.math.abs(scale - 1.0) < 0.1) return null to "scale must zoom: below 0.9 or above 1.1"
            val focus = (params.optJSONObject("center") ?: params.optJSONObject("focus"))?.let { f ->
                val x = f.optDouble("x", Double.NaN)
                val y = f.optDouble("y", Double.NaN)
                if (!x.isFinite() || !y.isFinite() || x !in 0.0..1.0 || y !in 0.0..1.0) {
                    return null to "center is {x, y} from 0 to 1 inside the area"
                }
                GesturePoint(x.toFloat(), y.toFloat())
            }
            return Pinch(scale, focus) to null
        }

        private fun draw(params: JSONObject): Pair<HandGestureRequest?, String?> {
            val hasShape = params.has("shape")
            val hasStrokes = params.has("strokes")
            if (hasShape == hasStrokes) return null to "draw needs either a shape or strokes, not both"
            if (hasShape) {
                val shape = DrawShape.parse(params.optString("shape"))
                    ?: return null to "shape must be one of ${DrawShape.entries.joinToString { it.wire }}"
                return Draw(shape, null) to null
            }
            val raw = params.optJSONArray("strokes") ?: return null to "strokes must be a list of point lists"
            if (raw.length() !in 1..HandGestures.DRAW_MAX_STROKES) return null to "strokes holds 1 to ${HandGestures.DRAW_MAX_STROKES} lines"
            val strokes = ArrayList<List<GesturePoint>>()
            for (i in 0 until raw.length()) {
                val line = raw.optJSONArray(i) ?: return null to "each stroke is a list of [x, y] points"
                if (line.length() !in 2..HandGestures.DRAW_MAX_POINTS) {
                    return null to "each stroke holds 2 to ${HandGestures.DRAW_MAX_POINTS} points"
                }
                val points = ArrayList<GesturePoint>()
                for (j in 0 until line.length()) {
                    val p = point(line.opt(j)) ?: return null to "points are [x, y] (or {x, y}) from 0 to 1 inside the canvas"
                    points += p
                }
                strokes += points
            }
            return Draw(null, strokes) to null
        }

        private fun point(value: Any?): GesturePoint? {
            val (x, y) = when (value) {
                is JSONArray -> if (value.length() == 2) value.optDouble(0, Double.NaN) to value.optDouble(1, Double.NaN) else return null
                is JSONObject -> value.optDouble("x", Double.NaN) to value.optDouble("y", Double.NaN)
                else -> return null
            }
            if (!x.isFinite() || !y.isFinite() || x !in 0.0..1.0 || y !in 0.0..1.0) return null
            return GesturePoint(x.toFloat(), y.toFloat())
        }
    }
}

/**
 * Plan 52 run 6: whether a control is a drawing surface Cyclone may draw on. Drawing is only ever allowed inside one:
 * a visible, enabled, large-enough element that is not a text field or a list, holds no controls of its own, and
 * reads as a canvas (its name or id says canvas, draw, sign, signature, sketch, paint, pad, whiteboard or doodle) or
 * is a bare drawing view (View, SurfaceView, TextureView).
 */
object DrawCanvas {
    const val MIN_WIDTH_PX = 120
    const val MIN_HEIGHT_PX = 80

    private val WORDS = Regex("(?i)canvas|draw|sign|signature|sketch|paint|\\bpad\\b|signpad|whiteboard|doodle|handtekening|teken|onderteken")
    private val BARE_VIEWS = setOf("android.view.View", "android.view.SurfaceView", "android.view.TextureView", "android.opengl.GLSurfaceView")
    private val SIGNATURE = Regex("(?i)sign|signature|handtekening|onderteken|paraaf|initials")

    /** Null when [node] is a drawing canvas; otherwise the reason it is not. */
    fun refusal(node: UiNodeSnapshot, nodes: List<UiNodeSnapshot>): String? {
        if (!node.visibleToUser || !node.enabled) return "the canvas is not visible"
        if (node.bounds.width < MIN_WIDTH_PX || node.bounds.height < MIN_HEIGHT_PX) return "the canvas is too small to draw on"
        if (node.editable || node.password) return "that is a text field, not a drawing canvas"
        if (node.scrollable) return "that is a scrolling list, not a drawing canvas"
        val controlsInside = nodes.any {
            it.path.startsWith(node.path + "/") && it.visibleToUser && (it.clickable || it.editable || it.checkable || it.scrollable)
        }
        if (controlsInside) return "that area holds its own controls, so it is not a drawing canvas"
        val words = "${node.className} ${node.resourceId} ${node.text} ${node.contentDescription}"
        if (!WORDS.containsMatchIn(words) && node.className !in BARE_VIEWS) return "that does not look like a drawing canvas"
        return null
    }

    /** True when drawing here signs something: the owner approves every signature. */
    fun signs(node: UiNodeSnapshot, shape: DrawShape?): Boolean =
        shape == DrawShape.SIGNATURE || SIGNATURE.containsMatchIn("${node.resourceId} ${node.text} ${node.contentDescription}")
}
