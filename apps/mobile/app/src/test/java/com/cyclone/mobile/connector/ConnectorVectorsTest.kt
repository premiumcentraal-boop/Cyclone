package com.cyclone.mobile.connector

import com.cyclone.mobile.runtime.workspaces.CycloneProfileRecord
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Plan 51 K4: the published test vectors (`tools/cyclone-connector-sdk/schemas/vectors.json`) are what this Cyclone
 * answers. A connector author can run the same file against their own fake; this keeps the file honest.
 */
class ConnectorVectorsTest {
    private val vectors: JSONObject by lazy {
        val relative = "tools/cyclone-connector-sdk/schemas/vectors.json"
        val dir = File(System.getProperty("user.dir"))
        val file = generateSequence(dir) { it.parentFile }.map { File(it, relative) }.firstOrNull { it.isFile }
            ?: error("Could not locate $relative from $dir")
        JSONObject(file.readText())
    }

    private class StateBackend(state: JSONObject) : ConnectorBackend {
        private val configs = HashMap<Triple<String, Int, ProfileConfigKey>, String>()
        private val now = state.getLong("now")
        private val current = state.optString("current").takeIf { it.isNotEmpty() }
        private val approvalList = state.getJSONArray("approvals").let { a -> (0 until a.length()).map { ConnectorApproval.fromJson(a.getJSONObject(it))!! } }
        private var records = state.getJSONArray("profiles").let { a ->
            (0 until a.length()).map { i ->
                val p = a.getJSONObject(i)
                val packages = p.getJSONArray("packages").let { pk -> (0 until pk.length()).map { pk.getString(it) }.toSet() }
                val ext = p.getJSONObject("ext").let { e -> e.keys().asSequence().associateWith { e.getJSONObject(it).toString() } }
                CycloneProfileRecord(p.getString("id"), p.getString("label"), 10, 0, true, packages, "READY", p.getBoolean("ready"),
                    if (p.isNull("removedAt")) null else p.getLong("removedAt"), if (p.isNull("emoji")) null else p.getString("emoji"),
                    if (p.isNull("color")) null else p.getLong("color"), ext)
            }
        }
        private val entryMap = HashMap<String, List<ConnectorEntry>>().apply {
            val e = state.getJSONObject("entries")
            e.keys().forEach { id -> val a = e.getJSONArray(id); put(id, (0 until a.length()).map { ConnectorEntry.fromJson(a.getJSONObject(it))!! }) }
        }
        private val journal = ConnectorJournal().apply {
            val a = state.getJSONArray("events")
            (0 until a.length()).map { a.getJSONObject(it) }.forEach { append(it.getString("type"), it.getString("profileId"), it.getLong("at")) }
        }
        override fun approvals() = approvalList
        override fun profiles() = records
        override fun setExt(profileId: String, connectorId: String, json: String?) {
            records = records.map { if (it.id != profileId) it else it.copy(ext = if (json == null) it.ext - connectorId else it.ext + (connectorId to json)) }
        }
        override fun entries(connectorId: String) = entryMap[connectorId].orEmpty()
        override fun setEntries(connectorId: String, entries: List<ConnectorEntry>) { entryMap[connectorId] = entries }
        override fun events(since: Long, now: Long) = journal.since(since, now)
        override fun config(connectorId: String, callerUser: Int, key: ProfileConfigKey) = configs[Triple(connectorId, callerUser, key)]
        override fun setConfig(connectorId: String, callerUser: Int, key: ProfileConfigKey, json: String?) {
            if (json == null) configs.remove(Triple(connectorId, callerUser, key)) else configs[Triple(connectorId, callerUser, key)] = json
        }
        override fun now() = now
        override fun currentProfile() = current
    }

    private fun caller(base: JSONObject, override: JSONObject?): ConnectorCaller {
        val merged = JSONObject(base.toString())
        override?.keys()?.forEach { merged.put(it, override.get(it)) }
        val m = merged.getJSONObject("manifest")
        val manifest = ConnectorManifest.parse(merged.getString("package"), m.keys().asSequence().associateWith { m.getString(it) })
        val certs = merged.getJSONArray("certs").let { a -> (0 until a.length()).map { a.getString(it) } }
        return ConnectorCaller(10_123, merged.getString("package"), certs, manifest)
    }

    /** Numbers compared by value, keys in any order. */
    private fun canonical(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().associateWith { canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { canonical(value.get(it)) }
        is Number -> java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        JSONObject.NULL, null -> null
        else -> value
    }

    @Test fun everyPublishedVectorIsWhatCycloneAnswers() {
        val cases = vectors.getJSONArray("cases")
        assertTrue(cases.length() >= 10)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val core = ConnectorCore(StateBackend(vectors.getJSONObject("state")))
            val request = if (case.has("rawRequest")) case.getString("rawRequest") else case.getJSONObject("request").toString()
            val answer = JSONObject(core.handle(caller(vectors.getJSONObject("caller"), case.optJSONObject("caller")), request))
            val expected = case.getJSONObject("answer")
            val name = case.getString("name")
            assertEquals(name, expected.getBoolean("ok"), answer.getBoolean("ok"))
            if (expected.getBoolean("ok")) {
                assertEquals(name, canonical(expected.get("result")), canonical(answer.get("result")))
            } else {
                assertEquals(name, expected.getJSONObject("error").getString("code"), answer.getJSONObject("error").getString("code"))
                assertTrue(name, answer.getJSONObject("error").getString("message").isNotBlank())
            }
        }
    }
}
