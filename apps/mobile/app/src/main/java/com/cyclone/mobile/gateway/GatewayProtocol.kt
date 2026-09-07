package com.cyclone.mobile.gateway

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

internal data class GatewayRequest(
    val id: String,
    val op: String,
    val args: JSONObject,
    val auth: String,
)

internal class GatewayProtocolException(
    val code: String,
    override val message: String,
    val requestId: String = "",
    val details: Any? = null,
) : IllegalArgumentException(message)

internal object GatewayProtocol {
    // Session and Layer 2 workspace operations are additive extensions. Keep V3.3 transport wire-compatible.
    const val VERSION = "3.3"
    const val SOCKET_NAME = "cyclone_gateway"
    const val DEFAULT_FORWARD_PORT = 8766
    const val MAX_LINE_BYTES = 1024 * 1024

    val unauthenticatedOperations = setOf(
        "trust.negotiate",
        "trust.begin",
        "trust.complete",
        "trust.session.begin",
        "trust.session.complete",
        "pair.begin",
        "pair.complete",
        "pair.qr.complete",
    )

    val legacyReadOnlyOperations = setOf(
        "bridge.status",
        "session.list",
        "workspace.list",
        "workspace.lock",
        "observe.semantic",
        "observe.page_debug",
        "capture.screenshot",
        "ui.search",
        "ui.element",
        "app_graph.get",
        "brain.recall",
        "teach.status",
        "debug.snapshot",
    )

    val operations = linkedSetOf(
        "trust.negotiate",
        "trust.begin",
        "trust.complete",
        "trust.session.begin",
        "trust.session.complete",
        "trust.rotate",
        "trust.revoke",
        "bridge.status",
        "session.list",
        "session.start",
        "session.status",
        "session.pause",
        "session.continue",
        "session.resume",
        "session.handoff",
        "session.stop",
        "session.snapshot",
        "workspace.list",
        "workspace.register",
        "workspace.switch",
        "workspace.lock",
        "observe.semantic",
        "observe.page_debug",
        "capture.screenshot",
        "ui.search",
        "ui.element",
        "app_graph.get",
        "brain.recall",
        "action.execute",
        "teach.start",
        "teach.status",
        "teach.stop",
        "debug.snapshot",
        "pair.begin",
        "pair.complete",
        "pair.qr.complete",
        "pair.revoke",
        "manual.execute",
        "clipboard.get",
        "clipboard.set",
        "skill.compile",
        "skill.run",
        "skill.match",
    )

    fun parse(line: String): GatewayRequest {
        val json = try {
            JSONObject(line)
        } catch (error: Exception) {
            throw GatewayProtocolException("INVALID_JSON", "Request must be one UTF-8 JSON object per line")
        }
        val id = json.optString("id").trim()
        if (id.isBlank()) throw GatewayProtocolException("INVALID_REQUEST", "id is required")
        val op = json.optString("op").trim()
        if (op.isBlank()) throw GatewayProtocolException("INVALID_REQUEST", "op is required", id)
        val auth = json.optString("auth")
        if (auth.isBlank() && op !in unauthenticatedOperations) {
            throw GatewayProtocolException("AUTH_REQUIRED", "auth is required", id)
        }
        val argsValue = json.opt("args")
        if (argsValue != null && argsValue !== JSONObject.NULL && argsValue !is JSONObject) {
            throw GatewayProtocolException("INVALID_REQUEST", "args must be a JSON object", id)
        }
        return GatewayRequest(id, op, argsValue as? JSONObject ?: JSONObject(), auth)
    }

    fun requireKnownOperation(op: String, id: String = "") {
        if (op !in operations) throw GatewayProtocolException("UNKNOWN_OPERATION", "Unsupported gateway operation: $op", id)
    }

    fun success(id: String, result: Any?): JSONObject = JSONObject()
        .put("id", id)
        .put("ok", true)
        .put("result", result ?: JSONObject.NULL)
        .put("error", JSONObject.NULL)

    fun error(id: String, code: String, message: String, details: Any? = null): JSONObject = JSONObject()
        .put("id", id)
        .put("ok", false)
        .put("result", JSONObject.NULL)
        .put("error", JSONObject()
            .put("code", code)
            .put("message", message.take(600))
            .put("details", details ?: JSONObject.NULL))
}

internal object GatewayAuth {
    fun matches(expected: String?, supplied: String?): Boolean {
        if (expected.isNullOrBlank() || supplied.isNullOrBlank()) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), supplied.toByteArray(Charsets.UTF_8))
    }
}

object GatewayLineReader {
    fun readUtf8Line(input: InputStream, maxBytes: Int = GatewayProtocol.MAX_LINE_BYTES): String? {
        require(maxBytes > 0)
        val out = ByteArrayOutputStream(minOf(4096, maxBytes))
        while (true) {
            val next = input.read()
            if (next == -1) return if (out.size() == 0) null else out.toString(Charsets.UTF_8.name())
            if (next == '\n'.code) return out.toString(Charsets.UTF_8.name()).trimEnd('\r')
            if (out.size() >= maxBytes) throw GatewayProtocolException("REQUEST_TOO_LARGE", "Gateway request exceeds $maxBytes bytes")
            out.write(next)
        }
    }
}
