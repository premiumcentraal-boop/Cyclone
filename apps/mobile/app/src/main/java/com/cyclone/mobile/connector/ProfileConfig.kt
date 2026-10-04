package com.cyclone.mobile.connector

import com.cyclone.mobile.runtime.workspaces.CycloneProfileRecord
import org.json.JSONObject
import java.security.MessageDigest

/** Tuple identity, never a caller-supplied filesystem path. */
data class ProfileConfigKey(val profileId: String, val androidUserId: Int, val packageName: String) {
    fun json(): JSONObject = JSONObject().put("profileId", profileId).put("androidUserId", androidUserId).put("packageName", packageName)
    fun storageKey(): String = MessageDigest.getInstance("SHA-256").digest(
        "$profileId\n$androidUserId\n$packageName".toByteArray(Charsets.UTF_8)
    ).joinToString("") { "%02x".format(it) }

    companion object {
        fun parse(args: JSONObject, profiles: List<CycloneProfileRecord>): ProfileConfigKey {
            val id = args.opt("profileId") as? String ?: throw ConnectorException("BAD_REQUEST", "profileId must be a string.")
            val user = args.opt("androidUserId")
            if (user !is Int || user < 0) throw ConnectorException("BAD_REQUEST", "androidUserId must be a nonnegative integer.")
            val pkg = args.opt("packageName") as? String ?: throw ConnectorException("BAD_REQUEST", "packageName must be a string.")
            val profile = profiles.singleOrNull { it.id == id && it.androidUserId == user && it.ready && !it.inTrash }
                ?: throw ConnectorException("NO_SUCH_PROFILE", "No ready profile matches that id and Android user.")
            if (!Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(pkg) || pkg !in profile.packages) {
                throw ConnectorException("BAD_REQUEST", "The package must belong to the selected profile.")
            }
            return ProfileConfigKey(id, user, pkg)
        }
    }
}

/** Opaque JSON: only shape and size are checked; contents are never interpreted or logged. */
object ProfileConfigRules {
    const val MAX_BYTES = 4096
    val STATES = setOf("unknown", "ready", "degraded", "failed")
    fun checkNesting(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        text.forEach { c ->
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; if (depth > 32) throw ConnectorException("BAD_REQUEST", "JSON nesting exceeds 32 levels.") }
                '}', ']' -> depth--
            }
        }
    }
    fun value(value: Any?): String? {
        if (value == JSONObject.NULL) return null
        val obj = value as? JSONObject ?: throw ConnectorException("BAD_REQUEST", "value must be an object or null.")
        val text = obj.toString()
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw ConnectorException("BAD_REQUEST", "Config exceeds 4096 UTF-8 bytes.")
        return text
    }
    fun state(value: Any?): String = (value as? String)?.takeIf { it in STATES }
        ?: throw ConnectorException("BAD_REQUEST", "state must be unknown, ready, degraded or failed.")

    fun reply(text: String?): JSONObject {
        if (text == null || text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw ConnectorException("BAD_REQUEST", "Startup reply exceeds 4096 bytes.")
        checkNesting(text)
        val o = JSONObject(text)
        if (o.opt("version") != 1) throw ConnectorException("UNSUPPORTED_CONTRACT", "Startup reply version must be 1.")
        val ref = o.opt("configRef")
        if (ref != null && ref != JSONObject.NULL && (ref !is String || ref.toByteArray(Charsets.UTF_8).size > 1024)) {
            throw ConnectorException("BAD_REQUEST", "configRef must be null or at most 1024 UTF-8 bytes.")
        }
        return JSONObject().put("version", 1).put("configRef", ref ?: JSONObject.NULL)
            .put("state", if (o.has("state")) state(o.opt("state")) else "ready")
    }
}

/** Dispatch hints, not Android process lifecycle claims. Scoped separately by profile, user and package. */
class ProfileLaunchTracker {
    private val seen = HashMap<ProfileConfigKey, Int>()
    private val switches = HashMap<Int, Int>()
    @Synchronized fun switched(user: Int) { switches[user] = (switches[user] ?: 0) + 1 }
    @Synchronized fun next(key: ProfileConfigKey): String {
        val generation = switches[key.androidUserId] ?: 0
        val old = seen.put(key, generation)
        if (seen.size > 256) { seen.clear(); seen[key] = generation }
        return if (generation > 0 && old != generation) "profile_switch" else if (old == null) "cold_start" else "relaunch"
    }
    companion object { val TYPES = setOf("cold_start", "profile_switch", "relaunch") }
}

/** A reply is accepted once, only from the registered UID and before the monotonic deadline. */
class ProfileReplyGate(private val expectedUid: Int, private val deadline: Long, private val clock: () -> Long) {
    private var accepted = false
    @Synchronized fun accept(uid: Int): Boolean {
        if (accepted || uid != expectedUid || clock() >= deadline) return false
        accepted = true
        return true
    }
}
