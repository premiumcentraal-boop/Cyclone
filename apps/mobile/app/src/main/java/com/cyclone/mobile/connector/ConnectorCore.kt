package com.cyclone.mobile.connector

import com.cyclone.mobile.runtime.workspaces.CycloneProfileRecord
import com.cyclone.mobile.runtime.workspaces.ProfileRegistryCodec
import org.json.JSONArray
import org.json.JSONObject

/** What [ConnectorCore] needs from the app. Android-backed in [ConnectorRuntime]; faked in tests. */
interface ConnectorBackend {
    fun approvals(): List<ConnectorApproval>
    fun profiles(): List<CycloneProfileRecord>
    fun setExt(profileId: String, connectorId: String, json: String?)
    fun entries(connectorId: String): List<ConnectorEntry>
    fun setEntries(connectorId: String, entries: List<ConnectorEntry>)
    fun events(since: Long, now: Long): JSONObject
    fun now(): Long
    /** The profile in front: `owner`, a profile id, or null when Cyclone can't tell (plan 51 K3). */
    fun currentProfile(): String? = null
    fun startupStatus(uid: Int, key: ProfileConfigKey): JSONObject = JSONObject().put("version", 1).put("state", "unknown").put("configRef", JSONObject.NULL)
    fun config(connectorId: String, callerUser: Int, key: ProfileConfigKey): String? = null
    fun updateConfig(connectorId: String, callerUser: Int, key: ProfileConfigKey, transform: (String?) -> String): String {
        val updated = transform(config(connectorId, callerUser, key))
        setConfig(connectorId, callerUser, key, updated)
        return updated
    }
    fun setConfig(connectorId: String, callerUser: Int, key: ProfileConfigKey, json: String?) { error("Config storage unavailable") }
}

/**
 * Plan 51: every connector call, as one JSON request and one JSON answer (pure, tested).
 *
 * Request: `{"method": "...", "args": {...}}`. Answer: `{"ok": true, "result": ...}` or
 * `{"ok": false, "error": {"code": "...", "message": "..."}}`. Identity comes from Binder, never from the request.
 * Every call except `hello` needs the owner's approval and the scope it uses.
 */
class ConnectorCore(private val backend: ConnectorBackend, private val limiter: ConnectorRateLimiter = ConnectorRateLimiter()) {

    fun handle(caller: ConnectorCaller?, request: String?): String = try {
        if (caller == null) throw ConnectorException("NOT_A_CONNECTOR", "Only an app that declares a Cyclone connector can call Cyclone.")
        if (!limiter.allow(caller.uid)) throw ConnectorException("RATE_LIMITED", "Too many calls. Slow down and try again.")
        if (request == null || request.toByteArray(Charsets.UTF_8).size > ConnectorContract.REQUEST_MAX_BYTES) throw ConnectorException("BAD_REQUEST", "The request is empty or too large.")
        ProfileConfigRules.checkNesting(request)
        val body = runCatching { JSONObject(request) }.getOrNull() ?: throw ConnectorException("BAD_REQUEST", "The request isn't a JSON object.")
        val method = body.optString("method")
        val args = body.optJSONObject("args") ?: JSONObject()
        ok(dispatch(caller, method, args))
    } catch (error: ConnectorException) {
        fail(error.code, error.message.orEmpty())
    } catch (error: Exception) {
        fail("INTERNAL", "Cyclone couldn't answer that call.")
    }

    private fun dispatch(caller: ConnectorCaller, method: String, args: JSONObject): Any {
        val manifest = caller.manifest ?: throw ConnectorException("NOT_A_CONNECTOR", "Only an app that declares a Cyclone connector can call Cyclone.")
        val approval = ConnectorIdentity.approvalFor(caller, backend.approvals())
        if (method == "hello") return hello(caller, approval, args)
        if (approval == null) throw ConnectorException("NOT_APPROVED", "Open Cyclone → Settings → Connectors and approve ${manifest.label}.")
        val granted = ConnectorIdentity.granted(caller, approval)
        fun need(scope: ConnectorScope) {
            if (scope !in granted) throw ConnectorException("SCOPE_NOT_GRANTED", "${manifest.label} isn't allowed to ${scope.plain.replaceFirstChar { it.lowercase() }}.")
        }
        return when (method) {
            "config.get.v1", "config.set.v1", "config.status.v1" -> {
                need(ConnectorScope.PROFILE_CONFIG)
                val key = ProfileConfigKey.parse(args, backend.profiles())
                val user = caller.uid / 100_000
                val stored = if (method == "config.get.v1") {
                    backend.config(manifest.id, user, key)?.let(::JSONObject) ?: JSONObject()
                } else JSONObject(backend.updateConfig(manifest.id, user, key) { text ->
                    val previous = text?.let(::JSONObject) ?: JSONObject()
                    if (method == "config.set.v1") {
                        if (!args.has("value")) throw ConnectorException("BAD_REQUEST", "Send an object or null as value.")
                        previous.put("value", ProfileConfigRules.value(args.opt("value"))?.let(::JSONObject) ?: JSONObject.NULL)
                    } else previous.put("state", ProfileConfigRules.state(args.opt("state")))
                    key.json().put("value", previous.opt("value") ?: JSONObject.NULL)
                        .put("state", previous.optString("state", "unknown")).toString()
                })
                key.json().put("version", 1).put("storageKey", key.storageKey())
                    .put("value", stored.opt("value") ?: JSONObject.NULL)
                    .put("state", stored.optString("state", "unknown"))
            }
            "startup.status.v1" -> { need(ConnectorScope.PROFILE_STARTUP); backend.startupStatus(caller.uid, ProfileConfigKey.parse(args, backend.profiles())) }
            "startup.check.v1" -> {
                need(ConnectorScope.PROFILE_STARTUP)
                if (args.opt("version") != 1) throw ConnectorException("UNSUPPORTED_CONTRACT", "Startup contract version must be 1.")
                JSONObject().put("version", 1).put("deadlineMs", 250)
            }
            "profiles" -> { need(ConnectorScope.PROFILES_READ); profiles(manifest.id, granted) }
            "ext.set" -> { need(ConnectorScope.PROFILES_EXT); setExt(manifest.id, args) }
            "entries.get" -> { need(ConnectorScope.SELECTOR_CONTRIBUTE); JSONObject().put("entries", JSONArray(backend.entries(manifest.id).map { it.toJson() })) }
            "entries.set" -> { need(ConnectorScope.SELECTOR_CONTRIBUTE); setEntries(manifest.id, args) }
            "events" -> {
                need(ConnectorScope.EVENTS_PROFILES)
                val since = args.optLong("since", 0L)
                if (since < 0) throw ConnectorException("BAD_REQUEST", "since must be 0 or more.")
                backend.events(since, backend.now())
            }
            else -> throw ConnectorException("UNKNOWN_METHOD", "Cyclone has no call named \"${method.take(40)}\".")
        }
    }

    private fun hello(caller: ConnectorCaller, approval: ConnectorApproval?, args: JSONObject): JSONObject {
        val asked = args.optString("contract", ConnectorContract.CONTRACT)
        if (!asked.startsWith("cyclone.connector/${ConnectorContract.MAJOR}")) {
            throw ConnectorException("UNSUPPORTED_CONTRACT", "This Cyclone speaks ${ConnectorContract.CONTRACT}.")
        }
        return JSONObject()
            .put("contract", ConnectorContract.CONTRACT).put("minor", ConnectorContract.MINOR)
            .put("connectorId", caller.manifest!!.id)
            .put("approved", approval != null)
            .put("granted", JSONArray(ConnectorIdentity.granted(caller, approval).map { it.wire }.sorted()))
            .put("pending", JSONArray(ConnectorIdentity.pending(caller, approval).map { it.wire }.sorted()))
            .put("profileSchema", ProfileRegistryCodec.SCHEMA_VERSION)
            .put("limits", JSONObject().put("extBytes", ConnectorContract.EXT_MAX_BYTES).put("extKeys", ConnectorContract.EXT_MAX_KEYS)
                .put("entries", ConnectorContract.ENTRIES_MAX).put("callsPerSecond", ConnectorContract.CALLS_PER_SECOND))
    }

    private fun profiles(connectorId: String, granted: Set<ConnectorScope>): JSONObject {
        val list = JSONArray()
        list.put(JSONObject().put("id", ConnectorEvent.OWNER).put("label", "This phone").put("kind", "owner").put("state", "ready"))
        backend.profiles().forEach { record ->
            val o = JSONObject().put("id", record.id).put("label", record.label).put("kind", "profile")
                .put("state", when { record.inTrash -> "in_trash"; record.ready -> "ready"; else -> "setting_up" })
                .put("emoji", record.emoji ?: JSONObject.NULL).put("color", record.color ?: JSONObject.NULL)
                .put("appCount", record.packages.size).put("androidUserId", record.androidUserId ?: JSONObject.NULL)
            if (ConnectorScope.PROFILES_APPS_READ in granted) o.put("packages", JSONArray(record.packages.sorted()))
            if (ConnectorScope.PROFILES_EXT in granted) o.put("ext", record.ext[connectorId]?.let(::JSONObject) ?: JSONObject.NULL)
            list.put(o)
        }
        return JSONObject().put("schemaVersion", ProfileRegistryCodec.SCHEMA_VERSION)
            .put("current", backend.currentProfile() ?: JSONObject.NULL).put("profiles", list)
    }

    private fun setExt(connectorId: String, args: JSONObject): JSONObject {
        val profileId = args.optString("profileId")
        if (profileId == ConnectorEvent.OWNER) throw ConnectorException("BAD_REQUEST", "Connector data can be kept on Cyclone profiles, not on this phone's own profile.")
        if (backend.profiles().none { it.id == profileId }) throw ConnectorException("NO_SUCH_PROFILE", "There's no profile with that id.")
        if (!args.has("value")) throw ConnectorException("BAD_REQUEST", "Send value: an object, or null to clear it.")
        val json = if (args.isNull("value")) null else ProfileExtRules.validate(args.get("value"))
        backend.setExt(profileId, connectorId, json)
        return JSONObject().put("profileId", profileId).put("cleared", json == null)
    }

    private fun setEntries(connectorId: String, args: JSONObject): JSONObject {
        val reserved = backend.profiles().map { it.label } + "This phone"
        val entries = ConnectorEntry.parseAll(args.opt("entries"), reserved)
        backend.setEntries(connectorId, entries)
        return JSONObject().put("count", entries.size)
    }

    private fun ok(result: Any): String = JSONObject().put("ok", true).put("result", result).toString()

    private fun fail(code: String, message: String): String =
        JSONObject().put("ok", false).put("error", JSONObject().put("code", code).put("message", message.take(300))).toString()
}
