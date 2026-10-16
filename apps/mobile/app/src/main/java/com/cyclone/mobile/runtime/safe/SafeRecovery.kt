package com.cyclone.mobile.runtime.safe

import org.json.JSONObject
import java.io.InputStream

/** A damaged newest header must not hide an older recoverable archive. No destination is touched here. */
internal object SafeRecovery {
    class Opened(val key: ByteArray, val header: JSONObject) : AutoCloseable {
        override fun close() = key.fill(0)
        override fun toString() = "SafeRecovery(redacted)"
    }
    fun find(sequences: List<Long>, user: Int, source: (Long) -> InputStream, unwrap: (JSONObject) -> ByteArray): Opened {
        for (seq in sequences.distinct().sortedDescending()) {
            var key: ByteArray? = null
            try {
                val hint = source(seq).use(SafeSnapshot::peek)
                val candidate = unwrap(hint)
                key = candidate
                val (header, entries) = source(seq).use { SafeSnapshot.read(it, candidate) }
                try {
                    check(header.getInt("androidUserId") == user && header.getLong("seq") == seq)
                    return Opened(candidate, header).also { key = null }
                } finally { entries.forEach { it.close() } }
            } catch (_: Exception) { /* Try an older complete copy, without exposing the parser failure. */ }
            finally { key?.fill(0) }
        }
        error("SAFE_NO_VALID_COPY")
    }
}
