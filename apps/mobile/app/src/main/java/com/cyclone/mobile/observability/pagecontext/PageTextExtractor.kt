package com.cyclone.mobile.observability.pagecontext

import com.cyclone.mobile.applearner.ActionSafetyPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Turns a sanitized Accessibility snapshot into a compact, spatially ordered text view of the
 * current page. This is the "read the page" evidence for agents: what text is on screen, in the
 * order a human would read it, with duplicate overlay text removed.
 *
 * Input must already be privacy-sanitized (editable values and sensitive fields replaced by
 * `<redacted>`), matching the snapshot emitted by the gateway boundary. Editable field *labels*
 * survive as contentDescription when available; entered values never do.
 */
object PageTextExtractor {
    const val DEFAULT_MAX_LINES = 160
    private const val REDACTED = "<redacted>"
    private val whitespace = Regex("\\s+")

    fun extract(snapshot: JSONObject, maxLines: Int = DEFAULT_MAX_LINES): JSONObject {
        val cap = maxLines.coerceAtLeast(1)
        val nodes = snapshot.optJSONArray("nodes") ?: JSONArray()
        val screen = snapshot.optJSONObject("screen")
        val screenWidth = screen?.optInt("width") ?: Int.MAX_VALUE
        val screenHeight = screen?.optInt("height") ?: Int.MAX_VALUE

        data class Line(val text: String, val role: String, val y: Int, val x: Int)

        val candidates = mutableListOf<Line>()
        for (index in 0 until nodes.length()) {
            val node = nodes.optJSONObject(index) ?: continue
            if (!node.optBoolean("visibleToUser", true)) continue
            val bounds = node.optJSONObject("bounds") ?: continue
            val left = bounds.optInt("left")
            val top = bounds.optInt("top")
            val width = bounds.optInt("right") - left
            val height = bounds.optInt("bottom") - top
            if (width <= 0 || height <= 0 || left >= screenWidth || top >= screenHeight) continue

            val rawText = node.optString("text").trim()
            val description = node.optString("contentDescription").trim()
            val editable = node.optBoolean("editable", false)
            val safeDescription = description.takeIf { it.isNotBlank() && it != REDACTED }.orEmpty()
            val sensitive = ActionSafetyPolicy.looksSensitiveField(node)
            val text = when {
                // Editable node text is user-entered state and is always discarded at the
                // gateway boundary. Sensitive fields remain absent; ordinary editable fields
                // keep only the Accessibility-provided label.
                sensitive && editable -> ""
                editable -> safeDescription
                rawText == REDACTED || sensitive -> safeDescription
                else -> rawText.ifBlank { safeDescription }
            }
            if (text.isBlank()) continue
            candidates += Line(text.take(240), node.optString("role"), top, left)
        }

        val lines = mutableListOf<Line>()
        candidates.sortedWith(compareBy({ it.y }, { it.x })).forEach { line ->
            val previous = lines.lastOrNull()
            val nearPrevious = previous != null &&
                Math.abs(line.y - previous.y) <= 32 &&
                Math.abs(line.x - previous.x) <= 96
            if (nearPrevious && normalize(line.text) == normalize(previous.text)) return@forEach
            lines += line
        }

        val out = JSONArray()
        lines.take(cap).forEach { line ->
            out.put(JSONObject()
                .put("text", line.text)
                .put("role", line.role)
                .put("y", line.y)
                .put("x", line.x))
        }
        return JSONObject()
            .put("protocol", "cyclone-page-text-v1")
            .put("lineCount", out.length())
            .put("truncated", out.length() < lines.size)
            .put("order", "top-to-bottom reading order by bounds; duplicate overlay text removed")
            .put("lines", out)
    }

    internal fun normalize(value: String): String =
        value.lowercase(Locale.US).replace(whitespace, " ").trim()
}
