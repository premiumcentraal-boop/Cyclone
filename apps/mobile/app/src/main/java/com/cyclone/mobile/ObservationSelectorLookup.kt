package com.cyclone.mobile

import com.cyclone.mobile.gateway.GatewayObservationStore
import org.json.JSONObject

object ObservationSelectorLookup {
    fun map(elementIdOrRef: String): ElementSelector? {
        val observation = GatewayObservationStore.current() ?: return ElementSelector(elementId = elementIdOrRef)
        val direct = observation.elements[elementIdOrRef]
        val evidence = direct?.evidence ?: observation.elements.values.firstOrNull { element ->
            element.evidence.optString("ref") == elementIdOrRef
        }?.evidence
        if (evidence == null) return ElementSelector(elementId = elementIdOrRef)
        return fromEvidence(evidence, elementIdOrRef)
    }

    fun fromEvidence(evidence: JSONObject, elementId: String): ElementSelector {
        val nested = evidence.optJSONObject("selector") ?: JSONObject()
        fun pick(vararg keys: String): String? {
            for (key in keys) {
                evidence.optString(key).takeIf { it.isNotBlank() }?.let { return it }
                nested.optString(key).takeIf { it.isNotBlank() }?.let { return it }
            }
            return null
        }
        return ElementSelector(
            resourceId = pick("resourceId"),
            text = pick("text", "label"),
            contentDescription = pick("contentDescription"),
            role = pick("role"),
            descendantText = pick("descendantText"),
            requireClickable = if (evidence.optBoolean("clickable") || nested.optBoolean("clickable")) true else null,
            requireEditable = if (evidence.optBoolean("editable") || nested.optBoolean("editable")) true else null,
            elementId = elementId,
            path = pick("path"),
        )
    }
}
