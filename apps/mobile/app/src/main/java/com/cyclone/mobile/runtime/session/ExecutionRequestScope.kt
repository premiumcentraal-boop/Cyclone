package com.cyclone.mobile.runtime.session

import org.json.JSONObject

/** Transport identity is never inferred from the active Android window. */
object ExecutionRequestScope {
    fun read(params: JSONObject): ExecutionContext {
        if (params.has("executionContext") && params.opt("executionContext") !is JSONObject) {
            throw SessionIdentityException("Invalid executionContext")
        }
        val nested = params.optJSONObject("executionContext")
        val session = string(params, "sessionId")
        val display = integer(params, "displayId")
        val observation = string(params, "observationId")
        val frame = positiveLong(params, "frameId")
        val nestedSession = nested?.let { string(it, "sessionId") }
        val nestedDisplay = nested?.let { integer(it, "displayId") }
        val nestedObservation = nested?.let { string(it, "observationId") }
        val nestedFrame = nested?.let { positiveLong(it, "frameId") }
        requireSame("execution session identities", session, nestedSession)
        requireSame("execution display identities", display, nestedDisplay)
        requireSame("execution observation identities", observation, nestedObservation)
        requireSame("execution frame identities", frame, nestedFrame)
        val id = session ?: nestedSession ?: ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID
        val targetDisplay = display ?: nestedDisplay
        if (id != ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID && targetDisplay == null) {
            throw SessionIdentityException("An explicit background session requires its displayId")
        }
        if (id == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID && targetDisplay != null && targetDisplay != 0) {
            throw SessionIdentityException("Default foreground session must use display 0")
        }
        return ExecutionContext(
            sessionId = id,
            displayId = targetDisplay ?: 0,
            observationId = observation ?: nestedObservation,
            frameId = frame ?: nestedFrame,
        )
    }

    /** Used at legacy boundaries until a complete display-scoped implementation owns the call. */
    fun requireForeground(params: JSONObject): ExecutionContext = read(params).also {
        if (it.sessionId != ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID || it.displayId != 0) {
            throw SessionIdentityException("BACKGROUND_MODE_UNAVAILABLE: this operation cannot address the requested workspace")
        }
    }

    /** Carry outer identity through adapters instead of dropping it while normalizing params. */
    fun merge(envelope: JSONObject, params: JSONObject): JSONObject {
        val out = JSONObject(params.toString())
        for (key in listOf("sessionId", "displayId", "observationId", "frameId", "executionContext")) {
            if (!envelope.has(key)) continue
            if (out.has(key) && out.get(key).toString() != envelope.get(key).toString()) {
                throw SessionIdentityException("Conflicting $key in request envelope and params")
            }
            out.put(key, envelope.get(key))
        }
        // Force one parse here so callers cannot accidentally forward an internally conflicting
        // nested/outer identity to a side-effecting layer.
        read(out)
        return out
    }

    private fun <T> requireSame(label: String, outer: T?, nested: T?) {
        if (outer != null && nested != null && outer != nested) {
            throw SessionIdentityException("Conflicting $label")
        }
    }

    private fun string(json: JSONObject, key: String): String? {
        if (!json.has(key)) return null
        val value = json.opt(key)
        if (value !is String || value.isBlank() || value != value.trim()) {
            throw SessionIdentityException("Invalid $key")
        }
        return value
    }

    private fun integer(json: JSONObject, key: String): Int? {
        if (!json.has(key)) return null
        val value = json.opt(key)
        if (value !is Number || value.toDouble() != value.toInt().toDouble() || value.toInt() < 0) {
            throw SessionIdentityException("Invalid $key")
        }
        return value.toInt()
    }

    private fun positiveLong(json: JSONObject, key: String): Long? {
        if (!json.has(key)) return null
        val value = json.opt(key)
        if (value !is Number || value.toDouble() != value.toLong().toDouble() || value.toLong() <= 0L) {
            throw SessionIdentityException("Invalid $key")
        }
        return value.toLong()
    }
}
