package com.cyclone.mobile.runtime.safe

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.cyclone.mobile.identity.HiveKeyring
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** One key per phone, independent of the disposable Android Keystore wrapping key. Never logs secrets. */
internal class SafeRuntime(private val context: Context) {
    companion object { private val lock = Any() }
    private val root = SafeRootStore()
    private val stateFile = File(context.noBackupFilesDir, "safe/state.json")
    private val user get() = android.os.Process.myUid() / 100000
    private val staging get() = File(context.cacheDir, "safe-ciphertext")
    fun state(): JSONObject = synchronized(lock) {
        if (!stateFile.exists()) JSONObject() else runCatching {
            check(stateFile.length() <= 65536)
            JSONObject(stateFile.readText())
        }.getOrElse { JSONObject().put("corrupt", true) }
    }
    private fun store(state: JSONObject) = SafeFiles.atomic(stateFile, state.toString().toByteArray(Charsets.UTF_8))
    fun needsStartupCheck(): Boolean = synchronized(lock) {
        if (!stateFile.exists()) return true
        val state = state()
        if (SafeState.needsGate(state)) return true
        if (state.optBoolean("enabled") && runCatching { open(state).fill(0) }.isFailure) {
            store(state.put("restorePending", true))
            return true
        }
        false
    }
    fun hasSavedCopy(): Boolean = root.list(user).isNotEmpty()
    fun previewRoot(): List<String> = synchronized(lock) {
        val record = root.record()
        val rootKey = root.key()
        val safe = try { SafeKey.openRoot(record.getJSONObject("wraps").getJSONObject("root"), rootKey) } finally { rootKey.fill(0) }
        staging.mkdirs()
        val file = File.createTempFile("safe-preview-", ".cyc", staging)
        try {
            (if (user == 0) root.users() else listOf(user)).map { target ->
                SafeRecovery.find(root.list(target), target, { seq -> root.copyTo(target, seq, file); file.inputStream() }, { safe.copyOf() }).use { opened ->
                    check(opened.header.getString("phoneId") == record.getString("phoneId"))
                    val (_, entries) = file.inputStream().use { SafeSnapshot.read(it, opened.key) }
                    try {
                        val counts = SafePreview.counts(entries) { _, bytes, table ->
                            val dbFile = File.createTempFile("safe-preview-", ".db", staging)
                            try {
                                dbFile.writeBytes(bytes)
                                android.database.sqlite.SQLiteDatabase.openDatabase(dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                                    db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }
                                }
                            } finally { dbFile.delete() }
                        }
                        "User $target · ${java.util.Date(opened.header.getLong("createdAt"))} · ${file.length()} bytes · ${counts.accounts} accounts · ${counts.routines} routines · ${counts.skills} skills · ${counts.maps} maps · opens with root."
                    } finally { entries.forEach { it.close() } }
                }
            }
        } finally { file.delete(); safe.fill(0) }
    }
    fun continueWithoutRestore() = synchronized(lock) {
        val state = state()
        check(!state.optBoolean("restorePending") && !state.optBoolean("corrupt")) { "SAFE_RESTORE_INCOMPLETE" }
        store(state.put("startupChecked", true))
        SafeStartupGate.complete()
    }
    fun setFolder(uri: Uri) = synchronized(lock) { store(state().put("folder", uri.toString())) }
    fun folder(): Uri? = state().optString("folder").takeIf { it.isNotBlank() }?.let(Uri::parse)
    fun archiveRecord(): JSONObject = if (state().optBoolean("folderOnly")) state().let {
        JSONObject().put("phoneId", it.getString("phoneId")).put("wraps", it.getJSONObject("wraps"))
            .put("pcs", it.optJSONArray("pcs") ?: org.json.JSONArray())
    } else root.record()
    fun archiveCopies(): List<JSONObject> {
        if (!state().optBoolean("folderOnly")) return root.users().flatMap { target -> root.list(target).take(3).map { root.describe(target, it) } }
        val phone = state().getString("phoneId")
        return folderCopies().filter { copy ->
            runCatching { context.contentResolver.openInputStream(copy.uri)!!.use(SafeSnapshot::peek).getString("phoneId") == phone }.getOrDefault(false)
        }.groupBy { it.user }.values.flatMap { it.sortedByDescending { copy -> copy.seq }.take(3) }.map { copy ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var size = 0L
            context.contentResolver.openInputStream(copy.uri)!!.use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; size += n; check(size <= SafeSnapshot.MAX_TOTAL); digest.update(buffer, 0, n) }
            }
            val header = context.contentResolver.openInputStream(copy.uri)!!.use(SafeSnapshot::peek)
            check(header.getInt("androidUserId") == copy.user && header.getLong("seq") == copy.seq)
            JSONObject().put("user", copy.user).put("seq", copy.seq).put("bytes", size)
                .put("sha256", digest.digest().joinToString("") { "%02x".format(it) }).put("createdAt", header.getLong("createdAt"))
        }
    }
    private fun folderCopies(): List<SafeFolderStore.Copy> = folder()?.let { SafeFolderStore(context, it).list() }.orEmpty()
    fun archiveChunk(target: Int, seq: Long, offset: Long): ByteArray {
        SafeRootPlan.name(target, seq)
        require(offset >= 0 && offset <= SafeSnapshot.MAX_TOTAL && offset % SafeBridge.CHUNK == 0L)
        if (!state().optBoolean("folderOnly")) return root.chunk(target, seq, offset)
        val copy = folderCopies().single { it.user == target && it.seq == seq }
        val hint = context.contentResolver.openInputStream(copy.uri)!!.use(SafeSnapshot::peek)
        check(hint.getString("phoneId") == state().getString("phoneId"))
        return context.contentResolver.openInputStream(copy.uri)!!.use { input ->
            var remaining = offset
            while (remaining > 0) { val skipped = input.skip(remaining); check(skipped > 0); remaining -= skipped }
            input.readNBytes(SafeBridge.CHUNK)
        }
    }
    fun registerSharedKey(record: JSONObject, safe: ByteArray) = synchronized(lock) {
        check(user > 0)
        val state = state()
        if (state.optBoolean("enabled")) {
            val previous = open(state)
            try { check(java.security.MessageDigest.isEqual(previous, safe)) } finally { previous.fill(0) }
        } else store(state.put("enabled", true).put("startupChecked", true).put("phoneId", record.getString("phoneId"))
            .put("wraps", record.getJSONObject("wraps")).put("localWrap", SafeKey.wrap(safe, localKey(true), "local")))
        SafeStartupGate.complete()
    }
    fun notePcReceipt(trustId: String) = synchronized(lock) {
        val peer = com.cyclone.mobile.gateway.AndroidGatewayTrustRepository(context).get(trustId)
            ?: error("SAFE_TRUST_REQUIRED")
        check(peer.revokedAtMs == null) { "SAFE_TRUST_REQUIRED" }
        val record = archiveRecord()
        val pcs = record.optJSONArray("pcs") ?: org.json.JSONArray()
        if ((0 until pcs.length()).none { pcs.getJSONObject(it).getString("id") == peer.pcId }) {
            pcs.put(JSONObject().put("id", peer.pcId).put("name", peer.pcLabel))
        }
        record.put("pcs", pcs)
        record.getJSONObject("wraps").put("pcs", pcs)
        if (!state().optBoolean("folderOnly")) root.updateRecord(record)
        store(state().put("pcs", pcs).put("wraps", record.getJSONObject("wraps")).put("pcSavedAt", System.currentTimeMillis()))
    }
    fun requestDeletion(request: JSONObject) = synchronized(lock) {
        store(state().put("deleting", true).put("deleteRequest", request).put("deleteAcks", org.json.JSONArray()))
    }
    // Keep an expired request visible while deletion is paused: never re-upload a PC archive just deleted.
    fun deletionRequest(): JSONObject? = state().takeIf { it.optBoolean("deleting") }?.optJSONObject("deleteRequest")
    fun ackDeletion(trustId: String, nonce: String) = synchronized(lock) {
        val request = deletionRequest() ?: error("SAFE_DELETE_EXPIRED")
        check(request.getJSONObject("token").getLong("expiresAt") >= System.currentTimeMillis()) { "SAFE_DELETE_EXPIRED" }
        check(request.getJSONObject("token").getString("nonce") == nonce)
        val peer = com.cyclone.mobile.gateway.AndroidGatewayTrustRepository(context).get(trustId) ?: error("SAFE_TRUST_REQUIRED")
        val acks = state().optJSONArray("deleteAcks") ?: org.json.JSONArray()
        if ((0 until acks.length()).none { acks.getString(it) == peer.pcId }) acks.put(peer.pcId)
        store(state().put("deleteAcks", acks))
    }
    fun stagePcKey(envelope: JSONObject) = synchronized(lock) {
        check(user == 0 && (!state().optBoolean("enabled") || state().optBoolean("restorePending"))) { "SAFE_LIVE_RESTORE_BLOCKED" }
        require(envelope.keys().asSequence().toSet() == setOf("enc", "ct", "aad"))
        val aad = envelope.getString("aad"); require(aad.length <= 1000)
        val bound = JSONObject(aad)
        require(bound.keys().asSequence().toSet() == setOf("phoneId", "user", "seq", "deviceKey", "expiresAt", "requestId"))
        val device = com.cyclone.mobile.secrets.DeviceKey.ensure()
        check(bound.getString("deviceKey") == device.fingerprint && bound.getInt("user") == 0)
        check(bound.getLong("expiresAt") in System.currentTimeMillis()..System.currentTimeMillis() + 300000)
        require(bound.getString("requestId").matches(Regex("[a-f0-9]{32}")))
        val enc = SafeKey.un64(envelope.getString("enc")); val ct = SafeKey.un64(envelope.getString("ct"))
        require(enc.size == 65 && ct.size == 48)
        val safe = com.cyclone.mobile.secrets.Hpke.open(enc, ct, "cyclone-safe/v1/restore".toByteArray(), aad.toByteArray(), device.raw,
            { key -> com.cyclone.mobile.secrets.DeviceKey.decap(key) }, com.cyclone.mobile.secrets.DeviceKey.params())
        try {
            val file = File(context.cacheDir, "safe-inbox/${SafeRootPlan.name(0, bound.getLong("seq"))}")
            val (header, entries) = file.inputStream().use { SafeSnapshot.read(it, safe) }
            try { check(header.getString("phoneId") == bound.getString("phoneId") && header.getInt("androidUserId") == 0 && header.getLong("seq") == bound.getLong("seq")) }
            finally { entries.forEach { it.close() } }
            store(state().put("pcRestore", bound).put("pcRestoreKey", SafeKey.wrap(safe, localKey(true), "pc-restore")))
        } finally { safe.fill(0) }
    }
    fun restorePc(): Long = synchronized(lock) {
        val state = state()
        val bound = state.getJSONObject("pcRestore")
        val safe = SafeKey.open(state.getJSONObject("pcRestoreKey"), localKey(false), "pc-restore")
        try {
            val file = File(context.cacheDir, "safe-inbox/${SafeRootPlan.name(0, bound.getLong("seq"))}")
            val (header, entries) = file.inputStream().use { SafeSnapshot.read(it, safe) }
            try { check(header.getString("phoneId") == bound.getString("phoneId") && header.getInt("androidUserId") == user && header.getLong("seq") == bound.getLong("seq")) }
            finally { entries.forEach { it.close() } }
            val wraps = JSONObject(header.getJSONObject("wraps").toString())
            val record = JSONObject().put("phoneId", header.getString("phoneId")).put("wraps", wraps)
            val seq = restore(safe, record, listOf(header.getLong("seq"))) { _, target -> file.copyTo(target, overwrite = true) }
            attachPcCopies(safe, header)
            seq
        } finally { safe.fill(0) }
    }

    fun restorePcRecovery(phrase: CharArray): Long = synchronized(lock) {
        val files = File(context.cacheDir, "safe-inbox").listFiles().orEmpty().mapNotNull { file ->
            Regex("safe-$user-([1-9][0-9]*)\\.cyc").matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()?.let { it to file }
        }.toMap()
        SafeRecovery.find(files.keys.toList(), user, { files.getValue(it).inputStream() },
            { header -> SafeKey.openRecovery(header.getJSONObject("wraps").getJSONObject("recovery"), phrase) }).use { opened ->
            val record = JSONObject().put("phoneId", opened.header.getString("phoneId")).put("wraps", opened.header.getJSONObject("wraps"))
            val seq = restore(opened.key, record, files.keys.toList(), folderOnly = true) { number, file -> files.getValue(number).copyTo(file, overwrite = true) }
            attachPcCopies(opened.key, opened.header)
            seq
        }
    }

    private fun attachPcCopies(safe: ByteArray, header: JSONObject) {
        // Local restore is complete even without root. Never replace an existing different phone's root key.
        try {
            ensureRoot(safe, state().put("phoneId", header.getString("phoneId")), header.getJSONObject("wraps"))
        } catch (_: Exception) {
            store(state().put("rootPending", true).put("folderOnly", true)); return
        }
        val pending = mutableSetOf<Int>()
        File(context.cacheDir, "safe-inbox").listFiles().orEmpty().filter { it.extension == "cyc" }.forEach { copy ->
            runCatching {
                val (other, body) = copy.inputStream().use { SafeSnapshot.read(it, safe) }
                try {
                    check(other.getString("phoneId") == header.getString("phoneId"))
                    val target = other.getInt("androidUserId"); val seq = other.getLong("seq")
                    if (seq !in root.list(target)) root.put(target, seq, copy, safe)
                } finally { body.forEach { it.close() } }
            }.onFailure {
                Regex("safe-([0-9]{1,5})-[1-9][0-9]*\\.cyc").matchEntire(copy.name)?.groupValues?.get(1)?.toIntOrNull()?.let(pending::add)
            }
        }
        pending += SafeProfiles.restore(context)
        store(state().put("rootPending", false).put("folderOnly", false).put("profilesPending", org.json.JSONArray(pending.sorted())))
    }

    private fun localKey(create: Boolean): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(SafeKey.ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "SAFE_LOCAL_KEY_MISSING" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(SafeKey.ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    /** Phrase is returned for an owner-only, non-saveable, screenshot-protected view. Never retained on disk. */
    fun enable(folderOnly: Boolean = false): CharArray = synchronized(lock) {
        check(user == 0) { "SAFE_MAIN_REQUIRED" }
        check(!state().optBoolean("corrupt") && !state().optBoolean("restorePending")) { "SAFE_RESTORE_REQUIRED" }
        check(!state().has("localWrap")) { "SAFE_ALREADY_ENABLED" }
        if (folderOnly) check(folder() != null) { "SAFE_FOLDER_REQUIRED" }
        var existing: JSONObject? = null
        val rootKey = if (folderOnly) null else {
            val inventory = root.inventory() // Never treat denied root as an empty Safe.
            if (inventory == "RECORD") {
                val record = root.record()
                existing = record
                // Recover a setup interrupted between writing the root record and local wrapping state.
                // Any snapshot or PC receipt instead requires restore; never overwrite an established Safe.
                check(root.users().all { root.list(it).isEmpty() } && (record.optJSONArray("pcs")?.length() ?: 0) == 0) { "SAFE_EXISTING_RESTORE_REQUIRED" }
            }
            if (inventory != "EMPTY") root.key() else SafeKey.fresh().also(root::createKey)
        }
        val safe = try { existing?.let { SafeKey.openRoot(it.getJSONObject("wraps").getJSONObject("root"), checkNotNull(rootKey)) } ?: SafeKey.fresh() }
            catch (error: Exception) { rootKey?.fill(0); throw error }
        val entropy = SafeKey.fresh()
        var phrase: CharArray? = null
        try {
            val words = context.assets.open("safe/bip39-english.txt").bufferedReader().use { it.readLines() }
            phrase = SafeKey.recoveryWords(entropy, words).joinToString(" ").toCharArray()
            val wraps = existing?.getJSONObject("wraps") ?: JSONObject()
            wraps.put("recovery", SafeKey.wrapRecovery(safe, phrase))
            if (rootKey != null && existing == null) wraps.put("root", SafeKey.wrapRoot(safe, rootKey))
            val phone = existing?.getString("phoneId") ?: java.util.UUID.randomUUID().toString()
            val record = existing ?: JSONObject().put("format", 1).put("phoneId", phone)
            record.put("wraps", wraps)
            if (rootKey != null) { if (existing == null) root.createRecord(record) else root.updateRecord(record) }
            store(state().put("startupChecked", true).put("enabled", true).put("phoneId", phone)
                .put("folderOnly", folderOnly).put("wraps", wraps).put("localWrap", SafeKey.wrap(safe, localKey(true), "local")))
            phrase
        } catch (error: Exception) { phrase?.fill('\u0000'); throw error }
        finally { safe.fill(0); rootKey?.fill(0); entropy.fill(0) }
    }

    private fun open(state: JSONObject): ByteArray = SafeKey.open(state.getJSONObject("localWrap"), localKey(false), "local")
    fun <T> withKey(action: (ByteArray) -> T): T = synchronized(lock) {
        val safe = open(state())
        try { action(safe) } finally { safe.fill(0) }
    }

    fun backup(): Long = synchronized(lock) {
        check(user == 0) { "SAFE_MAIN_REQUIRED" }
        val state = state()
        check(state.optBoolean("enabled") && !state.optBoolean("restorePending") && !state.optBoolean("deleting")) { "SAFE_NOT_ENABLED" }
        val safe = open(state)
        var entries: List<SafeSnapshot.Entry> = emptyList()
        try {
            val wraps = JSONObject(state.getJSONObject("wraps").toString())
            val folderOnly = state.optBoolean("folderOnly")
            if (folderOnly) check(folder() != null) { "SAFE_FOLDER_REQUIRED" }
            entries = SafeStores(context).capture {
                if (HiveKeyring.version(context) > 0) HiveKeyring.withKeys(context) { keys ->
                    val version = keys.keys.max()
                    wraps.put("hive", SafeKey.wrapHive(safe, keys.getValue(version), version))
                } else wraps.remove("hive")
            }
            if (!folderOnly) ensureRoot(safe, state, wraps)
            val previous = if (folderOnly) folderCopies().filter { it.user == user }.maxOfOrNull { it.seq } else root.list(user).maxOrNull()
            val seq = maxOf(System.currentTimeMillis(), (previous ?: 0) + 1)
            val identity = SafeSnapshot.Identity(user, com.cyclone.mobile.runtime.workspaces.ProfileCarry.me(context).first,
                state.getString("phoneId"), seq, System.currentTimeMillis())
            var folderSaved = false
            SafeSession(staging, { s, file -> root.copyTo(user, s, file) }, { s, file, key ->
                if (!folderOnly) root.put(user, s, file, key)
                folder()?.let { SafeFolderStore(context, it).put(user, s, file, key); folderSaved = true }
                check(!folderOnly || folderSaved) { "SAFE_FOLDER_REQUIRED" }
            }).save(safe, identity, entries, wraps)
            val missed = if (folderOnly) com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore.records(context).mapNotNull { it.androidUserId }.filter { it > 0 }
                else SafeProfiles.capture(context, safe, state.getString("phoneId"))
            store(state.put("lastSaved", identity.time).put("lastSequence", seq).put("folderSaved", folderSaved)
                .put("rootSaved", !folderOnly)
                .put("profilesPending", org.json.JSONArray(missed))
                .put("wraps", wraps).removeAndReturn("lastFailure"))
            seq
        } catch (_: Exception) {
            store(state.put("lastFailure", "SAFE_BACKUP_FAILED"))
            error("SAFE_BACKUP_FAILED")
        } finally { entries.forEach { it.close() }; safe.fill(0) }
    }

    private fun ensureRoot(safe: ByteArray, state: JSONObject, wraps: JSONObject) {
        val inventory = root.inventory()
        val rootKey = if (inventory == "EMPTY") SafeKey.fresh().also(root::createKey) else root.key()
        try {
            if (inventory == "RECORD") {
                val record = root.record()
                check(record.getString("phoneId") == state.getString("phoneId")) { "SAFE_PHONE_MISMATCH" }
                val existing = SafeKey.openRoot(record.getJSONObject("wraps").getJSONObject("root"), rootKey)
                try { check(java.security.MessageDigest.isEqual(existing, safe)) { "SAFE_KEY_MISMATCH" } } finally { existing.fill(0) }
                wraps.put("root", record.getJSONObject("wraps").getJSONObject("root"))
                root.updateRecord(record.put("wraps", wraps))
            } else {
                wraps.put("root", SafeKey.wrapRoot(safe, rootKey))
                root.createRecord(JSONObject().put("format", 1).put("phoneId", state.getString("phoneId")).put("wraps", wraps)
                    .put("pcs", wraps.optJSONArray("pcs") ?: org.json.JSONArray()))
            }
            store(state.put("wraps", wraps))
        } finally { rootKey.fill(0) }
    }

    /** Startup only: do not initialize normal runtime/database singletons before this returns. */
    fun restoreRoot(): Long = synchronized(lock) {
        val record = root.record()
        val rootKey = root.key()
        val safe = try { SafeKey.openRoot(record.getJSONObject("wraps").getJSONObject("root"), rootKey) }
        finally { rootKey.fill(0) }
        try {
            val seq = restore(safe, record, root.list(user)) { number, file -> root.copyTo(user, number, file) }
            if (user == 0) store(state().put("profilesPending", org.json.JSONArray(SafeProfiles.restore(context))))
            seq
        }
        finally { safe.fill(0) }
    }

    fun restoreFolder(phrase: CharArray): Long = synchronized(lock) {
        val uri = folder() ?: error("SAFE_FOLDER_REQUIRED")
        val copies = SafeFolderStore(context, uri).list().filter { it.user == user }.sortedByDescending { it.seq }
        check(copies.isNotEmpty()) { "SAFE_NO_COPY" }
        SafeRecovery.find(copies.map { it.seq }, user,
            { seq -> context.contentResolver.openInputStream(copies.single { it.seq == seq }.uri)!! },
            { hint -> SafeKey.openRecovery(hint.getJSONObject("wraps").getJSONObject("recovery"), phrase) }).use { opened ->
            val record = JSONObject().put("phoneId", opened.header.getString("phoneId")).put("wraps", opened.header.getJSONObject("wraps"))
            restore(opened.key, record, copies.map { it.seq }, folderOnly = true) { seq, file ->
                context.contentResolver.openInputStream(copies.single { it.seq == seq }.uri)!!.use { input ->
                    file.outputStream().use { out -> input.copyTo(out) }
                }
            }
        }
    }

    private fun restore(safe: ByteArray, record: JSONObject, sequences: List<Long>, folderOnly: Boolean = false, read: (Long, File) -> Unit): Long {
        check(SafeStartupGate.blocked) { "SAFE_COLD_START_REQUIRED" }
        val old = state()
        check(!old.optBoolean("enabled") || old.optBoolean("restorePending")) { "SAFE_LIVE_RESTORE_BLOCKED" }
        val pinned = SafeState.pinnedSequence(old)
        store(old.put("restorePending", true))
        val stores = SafeStores(context)
        val header = SafeSession(staging, read, { _, _, _ -> error("SAFE_RESTORE_ONLY") })
            .recover(safe, user,
                pinned?.let(::listOf) ?: sequences,
                stores::accepts, record.getString("phoneId"),
                { header -> store(old.put("restoreSequence", header.getLong("seq"))) }, stores::write)
        check(header.getString("phoneId") == record.getString("phoneId")) { "SAFE_PHONE_MISMATCH" }
        store(SafeState.restored(old, header, SafeKey.wrap(safe, localKey(true), "local"), folderOnly))
        SafeStartupGate.complete()
        return header.getLong("seq")
    }

    private fun JSONObject.removeAndReturn(name: String): JSONObject = apply { remove(name) }
}
