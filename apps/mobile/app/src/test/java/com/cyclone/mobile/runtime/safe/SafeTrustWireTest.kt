package com.cyclone.mobile.runtime.safe

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SafeTrustWireTest {
    private fun row(id: String, phone: String = "new", revoked: Long? = null) = JSONObject()
        .put("trustId", id).put("phoneId", phone).put("revokedAtMs", revoked ?: JSONObject.NULL)
    @Test fun savedAuthorizationIsAlwaysRevoked() {
        val restored = SafeTrustWire.restore(JSONArray().put(row("old", "old-phone")), JSONArray(), "new", 99)
        assertEquals(99L, restored.getJSONObject(0).getLong("revokedAtMs"))
    }
    @Test fun onlyFreshExplicitPairingSurvives() {
        val current = JSONArray().put(row("fresh")).put(row("revoked", revoked = 1)).put(row("foreign", "another-phone"))
        val restored = SafeTrustWire.restore(JSONArray().put(row("old", "old-phone")), current, "new", 99)
        assertEquals(2, restored.length())
        assertTrue(restored.getJSONObject(1).isNull("revokedAtMs"))
        assertEquals("fresh", restored.getJSONObject(1).getString("trustId"))
    }
    @Test fun retryDoesNotDuplicateOrRevokeTheFreshPairing() {
        val fresh = JSONArray().put(row("fresh"))
        val once = SafeTrustWire.restore(JSONArray().put(row("old", "old-phone")), fresh, "new", 99)
        val twice = SafeTrustWire.restore(once, fresh, "new", 100)
        assertEquals(2, twice.length())
        assertEquals(1, (0 until twice.length()).count { twice.getJSONObject(it).isNull("revokedAtMs") })
    }
}
