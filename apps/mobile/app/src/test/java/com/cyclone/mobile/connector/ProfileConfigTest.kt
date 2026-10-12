package com.cyclone.mobile.connector

import com.cyclone.mobile.runtime.workspaces.CycloneProfileRecord
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProfileConfigTest {
    private val id = "Cyclone_0123456789abcdef"
    private val pkg = "com.acme.target"
    private val record = CycloneProfileRecord(id, "Work", 10, 0, true, setOf(pkg), "READY", true)
    private fun args() = JSONObject().put("profileId", id).put("androidUserId", 10).put("packageName", pkg)
    private fun refused(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: ConnectorException) { assertEquals(code, e.code) }
    }
    @Test fun tupleChecksUserPackageReadinessAndTrash() {
        assertEquals(ProfileConfigKey(id, 10, pkg), ProfileConfigKey.parse(args(), listOf(record)))
        refused("NO_SUCH_PROFILE") { ProfileConfigKey.parse(args().put("androidUserId", 11), listOf(record)) }
        refused("BAD_REQUEST") { ProfileConfigKey.parse(args().put("androidUserId", "10"), listOf(record)) }
        refused("BAD_REQUEST") { ProfileConfigKey.parse(args().put("packageName", "../../x"), listOf(record)) }
        refused("BAD_REQUEST") { ProfileConfigKey.parse(args().put("packageName", "com.other.app"), listOf(record)) }
        refused("NO_SUCH_PROFILE") { ProfileConfigKey.parse(args(), listOf(record.copy(ready = false))) }
        refused("NO_SUCH_PROFILE") { ProfileConfigKey.parse(args(), listOf(record.copy(removedAtMs = 1))) }
    }
    @Test fun storageKeysAreStableAndSeparateEveryTupleField() {
        val key = ProfileConfigKey(id, 10, pkg)
        assertEquals(key.storageKey(), key.copy().storageKey())
        assertTrue(key.storageKey().matches(Regex("[a-f0-9]{64}")))
        assertEquals(4, setOf(key.storageKey(), key.copy(androidUserId = 11).storageKey(),
            key.copy(profileId = "Cyclone_fedcba9876543210").storageKey(), key.copy(packageName = "com.other.app").storageKey()).size)
    }
    @Test fun contentsAreOpaqueAndLimitsAreUtf8Bytes() {
        val value = JSONObject().put("automation", true).put("token", "not interpreted")
        assertEquals(value.toString(), ProfileConfigRules.value(value))
        assertNull(ProfileConfigRules.value(JSONObject.NULL))
        refused("BAD_REQUEST") { ProfileConfigRules.value("{}") }
        refused("BAD_REQUEST") { ProfileConfigRules.value(JSONObject().put("x", "🦊".repeat(1024))) }
        assertNotNull(ProfileConfigRules.value(JSONObject().put("x", "a".repeat(4088))))
        refused("BAD_REQUEST") { ProfileConfigRules.value(JSONObject().put("x", "a".repeat(4089))) }
    }
    @Test fun repliesPermitNoReferenceAndIgnoreFutureFields() {
        assertTrue(ProfileConfigRules.reply("""{"version":1,"future":true}""").isNull("configRef"))
        assertEquals("ready", ProfileConfigRules.reply("""{"version":1}""").getString("state"))
        refused("UNSUPPORTED_CONTRACT") { ProfileConfigRules.reply("""{"version":2}""") }
        refused("BAD_REQUEST") { ProfileConfigRules.reply("""{"version":1,"state":"execute"}""") }
        refused("BAD_REQUEST") { ProfileConfigRules.reply(JSONObject().put("version", 1).put("configRef", "é".repeat(513)).toString()) }
    }
    @Test fun dispatchHintsAreIsolatedAndSwitchWins() {
        val tracker = ProfileLaunchTracker()
        val key = ProfileConfigKey(id, 10, pkg)
        assertEquals("cold_start", tracker.next(key))
        assertEquals("relaunch", tracker.next(key))
        assertEquals("cold_start", tracker.next(key.copy(androidUserId = 11)))
        tracker.switched(10)
        assertEquals("profile_switch", tracker.next(key))
        assertEquals("relaunch", tracker.next(key))
    }

    @Test fun callbackRepliesRejectSpoofingDuplicatesAndExpiration() {
        var now = 0L
        val gate = ProfileReplyGate(100_123, 250) { now }
        assertFalse(gate.accept(123))
        now = 249
        assertTrue(gate.accept(100_123))
        assertFalse(gate.accept(100_123))
        val late = ProfileReplyGate(100_123, 250) { now }
        now = 250
        assertFalse(late.accept(100_123))
    }
    @Test fun deeplyNestedRequestsAreRefusedBeforeParsing() {
        refused("BAD_REQUEST") { ProfileConfigRules.checkNesting("[".repeat(33) + "0" + "]".repeat(33)) }
        ProfileConfigRules.checkNesting(JSONObject().put("opaque", "[".repeat(100)).toString())
    }

    @Test fun publishedCallbackVectorsMatchRuntime() {
        val relative = "tools/cyclone-connector-sdk/schemas/startup-vectors.json"
        val start = java.io.File(System.getProperty("user.dir"))
        val file = generateSequence(start) { it.parentFile }.map { java.io.File(it, relative) }.first { it.isFile }
        val vectors = JSONObject(file.readText()).getJSONArray("replies")
        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            val response = vector.getJSONObject("response").toString()
            if (vector.has("error")) refused(vector.getString("error")) { ProfileConfigRules.reply(response) }
            else {
                val actual = ProfileConfigRules.reply(response)
                val expected = vector.getJSONObject("result")
                assertEquals(vector.getString("name"), expected.getInt("version"), actual.getInt("version"))
                assertEquals(vector.getString("name"), expected.get("configRef"), actual.get("configRef"))
                assertEquals(vector.getString("name"), expected.getString("state"), actual.getString("state"))
            }
        }
    }

    private inner class Backend : ConnectorBackend {
        val values = HashMap<Triple<String, Int, ProfileConfigKey>, String>()
        val m = ConnectorManifest.parse("com.acme.plugin", mapOf("contract" to "cyclone.connector/1", "id" to "acme-plugin", "label" to "Plugin", "scopes" to "profiles.config profiles.startup"))
        var approved = m.scopes
        override fun approvals() = listOf(ConnectorApproval(m.id, m.packageName, "cert", approved, m.label, 1))
        override fun profiles() = listOf(record)
        override fun setExt(profileId: String, connectorId: String, json: String?) = Unit
        override fun entries(connectorId: String) = emptyList<ConnectorEntry>()
        override fun setEntries(connectorId: String, entries: List<ConnectorEntry>) = Unit
        override fun events(since: Long, now: Long) = JSONObject()
        override fun now() = 1L
        override fun config(connectorId: String, callerUser: Int, key: ProfileConfigKey) = values[Triple(connectorId, callerUser, key)]
        override fun setConfig(connectorId: String, callerUser: Int, key: ProfileConfigKey, json: String?) { if (json == null) values.remove(Triple(connectorId, callerUser, key)) else values[Triple(connectorId, callerUser, key)] = json }
    }
    @Test fun scopedStoreRoundTripClearStatusAndInstallationIsolation() {
        val b = Backend()
        val core = ConnectorCore(b, ConnectorRateLimiter(100))
        val caller = ConnectorCaller(10123, b.m.packageName, listOf("cert"), b.m)
        fun call(method: String, a: JSONObject = args(), c: ConnectorCaller = caller) = JSONObject(core.handle(c, JSONObject().put("method", method).put("args", a).toString()))
        assertTrue(call("config.get.v1").getJSONObject("result").isNull("value"))
        assertTrue(call("config.set.v1", args().put("value", JSONObject().put("automation", true))).getBoolean("ok"))
        assertTrue(call("config.get.v1").getJSONObject("result").getJSONObject("value").getBoolean("automation"))
        assertTrue(call("config.get.v1", c = caller.copy(uid = 1_010_123)).getJSONObject("result").isNull("value"))
        assertEquals("degraded", call("config.status.v1", args().put("state", "degraded")).getJSONObject("result").getString("state"))
        assertTrue(call("config.set.v1", args().put("value", JSONObject.NULL)).getJSONObject("result").isNull("value"))
        assertEquals("UNSUPPORTED_CONTRACT", call("startup.check.v1", JSONObject().put("version", 2)).getJSONObject("error").getString("code"))
        b.approved = emptySet()
        assertEquals("SCOPE_NOT_GRANTED", call("config.get.v1").getJSONObject("error").getString("code"))
        assertEquals("SCOPE_NOT_GRANTED", call("startup.check.v1", JSONObject().put("version", 1)).getJSONObject("error").getString("code"))
    }
}
