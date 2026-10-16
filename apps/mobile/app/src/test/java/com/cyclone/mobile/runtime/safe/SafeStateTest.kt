package com.cyclone.mobile.runtime.safe

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SafeStateTest {
    @Test fun firstStartCorruptionAndPartialRestoreKeepTheGateClosed() {
        assertTrue(SafeState.needsGate(JSONObject()))
        for (flag in listOf("corrupt", "restorePending")) assertTrue(SafeState.needsGate(JSONObject().put("startupChecked", true).put(flag, true)))
        assertFalse(SafeState.needsGate(JSONObject().put("startupChecked", true)))
    }
    @Test fun onlyAnInterruptedRestorePinsTheSourceSequence() {
        val state = JSONObject().put("restoreSequence", 1)
        assertNull(SafeState.pinnedSequence(state))
        state.put("restorePending", true)
        assertEquals(1L, SafeState.pinnedSequence(state))
    }
    @Test fun successfulRecoveryReplacesDisposableWrappingAndClearsStaleFailureState() {
        val previous = JSONObject().put("folder", "owner-selected-uri").put("corrupt", true).put("restorePending", true)
            .put("restoreSequence", 1).put("pcRestoreKey", JSONObject()).put("pcRestore", JSONObject())
        val wrap = JSONObject().put("fixture", "sealed")
        val result = SafeState.restored(previous, JSONObject().put("phoneId", "phone").put("seq", 1).put("wraps", JSONObject()), wrap, true)
        assertFalse(SafeState.needsGate(result))
        for (name in listOf("corrupt", "restoreSequence", "pcRestoreKey", "pcRestore")) assertFalse(result.has(name))
        assertEquals("owner-selected-uri", result.getString("folder"))
        assertEquals(wrap.toString(), result.getJSONObject("localWrap").toString())
        assertTrue(result.getBoolean("folderOnly"))
        assertTrue(previous.getBoolean("corrupt"))
    }
}
