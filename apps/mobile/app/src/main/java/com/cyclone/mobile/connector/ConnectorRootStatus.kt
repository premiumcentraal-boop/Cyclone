package com.cyclone.mobile.connector

import com.cyclone.mobile.runtime.workspaces.ProfileInventory
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 57 P3 (alpha.121), contract minor 2: `root.status.v1` for connectors with `device.root.read` (Cloak handoff
 * CC6). Built only from what Cyclone last saw on a switch into each profile and its own profile-room notes: no
 * command, path, version or package name. Pure.
 */
object ConnectorRootStatus {
    fun build(inventories: List<ProfileInventory>, room: Pair<Int, Boolean>?): JSONObject {
        val newest = inventories.filter { it.rootManager != null }.maxByOrNull { it.atMs }
        return JSONObject().put("version", 1)
            .put("rootManager", newest?.rootManager?.lowercase()?.takeIf { it in setOf("magisk", "kernelsu", "apatch") } ?: JSONObject.NULL)
            .put("profileRoom", room?.let { (limit, raised) -> JSONObject().put("limit", limit).put("raisedByCyclone", raised) } ?: JSONObject.NULL)
            .put("profiles", JSONArray(inventories.sortedBy { it.profileId }.map {
                JSONObject().put("id", it.profileId).put("rootProven", it.rootProven ?: JSONObject.NULL)
                    .put("checkedAt", if (it.rootProven == null) JSONObject.NULL else it.atMs)
            }))
    }
}
