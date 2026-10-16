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
    fun list(user: Int): String = "$ROOT/u${user(user)}".let {
        "test ! -L $it && if test -d $it; then find $it -maxdepth 1 -type f -name 'safe-${user}-*.cyc' -print; fi"
    }
    fun read(user: Int, seq: Long): String = "cat $ROOT/u${user(user)}/${name(user, seq)}"
    fun users(): String = "find $ROOT -mindepth 1 -maxdepth 1 -type d -name 'u[0-9]*' -print"
    fun describe(user: Int, seq: Long): String = "$ROOT/u${user(user)}/${name(user, seq)}".let {
        "test ! -L $it && stat -c %s $it && sha256sum $it"
    }
    fun prefix(user: Int, seq: Long): String = "head -c ${SafeSnapshot.MAX_HEADER + 12} $ROOT/u${user(user)}/${name(user, seq)}"
    fun chunk(user: Int, seq: Long, offset: Long): String {
        require(offset >= 0 && offset <= SafeSnapshot.MAX_TOTAL && offset % (256 * 1024) == 0L)
        return "dd if=$ROOT/u${user(user)}/${name(user, seq)} bs=65536 skip=${offset / 65536} count=4 2>/dev/null"
    }
    fun rootKey(): String = "test -f $ROOT/safe.key && test ! -L $ROOT/safe.key && test \"\$(stat -c %a $ROOT/safe.key)\" = 600 && test \"\$(stat -c %u $ROOT/safe.key)\" = 0 && cat $ROOT/safe.key"
    fun inventory(): String = "test ! -L $ROOT && if test -f $ROOT/safe.record; then echo RECORD; elif test -f $ROOT/safe.key; then echo KEY_ONLY; elif test -d $ROOT; then echo EMPTY; else echo EMPTY; fi"
    fun record(): String = "test ! -L $ROOT && test ! -L $ROOT/safe.record && cat $ROOT/safe.record"
    fun createRecord(): String = "umask 077; test -d $ROOT && test ! -L $ROOT && test ! -e $ROOT/safe.record && " +
        "(set -C; cat > $ROOT/safe.record.part) && chown 0:0 $ROOT/safe.record.part && chmod 600 $ROOT/safe.record.part && sync && mv $ROOT/safe.record.part $ROOT/safe.record && sync"
    fun updateRecord(): String = "umask 077; test -f $ROOT/safe.record && test ! -L $ROOT && test ! -L $ROOT/safe.record.update.part && " +
        "cat > $ROOT/safe.record.update.part && chown 0:0 $ROOT/safe.record.update.part && chmod 600 $ROOT/safe.record.update.part && sync && mv $ROOT/safe.record.update.part $ROOT/safe.record && sync"
    fun deleteAll(): String = "test ! -L $ROOT && rm -rf $ROOT"
    fun clearProfile(user: Int): String {
        require(user(user) > 0)
        return "/system/bin/pm clear --user $user com.cyclone.mobile"
    }
    private fun exchange(user: Int, nonce: String): String {
        require(user(user) > 0 && nonce.matches(Regex("[a-f0-9]{32}")))
        return "/data/user_de/$user/com.cyclone.mobile/files/safe-outbox/$nonce"
    }
    fun profileReady(user: Int): String {
        require(user(user) > 0)
        return "/system/bin/am get-started-user-state $user"
    }
    fun installProfile(user: Int): String {
        require(user(user) > 0)
        return "/system/bin/cmd package install-existing --user $user com.cyclone.mobile"
    }
    fun startProfile(user: Int): String {
        require(user(user) > 0)
        return "/system/bin/am start-user -w $user"
    }
    fun profileStart(user: Int, nonce: String, seq: Long, restore: Boolean): String {
        exchange(user, nonce); require(seq > 0)
        return "/system/bin/am start-foreground-service --user $user -n com.cyclone.mobile/.runtime.safe.SafeProfileService " +
            "-a com.cyclone.SAFE_${if (restore) "RESTORE" else "CAPTURE"} --es nonce $nonce --el seq $seq"
    }
    fun profileRead(user: Int, nonce: String, status: Boolean): String = "cat ${exchange(user, nonce)}.${if (status) "json" else "cyc"}"
    fun profileClean(user: Int, nonce: String): String = exchange(user, nonce).let { "rm -f $it.json $it.cyc" }
    fun notifyMain(user: Int): String {
        require(user(user) > 0)
        return "/system/bin/am start-foreground-service --user 0 -n com.cyclone.mobile/.runtime.safe.SafeMainService --ei source $user"
    }
    fun createRootKey(): String = "umask 077; test ! -L $ROOT && mkdir -p $ROOT && test ! -L $ROOT && chown 0:0 $ROOT && chmod 700 $ROOT && " +
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
    fun users(): List<Int> = String(execute(SafeRootPlan.users()), Charsets.UTF_8).lineSequence().mapNotNull {
        Regex("${Regex.escape(SafeRootPlan.ROOT)}/u([0-9]{1,5})").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull()
    }.distinct().sorted().toList()
    fun describe(user: Int, seq: Long): org.json.JSONObject {
        val lines = String(execute(SafeRootPlan.describe(user, seq)), Charsets.US_ASCII).lines()
        val size = lines[0].toLong()
        val sha = lines[1].substringBefore(' ')
        check(size in 44..SafeSnapshot.MAX_TOTAL && sha.matches(Regex("[a-f0-9]{64}"))) { "SAFE_ROOT_METADATA" }
        val header = SafeSnapshot.peek(execute(SafeRootPlan.prefix(user, seq), maxBytes = (SafeSnapshot.MAX_HEADER + 12).toLong()).inputStream())
        check(header.getInt("androidUserId") == user && header.getLong("seq") == seq)
        return org.json.JSONObject().put("user", user).put("seq", seq).put("bytes", size).put("sha256", sha).put("createdAt", header.getLong("createdAt"))
    }
    fun chunk(user: Int, seq: Long, offset: Long): ByteArray = execute(SafeRootPlan.chunk(user, seq, offset), maxBytes = 256 * 1024)
    fun createKey(key: ByteArray) {
        requireMain(); require(key.size == 32)
        execute(SafeRootPlan.createRootKey(), key.inputStream(), maxBytes = 0)
    }
    fun inventory(): String = String(execute(SafeRootPlan.inventory()), Charsets.US_ASCII).trim().also {
        check(it in setOf("RECORD", "KEY_ONLY", "EMPTY")) { "SAFE_ROOT_INVENTORY" }
    }
    fun record(): org.json.JSONObject = org.json.JSONObject(String(execute(SafeRootPlan.record()), Charsets.UTF_8))
    fun createRecord(record: org.json.JSONObject) {
        requireMain()
        val bytes = record.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= 65536)
        execute(SafeRootPlan.createRecord(), bytes.inputStream(), maxBytes = 0)
        check(record().toString() == record.toString()) { "SAFE_ROOT_RECORD_VERIFY" }
    }
    fun updateRecord(record: org.json.JSONObject) {
        requireMain()
        val old = record()
        check(record.getString("phoneId") == old.getString("phoneId") &&
            record.getJSONObject("wraps").getJSONObject("root").toString() == old.getJSONObject("wraps").getJSONObject("root").toString())
        val bytes = record.toString().toByteArray()
        check(bytes.size <= 65536)
        execute(SafeRootPlan.updateRecord(), bytes.inputStream(), maxBytes = 0)
    }
    fun deleteAll() { requireMain(); execute(SafeRootPlan.deleteAll(), maxBytes = 0) }
    fun clearProfile(user: Int) { requireMain(); check(String(execute(SafeRootPlan.clearProfile(user))).trim() == "Success") { "SAFE_PROFILE_CLEAR_FAILED" } }
    fun profileReady(user: Int): Boolean = String(execute(SafeRootPlan.profileReady(user)), Charsets.UTF_8).trim() == "RUNNING_UNLOCKED"
    fun installProfile(user: Int) { requireMain(); execute(SafeRootPlan.installProfile(user)) }
    fun startProfile(user: Int) { requireMain(); execute(SafeRootPlan.startProfile(user)) }
    fun profileStart(user: Int, nonce: String, seq: Long, restore: Boolean = false) {
        requireMain(); execute(SafeRootPlan.profileStart(user, nonce, seq, restore))
    }
    fun profileStatus(user: Int, nonce: String): org.json.JSONObject =
        org.json.JSONObject(String(execute(SafeRootPlan.profileRead(user, nonce, true)), Charsets.UTF_8))
    fun profileCopy(user: Int, nonce: String, target: File) {
        requireMain()
        FileOutputStream(target).use { out ->
            execute(SafeRootPlan.profileRead(user, nonce, false), destination = out, maxBytes = SafeSnapshot.MAX_TOTAL)
            out.fd.sync()
        }
    }
    fun profileClean(user: Int, nonce: String) { requireMain(); execute(SafeRootPlan.profileClean(user, nonce), maxBytes = 0) }
    fun notifyMain(user: Int) { execute(SafeRootPlan.notifyMain(user)) }
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
