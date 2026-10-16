package com.cyclone.mobile.runtime.safe

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** Closed command set. Never accepts shell text, caller paths, package names or secrets as argv. */
internal object SafeRootPlan {
    const val ROOT = "/data/adb/cyclone-safe"
    private fun user(user: Int): Int { require(user in 0..99999); return user }
    fun name(user: Int, seq: Long): String { user(user); require(seq > 0); return "safe-$user-$seq.cyc" }
    fun list(user: Int): String = "find $ROOT/u${user(user)} -maxdepth 1 -type f -name 'safe-${user}-*.cyc' -print"
    fun read(user: Int, seq: Long): String = "cat $ROOT/u${user(user)}/${name(user, seq)}"
    fun rootKey(): String = "test -f $ROOT/safe.key && test ! -L $ROOT/safe.key && test \"\$(stat -c %a $ROOT/safe.key)\" = 600 && test \"\$(stat -c %u $ROOT/safe.key)\" = 0 && cat $ROOT/safe.key"
    fun createRootKey(): String = "umask 077; mkdir -p $ROOT && chown 0:0 $ROOT && chmod 700 $ROOT && " +
        "test ! -L $ROOT && (set -C; cat > $ROOT/safe.key) && chown 0:0 $ROOT/safe.key && chmod 600 $ROOT/safe.key && sync"
    fun write(user: Int, seq: Long): String {
        val dir = "$ROOT/u${user(user)}"; val file = "$dir/${name(user, seq)}"
        // stdin carries ciphertext only. Retention happens separately, after authenticated read-back.
        return "umask 077; test -d $ROOT && test ! -L $ROOT && mkdir -p $dir && test ! -L $dir && " +
            "test ! -e $file && test ! -e $file.part && cat > $file.part && chown 0:0 $file.part && chmod 600 $file.part && sync && mv $file.part $file && sync"
    }
    fun removeInvalid(user: Int, seq: Long): String = "rm -f $ROOT/u${user(user)}/${name(user, seq)}"
    fun deleteCopy(user: Int, seq: Long): String = removeInvalid(user, seq)
}

/** Private transport. Output is never sent through the bootstrap command journal or exception messages. */
internal class SafeRootStore {
    private fun requireMain() = check(android.os.Process.myUid() / 100000 == 0) { "SAFE_MAIN_REQUIRED" }
    private fun execute(plan: String, input: InputStream? = null, destination: OutputStream? = null, maxBytes: Long = 65536): ByteArray {
        val process = ProcessBuilder("su", "--mount-master", "-c", plan).start()
        val errors = Thread { runCatching { process.errorStream.use { stream -> val b = ByteArray(1024); while (stream.read(b) >= 0) b.fill(0) } } }
        errors.isDaemon = true; errors.start()
        val watchdog = Thread {
            if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly()
        }.apply { isDaemon = true; start() }
        try {
            process.outputStream.use { out -> input?.use { it.copyTo(out) } }
            val collected = WipingBuffer()
            val out = destination ?: collected
            val buffer = ByteArray(65536)
            try {
                process.inputStream.use { stream ->
                    var total = 0L
                    while (true) {
                        val n = stream.read(buffer); if (n < 0) break
                        total += n; check(total <= maxBytes) { "SAFE_ROOT_OUTPUT_LIMIT" }
                        out.write(buffer, 0, n)
                    }
                }
                check(process.waitFor() == 0) { "SAFE_ROOT_UNAVAILABLE" }
                return collected.toByteArray()
            } finally {
                buffer.fill(0)
                collected.wipe()
            }
        } finally { process.destroy(); watchdog.join(100); errors.join(100) }
    }
    private class WipingBuffer : ByteArrayOutputStream() {
        fun wipe() { buf.fill(0); reset() }
    }
    fun key(): ByteArray = execute(SafeRootPlan.rootKey(), maxBytes = 32).also {
        if (it.size != 32) { it.fill(0); error("SAFE_ROOT_KEY_INVALID") }
    }
    fun createKey(key: ByteArray) {
        requireMain(); require(key.size == 32)
        execute(SafeRootPlan.createRootKey(), key.inputStream(), maxBytes = 0)
    }
    fun list(user: Int): List<Long> = String(execute(SafeRootPlan.list(user)), Charsets.UTF_8).lineSequence()
        .mapNotNull { Regex("${Regex.escape(SafeRootPlan.ROOT)}/u$user/safe-$user-([1-9][0-9]*)\\.cyc").matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
        .distinct().sortedDescending().toList()
    fun copyTo(user: Int, seq: Long, target: File) {
        FileOutputStream(target).use { out ->
            execute(SafeRootPlan.read(user, seq), destination = out, maxBytes = SafeSnapshot.MAX_TOTAL)
            out.fd.sync()
        }
    }
    @Synchronized fun put(user: Int, seq: Long, ciphertext: File, key: ByteArray) {
        requireMain()
        val readback = File.createTempFile("safe-readback-", ".cyc", ciphertext.parentFile)
        try {
            execute(SafeRootPlan.write(user, seq), ciphertext.inputStream(), maxBytes = 0)
            try {
                copyTo(user, seq, readback)
                val (header, entries) = readback.inputStream().use { SafeSnapshot.read(it, key) }
                try { check(header.getInt("androidUserId") == user && header.getLong("seq") == seq) { "SAFE_ROOT_MISMATCH" } }
                finally { entries.forEach { it.close() } }
            } catch (_: Exception) {
                execute(SafeRootPlan.removeInvalid(user, seq), maxBytes = 0)
                throw IllegalStateException("SAFE_ROOT_VERIFY_FAILED")
            }
            list(user).drop(3).forEach { execute(SafeRootPlan.deleteCopy(user, it), maxBytes = 0) }
        } finally { readback.delete() }
    }
}
