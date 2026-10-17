package com.cyclone.mobile.runtime.safe

import org.json.JSONObject

/** Only recovery bookkeeping; never profile contents or raw key material. */
internal object SafeState {
    fun needsGate(state: JSONObject): Boolean = !state.optBoolean("startupChecked") ||
        state.optBoolean("restorePending") || state.optBoolean("corrupt")
    fun pinnedSequence(state: JSONObject): Long? = state.optLong("restoreSequence")
        .takeIf { state.optBoolean("restorePending") && it > 0 }
    fun restored(previous: JSONObject, header: JSONObject, localWrap: JSONObject, folderOnly: Boolean): JSONObject =
        JSONObject(previous.toString()).apply {
            put("enabled", true); put("startupChecked", true); put("restorePending", false)
            put("folderOnly", folderOnly); put("rootPending", false)
            put("phoneId", header.getString("phoneId")); put("wraps", header.getJSONObject("wraps"))
            put("localWrap", localWrap); put("restoredSequence", header.getLong("seq"))
            for (name in listOf("restoreSequence", "pcRestoreKey", "pcRestore", "corrupt")) remove(name)
        }
}
