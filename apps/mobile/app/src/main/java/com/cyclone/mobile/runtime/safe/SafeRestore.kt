package com.cyclone.mobile.runtime.safe

import java.io.InputStream
import org.json.JSONObject

/** Pure restore planner. Verification finishes before any destination sees plaintext. */
internal object SafeRestore {
    enum class Source { ROOT, PC, FOLDER }
    data class Candidate(val source: Source, val user: Int, val seq: Long, val bytes: Long, val createdAt: Long)
    fun find(candidates: List<Candidate>, existingUsers: Set<Int>): List<Candidate> = candidates
        .filter { it.user in existingUsers && it.seq > 0 && it.bytes > 0 }
        .groupBy { it.user }.toSortedMap().values.map { rows ->
            rows.sortedWith(compareByDescending<Candidate> { it.seq }.thenBy { it.source.ordinal }).first()
        }
    fun from(input: InputStream, key: ByteArray, user: Int, accepts: (String) -> Boolean, write: (SafeSnapshot.Entry) -> Unit): JSONObject {
        val (header, entries) = SafeSnapshot.read(input, key)
        try {
            check(header.getInt("androidUserId") == user) { "SAFE_WRONG_ANDROID_USER" }
            apply(entries, accepts, write)
            return header
        } finally { entries.forEach { it.close() } }
    }
    enum class Stage { DATABASES, FILES, PREFS }
    fun stage(path: String): Stage = when {
        path.startsWith("databases/") -> Stage.DATABASES
        path.startsWith("files/") || path.startsWith("no_backup/") || path == "sealed/mind" -> Stage.FILES
        path.startsWith("prefs/") || path.startsWith("sealed/") -> Stage.PREFS
        else -> throw SafeSnapshot.Invalid()
    }
    fun apply(entries: List<SafeSnapshot.Entry>, accepts: (String) -> Boolean, write: (SafeSnapshot.Entry) -> Unit) {
        // Unknown entries fail closed before ANY write. No cross-snapshot merge.
        require(entries.map { it.path }.distinct().size == entries.size)
        entries.forEach { require(accepts(it.path)); stage(it.path) }
        entries.sortedWith(compareBy<SafeSnapshot.Entry> { stage(it.path).ordinal }.thenBy { it.path }).forEach(write)
    }
}
