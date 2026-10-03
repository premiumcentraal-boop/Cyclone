package com.cyclone.mobile.connector

import com.cyclone.mobile.runtime.workspaces.CycloneProfileRecord
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 51 §3.1: a connector's own data on a profile. At most 4 KB and 32 keys in all, 4 levels deep, JSON only, and
 * nothing that looks like a secret: such keys and "password: …" style values are refused, the same screening the
 * gateway uses. Cyclone never logs it, never sends it to the PC or a model.
 */
object ProfileExtRules {
    private val SECRET_KEY = Regex("(?i)(password|passcode|passwd|secret|token|api.?key|otp|one.?time|verification.?code|cvv|cvc|card.?number|credential|cookie|authorization|\\bpin\\b)")
    private val INLINE_SECRET = Regex("(?i)\\b(password|passcode|passwd|pin|otp|verification\\s*code|api[_ -]?key|token|secret)\\s*(?:is|:|=)\\s*\\S+")

    /** Returns the stored text for [value], or throws [ConnectorException] with a plain reason. */
    fun validate(value: Any?): String {
        val obj = value as? JSONObject ?: throw ConnectorException("BAD_EXT", "The value must be a JSON object.")
        var keys = 0
        fun walk(node: Any?, depth: Int) {
            if (depth > ConnectorContract.EXT_MAX_DEPTH) throw ConnectorException("BAD_EXT", "The value is nested too deeply (at most ${ConnectorContract.EXT_MAX_DEPTH} levels).")
            when (node) {
                is JSONObject -> node.keys().forEach { key ->
                    keys++
                    if (SECRET_KEY.containsMatchIn(key)) throw ConnectorException("SECRET_REFUSED", "Cyclone doesn't keep secrets for connectors (key \"${key.take(40)}\").")
                    walk(node.get(key), depth + 1)
                }
                is JSONArray -> (0 until node.length()).forEach { walk(node.get(it), depth + 1) }
                is String -> if (INLINE_SECRET.containsMatchIn(node)) throw ConnectorException("SECRET_REFUSED", "Cyclone doesn't keep secrets for connectors.")
                is Number, is Boolean, JSONObject.NULL -> Unit
                else -> throw ConnectorException("BAD_EXT", "Only JSON values are allowed.")
            }
        }
        walk(obj, 1)
        if (keys > ConnectorContract.EXT_MAX_KEYS) throw ConnectorException("BAD_EXT", "At most ${ConnectorContract.EXT_MAX_KEYS} keys in all.")
        val text = obj.toString()
        if (text.toByteArray(Charsets.UTF_8).size > ConnectorContract.EXT_MAX_BYTES) {
            throw ConnectorException("BAD_EXT", "The value is larger than ${ConnectorContract.EXT_MAX_BYTES / 1024} KB.")
        }
        return text
    }
}

/** Plan 51 §3.2: an entry a connector adds to the profile selector. Never a profile: it is shown as the connector's. */
data class ConnectorEntry(val id: String, val type: String, val label: String, val subtitle: String, val icon: String?,
                          val state: String, val statusText: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("type", type).put("label", label).put("subtitle", subtitle)
        .put("icon", icon ?: JSONObject.NULL).put("status", JSONObject().put("state", state).put("text", statusText))

    companion object {
        private val ID = Regex("^[a-z0-9][a-z0-9_-]{0,39}$")
        private val TYPE = Regex("^[a-z][a-z0-9._-]{0,39}$")
        private val ICON = Regex("^[a-z][a-z0-9_]{0,59}$")
        val STATES = setOf("ready", "attention", "off")

        /** Checks a whole list. [reserved] are labels the entries may not take (real profiles, "This phone"). */
        fun parseAll(value: Any?, reserved: Collection<String>): List<ConnectorEntry> {
            val array = value as? JSONArray ?: throw ConnectorException("BAD_ENTRIES", "Send entries as a list.")
            if (array.length() > ConnectorContract.ENTRIES_MAX) throw ConnectorException("BAD_ENTRIES", "At most ${ConnectorContract.ENTRIES_MAX} entries.")
            val taken = reserved.map { it.trim().lowercase() }.toSet()
            val entries = (0 until array.length()).map { i ->
                val o = array.optJSONObject(i) ?: throw ConnectorException("BAD_ENTRIES", "Entry ${i + 1} must be an object.")
                val extra = o.keys().asSequence().filterNot { it in setOf("id", "type", "label", "subtitle", "icon", "status") }.firstOrNull()
                if (extra != null) throw ConnectorException("BAD_ENTRIES", "Entry ${i + 1} has an unknown field \"${extra.take(40)}\".")
                val id = o.optString("id")
                if (!ID.matches(id)) throw ConnectorException("BAD_ENTRIES", "Entry ${i + 1}: id must be lowercase letters, digits, - or _.")
                val type = o.optString("type")
                if (!TYPE.matches(type)) throw ConnectorException("BAD_ENTRIES", "Entry ${i + 1}: type must be a short lowercase name.")
                val label = o.optString("label").trim().replace(Regex("\\s+"), " ")
                if (label.isEmpty() || label.length > 40) throw ConnectorException("BAD_ENTRIES", "Entry ${i + 1}: label must be 1-40 characters.")
                if (label.lowercase() in taken) throw ConnectorException("BAD_ENTRIES", "Entry ${i + 1}: \"$label\" is the name of one of your profiles.")
                val subtitle = o.optString("subtitle").trim()
                if (subtitle.length > 60) throw ConnectorException("BAD_ENTRIES", "Entry ${i + 1}: subtitle must be at most 60 characters.")
                val icon = if (o.isNull("icon")) null else o.optString("icon").takeIf { it.isNotEmpty() }
                if (icon != null && !ICON.matches(icon)) throw ConnectorException("BAD_ENTRIES", "Entry ${i + 1}: icon must be a drawable name from your app.")
                val status = o.optJSONObject("status") ?: JSONObject().put("state", "ready")
                val state = status.optString("state")
                if (state !in STATES) throw ConnectorException("BAD_ENTRIES", "Entry ${i + 1}: status.state must be ready, attention or off.")
                val text = status.optString("text").trim()
                if (text.length > 60) throw ConnectorException("BAD_ENTRIES", "Entry ${i + 1}: status.text must be at most 60 characters.")
                ConnectorEntry(id, type, label, subtitle, icon, state, text)
            }
            if (entries.map { it.id }.toSet().size != entries.size) throw ConnectorException("BAD_ENTRIES", "Entry ids must be unique.")
            return entries
        }

        fun fromJson(o: JSONObject): ConnectorEntry? = runCatching {
            val status = o.getJSONObject("status")
            ConnectorEntry(o.getString("id"), o.getString("type"), o.getString("label"), o.optString("subtitle"),
                if (o.isNull("icon")) null else o.optString("icon"), status.getString("state"), status.optString("text"))
        }.getOrNull()
    }
}

/** Plan 51 §3.3: profile events. Ids only: no package names, no labels, nothing private travels in an event. */
data class ConnectorEvent(val seq: Long, val type: String, val profileId: String, val at: Long) {
    fun toJson(): JSONObject = JSONObject().put("seq", seq).put("type", type).put("profileId", profileId).put("at", at)

    companion object {
        const val CREATED = "profile.created"
        const val UPDATED = "profile.updated"
        const val SWITCHED = "profile.switched"
        const val TRASHED = "profile.trashed"
        const val RESTORED = "profile.restored"
        const val REMOVED = "profile.removed"
        const val OWNER = "owner"

        fun fromJson(o: JSONObject): ConnectorEvent? = runCatching {
            ConnectorEvent(o.getLong("seq"), o.getString("type"), o.getString("profileId"), o.getLong("at"))
        }.getOrNull()

        /** The events a registry save means. Connector data (`ext`) changes alone are not an event. */
        fun diff(before: List<CycloneProfileRecord>, after: List<CycloneProfileRecord>): List<Pair<String, String>> {
            val old = before.associateBy { it.id }
            val new = after.associateBy { it.id }
            val out = mutableListOf<Pair<String, String>>()
            for ((id, record) in new) {
                val was = old[id]
                when {
                    was == null -> out += CREATED to id
                    was.removedAtMs == null && record.removedAtMs != null -> out += TRASHED to id
                    was.removedAtMs != null && record.removedAtMs == null -> out += RESTORED to id
                    was.copy(ext = emptyMap()) != record.copy(ext = emptyMap()) -> out += UPDATED to id
                }
            }
            for (id in old.keys - new.keys) out += REMOVED to id
            return out
        }
    }
}

/**
 * The event journal (pure). Ordered by [ConnectorEvent.seq], kept for [KEEP_MS] or [KEEP_COUNT] events. A reader
 * that fell behind what is kept gets `reset` and should read the profiles again.
 */
class ConnectorJournal(events: List<ConnectorEvent> = emptyList(), nextSeq: Long = 1) {
    companion object {
        const val KEEP_MS = 7L * 24 * 60 * 60 * 1000
        const val KEEP_COUNT = 1_000
        const val PAGE = 200
    }

    var events: List<ConnectorEvent> = events
        private set
    var nextSeq: Long = maxOf(nextSeq, (events.maxOfOrNull { it.seq } ?: 0) + 1)
        private set

    fun append(type: String, profileId: String, at: Long): ConnectorEvent {
        val event = ConnectorEvent(nextSeq++, type, profileId, at)
        events = (events + event).filter { at - it.at <= KEEP_MS }.takeLast(KEEP_COUNT)
        return event
    }

    /** Events after [since]; `reset` when some of them are no longer kept. */
    fun since(since: Long, now: Long): JSONObject {
        val kept = events.filter { now - it.at <= KEEP_MS }
        val oldest = kept.firstOrNull()?.seq ?: nextSeq
        val reset = since + 1 < oldest && since < nextSeq - 1
        val page = kept.filter { it.seq > since }.take(PAGE)
        val next = page.lastOrNull()?.seq ?: maxOf(since, nextSeq - 1)
        return JSONObject().put("events", JSONArray(page.map { it.toJson() })).put("next", next).put("reset", reset)
            .put("more", kept.count { it.seq > since } > page.size)
    }
}
