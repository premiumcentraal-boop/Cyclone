package com.cyclone.mobile.runtime.workspaces

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Internal bootstrap files retain nonce/user/ok. Optional stage/reasonCode never contain transfer contents. */
internal object ProfileBootstrapAcknowledgement {
    enum class Stage { KEYSTORE, TRANSFER, SETTINGS, PERMISSIONS, ORIGIN, COMPLETE }
    data class Reply(val ok: Boolean, val user: Int, val nonce: String, val stage: String, val reasonCode: String?) {
        fun json(): JSONObject = summary().put("nonce", nonce)
        fun summary(): JSONObject = JSONObject().put("ok", ok).put("user", user).put("stage", stage)
            .put("reasonCode", reasonCode ?: JSONObject.NULL)
    }
    class Failed(val reply: Reply) : IllegalStateException(
        "The receiving Cyclone failed during ${reply.stage} (${reply.reasonCode ?: "BOOTSTRAP_FAILED"}). Your current profile was kept open.")
    class TimedOut : IllegalStateException("The receiving Cyclone did not return a matching bootstrap acknowledgement in time. Retry opening the profile; your current profile was kept open.")

    fun parse(text: String?): Reply? = runCatching {
        require(text != null && text.length <= 8192)
        val value = JSONObject(text)
        val ok = value.get("ok") as? Boolean ?: return null
        val user = value.opt("user") as? Number ?: return null
        val number = user.toDouble()
        require(number.isFinite() && number >= 0 && number <= 21473 && number % 1.0 == 0.0)
        val nonce = value.opt("nonce") as? String ?: ""
        require(nonce.length <= 128)
        // Only fixed diagnostic vocabulary is allowed into the shareable journal.
        val stage = value.optString("stage").takeIf { s -> Stage.entries.any { it.name == s } } ?: "TRANSFER"
        val reason = value.optString("reasonCode").takeIf { it in setOf("BOOTSTRAP_FAILED", "REPLY_WRITE_FAILED") }
        Reply(ok, number.toInt(), nonce, stage, reason)
    }.getOrNull()

    fun write(file: File, reply: Reply) {
        val temporary = File.createTempFile(".bootstrap-ack-", ".tmp", file.absoluteFile.parentFile)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(reply.json().toString().toByteArray(Charsets.UTF_8)); stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    /** Missing/truncated/transient/stale replies retry. A correlated negative reply fails immediately. */
    fun await(target: Int, nonce: String, read: () -> String?, timeoutMs: Long = 30_000,
              now: () -> Long = { System.nanoTime() / 1_000_000 }, sleep: (Long) -> Unit = Thread::sleep,
              observe: (Reply?, String) -> Unit = { _, _ -> }): Reply {
        require(target > 0 && nonce.isNotBlank() && timeoutMs > 0)
        val deadline = now() + timeoutMs
        repeat(150) {
            val reply = parse(runCatching(read).getOrNull())
            if (now() >= deadline) { observe(reply, "ACK_TIMEOUT"); throw TimedOut() }
            if (reply != null && reply.nonce == nonce && reply.user == target) {
                observe(reply, if (reply.ok) "ACK_COMPLETED" else "ACK_FAILED")
                if (!reply.ok) throw Failed(reply)
                return reply
            }
            sleep(minOf(200, (deadline - now()).coerceAtLeast(0)))
        }
        observe(null, "ACK_TIMEOUT")
        throw TimedOut()
    }
}
