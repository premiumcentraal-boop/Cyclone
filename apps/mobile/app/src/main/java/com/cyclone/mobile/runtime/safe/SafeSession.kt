package com.cyclone.mobile.runtime.safe

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Platform-independent authenticated commit/recovery path. Sources never receive plaintext keys. */
internal class SafeSession(
    private val privateDirectory: File,
    private val read: (Long, File) -> Unit,
    private val publish: (Long, File, ByteArray) -> Unit,
) {
    fun save(key: ByteArray, identity: SafeSnapshot.Identity, entries: List<SafeSnapshot.Entry>, wraps: JSONObject) {
        privateDirectory.mkdirs()
        val ciphertext = File.createTempFile("safe-", ".cyc", privateDirectory)
        try {
            FileOutputStream(ciphertext).use { out ->
                SafeSnapshot.write(out, key, identity, entries, wraps)
                out.fd.sync()
            }
            // Authenticate our local output before publishing. Publication also authenticates its read-back.
            verify(ciphertext, key, identity.user, identity.seq)
            publish(identity.seq, ciphertext, key)
        } finally { ciphertext.delete() }
    }

    /** A bad newest snapshot must never conceal an older complete one. No writes until full authentication. */
    fun recover(
        key: ByteArray, user: Int, sequences: List<Long>, accepts: (String) -> Boolean,
        expectedPhone: String? = null,
        beforeApply: (JSONObject) -> Unit = {},
        write: (SafeSnapshot.Entry) -> Unit,
    ): JSONObject {
        privateDirectory.mkdirs()
        val ciphertext = File.createTempFile("safe-restore-", ".cyc", privateDirectory)
        try {
            for (seq in sequences.distinct().sortedDescending()) {
                val opened = try {
                    read(seq, ciphertext)
                    val result = ciphertext.inputStream().use { SafeSnapshot.read(it, key) }
                    if (result.first.getInt("androidUserId") != user || result.first.getLong("seq") != seq ||
                        (expectedPhone != null && result.first.getString("phoneId") != expectedPhone)) {
                        result.second.forEach { it.close() }
                        continue
                    }
                    result
                } catch (_: Exception) { continue }
                try {
                    // Application errors stop recovery, never silently mix two different snapshots.
                    beforeApply(opened.first)
                    SafeRestore.apply(opened.second, accepts, write)
                    return opened.first
                } finally { opened.second.forEach { it.close() } }
            }
            error("SAFE_NO_VALID_COPY")
        } finally { ciphertext.delete() }
    }

    private fun verify(file: File, key: ByteArray, user: Int, seq: Long) {
        val (header, entries) = file.inputStream().use { SafeSnapshot.read(it, key) }
        try { check(header.getInt("androidUserId") == user && header.getLong("seq") == seq) }
        finally { entries.forEach { it.close() } }
    }
}
