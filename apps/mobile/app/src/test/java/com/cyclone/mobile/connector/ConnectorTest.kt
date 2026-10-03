package com.cyclone.mobile.connector

import com.cyclone.mobile.runtime.workspaces.CycloneProfileRecord
import com.cyclone.mobile.runtime.workspaces.ProfileRegistryCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConnectorTest {
    private val cert = "a".repeat(64)
    private val oldCert = "b".repeat(64)

    private fun manifest(scopes: String = "profiles.read profiles.apps.read profiles.ext selector.contribute events.profiles") =
        ConnectorManifest.parse("com.acme.profiles", mapOf("contract" to "cyclone.connector/1", "id" to "acme-profiles",
            "label" to "Acme Profiles", "scopes" to scopes, "entryActivity" to ".Entry", "wakeReceiver" to ".Wake"))

    private fun record(id: String, label: String, removed: Long? = null, ext: Map<String, String> = emptyMap()) =
        CycloneProfileRecord(id, label, 10, 0, true, setOf("com.instagram.android"), "READY", true, removed, "🦊", 0xFF00FF00, ext)

    private fun expectError(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: ConnectorException) {
            assertEquals(code, e.code)
        }
    }

    // ---- the registry ------------------------------------------------------------------------------------------------

    @Test fun schemaOneRecordsReadAsTwoAndExtSurvivesEverySave() {
        val v1 = """[{"id":"Cyclone_0123456789abcdef","label":"Work","user":11,"parent":0,"secondary":true,"packages":["a.b"],"stage":"READY","ready":true,"removed_at":0,"emoji":"","color":0}]"""
        val read = ProfileRegistryCodec.decode(v1).single()
        assertEquals(emptyMap<String, String>(), read.ext)
        val withExt = read.copy(ext = mapOf("acme-profiles" to """{"tier":"gold"}"""))
        val again = ProfileRegistryCodec.decode(ProfileRegistryCodec.encode(listOf(withExt))).single()
        assertEquals(withExt, again)
        assertEquals("gold", JSONObject(again.ext.getValue("acme-profiles")).getString("tier"))
        val renamed = ProfileRegistryCodec.decode(ProfileRegistryCodec.encode(listOf(again.copy(label = "Office")))).single()
        assertEquals(again.ext, renamed.ext)
        assertEquals(2, ProfileRegistryCodec.SCHEMA_VERSION)
    }

    // ---- the manifest and who is calling -------------------------------------------------------------------------------

    @Test fun manifestsAreStrictAboutWhatMatters() {
        val m = manifest("profiles.read made.up events.profiles")
        assertEquals(setOf(ConnectorScope.PROFILES_READ, ConnectorScope.EVENTS_PROFILES), m.scopes)
        assertEquals(listOf("made.up"), m.unknownScopes)
        assertEquals("com.acme.profiles.Entry", ConnectorManifest.qualify(m.packageName, m.entryActivity!!))
        val base = mapOf("contract" to "cyclone.connector/1", "id" to "acme", "label" to "Acme")
        expectError("UNSUPPORTED_CONTRACT") { ConnectorManifest.parse("p", base + ("contract" to "cyclone.connector/2")) }
        expectError("BAD_MANIFEST") { ConnectorManifest.parse("p", base + ("contract" to "")) }
        expectError("BAD_MANIFEST") { ConnectorManifest.parse("p", base + ("id" to "Acme Profiles")) }
        expectError("BAD_MANIFEST") { ConnectorManifest.parse("p", base + ("label" to "x".repeat(41))) }
        expectError("BAD_MANIFEST") { ConnectorManifest.parse("p", base + ("entryActivity" to "not a class")) }
        assertEquals(3, ConnectorManifest.parse("p", base + ("contract" to "cyclone.connector/1.3")).contractMinor)
    }

    @Test fun approvalsFollowPackageIdAndSigningLineage() {
        val m = manifest()
        val approval = ConnectorApproval("acme-profiles", "com.acme.profiles", oldCert, setOf(ConnectorScope.PROFILES_READ), "Acme", 1)
        val rotated = ConnectorCaller(1, "com.acme.profiles", listOf(cert, oldCert), m)
        assertEquals(approval, ConnectorIdentity.approvalFor(rotated, listOf(approval)))
        assertNull(ConnectorIdentity.approvalFor(rotated.copy(certHistory = listOf(cert)), listOf(approval)))
        assertNull(ConnectorIdentity.approvalFor(rotated.copy(packageName = "com.evil"), listOf(approval)))
        assertEquals(setOf(ConnectorScope.PROFILES_READ), ConnectorIdentity.granted(rotated, approval))
        assertEquals(m.scopes - ConnectorScope.PROFILES_READ, ConnectorIdentity.pending(rotated, approval))
        val narrower = rotated.copy(manifest = manifest("events.profiles"))
        assertEquals(emptySet<ConnectorScope>(), ConnectorIdentity.granted(narrower, approval))
        assertEquals("AAAA AAAA AAAA AAAA AAAA AAAA AAAA AAAA", ConnectorIdentity.fingerprint(cert))
    }

    @Test fun theRateLimitRefillsOverTime() {
        var now = 0L
        val limiter = ConnectorRateLimiter(20) { now }
        repeat(20) { assertTrue(limiter.allow(7)) }
        assertFalse(limiter.allow(7))
        assertTrue("another app has its own bucket", limiter.allow(8))
        now += 100
        assertTrue(limiter.allow(7))
        assertTrue(limiter.allow(7))
        assertFalse(limiter.allow(7))
    }

    // ---- data, entries, events ----------------------------------------------------------------------------------------

    @Test fun connectorDataIsSmallPlainAndNeverASecret() {
        assertEquals("""{"tier":"gold","n":[1,2]}""", ProfileExtRules.validate(JSONObject("""{"tier":"gold","n":[1,2]}""")))
        expectError("SECRET_REFUSED") { ProfileExtRules.validate(JSONObject("""{"apiKey":"x"}""")) }
        expectError("SECRET_REFUSED") { ProfileExtRules.validate(JSONObject("""{"a":{"b":{"password":"x"}}}""")) }
        expectError("SECRET_REFUSED") { ProfileExtRules.validate(JSONObject("""{"note":"the otp is 123456"}""")) }
        expectError("BAD_EXT") { ProfileExtRules.validate(JSONArray("[1]")) }
        expectError("BAD_EXT") { ProfileExtRules.validate(JSONObject().put("big", "x".repeat(5000))) }
        expectError("BAD_EXT") { ProfileExtRules.validate(JSONObject((1..33).associate { "k$it" to it })) }
        expectError("BAD_EXT") { ProfileExtRules.validate(JSONObject("""{"a":{"b":{"c":{"d":{"e":1}}}}}""")) }
        ProfileExtRules.validate(JSONObject("""{"pinned":true,"spinner":"x"}""")) // "pin" only as a word
    }

    @Test fun entriesAreTheConnectorsOwnAndCantPoseAsProfiles() {
        val ok = JSONArray("""[{"id":"work-cloud","type":"acme.cloud","label":"Cloud work","subtitle":"3 devices","icon":"ic_cloud","status":{"state":"attention","text":"Sign in again"}}]""")
        val parsed = ConnectorEntry.parseAll(ok, listOf("Work", "This phone")).single()
        assertEquals("attention", parsed.state)
        assertEquals(parsed, ConnectorEntry.fromJson(parsed.toJson()))
        expectError("BAD_ENTRIES") { ConnectorEntry.parseAll(JSONArray("""[{"id":"a","type":"t","label":"work"}]"""), listOf("Work")) }
        expectError("BAD_ENTRIES") { ConnectorEntry.parseAll(JSONArray("""[{"id":"a","type":"t","label":"x","status":{"state":"on"}}]"""), emptyList()) }
        expectError("BAD_ENTRIES") { ConnectorEntry.parseAll(JSONArray("""[{"id":"a","type":"t","label":"x","url":"https://x"}]"""), emptyList()) }
        expectError("BAD_ENTRIES") { ConnectorEntry.parseAll(JSONArray("""[{"id":"a","type":"t","label":"x"},{"id":"a","type":"t","label":"y"}]"""), emptyList()) }
        expectError("BAD_ENTRIES") { ConnectorEntry.parseAll(JSONArray((1..9).map { JSONObject("""{"id":"e$it","type":"t","label":"L$it"}""") }), emptyList()) }
        expectError("BAD_ENTRIES") { ConnectorEntry.parseAll(JSONArray("""[{"id":"a","type":"t","label":"x","icon":"https://evil/x.png"}]"""), emptyList()) }
    }

    @Test fun registryChangesBecomeEventsButConnectorDataDoesNot() {
        val a = record("Cyclone_aaaaaaaaaaaaaaaa", "Work")
        val b = record("Cyclone_bbbbbbbbbbbbbbbb", "Play")
        assertEquals(listOf(ConnectorEvent.CREATED to b.id), ConnectorEvent.diff(listOf(a), listOf(a, b)))
        assertEquals(listOf(ConnectorEvent.UPDATED to a.id), ConnectorEvent.diff(listOf(a), listOf(a.copy(label = "Office"))))
        assertEquals(listOf(ConnectorEvent.TRASHED to a.id), ConnectorEvent.diff(listOf(a), listOf(a.copy(removedAtMs = 5))))
        assertEquals(listOf(ConnectorEvent.RESTORED to a.id), ConnectorEvent.diff(listOf(a.copy(removedAtMs = 5)), listOf(a)))
        assertEquals(listOf(ConnectorEvent.REMOVED to a.id), ConnectorEvent.diff(listOf(a), emptyList()))
        assertEquals(emptyList<Pair<String, String>>(), ConnectorEvent.diff(listOf(a), listOf(a.copy(ext = mapOf("x" to "{}")))))
    }

    @Test fun theJournalIsOrderedPagedAndSaysWhenYouFellBehind() {
        val j = ConnectorJournal()
        val t0 = 1_000_000L
        (1..3).forEach { j.append(ConnectorEvent.UPDATED, "p$it", t0) }
        val first = j.since(0, t0)
        assertEquals(3, first.getJSONArray("events").length())
        assertEquals(3L, first.getLong("next"))
        assertFalse(first.getBoolean("reset"))
        assertEquals(0, j.since(3, t0).getJSONArray("events").length())
        assertEquals(3L, j.since(3, t0).getLong("next"))
        val later = t0 + ConnectorJournal.KEEP_MS + 1
        j.append(ConnectorEvent.CREATED, "p9", later)
        val behind = j.since(1, later)
        assertTrue("events 2 and 3 are gone", behind.getBoolean("reset"))
        assertEquals(4L, behind.getJSONArray("events").getJSONObject(0).getLong("seq"))
        val big = ConnectorJournal()
        repeat(ConnectorJournal.PAGE + 5) { big.append(ConnectorEvent.UPDATED, "p", t0) }
        val page = big.since(0, t0)
        assertEquals(ConnectorJournal.PAGE, page.getJSONArray("events").length())
        assertTrue(page.getBoolean("more"))
        assertEquals(5, big.since(page.getLong("next"), t0).getJSONArray("events").length())
    }

    // ---- the calls ---------------------------------------------------------------------------------------------------

    private class FakeBackend(var approvalList: List<ConnectorApproval>, var records: List<CycloneProfileRecord>) : ConnectorBackend {
        val entryMap = HashMap<String, List<ConnectorEntry>>()
        val journal = ConnectorJournal()
        override fun approvals() = approvalList
        override fun profiles() = records
        override fun setExt(profileId: String, connectorId: String, json: String?) {
            records = records.map { if (it.id != profileId) it else it.copy(ext = if (json == null) it.ext - connectorId else it.ext + (connectorId to json)) }
        }
        override fun entries(connectorId: String) = entryMap[connectorId].orEmpty()
        override fun setEntries(connectorId: String, entries: List<ConnectorEntry>) { entryMap[connectorId] = entries }
        override fun events(since: Long, now: Long) = journal.since(since, now)
        override fun now() = 5_000L
    }

    private fun call(core: ConnectorCore, caller: ConnectorCaller?, method: String, args: JSONObject = JSONObject()): JSONObject =
        JSONObject(core.handle(caller, JSONObject().put("method", method).put("args", args).toString()))

    private fun code(answer: JSONObject) = answer.getJSONObject("error").getString("code")

    @Test fun everyCallButHelloNeedsTheOwnersApproval() {
        val backend = FakeBackend(emptyList(), listOf(record("Cyclone_aaaaaaaaaaaaaaaa", "Work")))
        val core = ConnectorCore(backend)
        val caller = ConnectorCaller(10_123, "com.acme.profiles", listOf(cert), manifest())
        assertEquals("NOT_A_CONNECTOR", code(call(core, null, "hello")))
        assertEquals("NOT_A_CONNECTOR", code(call(core, caller.copy(manifest = null), "hello")))
        val hello = call(core, caller, "hello", JSONObject().put("contract", "cyclone.connector/1")).getJSONObject("result")
        assertFalse(hello.getBoolean("approved"))
        assertEquals(5, hello.getJSONArray("pending").length())
        assertEquals(2, hello.getInt("profileSchema"))
        assertEquals("UNSUPPORTED_CONTRACT", code(call(core, caller, "hello", JSONObject().put("contract", "cyclone.connector/2"))))
        assertEquals("NOT_APPROVED", code(call(core, caller, "profiles")))
        backend.approvalList = listOf(ConnectorApproval("acme-profiles", "com.acme.profiles", cert, setOf(ConnectorScope.PROFILES_READ), "Acme", 1))
        assertTrue(call(core, caller, "profiles").getBoolean("ok"))
        assertEquals("SCOPE_NOT_GRANTED", code(call(core, caller, "events")))
        assertEquals("SCOPE_NOT_GRANTED", code(call(core, caller, "ext.set", JSONObject().put("profileId", "Cyclone_aaaaaaaaaaaaaaaa").put("value", JSONObject()))))
        assertEquals("UNKNOWN_METHOD", code(call(core, caller, "profiles.switch")))
        assertEquals("BAD_REQUEST", code(JSONObject(core.handle(caller, "not json"))))
    }

    @Test fun profilesShowOnlyWhatTheScopesAllowAndOnlyTheConnectorsOwnData() {
        val work = record("Cyclone_aaaaaaaaaaaaaaaa", "Work", ext = mapOf("acme-profiles" to """{"tier":"gold"}""", "other" to """{"spy":1}"""))
        val trashed = record("Cyclone_bbbbbbbbbbbbbbbb", "Old", removed = 9)
        val backend = FakeBackend(listOf(ConnectorApproval("acme-profiles", "com.acme.profiles", cert,
            setOf(ConnectorScope.PROFILES_READ, ConnectorScope.PROFILES_EXT), "Acme", 1)), listOf(work, trashed))
        val core = ConnectorCore(backend)
        val caller = ConnectorCaller(10_123, "com.acme.profiles", listOf(cert), manifest())
        val result = call(core, caller, "profiles").getJSONObject("result")
        val list = result.getJSONArray("profiles")
        assertEquals("owner", list.getJSONObject(0).getString("kind"))
        val p = list.getJSONObject(1)
        assertEquals("ready", p.getString("state"))
        assertEquals(1, p.getInt("appCount"))
        assertFalse("package names need profiles.apps.read", p.has("packages"))
        assertEquals("gold", p.getJSONObject("ext").getString("tier"))
        assertFalse(p.getJSONObject("ext").has("spy"))
        assertEquals("in_trash", list.getJSONObject(2).getString("state"))
        assertFalse(result.toString().contains("spy"))
    }

    @Test fun connectorDataCanBeSetAndClearedOnCycloneProfilesOnly() {
        val backend = FakeBackend(listOf(ConnectorApproval("acme-profiles", "com.acme.profiles", cert,
            setOf(ConnectorScope.PROFILES_EXT), "Acme", 1)), listOf(record("Cyclone_aaaaaaaaaaaaaaaa", "Work")))
        val core = ConnectorCore(backend)
        val caller = ConnectorCaller(10_123, "com.acme.profiles", listOf(cert), manifest())
        val set = call(core, caller, "ext.set", JSONObject().put("profileId", "Cyclone_aaaaaaaaaaaaaaaa").put("value", JSONObject().put("tier", "gold")))
        assertTrue(set.toString(), set.getBoolean("ok"))
        assertEquals("""{"tier":"gold"}""", backend.records.single().ext["acme-profiles"])
        assertEquals("SECRET_REFUSED", code(call(core, caller, "ext.set", JSONObject().put("profileId", "Cyclone_aaaaaaaaaaaaaaaa").put("value", JSONObject().put("token", "x")))))
        assertEquals("BAD_REQUEST", code(call(core, caller, "ext.set", JSONObject().put("profileId", "owner").put("value", JSONObject()))))
        assertEquals("NO_SUCH_PROFILE", code(call(core, caller, "ext.set", JSONObject().put("profileId", "Cyclone_ffffffffffffffff").put("value", JSONObject()))))
        assertEquals("BAD_REQUEST", code(call(core, caller, "ext.set", JSONObject().put("profileId", "Cyclone_aaaaaaaaaaaaaaaa"))))
        call(core, caller, "ext.set", JSONObject().put("profileId", "Cyclone_aaaaaaaaaaaaaaaa").put("value", JSONObject.NULL))
        assertNull(backend.records.single().ext["acme-profiles"])
    }

    @Test fun entriesAndEventsWorkThroughTheCalls() {
        val backend = FakeBackend(listOf(ConnectorApproval("acme-profiles", "com.acme.profiles", cert,
            setOf(ConnectorScope.SELECTOR_CONTRIBUTE, ConnectorScope.EVENTS_PROFILES), "Acme", 1)), listOf(record("Cyclone_aaaaaaaaaaaaaaaa", "Work")))
        val core = ConnectorCore(backend)
        val caller = ConnectorCaller(10_123, "com.acme.profiles", listOf(cert), manifest())
        val set = call(core, caller, "entries.set", JSONObject().put("entries", JSONArray("""[{"id":"c","type":"acme.cloud","label":"Cloud"}]""")))
        assertTrue(set.toString(), set.getBoolean("ok"))
        assertEquals("Cloud", call(core, caller, "entries.get").getJSONObject("result").getJSONArray("entries").getJSONObject(0).getString("label"))
        assertEquals("BAD_ENTRIES", code(call(core, caller, "entries.set", JSONObject().put("entries", JSONArray("""[{"id":"c","type":"t","label":"Work"}]""")))))
        backend.journal.append(ConnectorEvent.SWITCHED, "owner", 4_000L)
        val events = call(core, caller, "events", JSONObject().put("since", 0)).getJSONObject("result")
        assertEquals(ConnectorEvent.SWITCHED, events.getJSONArray("events").getJSONObject(0).getString("type"))
        assertEquals("BAD_REQUEST", code(call(core, caller, "events", JSONObject().put("since", -1))))
    }

    @Test fun tooManyCallsAreRefusedAndInternalErrorsSayNothing() {
        val backend = object : ConnectorBackend by FakeBackend(listOf(ConnectorApproval("acme-profiles", "com.acme.profiles", cert,
            setOf(ConnectorScope.PROFILES_READ), "Acme", 1)), emptyList()) {
            override fun profiles(): List<CycloneProfileRecord> = throw IllegalStateException("/data/secret/path")
        }
        val core = ConnectorCore(backend, ConnectorRateLimiter(2) { 0L })
        val caller = ConnectorCaller(10_123, "com.acme.profiles", listOf(cert), manifest())
        val broken = call(core, caller, "profiles")
        assertEquals("INTERNAL", code(broken))
        assertFalse(broken.toString().contains("/data/secret/path"))
        call(core, caller, "hello")
        assertEquals("RATE_LIMITED", code(call(core, caller, "hello")))
    }
}
