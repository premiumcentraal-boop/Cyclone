package com.cyclone.mobile.gateway

import android.content.Context
import com.cyclone.mobile.runtime.workspace.CycloneWorkspace
import com.cyclone.mobile.runtime.workspace.WorkspaceRuntime
import org.json.JSONArray
import org.json.JSONObject

internal object GatewayWorkspaceAdapter {
    fun list(context: Context): JSONObject = JSONObject()
        .put("workspaces", JSONArray().also { out -> WorkspaceRuntime.list(context).forEach { out.put(it.toJson()) } })
        .put("mutateLockHolder", WorkspaceRuntime.mutateLockHolder(context) ?: JSONObject.NULL)
        .put("layer", 2)
        .put("parallelMutation", false)

    fun register(context: Context, args: JSONObject): JSONObject {
        val id = args.optString("id").trim().takeIf { it.isNotBlank() }
        val label = args.optString("label").trim()
        val appPackage = args.optString("appPackage", args.optString("package")).trim()
        val userId = args.optInt("androidUserId", args.optInt("userId", 0))
        if (label.isBlank() || label.length > 80) {
            throw GatewayProtocolException("PROTOCOL_MISMATCH", "workspace label is required and must be <= 80 characters")
        }
        if (!PACKAGE.matches(appPackage)) {
            throw GatewayProtocolException("PROTOCOL_MISMATCH", "workspace appPackage is invalid")
        }
        if (userId < 0) throw GatewayProtocolException("PROTOCOL_MISMATCH", "androidUserId must be non-negative")
        val workspace = if (id == null) {
            WorkspaceRuntime.register(context, label, appPackage, userId)
        } else {
            if (!ID.matches(id)) throw GatewayProtocolException("PROTOCOL_MISMATCH", "workspace id is invalid")
            WorkspaceRuntime.register(context, label, appPackage, userId, id)
        }
        return JSONObject().put("workspace", workspace.toJson())
    }

    fun switch(context: Context, args: JSONObject): JSONObject {
        val id = args.optString("id", args.optString("workspaceId")).trim()
        if (!ID.matches(id)) throw GatewayProtocolException("PROTOCOL_MISMATCH", "workspace id is required")
        val result = WorkspaceRuntime.switch(context, id)
        if (!result.ok) {
            throw GatewayProtocolException(result.code, result.message, details = result.workspace?.toJson())
        }
        return JSONObject()
            .put("switched", true)
            .put("workspace", result.workspace?.toJson() ?: JSONObject.NULL)
            .put("mutateLockHolder", WorkspaceRuntime.mutateLockHolder(context) ?: JSONObject.NULL)
    }

    fun lock(context: Context, args: JSONObject): JSONObject {
        val action = args.optString("action", "status").lowercase()
        val affected = when (action) {
            "status" -> null
            "release" -> WorkspaceRuntime.releaseMutateLock(context)
            "pause" -> WorkspaceRuntime.pauseMutateLockHolder(context)
            else -> throw GatewayProtocolException("PROTOCOL_MISMATCH", "workspace lock action must be status, release or pause")
        }
        return JSONObject()
            .put("action", action)
            .put("affectedWorkspaceId", affected ?: JSONObject.NULL)
            .put("mutateLockHolder", WorkspaceRuntime.mutateLockHolder(context) ?: JSONObject.NULL)
    }

    private fun CycloneWorkspace.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("appPackage", appPackage)
        .put("androidUserId", androidUserId)
        .put("displayId", displayId)
        .put("state", state.name.lowercase())

    private val ID = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,79}$")
    private val PACKAGE = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
}
