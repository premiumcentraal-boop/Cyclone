package com.cyclone.mobile.gateway

import android.content.Context
import android.content.res.Configuration
import com.cyclone.mobile.AccessibilityRoles
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.applearner.PageAwarenessRuntime
import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.applearner.PageControl
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.capture.PhoneScreenCapture
import com.cyclone.mobile.capture.PhoneScreenCapture.ScreenCaptureException
import com.cyclone.mobile.observability.pagecontext.PageContextSummary
import com.cyclone.mobile.observability.pagecontext.PageTextExtractor
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.max

internal data class GatewayElement(
    val id: String,
    val source: String,
    val label: String,
    val semanticName: String,
    val role: String,
    val evidence: JSONObject,
)

internal data class GatewayObservation(
    val id: String,
    val capturedAt: Long,
    val page: PageContext,
    val payload: JSONObject,
    val elements: Map<String, GatewayElement>,
)

internal object GatewayObservationStore {
    @Volatile private var current: GatewayObservation? = null
    fun current(): GatewayObservation? = current
    fun replace(observation: GatewayObservation) { current = observation }
    fun clear() { current = null }
}

internal object GatewayObservationAdapter {
    fun capture(context: Context, args: JSONObject = JSONObject()): GatewayObservation {
        val service = CycloneAccessibilityService.instance
            ?: throw GatewayProtocolException("ACCESSIBILITY_NOT_CONNECTED", "Cyclone Accessibility is not connected")
        PageAwarenessRuntime.initialize(context)
        val snapshot = service.observe(markFresh = true)
        val raw = snapshot.toJson()
        val page = PageAwarenessRuntime.capture(context, raw)
        val safeRaw = GatewayPrivacy.sanitizeAccessibilitySnapshot(raw)
        val observationId = UUID.randomUUID().toString()
        val rawNodes = safeRaw.optJSONArray("nodes") ?: JSONArray()
        val elements = linkedMapOf<String, GatewayElement>()
        val semanticControls = JSONArray()
        val controlSignatures = linkedSetOf<String>()

        page.controls.forEach { control ->
            controlSignatures += signature(control)
            val matchingNode = bestNode(control, rawNodes)
            val elementId = "semantic:$observationId:${control.key}"
            val evidence = JSONObject()
                .put("elementId", elementId)
                .put("observationId", observationId)
                .put("source", "semantic")
                .put("controlKey", control.key)
                .put("label", control.label)
                .put("semanticName", control.semanticName)
                .put("role", control.role)
                .put("selector", GatewayPrivacy.sanitizeDeep(JSONObject(control.selector.toString())))
                .put("androidActions", JSONArray(control.androidActions))
                .put("risk", control.risk.name)
                .put("expectedEffect", control.expectedEffect ?: JSONObject.NULL)
                .put("confidence", control.confidence)
                .put("resourceId", matchingNode?.optString("resourceId").orEmpty())
                .put("contentDescription", matchingNode?.optString("contentDescription").orEmpty())
                .put("bounds", matchingNode?.optJSONObject("bounds") ?: JSONObject.NULL)
                .put("clickable", matchingNode?.optBoolean("clickable") ?: false)
                .put("longClickable", matchingNode?.optBoolean("longClickable") ?: false)
                .put("scrollable", matchingNode?.optBoolean("scrollable") ?: false)
                .put("editable", matchingNode?.optBoolean("editable") ?: false)
                .put("enabled", matchingNode?.optBoolean("enabled") ?: true)
                .put("selected", matchingNode?.optBoolean("selected") ?: false)
                .put("checked", matchingNode?.optBoolean("checked") ?: false)
                .put("checkable", matchingNode?.optBoolean("checkable") ?: false)
                .put("rawNodeId", matchingNode?.optString("id")?.takeIf(String::isNotBlank) ?: JSONObject.NULL)
            semanticControls.put(evidence)
            elements[elementId] = GatewayElement(elementId, "semantic", control.label, control.semanticName, control.role, evidence)
        }

        // The canonical semantic store scans at most 450 raw nodes. Surface interactive nodes
        // outside that window as supplemental semantic controls so agents can see the whole page,
        // not only the stored PageContext slice.
        var supplementalCount = 0
        for (index in 0 until rawNodes.length()) {
            val node = rawNodes.optJSONObject(index) ?: continue
            val interactive = node.optBoolean("clickable") || node.optBoolean("longClickable") ||
                node.optBoolean("editable") || node.optBoolean("scrollable") || node.optBoolean("checkable") ||
                node.optString("role") in setOf("button", "tab", "switch", "checkbox", "edit_text", "textbox")
            val bounds = node.optJSONObject("bounds")
            val boundsWidth = if (bounds == null) 0 else bounds.optInt("right") - bounds.optInt("left")
            val boundsHeight = if (bounds == null) 0 else bounds.optInt("bottom") - bounds.optInt("top")
            if (!AccessibilityRoles.isPublishedInteractive(
                    node.optBoolean("visibleToUser", true), interactive, boundsWidth, boundsHeight,
                )
            ) continue
            if (!interactive) continue
            if (signature(node) in controlSignatures) continue
            val ownLabel = node.optString("text").takeUnless { it.isBlank() || it == "<redacted>" }
                ?: node.optString("contentDescription").takeUnless { it.isBlank() || it == "<redacted>" }
                ?: node.optString("resourceId").substringAfterLast('/').replace('_', ' ').takeIf { it.isNotBlank() }
            val inheritedLabel = if (ownLabel == null) descendantLabel(node, rawNodes) else ""
            val label = ownLabel ?: inheritedLabel.takeIf { it.isNotBlank() }
                ?: continue
            if (label.isBlank()) continue
            supplementalCount++
            val elementId = "semantic:$observationId:supp:$supplementalCount"
            val evidence = JSONObject()
                .put("elementId", elementId)
                .put("observationId", observationId)
                .put("source", "semantic_supplement")
                .put("controlKey", "supp:$supplementalCount")
                .put("label", label.take(140))
                .put("semanticName", semanticize(label))
                .put("role", node.optString("role"))
                .put("selector", GatewayPrivacy.sanitizeDeep(supplementSelector(node, inheritedLabel)))
                .put("androidActions", node.optJSONArray("actions") ?: JSONArray())
                .put("risk", "SAFE")
                .put("expectedEffect", JSONObject.NULL)
                .put("confidence", 0.55)
                .put("resourceId", node.optString("resourceId"))
                .put("contentDescription", node.optString("contentDescription"))
                .put("bounds", node.optJSONObject("bounds") ?: JSONObject.NULL)
                .put("clickable", node.optBoolean("clickable"))
                .put("longClickable", node.optBoolean("longClickable"))
                .put("scrollable", node.optBoolean("scrollable"))
                .put("editable", node.optBoolean("editable"))
                .put("enabled", node.optBoolean("enabled", true))
                .put("selected", node.optBoolean("selected"))
                .put("checked", node.optBoolean("checked"))
                .put("checkable", node.optBoolean("checkable"))
                .put("rawNodeId", node.optString("id").takeIf(String::isNotBlank) ?: JSONObject.NULL)
            semanticControls.put(evidence)
            elements[elementId] = GatewayElement(elementId, "semantic_supplement", label.take(140), semanticize(label), node.optString("role"), evidence)
        }

        for (index in 0 until rawNodes.length()) {
            val node = rawNodes.optJSONObject(index) ?: continue
            val rawId = node.optString("id").ifBlank { "index-$index" }
            val elementId = "raw:$observationId:$rawId"
            val label = node.optString("text").takeUnless { it == "<redacted>" }.orEmpty()
                .ifBlank { node.optString("contentDescription").takeUnless { it == "<redacted>" }.orEmpty() }
                .ifBlank { node.optString("resourceId").substringAfterLast('/').replace('_', ' ') }
            val evidence = JSONObject(node.toString())
                .put("elementId", elementId)
                .put("observationId", observationId)
                .put("source", "raw_accessibility")
            elements[elementId] = GatewayElement(
                id = elementId,
                source = "raw_accessibility",
                label = label,
                semanticName = semanticize(label),
                role = node.optString("role"),
                evidence = evidence,
            )
        }

        val pageText = PageTextExtractor.extract(safeRaw)
        val pageSummary = PageContextSummary.build(
            snapshot = safeRaw,
            pageKey = page.pageKey,
            title = page.title,
            controlCount = page.controls.size + supplementalCount,
            textLineCount = pageText.optInt("lineCount"),
        )
        // Page context is the canonical PC-agent source of truth. Graph/Brain knowledge can
        // contribute only advisory, repeatedly-verified next hops; it cannot authorize an action.
        AppLearnerRuntime.initialize(context)
        val nextHopHints = GatewayRouteEvidence.nextHops(
            page = page,
            accessibilityFingerprint = snapshot.fingerprint,
            graph = AppLearnerRuntime.graph(page.packageName),
            brainSkills = AdaptiveBrainRuntime.reusableMicroSkills(context),
        )
        val windows = safeRaw.optJSONArray("windows") ?: JSONArray()
        val boundedPageEvidence = GatewayRouteEvidence.pageEvidence(
            page = page,
            packageName = snapshot.packageName,
            activity = snapshot.className,
            pageText = pageText,
            pageSummary = pageSummary,
            semanticControls = page.controls.size,
            supplementalControls = supplementalCount,
            rawNodes = rawNodes.length(),
            windows = windows.length(),
            nextHopHints = nextHopHints,
        )
        val includeScreenshot = args.optBoolean("includeScreenshot", false)
        val screenshot = if (includeScreenshot) {
            runCatching {
                PhoneScreenCapture.capture(
                    service = service,
                    maxDimension = args.optInt("screenshotMaxDimension", PhoneScreenCapture.DEFAULT_EVIDENCE_MAX_DIMENSION)
                        .takeIf { it > 0 },
                    includeBase64 = args.optBoolean("includeScreenshotBase64", false),
                )
            }.getOrElse { error ->
                JSONObject()
                    .put("available", false)
                    .put("errorCode", (error as? ScreenCaptureException)?.code ?: "SCREENSHOT_FAILED")
                    .put("error", (error.message ?: "Screen capture failed").take(240))
            }
        } else null
        screenshot?.optString("filePath")?.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { PageAwarenessRuntime.store.attachPreview(page.pageKey, path) }
        }

        val fullPage = page.toAgentJson(maxControls = page.controls.size)
            .put("structuralKey", page.structuralKey)
            .put("contentKey", page.contentKey)
            .put("firstSeenAt", page.firstSeenAt)
            .put("lastSeenAt", page.lastSeenAt)
            .put("previewPath", page.previewPath ?: JSONObject.NULL)
        val orientation = when (context.resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> "undefined"
        }
        val payload = JSONObject()
            .put("observationId", observationId)
            .put("elementIdScope", "observation-local; IDs are valid only while this observation is current")
            .put("timestamp", snapshot.timestampMs)
            .put("package", snapshot.packageName ?: JSONObject.NULL)
            .put("activity", snapshot.className ?: JSONObject.NULL)
            .put("display", JSONObject()
                .put("width", snapshot.screenWidth)
                .put("height", snapshot.screenHeight)
                .put("orientation", orientation))
            .put("pageKey", page.pageKey)
            .put("pageTitle", page.title)
            .put("semanticFingerprint", page.pageKey)
            .put("accessibilityFingerprint", snapshot.fingerprint)
            .put("pageContext", fullPage)
            .put("semanticControls", semanticControls)
            .put("controlCount", page.controls.size)
            .put("supplementalControlCount", supplementalCount)
            .put("pageText", pageText)
            .put("pageSummary", pageSummary)
            .put("pageEvidence", boundedPageEvidence)
            .put("nextHopHints", nextHopHints)
            .put("screenshot", screenshot ?: JSONObject.NULL)
            .put("windows", windows)
            .put("rawAccessibility", safeRaw)
            .put("rawNodeCount", rawNodes.length())

        return GatewayObservation(observationId, System.currentTimeMillis(), page, payload, elements).also(GatewayObservationStore::replace)
    }

    fun search(observation: GatewayObservation, query: String, limit: Int): JSONArray {
        val normalized = normalize(query)
        if (normalized.isBlank()) throw GatewayProtocolException("INVALID_REQUEST", "query is required")
        val ranked = observation.elements.values.mapNotNull { element ->
            val score = score(normalized, element)
            if (score <= 0.0) null else score to element
        }.sortedWith(compareByDescending<Pair<Double, GatewayElement>> { it.first }
            .thenBy { if (it.second.source == "semantic") 0 else 1 }
            .thenBy { it.second.label })
            .take(limit.coerceIn(1, 100))
        return JSONArray().also { out ->
            ranked.forEach { (score, element) ->
                val e = element.evidence
                out.put(JSONObject()
                    .put("elementId", element.id)
                    .put("observationId", observation.id)
                    .put("label", element.label)
                    .put("semanticName", element.semanticName)
                    .put("role", element.role)
                    .put("resourceId", e.optString("resourceId"))
                    .put("contentDescription", e.optString("contentDescription"))
                    .put("bounds", e.optJSONObject("bounds") ?: JSONObject.NULL)
                    .put("actions", e.optJSONArray("androidActions") ?: e.optJSONArray("actions") ?: JSONArray())
                    .put("source", element.source)
                    .put("relevance", score))
            }
        }
    }

    fun element(observation: GatewayObservation, elementId: String): JSONObject {
        val embeddedObservation = elementId.split(':').getOrNull(1)
        if (embeddedObservation != null && embeddedObservation != observation.id) {
            throw GatewayProtocolException("STALE_ELEMENT", "Element ID belongs to a previous observation")
        }
        return observation.elements[elementId]?.evidence
            ?: throw GatewayProtocolException("ELEMENT_NOT_FOUND", "Element ID is not present in the current observation")
    }

    private fun bestNode(control: PageControl, nodes: JSONArray): JSONObject? {
        val selector = control.selector
        var best: JSONObject? = null
        var bestScore = -1
        for (index in 0 until nodes.length()) {
            val node = nodes.optJSONObject(index) ?: continue
            var score = 0
            val resource = selector.optString("resourceId")
            val text = selector.optString("text")
            val description = selector.optString("contentDescription")
            val role = selector.optString("role")
            if (resource.isNotBlank()) score += if (resource == node.optString("resourceId")) 8 else -5
            if (text.isNotBlank()) {
                val nodeText = normalize(node.optString("text"))
                val descText = normalize(node.optString("contentDescription"))
                val wanted = normalize(text)
                if (wanted == nodeText || wanted == descText) score += 5
            }
            if (description.isNotBlank()) score += if (normalize(description) == normalize(node.optString("contentDescription"))) 5 else 0
            if (role.isNotBlank() && role.equals(node.optString("role"), ignoreCase = true)) score += 2
            val clickable = node.optBoolean("clickable")
            val actions = node.optJSONArray("actions")
            var hasClick = clickable
            if (actions != null) {
                for (i in 0 until actions.length()) {
                    if (actions.optString(i) == "ACTION_CLICK") hasClick = true
                }
            }
            val nodeRole = node.optString("role").lowercase(Locale.US)
            if (hasClick || nodeRole in setOf("button", "tab", "row", "textbox", "switch", "checkbox")) score += 6
            if (!hasClick && nodeRole in setOf("text", "generic")) score -= 4
            if (score > bestScore) { bestScore = score; best = node }
        }
        return best?.takeIf { bestScore > 0 }
    }

    private fun score(query: String, element: GatewayElement): Double {
        val label = normalize(element.label)
        val semantic = normalize(element.semanticName)
        val resource = normalize(element.evidence.optString("resourceId").substringAfterLast('/'))
        val description = normalize(element.evidence.optString("contentDescription"))
        val corpus = "$label $semantic $resource $description ${normalize(element.role)}"
        if (label == query || semantic == query || resource == query || description == query) return 1.0
        if (label.contains(query) || semantic.contains(query) || resource.contains(query) || description.contains(query)) return 0.92
        val tokens = query.split(' ').filter { it.isNotBlank() }.distinct()
        val usable = tokens.filter { it.length >= 2 || (it.length == 1 && it[0].isLetterOrDigit()) }
        if (usable.isEmpty()) return 0.0
        val matched = usable.count(corpus::contains)
        if (matched == 0) return 0.0
        val ratio = matched.toDouble() / usable.size
        return (0.50 + ratio * 0.35 + if (element.source == "semantic") 0.05 else 0.0).coerceAtMost(0.89)
    }

    private fun normalize(value: String): String = value.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun semanticize(value: String): String = normalize(value).replace(' ', '_').take(100)

    private fun signature(control: PageControl): String {
        val selector = control.selector
        return buildList {
            selector.optString("resourceId").takeIf(String::isNotBlank)?.let { add("resource:$it") }
            selector.optString("text").takeIf(String::isNotBlank)?.let { add("text:${normalize(it)}") }
            selector.optString("contentDescription").takeIf(String::isNotBlank)?.let { add("desc:${normalize(it)}") }
            selector.optString("role").takeIf(String::isNotBlank)?.let { add("role:${it.lowercase(Locale.US)}") }
        }.joinToString("|")
    }

    private fun signature(node: JSONObject): String = buildList {
        node.optString("resourceId").takeIf(String::isNotBlank)?.let { add("resource:$it") }
        node.optString("text").takeIf { it.isNotBlank() && it != "<redacted>" }?.let { add("text:${normalize(it)}") }
        node.optString("contentDescription").takeIf { it.isNotBlank() && it != "<redacted>" }?.let { add("desc:${normalize(it)}") }
        node.optString("role").takeIf(String::isNotBlank)?.let { add("role:${it.lowercase(Locale.US)}") }
    }.joinToString("|")

    private fun supplementSelector(node: JSONObject, inheritedLabel: String = ""): JSONObject = JSONObject().apply {
        node.optString("resourceId").takeIf { it.isNotBlank() }?.let { put("resourceId", it) }
        node.optString("text").takeIf { it.isNotBlank() && it != "<redacted>" }?.let { put("text", it.take(160)) }
        node.optString("contentDescription").takeIf { it.isNotBlank() && it != "<redacted>" }?.let { put("contentDescription", it.take(160)) }
        inheritedLabel.takeIf { it.isNotBlank() }?.let { put("descendantText", it.take(160)) }
        node.optString("role").takeIf { it.isNotBlank() }?.let { put("role", it) }
        if (node.optBoolean("clickable")) put("clickable", true)
        if (node.optBoolean("editable")) put("editable", true)
        if (node.optBoolean("scrollable")) put("scrollable", true)
    }

    private fun descendantLabel(parent: JSONObject, nodes: JSONArray): String {
        val parentPath = parent.optString("path").trimEnd('/')
        if (parentPath.isBlank()) return ""
        val prefix = "$parentPath/"
        for (index in 0 until nodes.length()) {
            val candidate = nodes.optJSONObject(index) ?: continue
            if (!candidate.optBoolean("visibleToUser", true) || !candidate.optString("path").startsWith(prefix)) continue
            val label = candidate.optString("text").trim()
                .ifBlank { candidate.optString("contentDescription").trim() }
                .ifBlank { candidate.optString("resourceId").substringAfterLast('/').replace('_', ' ').trim() }
            if (label.isNotBlank() && label != "<redacted>") return label.take(160)
        }
        return ""
    }
}
