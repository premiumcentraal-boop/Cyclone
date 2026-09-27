package com.cyclone.mobile.mind

import com.cyclone.mobile.mind.mission.MindRedaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One durable fact the Mind chose to keep for future missions. */
data class MindFact(
    val id: String,
    val text: String,
    val createdAtMs: Long,
    val lastUsedAtMs: Long,
    val missionId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("created", createdAtMs)
        .put("used", lastUsedAtMs).put("mission", missionId ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject) = MindFact(json.getString("id"), json.optString("text"), json.optLong("created"),
            json.optLong("used"), json.optString("mission").takeUnless { json.isNull("mission") || it.isBlank() })
    }
}

/**
 * What the Mind remembers between missions: short facts about the owner, their accounts' public names, apps and
 * where things are. The owner can read and delete every fact. Anything that looks like a secret is refused, not
 * stored, so passwords, codes, keys and card or account numbers never enter this file.
 */
class MindMemory(private val file: File, private val clock: () -> Long = System::currentTimeMillis, private val limit: Int = 200) {
    sealed class Saved {
        data class Stored(val fact: MindFact) : Saved()
        data class Updated(val fact: MindFact) : Saved()
        data class Refused(val reason: String) : Saved()
    }

    @Synchronized fun all(): List<MindFact> = read().sortedByDescending { maxOf(it.lastUsedAtMs, it.createdAtMs) }

    @Synchronized fun remember(text: String, missionId: String? = null): Saved {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length < 4) return Saved.Refused("the fact is empty")
        if (clean.length > MAX_CHARS) return Saved.Refused("keep a fact under $MAX_CHARS characters")
        if (MindRedaction.scrub(clean) != clean || SECRET_WORDS.containsMatchIn(clean)) {
            return Saved.Refused("it looks like it contains a secret (password, code, key or card/account number); secrets are never remembered")
        }
        val facts = read().toMutableList()
        val now = clock()
        facts.firstOrNull { it.text.equals(clean, ignoreCase = true) }?.let { existing ->
            val updated = existing.copy(lastUsedAtMs = now)
            facts[facts.indexOf(existing)] = updated
            write(facts)
            return Saved.Updated(updated)
        }
        val fact = MindFact(nextId(facts), clean, now, now, missionId)
        facts += fact
        write(facts.sortedByDescending { maxOf(it.lastUsedAtMs, it.createdAtMs) }.take(limit))
        return Saved.Stored(fact)
    }

    @Synchronized fun forget(id: String): Boolean {
        val facts = read()
        val kept = facts.filterNot { it.id == id.trim() }
        if (kept.size == facts.size) return false
        write(kept)
        return true
    }

    @Synchronized fun forgetAll() = write(emptyList())

    /** Facts sharing words with [query], best first; marks them as used. */
    @Synchronized fun search(query: String, max: Int = 12): List<MindFact> {
        val words = words(query)
        if (words.isEmpty()) return emptyList()
        val facts = read()
        val hits = facts.map { it to words(it.text).intersect(words).size }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<MindFact, Int>> { it.second }.thenByDescending { it.first.lastUsedAtMs })
            .take(max).map { it.first }
        if (hits.isNotEmpty()) {
            val now = clock()
            val ids = hits.map { it.id }.toSet()
            write(facts.map { if (it.id in ids) it.copy(lastUsedAtMs = now) else it })
        }
        return hits
    }

    /** The block placed in a new mission's opening message. */
    @Synchronized fun digest(maxChars: Int = 3_000): String {
        val lines = mutableListOf<String>()
        var used = 0
        for (fact in all()) {
            val line = "- [${fact.id}] ${fact.text}"
            if (used + line.length > maxChars) break
            lines += line
            used += line.length
        }
        return lines.joinToString("\n")
    }

    private fun nextId(facts: List<MindFact>): String =
        "f" + ((facts.mapNotNull { it.id.removePrefix("f").toIntOrNull() }.maxOrNull() ?: 0) + 1)

    private fun read(): List<MindFact> = runCatching {
        if (!file.isFile) return emptyList()
        val array = JSONObject(file.readText()).optJSONArray("facts") ?: JSONArray()
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let { json -> runCatching { MindFact.fromJson(json) }.getOrNull() } }
    }.getOrDefault(emptyList())

    private fun write(facts: List<MindFact>) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(JSONObject().put("schema", SCHEMA).put("facts", JSONArray().also { array -> facts.forEach { array.put(it.toJson()) } }).toString())
        if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
    }

    companion object {
        const val SCHEMA = "cyclone-mind-memory-v1"
        const val MAX_CHARS = 300
        private val SECRET_WORDS = Regex("(?i)\\b(password|passcode|wachtwoord|pin code|otp|one[- ]time code|verification code|cvv|cvc|api key|secret key|private key|seed phrase|recovery phrase)\\b")
        private val STOP = setOf("the", "a", "an", "of", "to", "in", "on", "for", "and", "or", "is", "my", "me", "de", "het", "een", "van", "op", "en", "mijn")
        private fun words(text: String): Set<String> =
            text.lowercase().split(Regex("[^\\p{L}\\p{N}@.]+")).map { it.trim('.') }.filter { it.length >= 2 && it !in STOP }.toSet()
    }
}
