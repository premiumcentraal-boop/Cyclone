package com.cyclone.mobile.runtime.safe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Authenticated bridge only: ciphertext and metadata. No model tools, plaintext read or local delete entry point. */
internal object SafeBridge {
    const val CHUNK = 256 * 1024
    private val lock = Any()
    fun dispatch(context: Context, operation: String, args: JSONObject): JSONObject = synchronized(lock) {
        check(android.os.Process.myUid() / 100000 == 0) { "SAFE_MAIN_REQUIRED" }
        val runtime = SafeRuntime(context)
        when (operation) {
            "safe.list" -> {
                require(args.length() == 0)
                val snapshots = JSONArray()
                val record = runtime.archiveRecord()
                val deletion = runtime.deletionRequest()
                if (deletion == null) runtime.archiveCopies().forEach(snapshots::put)
                JSONObject().put("phoneId", record.getString("phoneId")).put("snapshots", snapshots)
                    .put("deleteRequest", deletion ?: JSONObject.NULL)
            }
            "safe.get" -> {
                fields(args, setOf("user", "seq", "offset"))
                val offset = args.getLong("offset")
                val data = runtime.archiveChunk(args.getInt("user"), args.getLong("seq"), offset)
                JSONObject().put("offset", offset).put("data", SafeKey.b64(data))
            }
            "safe.put" -> if (args.has("receipt")) {
                fields(args, setOf("receipt"))
                val receipt = args.getJSONObject("receipt")
                fields(receipt, setOf("user", "seq", "sha256"))
                check(runtime.archiveCopies().single { it.getInt("user") == receipt.getInt("user") && it.getLong("seq") == receipt.getLong("seq") }
                    .getString("sha256") == receipt.getString("sha256"))
                SafeRuntime(context).notePcReceipt(trustId())
                JSONObject().put("recorded", true)
            } else if (args.has("keyEnvelope")) {
                fields(args, setOf("keyEnvelope"))
                SafeRuntime(context).stagePcKey(args.getJSONObject("keyEnvelope"))
                JSONObject().put("recorded", true)
            } else receive(context, args)
            // This acknowledges a PC-side deletion. It can neither mint a token nor delete local data.
            "safe.delete" -> {
                fields(args, setOf("nonce", "deleted")); require(args.getBoolean("deleted"))
                SafeRuntime(context).ackDeletion(trustId(), args.getString("nonce"))
                JSONObject().put("recorded", true)
            }
            else -> error("SAFE_OPERATION_UNKNOWN")
        }
    }
    private fun fields(args: JSONObject, expected: Set<String>) { require(args.keys().asSequence().toSet() == expected) }
    private fun trustId(): String = com.cyclone.mobile.gateway.GatewaySessionExecutionContext.currentTrusted()?.trustId ?: error("SAFE_TRUST_REQUIRED")

    /** Inbox is ciphertext only. Upload never restores or changes any live store; the owner must choose Restore. */
    private fun receive(context: Context, args: JSONObject): JSONObject {
        fields(args, setOf("user", "seq", "offset", "size", "sha256", "data"))
        val user = args.getInt("user"); val seq = args.getLong("seq"); val offset = args.getLong("offset"); val size = args.getLong("size")
        val digest = args.getString("sha256")
        SafeRootPlan.name(user, seq)
        require(offset >= 0 && offset % CHUNK == 0L && size in 44..SafeSnapshot.MAX_TOTAL && digest.matches(Regex("[a-f0-9]{64}")))
        require(args.getString("data").length <= (CHUNK + 2) / 3 * 4)
        val data = SafeKey.un64(args.getString("data"))
        require(data.size in 1..CHUNK && offset + data.size <= size && (data.size == CHUNK || offset + data.size == size))
        val dir = File(context.cacheDir, "safe-inbox").apply { mkdirs() }
        // Exactly one bounded inbox transfer. Restarting at zero discards only ciphertext staging.
        val part = File(dir, "incoming.cyc.part")
        val meta = File(dir, "incoming.json")
        val header = JSONObject().put("user", user).put("seq", seq).put("size", size).put("sha256", digest)
        if (offset == 0L) {
            part.outputStream().use { }
            SafeFiles.atomic(meta, header.toString().toByteArray())
        } else check(meta.isFile && JSONObject(meta.readText()).toString() == header.toString()) { "SAFE_UPLOAD_CHANGED" }
        check(part.length() == offset) { "SAFE_UPLOAD_OFFSET" }
        RandomAccessFile(part, "rw").use { out -> out.seek(offset); out.write(data); out.fd.sync() }
        val received = offset + data.size
        if (received == size) {
            val sha = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { stream -> val buffer = ByteArray(65536); while (true) { val n = stream.read(buffer); if (n < 0) break; sha.update(buffer, 0, n) } }
            check(sha.digest().joinToString("") { "%02x".format(it) } == digest) { "SAFE_UPLOAD_DIGEST" }
            val snapshot = part.inputStream().use(SafeSnapshot::peek)
            check(snapshot.getInt("androidUserId") == user && snapshot.getLong("seq") == seq) { "SAFE_UPLOAD_METADATA" }
            java.nio.file.Files.move(part.toPath(), File(dir, SafeRootPlan.name(user, seq)).toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        return JSONObject().put("received", received).put("complete", received == size)
    }
}
