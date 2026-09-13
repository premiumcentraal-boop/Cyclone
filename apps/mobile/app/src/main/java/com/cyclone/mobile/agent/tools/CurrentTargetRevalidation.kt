package com.cyclone.mobile.agent.tools

import com.cyclone.mobile.gateway.GatewayObservation

enum class TargetDrift { MATCHED, MOVED_SAME_IDENTITY, OCCLUDED, DISAPPEARED, AMBIGUOUS, STALE_FRAME, SCOPE_MISMATCH }
data class TargetRevalidation(val status: TargetDrift, val elementId: String? = null)

/** Identity-based re-resolution; the old rectangle never authorizes a new target. */
internal object CurrentTargetRevalidation {
    fun resolve(before: GatewayObservation, after: GatewayObservation, id: String): TargetRevalidation {
        if (before.execution.sessionId != after.execution.sessionId || before.execution.displayId != after.execution.displayId ||
            before.payload.optLong("executionGeneration") != after.payload.optLong("executionGeneration") || before.page.packageName != after.page.packageName ||
            before.payload.optString("activity") != after.payload.optString("activity")) return TargetRevalidation(TargetDrift.SCOPE_MISMATCH)
        val old = before.elements[id] ?: return TargetRevalidation(TargetDrift.STALE_FRAME)
        val resource = old.evidence.optString("resourceId")
        if (old.label.isBlank() || old.label == "<redacted>") return TargetRevalidation(TargetDrift.AMBIGUOUS)
        val matches = after.elements.values.filter {
            it.role == old.role && it.label == old.label && it.semanticName == old.semanticName &&
                (resource.isBlank() || it.evidence.optString("resourceId") == resource)
        }
        if (matches.isEmpty()) return TargetRevalidation(TargetDrift.DISAPPEARED)
        if (matches.size != 1) return TargetRevalidation(TargetDrift.AMBIGUOUS)
        val target = matches.single()
        if (!target.evidence.optBoolean("enabled", true) || !target.evidence.optBoolean("visibleToUser", true))
            return TargetRevalidation(TargetDrift.OCCLUDED)
        val rect = target.evidence.optJSONObject("bounds") ?: return TargetRevalidation(TargetDrift.AMBIGUOUS)
        if (rect.optInt("right") <= rect.optInt("left") || rect.optInt("bottom") <= rect.optInt("top"))
            return TargetRevalidation(TargetDrift.OCCLUDED)
        val overlaps = after.elements.values.any { other ->
            if (other.id == target.id || !other.evidence.optBoolean("visibleToUser", true) ||
                !other.evidence.optBoolean("enabled", true) || !other.evidence.optBoolean("clickable")) return@any false
            val b = other.evidence.optJSONObject("bounds") ?: return@any false
            b.optInt("left") < rect.optInt("right") && b.optInt("right") > rect.optInt("left") &&
                b.optInt("top") < rect.optInt("bottom") && b.optInt("bottom") > rect.optInt("top")
        }
        if (overlaps) return TargetRevalidation(TargetDrift.AMBIGUOUS)
        return TargetRevalidation(if (old.evidence.optJSONObject("bounds")?.toString() == rect.toString())
            TargetDrift.MATCHED else TargetDrift.MOVED_SAME_IDENTITY, target.id)
    }
}
