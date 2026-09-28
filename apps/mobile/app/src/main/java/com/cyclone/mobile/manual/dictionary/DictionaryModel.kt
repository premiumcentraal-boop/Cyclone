package com.cyclone.mobile.manual.dictionary

import com.cyclone.mobile.brain.graphv2.AtlasPrivacy
import com.cyclone.mobile.manual.Anchor
import com.cyclone.mobile.manual.AnchorKind
import com.cyclone.mobile.manual.AppLexicon
import com.cyclone.mobile.manual.ChromeProof
import com.cyclone.mobile.manual.CoreKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where an entry stands. Only the organizer (gates or its one question) and the owner move an entry out of
 * [CANDIDATE]; a model can only propose (plan 36 §7.5).
 */
enum class EntryStatus(val wire: String) {
    CANDIDATE("candidate"),
    CONFIRMED("confirmed"),
    /** Confirmed and locked by the owner: the organizer never changes it. */
    LOCKED("locked"),
    REJECTED("rejected"),
    /** Folded into another entry; its id still resolves there. */
    MERGED("merged"),
    /** Not seen for two passes over its screens; kept so old references still resolve. */
    RETIRED("retired");

    val active: Boolean get() = this == CONFIRMED || this == LOCKED

    companion object {
        fun fromWire(value: String?): EntryStatus? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One set in an app's dictionary: a named group of one [CoreKind] ("Close friends" ⊂ Person), defined by where the
 * app shows it. It holds the app's own words, anchors, markers and counts of sightings: never a member, a count of
 * members or any name of a person (plan 36 §7.3). [id] is stable and never reused.
 */
data class DictEntry(
    val id: String,
    val kind: CoreKind,
    val name: String,
    val nameProof: ChromeProof,
    val resKey: String? = null,
    val aliases: List<String> = emptyList(),
    val ownerLabel: String? = null,
    val parentId: String? = null,
    /** A parent named on screen (the title above a category strip) that is resolved to an id when the organizer runs. */
    val parentHint: String? = null,
    val status: EntryStatus = EntryStatus.CANDIDATE,
    val anchors: List<Anchor> = emptyList(),
    val markers: List<String> = emptyList(),
    val observations: Int = 0,
    val days: List<Long> = emptyList(),
    val firstSeenAt: Long = 0,
    val lastSeenAt: Long = 0,
    val versions: List<String> = emptyList(),
    val missedPasses: Int = 0,
    val redirectTo: String? = null,
    val note: String? = null,
) {
    val shownName: String get() = ownerLabel ?: name
    fun names(): List<String> = (listOf(name) + aliases + listOfNotNull(ownerLabel)).distinct()
}

data class AuditEvent(val at: Long, val action: String, val entryId: String, val detail: String, val by: String)

/** JEV watching the organizer's question: how often its pick matched the model's. Kinds and timings only. */
data class JevOrganizerTally(
    val answered: Int = 0,
    val agreed: Int = 0,
    val sureAnswered: Int = 0,
    val sureAgreed: Int = 0,
    val timesMs: List<Long> = emptyList(),
    val lastError: String? = null,
) {
    fun add(agree: Boolean, confidence: Double, ms: Long): JevOrganizerTally = copy(
        answered = answered + 1,
        agreed = agreed + if (agree) 1 else 0,
        sureAnswered = sureAnswered + if (confidence >= 0.8) 1 else 0,
        sureAgreed = sureAgreed + if (confidence >= 0.8 && agree) 1 else 0,
        timesMs = (timesMs + ms).takeLast(50),
        lastError = null,
    )

    fun summary(): String {
        if (answered == 0) return lastError?.let { "No answer yet: $it" } ?: "No decisions yet."
        val median = timesMs.sorted().let { if (it.isEmpty()) null else it[(it.size - 1) / 2] }
        return "Agreed $agreed of $answered" + (median?.let { " · ${"%.1f".format(it / 1000.0)} s" } ?: "") +
            (if (sureAnswered > 0) " · ${sureAgreed * 100 / sureAnswered}% when sure" else "")
    }
}

data class AppDictionary(
    val packageName: String,
    val entries: Map<String, DictEntry> = emptyMap(),
    val audit: List<AuditEvent> = emptyList(),
    val jev: JevOrganizerTally = JevOrganizerTally(),
    val passes: Int = 0,
    val updatedAt: Long = 0,
) {
    /** Follows merges to the entry that stands for [id] now. */
    fun resolve(id: String?): DictEntry? {
        var current = id?.let(entries::get) ?: return null
        repeat(MAX_REDIRECTS) {
            val next = current.redirectTo?.let(entries::get) ?: return current
            current = next
        }
        return current
    }

    fun active(): List<DictEntry> = entries.values.filter { it.status.active }

    fun children(id: String): List<DictEntry> = entries.values.filter { it.parentId == id && it.status != EntryStatus.MERGED && it.status != EntryStatus.REJECTED }

    fun depth(id: String?): Int {
        var depth = 0
        var at = id?.let(entries::get)
        val seen = HashSet<String>()
        while (at != null && seen.add(at.id)) {
            depth++
            at = at.parentId?.let(entries::get)
        }
        return depth
    }

    /** The chain from the core kind down to [entry]: "Person › Followers". */
    fun path(entry: DictEntry): String {
        val names = ArrayList<String>()
        var at: DictEntry? = entry
        val seen = HashSet<String>()
        while (at != null && seen.add(at.id)) {
            names.add(0, at.shownName)
            at = at.parentId?.let(entries::get)
        }
        return (listOf(entry.kind.label) + names).joinToString(" › ")
    }

    fun withAudit(event: AuditEvent): AppDictionary = copy(audit = (audit + event).takeLast(MAX_AUDIT))

    fun newId(name: String): String {
        val slug = AppLexicon.normalize(name).replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(32).ifBlank { "set" }
        var id = "set:$slug"
        var n = 2
        while (id in entries) id = "set:${slug}_${n++}"
        return id
    }

    companion object {
        const val MAX_AUDIT = 200
        const val MAX_REDIRECTS = 8
        const val MAX_ENTRIES = 400
    }
}

/**
 * The dictionary holds structure only (plan 36 §7.3). Every text field is checked here on the way in and on the way
 * out: short, free of captured values (emails, tokens, codes), and never more than a few words.
 */
object DictionaryPrivacy {
    const val MAX_TEXT = 60
    const val MAX_WORDS = 6

    fun text(raw: String?): String? {
        val value = raw?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (value.isEmpty() || value.length > MAX_TEXT) return null
        if (value.split(' ').size > MAX_WORDS) return null
        if (Regex("\\d{3,}").containsMatchIn(value)) return null
        if (AtlasPrivacy.structuralLabel(value, "").isBlank()) return null
        return value
    }

    fun texts(values: List<String>, max: Int): List<String> = values.mapNotNull(::text).distinct().take(max)

    fun anchor(anchor: Anchor): Anchor = anchor.copy(
        screenTitle = text(anchor.screenTitle),
        siblings = texts(anchor.siblings, 11),
        groups = texts(anchor.groups, 12),
        searchLabel = text(anchor.searchLabel),
        rowShape = anchor.rowShape?.takeIf { SHAPE.matches(it) },
        roomKey = anchor.roomKey.takeIf { ROOM.matches(it) } ?: "screen:unknown",
        containerKey = anchor.containerKey.takeIf { HEX.matches(it) } ?: "0",
    )

    /** An entry as it may be stored or shown; null when its name itself fails. */
    fun entry(entry: DictEntry): DictEntry? {
        val name = text(entry.name) ?: return null
        return entry.copy(
            name = name,
            aliases = texts(entry.aliases, 8).filter { it != name },
            ownerLabel = text(entry.ownerLabel),
            parentHint = text(entry.parentHint),
            markers = texts(entry.markers, 6),
            anchors = entry.anchors.map(::anchor).distinctBy { it.identity }.take(6),
            resKey = entry.resKey?.takeIf { RES.matches(it) },
            note = entry.note?.take(120)?.let(::noteText),
        )
    }

    private fun noteText(value: String): String? = value.takeIf { AtlasPrivacy.structuralLabel(it.take(60), "").isNotBlank() }

    private val SHAPE = Regex("^[0-9]{1,2} texts?( · image)?( · button)?$")
    private val ROOM = Regex("^screen:[a-z_]{1,20}:[0-9a-f]{16}$|^screen:unknown$")
    private val HEX = Regex("^[0-9a-f]{1,32}$")
    private val RES = Regex("^[A-Za-z0-9_.]{2,80}$")
}

/** JSON with a fixed set of keys: what is written is exactly what [DictionaryPrivacy] allowed. */
object DictionaryJson {
    val ENTRY_KEYS = setOf("id", "kind", "name", "nameProof", "resKey", "aliases", "ownerLabel", "parentId", "parentHint",
        "status", "anchors", "markers", "observations", "days", "firstSeenAt", "lastSeenAt", "versions", "missedPasses",
        "redirectTo", "note")
    val ANCHOR_KEYS = setOf("kind", "roomKey", "containerKey", "screenTitle", "position", "siblings", "groups", "rowShape",
        "searchable", "searchLabel")

    fun write(dictionary: AppDictionary): JSONObject = JSONObject()
        .put("schema", 1)
        .put("packageName", dictionary.packageName)
        .put("passes", dictionary.passes)
        .put("updatedAt", dictionary.updatedAt)
        .put("entries", JSONArray().also { out -> dictionary.entries.values.mapNotNull(DictionaryPrivacy::entry).forEach { out.put(entry(it)) } })
        .put("audit", JSONArray().also { out ->
            dictionary.audit.forEach { e ->
                out.put(JSONObject().put("at", e.at).put("action", e.action).put("entryId", e.entryId).put("detail", e.detail.take(160)).put("by", e.by))
            }
        })
        .put("jev", JSONObject().put("answered", dictionary.jev.answered).put("agreed", dictionary.jev.agreed)
            .put("sureAnswered", dictionary.jev.sureAnswered).put("sureAgreed", dictionary.jev.sureAgreed)
            .put("timesMs", JSONArray(dictionary.jev.timesMs)).put("lastError", dictionary.jev.lastError ?: JSONObject.NULL))

    fun entry(e: DictEntry): JSONObject = JSONObject()
        .put("id", e.id)
        .put("kind", e.kind.wire)
        .put("name", e.name)
        .put("nameProof", e.nameProof.wire)
        .put("resKey", e.resKey ?: JSONObject.NULL)
        .put("aliases", JSONArray(e.aliases))
        .put("ownerLabel", e.ownerLabel ?: JSONObject.NULL)
        .put("parentId", e.parentId ?: JSONObject.NULL)
        .put("parentHint", e.parentHint ?: JSONObject.NULL)
        .put("status", e.status.wire)
        .put("anchors", JSONArray().also { out -> e.anchors.forEach { out.put(anchor(it)) } })
        .put("markers", JSONArray(e.markers))
        .put("observations", e.observations)
        .put("days", JSONArray(e.days))
        .put("firstSeenAt", e.firstSeenAt)
        .put("lastSeenAt", e.lastSeenAt)
        .put("versions", JSONArray(e.versions))
        .put("missedPasses", e.missedPasses)
        .put("redirectTo", e.redirectTo ?: JSONObject.NULL)
        .put("note", e.note ?: JSONObject.NULL)

    fun anchor(a: Anchor): JSONObject = JSONObject()
        .put("kind", a.kind.wire)
        .put("roomKey", a.roomKey)
        .put("containerKey", a.containerKey)
        .put("screenTitle", a.screenTitle ?: JSONObject.NULL)
        .put("position", a.position ?: JSONObject.NULL)
        .put("siblings", JSONArray(a.siblings))
        .put("groups", JSONArray(a.groups))
        .put("rowShape", a.rowShape ?: JSONObject.NULL)
        .put("searchable", a.searchable)
        .put("searchLabel", a.searchLabel ?: JSONObject.NULL)

    fun read(json: JSONObject): AppDictionary {
        val entries = LinkedHashMap<String, DictEntry>()
        json.optJSONArray("entries")?.let { array ->
            for (i in 0 until array.length()) {
                val raw = array.optJSONObject(i) ?: continue
                val parsed = parseEntry(raw)?.let(DictionaryPrivacy::entry) ?: continue
                entries[parsed.id] = parsed
            }
        }
        val audit = ArrayList<AuditEvent>()
        json.optJSONArray("audit")?.let { array ->
            for (i in 0 until array.length()) {
                val e = array.optJSONObject(i) ?: continue
                audit += AuditEvent(e.optLong("at"), e.optString("action"), e.optString("entryId"), e.optString("detail").take(160), e.optString("by"))
            }
        }
        val jev = json.optJSONObject("jev")?.let { j ->
            JevOrganizerTally(j.optInt("answered"), j.optInt("agreed"), j.optInt("sureAnswered"), j.optInt("sureAgreed"),
                j.optJSONArray("timesMs")?.let { t -> (0 until t.length()).map { t.optLong(it) } }.orEmpty(),
                j.optString("lastError").takeIf { it.isNotBlank() && it != "null" })
        } ?: JevOrganizerTally()
        return AppDictionary(json.optString("packageName"), entries, audit.takeLast(AppDictionary.MAX_AUDIT), jev,
            json.optInt("passes"), json.optLong("updatedAt"))
    }

    private fun strings(array: JSONArray?): List<String> = array?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }.orEmpty()
    private fun nullable(json: JSONObject, key: String): String? = json.optString(key).takeIf { json.has(key) && !json.isNull(key) && it.isNotBlank() }

    private fun parseEntry(json: JSONObject): DictEntry? {
        val id = json.optString("id").takeIf { ID.matches(it) } ?: return null
        return DictEntry(
            id = id,
            kind = CoreKind.fromWire(json.optString("kind")) ?: CoreKind.OTHER,
            name = json.optString("name"),
            nameProof = ChromeProof.fromWire(json.optString("nameProof")) ?: return null,
            resKey = nullable(json, "resKey"),
            aliases = strings(json.optJSONArray("aliases")),
            ownerLabel = nullable(json, "ownerLabel"),
            parentId = nullable(json, "parentId")?.takeIf { ID.matches(it) },
            parentHint = nullable(json, "parentHint"),
            status = EntryStatus.fromWire(json.optString("status")) ?: EntryStatus.CANDIDATE,
            anchors = json.optJSONArray("anchors")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::parseAnchor) } }.orEmpty(),
            markers = strings(json.optJSONArray("markers")),
            observations = json.optInt("observations"),
            days = json.optJSONArray("days")?.let { d -> (0 until d.length()).map { d.optLong(it) } }.orEmpty(),
            firstSeenAt = json.optLong("firstSeenAt"),
            lastSeenAt = json.optLong("lastSeenAt"),
            versions = strings(json.optJSONArray("versions")),
            missedPasses = json.optInt("missedPasses"),
            redirectTo = nullable(json, "redirectTo")?.takeIf { ID.matches(it) },
            note = nullable(json, "note"),
        )
    }

    private fun parseAnchor(json: JSONObject): Anchor? {
        val kind = AnchorKind.fromWire(json.optString("kind")) ?: return null
        return Anchor(
        kind = kind,
        roomKey = json.optString("roomKey"),
        containerKey = json.optString("containerKey"),
        screenTitle = nullable(json, "screenTitle"),
        position = if (json.has("position") && !json.isNull("position")) json.optInt("position") else null,
        siblings = strings(json.optJSONArray("siblings")),
        groups = strings(json.optJSONArray("groups")),
        rowShape = nullable(json, "rowShape"),
        searchable = json.optBoolean("searchable"),
        searchLabel = nullable(json, "searchLabel"),
    )
    }

    val ID = Regex("^set:[\\p{L}\\p{N}_]{1,40}$")
}
