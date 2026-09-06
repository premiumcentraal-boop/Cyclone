package com.cyclone.mobile.runtime.session

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExecutionRequestScopeTest {
    @Test fun legacyAndExplicitForegroundStillResolve() {
        assertEquals(ExecutionContext.DEFAULT, ExecutionRequestScope.requireForeground(JSONObject()))
        assertEquals(ExecutionContext.DEFAULT, ExecutionRequestScope.requireForeground(JSONObject()
            .put("sessionId", "default-foreground").put("displayId", 0)))
    }
    @Test fun malformedOrBackgroundIdentityCannotEnterLegacyDispatch() {
        val requests = listOf(
            "{\"sessionId\":\"B\"}", "{\"sessionId\":\"B\",\"displayId\":0}",
            "{\"sessionId\":\"B\",\"displayId\":8}", "{\"displayId\":8}",
            "{\"sessionId\":\"\"}", "{\"sessionId\":null}", "{\"sessionId\":42}",
            "{\"displayId\":\"0\"}", "{\"displayId\":0.5}", "{\"displayId\":-1}",
            "{\"displayId\":4294967296}", "{\"executionContext\":null}",
            "{\"executionContext\":{\"sessionId\":\"B\",\"displayId\":8}}",
            "{\"sessionId\":\"default-foreground\",\"executionContext\":{\"sessionId\":\"B\",\"displayId\":8}}",
        )
        var dispatched = 0
        requests.forEach { json ->
            try { ExecutionRequestScope.requireForeground(JSONObject(json)); dispatched++ }
            catch (_: SessionIdentityException) { }
        }
        assertEquals("Invalid scopes must never reach even read-only foreground side effects", 0, dispatched)
    }
    @Test fun envelopeIdentitySurvivesNormalizationAndConflictsAreRejected() {
        val envelope = JSONObject().put("sessionId", "B").put("displayId", 8)
        val scoped = ExecutionRequestScope.merge(envelope, JSONObject().put("x", 15))
        assertEquals(ExecutionContext("B", 8), ExecutionRequestScope.read(scoped))
        assertEquals(15, scoped.getInt("x"))
        assertThrows(SessionIdentityException::class.java) {
            ExecutionRequestScope.merge(envelope, JSONObject().put("sessionId", "default-foreground"))
        }
    }
}
