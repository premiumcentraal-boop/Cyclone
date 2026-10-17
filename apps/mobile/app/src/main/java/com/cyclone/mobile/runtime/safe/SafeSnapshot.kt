package com.cyclone.mobile.runtime.safe

import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Ciphertext-only wire format. No key, plaintext or exception payload is suitable for logging. */
internal object SafeSnapshot {
    const val FORMAT = 1
    const val CHUNK = 64 * 1024
    const val MAX_HEADER = 1024 * 1024
    const val MAX_ENTRY = 512L * 1024 * 1024
    const val MAX_TOTAL = 2L * 1024 * 1024 * 1024
    private val magic = "CYCSAFE1".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    class Invalid : IOException("SAFE_INVALID")
    class Entry(val path: String, val bytes: ByteArray) : AutoCloseable {
        init { require(validPath(path)) }
        override fun close() = bytes.fill(0)
        override fun toString() = "SafeEntry(redacted)"
    }

    data class Identity(val user: Int, val profileId: String, val phoneId: String, val seq: Long, val time: Long) {
        init {
            require(user in 0..99999 && seq > 0 && time > 0)
            require(profileId.length in 1..160 && phoneId.matches(Regex("[A-Za-z0-9_-]{1,120}")))
        }
    }

    fun validPath(path: String): Boolean = path.length in 1..240 && !path.startsWith('/') &&
        !path.contains('\\') && !path.contains('\u0000') && path.split('/').all { it.isNotBlank() && it != "." && it != ".." }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun write(out: OutputStream, key: ByteArray, identity: Identity, entries: List<Entry>, wraps: JSONObject) {
        require(key.size == 32 && entries.size <= 10000 && entries.map { it.path }.distinct().size == entries.size)
        require(entries.sumOf { it.bytes.size.toLong() } <= MAX_TOTAL && entries.all { it.bytes.size <= MAX_ENTRY })
        val header = JSONObject().put("format", FORMAT).put("androidUserId", identity.user)
            .put("profileId", identity.profileId).put("phoneId", identity.phoneId).put("seq", identity.seq)
            .put("createdAt", identity.time).put("wraps", wraps)
            .put("entries", JSONArray().apply { entries.sortedBy { it.path }.forEach {
                put(JSONObject().put("path", it.path).put("size", it.bytes.size).put("sha256", sha(it.bytes)))
            } }).toString().toByteArray(Charsets.UTF_8)
        require(header.size <= MAX_HEADER)
        val data = DataOutputStream(out)
        data.write(magic); data.writeInt(header.size); data.write(header)
        val chunks = EncryptingChunks(data, key, MessageDigest.getInstance("SHA-256").digest(header))
        ZipOutputStream(chunks).use { zip ->
            entries.sortedBy { it.path }.forEach {
                zip.putNextEntry(ZipEntry(it.path).apply { time = identity.time })
                zip.write(it.bytes); zip.closeEntry()
            }
        }
    }

    /** Header is untrusted until [read] completes. Use it only for bounded previews/key selection. */
    fun peek(input: InputStream): JSONObject {
        val data = DataInputStream(input)
        val prefix = ByteArray(magic.size); data.readFully(prefix)
        if (!prefix.contentEquals(magic)) throw Invalid()
        val length = data.readInt()
        if (length !in 2..MAX_HEADER) throw Invalid()
        return JSONObject(String(ByteArray(length).also(data::readFully), Charsets.UTF_8)).also {
            if (it.getInt("format") != FORMAT) throw Invalid()
            Identity(it.getInt("androidUserId"), it.getString("profileId"), it.getString("phoneId"), it.getLong("seq"), it.getLong("createdAt"))
        }
    }

    /** Authenticate the complete archive BEFORE handing any entry to a restore destination. */
    fun read(input: InputStream, key: ByteArray): Pair<JSONObject, List<Entry>> {
        require(key.size == 32)
        val entries = mutableListOf<Entry>()
        try {
            val data = DataInputStream(input)
            val prefix = ByteArray(magic.size); data.readFully(prefix)
            if (!prefix.contentEquals(magic)) throw Invalid()
            val size = data.readInt(); if (size !in 2..MAX_HEADER) throw Invalid()
            val raw = ByteArray(size).also(data::readFully)
            val header = JSONObject(String(raw, Charsets.UTF_8))
            if (header.getInt("format") != FORMAT) throw Invalid()
            Identity(header.getInt("androidUserId"), header.getString("profileId"), header.getString("phoneId"), header.getLong("seq"), header.getLong("createdAt"))
            val expected = header.getJSONArray("entries")
            if (expected.length() > 10000) throw Invalid()
            val hashes = mutableMapOf<String, JSONObject>()
            for (i in 0 until expected.length()) {
                val row = expected.getJSONObject(i); val path = row.getString("path")
                if (!validPath(path) || hashes.put(path, row) != null || row.getLong("size") !in 0..MAX_ENTRY) throw Invalid()
            }
            var total = 0L
            val chunks = DecryptingChunks(data, key, MessageDigest.getInstance("SHA-256").digest(raw))
            val zip = ZipInputStream(chunks)
            while (true) {
                val item = zip.nextEntry ?: break
                val meta = hashes.remove(item.name) ?: throw Invalid()
                if (item.isDirectory) throw Invalid()
                val bytes = WipingBuffer()
                val buffer = ByteArray(CHUNK)
                try {
                    while (true) {
                        val n = zip.read(buffer); if (n < 0) break
                        total += n
                        if (bytes.size().toLong() + n > meta.getLong("size") || total > MAX_TOTAL) throw Invalid()
                        bytes.write(buffer, 0, n)
                    }
                    val value = bytes.toByteArray()
                    if (value.size.toLong() != meta.getLong("size") || sha(value) != meta.getString("sha256")) {
                        value.fill(0); throw Invalid()
                    }
                    entries += Entry(item.name, value)
                } finally { buffer.fill(0); bytes.wipe() }
            }
            // ZIP may stop at its directory before consuming the encrypted terminal frame.
            chunks.authenticateEnd()
            if (hashes.isNotEmpty()) throw Invalid()
            return header to entries
        } catch (_: Exception) {
            entries.forEach(Entry::close)
            throw Invalid()
        }
    }

    private class WipingBuffer : ByteArrayOutputStream() {
        fun wipe() { buf.fill(0); reset() }
    }

    private fun aad(header: ByteArray, index: Long, terminal: Boolean): ByteArray =
        ByteArrayOutputStream().also { b -> DataOutputStream(b).apply { write(header); writeLong(index); writeBoolean(terminal) } }.toByteArray()

    private class EncryptingChunks(val out: DataOutputStream, val key: ByteArray, val header: ByteArray) : OutputStream() {
        val buffer = ByteArray(CHUNK); var used = 0; var index = 0L
        override fun write(value: Int) { buffer[used++] = value.toByte(); if (used == CHUNK) frame(false) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            var from = offset; var left = length
            while (left > 0) {
                val n = minOf(left, CHUNK - used); bytes.copyInto(buffer, used, from, from + n)
                used += n; from += n; left -= n; if (used == CHUNK) frame(false)
            }
        }
        fun frame(terminal: Boolean) {
            val nonce = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(header, index++, terminal))
            val ct = cipher.doFinal(buffer, 0, used)
            out.writeInt(if (terminal) -1 else used); out.write(nonce); out.write(ct)
            buffer.fill(0); used = 0
        }
        override fun close() { if (used > 0) frame(false); frame(true); out.flush(); buffer.fill(0) }
    }

    private class DecryptingChunks(val input: DataInputStream, val key: ByteArray, val header: ByteArray) : InputStream() {
        var buffer = ByteArray(0); var position = 0; var index = 0L; var ended = false
        fun next(): Boolean {
            buffer.fill(0)
            val size = input.readInt(); if (size != -1 && size !in 1..CHUNK) throw Invalid()
            val terminal = size == -1
            val nonce = ByteArray(12).also(input::readFully)
            val ct = ByteArray((if (terminal) 0 else size) + 16).also(input::readFully)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(header, index++, terminal))
            buffer = cipher.doFinal(ct); position = 0
            if (terminal) { ended = true; if (input.read() != -1) throw Invalid() }
            return !ended
        }
        override fun read(): Int {
            if (position == buffer.size && (ended || !next())) return -1
            return buffer[position++].toInt() and 255
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position == buffer.size && (ended || !next())) return -1
            val n = minOf(length, buffer.size - position); buffer.copyInto(bytes, offset, position, position + n)
            position += n; return n
        }
        fun authenticateEnd() { while (!ended) next(); buffer.fill(0) }
    }
}
