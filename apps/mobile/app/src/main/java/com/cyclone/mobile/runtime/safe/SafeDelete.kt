package com.cyclone.mobile.runtime.safe

import android.content.Context
import org.json.JSONObject

/** No Android/tool entry point. The only caller of this coordinator is the credential-gated owner Activity. */
internal object SafeDelete {
    enum class Step { PC, FOLDER, ROOT, HIVE, PROFILES, MAIN }
    class Confirmation private constructor(internal val issuedAt: Long) {
        private var consumed = false
        @Synchronized internal fun consume(now: Long) {
            check(!consumed && now >= issuedAt && now - issuedAt <= 60000) { "SAFE_DELETE_CONFIRMATION_EXPIRED" }
            consumed = true
        }
        companion object {
            /** Called only in the successful DEVICE_CREDENTIAL callback; never from an Intent or agent. */
            internal fun afterScreenLock(now: Long): Confirmation = Confirmation(now)
        }
    }
    data class Result(val completed: List<Step>, val stoppedAt: Step? = null) {
        val message: String get() = if (stoppedAt == null) "Cyclone data deleted" else "Nothing more was deleted"
    }
    fun run(confirmation: Confirmation, now: Long, perform: (Step) -> Unit): Result {
        confirmation.consume(now)
        val done = mutableListOf<Step>()
        for (step in Step.entries) {
            try { perform(step); done += step } catch (_: Exception) { return Result(done.toList(), step) }
        }
        return Result(done.toList())
    }

    /** Only DeleteAllDataScreen calls this, immediately after native DEVICE_CREDENTIAL succeeds. */
    fun execute(context: Context, confirmation: Confirmation, deleteHiveItems: Boolean = false): Result {
        check(android.os.Process.myUid() / 100000 == 0) { "SAFE_MAIN_REQUIRED" }
        val runtime = SafeRuntime(context)
        val root = SafeRootStore()
        val record = runtime.archiveRecord()
        val profiles = com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore.records(context).mapNotNull { it.androidUserId }.filter { it > 0 }.distinct().sorted()
        val pcs = record.optJSONArray("pcs") ?: org.json.JSONArray()
        val required = (0 until pcs.length()).map { pcs.getJSONObject(it).getString("id") }.toSet() +
            com.cyclone.mobile.gateway.AndroidGatewayTrustRepository(context).all().map { it.pcId }
        return run(confirmation, System.currentTimeMillis()) { step -> when (step) {
            Step.PC -> {
                val now = System.currentTimeMillis()
                val token = JSONObject().put("expiresAt", now + 60000).put("issuedAt", now)
                    .put("nonce", java.util.UUID.randomUUID().toString().replace("-", ""))
                    .put("phoneId", record.getString("phoneId")).put("purpose", "cyclone-safe-delete/v1")
                // Python's canonical JSON: sorted ASCII field names, no whitespace; all values are ASCII.
                val canonical = "{\"expiresAt\":${token.getLong("expiresAt")},\"issuedAt\":$now,\"nonce\":\"${token.getString("nonce")}\",\"phoneId\":\"${token.getString("phoneId")}\",\"purpose\":\"cyclone-safe-delete/v1\"}"
                val signature = com.cyclone.mobile.gateway.AndroidGatewayPhoneIdentity(context).sign(canonical)
                runtime.requestDeletion(JSONObject().put("token", token).put("signature", signature))
                SafeSchedule.stop(context)
                while (required.isNotEmpty()) {
                    val acks = runtime.state().optJSONArray("deleteAcks") ?: org.json.JSONArray()
                    if ((0 until acks.length()).map { acks.getString(it) }.containsAll(required)) break
                    check(System.currentTimeMillis() < now + 60000) { "SAFE_PC_DELETE_NOT_CONFIRMED" }
                    Thread.sleep(250)
                }
            }
            Step.FOLDER -> runtime.folder()?.let { uri ->
                runtime.withKey { key -> SafeFolderStore(context, uri).deleteOwned(record.getString("phoneId"), key) }
            }
            Step.ROOT -> if (!runtime.state().optBoolean("folderOnly")) root.deleteAll() else Unit
            Step.HIVE -> {
                if (deleteHiveItems && com.cyclone.mobile.identity.HiveKeyring.version(context) > 0) {
                    // Owner-only, separately selected. Reuse the existing encrypted tombstones and acknowledgements.
                    val until = android.os.SystemClock.elapsedRealtime() + 120000
                    val ids = com.cyclone.mobile.identity.IdentityVault.change(context) { book, _ -> book.all().map { it.id } }
                    for (chunk in ids.chunked(com.cyclone.mobile.identity.HiveSync.MAX_OUT)) {
                        com.cyclone.mobile.identity.IdentityVault.change(context) { book, _ ->
                            val now = System.currentTimeMillis()
                            chunk.forEach { id -> book.moveToTrash(id, now); check(book.purge(id, now)) }
                        }
                        while (com.cyclone.mobile.identity.IdentityVault.change(context) { book, _ -> book.pending().isNotEmpty() }) {
                            check(android.os.SystemClock.elapsedRealtime() < until) { "SAFE_HIVE_DELETE_NOT_CONFIRMED" }
                            Thread.sleep(250)
                        }
                    }
                }
                com.cyclone.mobile.identity.HiveSync.left(context)
            }
            Step.PROFILES -> profiles.forEach(root::clearProfile)
            Step.MAIN -> {
                val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                keys.aliases().toList().forEach(keys::deleteEntry)
                check(context.getSystemService(android.app.ActivityManager::class.java).clearApplicationUserData()) { "SAFE_MAIN_CLEAR_FAILED" }
            }
        } }
    }
}
