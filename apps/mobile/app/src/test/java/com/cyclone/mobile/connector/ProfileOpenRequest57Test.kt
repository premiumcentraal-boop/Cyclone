package com.cyclone.mobile.connector

import com.cyclone.mobile.runtime.workspaces.CycloneProfileRecord
import com.cyclone.mobile.runtime.workspaces.ProfileOpenRequests
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 57 (alpha.122, Cloak handoff CC7): a connector may ask the owner to open a profile; only the owner's tap switches. */
class ProfileOpenRequest57Test {
    private val cert = "a".repeat(64)
    private val b = CycloneProfileRecord("Cyclone_aaaaaaaaaaaaaaaa", "Profile B", 10, 0, true, setOf("com.example.social"), "READY", true)
    private val c = b.copy(id = "Cyclone_bbbbbbbbbbbbbbbb", label = "Profile C", androidUserId = 11)
    private val trashed = b.copy(id = "Cyclone_cccccccccccccccc", label = "Old", androidUserId = 12, removedAtMs = 5L)

    private inner class Backend(var answer: String? = null, val current: String? = b.id) : ConnectorBackend {
        val asked = mutableListOf<List<String>>()
        override fun approvals() = listOf(ConnectorApproval("cyclone-cloak", "com.cyclone.cloak", cert,
            setOf(ConnectorScope.PROFILES_READ, ConnectorScope.PROFILES_OPEN_REQUEST), "Cyclone Cloak", 1L))
        override fun profiles() = listOf(b, c, trashed)
        override fun setExt(profileId: String, connectorId: String, json: String?) = Unit
        override fun entries(connectorId: String) = emptyList<ConnectorEntry>()
        override fun setEntries(connectorId: String, entries: List<ConnectorEntry>) = Unit
        override fun events(since: Long, now: Long) = JSONObject()
        override fun now() = 1L
        override fun currentProfile() = current
        override fun requestOpen(connectorId: String, connectorLabel: String, profileId: String, profileLabel: String): String? {
            asked += listOf(connectorId, connectorLabel, profileId, profileLabel)
            return answer
        }
    }

    private fun caller(scopes: String = "profiles.read profiles.open.request") = ConnectorCaller(10_123, "com.cyclone.cloak", listOf(cert),
        ConnectorManifest.parse("com.cyclone.cloak", mapOf("contract" to "cyclone.connector/1.3", "id" to "cyclone-cloak",
            "label" to "Cyclone Cloak", "scopes" to scopes)))

    private fun ask(backend: Backend, profileId: Any?, scopes: String = "profiles.read profiles.open.request"): JSONObject =
        JSONObject(ConnectorCore(backend, ConnectorRateLimiter(1_000)).handle(caller(scopes),
            JSONObject().put("method", "profiles.open.request.v1").put("args", JSONObject().put("profileId", profileId ?: JSONObject.NULL)).toString()))

    private fun code(answer: JSONObject) = answer.optJSONObject("error")?.optString("code")

    @Test fun `a request reaches Cyclone's own question, named for the profile`() {
        val backend = Backend()
        val answer = ask(backend, c.id)
        assertTrue(answer.getBoolean("ok"))
        assertEquals(JSONObject().put("version", 1).put("requested", true).toString(), answer.getJSONObject("result").toString())
        assertEquals(listOf("cyclone-cloak", "Cyclone Cloak", c.id, "Profile C"), backend.asked.single())
        ask(backend, "owner")
        assertEquals(listOf("cyclone-cloak", "Cyclone Cloak", "owner", "Main"), backend.asked.last())
    }

    @Test fun `only ready profiles that aren't in front, and only with its own scope`() {
        val backend = Backend()
        assertEquals("NO_SUCH_PROFILE", code(ask(backend, trashed.id)))
        assertEquals("NO_SUCH_PROFILE", code(ask(backend, "Cyclone_dddddddddddddddd")))
        assertEquals("ALREADY_OPEN", code(ask(backend, b.id)))
        assertEquals("BAD_REQUEST", code(ask(backend, null)))
        assertEquals("SCOPE_NOT_GRANTED", code(ask(backend, c.id, scopes = "profiles.read")))
        assertTrue(backend.asked.isEmpty())
        assertEquals("BUSY", code(ask(Backend(answer = "BUSY"), c.id)))
        assertEquals("RATE_LIMITED", code(ask(Backend(answer = "RATE_LIMITED"), c.id)))
        assertEquals(3, ConnectorContract.MINOR)
    }

    @Test fun `one question waits at a time, each connector asks at most every 10 seconds, and it expires`() {
        var now = 0L
        val gate = ProfileOpenRequests.Gate { now }
        fun request(connector: String, nonce: String) = ProfileOpenRequests.Request(nonce, connector, "Cloak", c.id, "Profile C", 0L)
        assertNull(gate.offer(request("cyclone-cloak", "n1")))
        assertEquals("n1", gate.peek("n1")?.nonce)
        assertNull(gate.peek("other"))
        now = 5_000
        assertEquals("RATE_LIMITED", gate.offer(request("cyclone-cloak", "n2")))
        assertEquals("BUSY", gate.offer(request("acme", "n3")))
        now = ProfileOpenRequests.EXPIRES_MS + 1
        assertNull(gate.peek("n1"))
        assertNull(gate.offer(request("cyclone-cloak", "n4")))
        gate.close("n4")
        assertNull(gate.peek("n4"))
    }

    @Test fun `Cloak binds in every profile but never in Main`() {
        val owner = JSONObject().put("profileId", "owner").put("androidUserId", 0).put("packageName", "com.example.social")
        val refused = runCatching { ProfileConfigKey.parse(owner, listOf(b, c)) }.exceptionOrNull() as ConnectorException
        assertEquals("NO_SUCH_PROFILE", refused.code)
        val inB = JSONObject().put("profileId", b.id).put("androidUserId", 10).put("packageName", "com.example.social")
        assertEquals(b.id, ProfileConfigKey.parse(inB, listOf(b, c)).profileId)
    }
}
