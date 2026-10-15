package com.cyclone.mobile.identity

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Plan 59 (build 2, run 2b): this phone's accounts in the hive.
 *
 * Every saved account is one **hive item**, encrypted here with the hive key (AES-256-GCM). The PC relays items and
 * can't open them.
 *
 * The item's id, kind, key version, time and writer are bound into the AAD ([aad]). So the PC can't move a version to
 * another item, or change its order, without the open failing. Glass builds exactly the same AAD
 * (`apps/glass/src/services/hiveItems.ts`).
 *
 * `hive.sync` (the PC, every few seconds while this phone is reachable) does three things:
 * - it brings the items new to this phone, and they are applied to the book (the newest version wins);
 * - it says which of this phone's writes the hive kept, and those leave the outbox;
 * - it takes the outbox, at most [MAX_OUT] items at a time.
 *
 * Main only, and nothing here logs.
 */
object HiveSync {
    const val KIND_ACCOUNT = "account"
    const val MAX_OUT = 20
    private const val MAX_IN = 20
    private val ITEM_ID = Regex("^[a-z]{2,4}_[A-Za-z0-9_-]{8,64}$")
    private val random = SecureRandom()

    fun aad(itemId: String, kind: String, keyVersion: Int, at: Long, by: String, gone: Boolean): ByteArray =
        "cyclone-hive-item/v1\n$itemId\n$kind\n$keyVersion\n$at\n$by\n${if (gone) 1 else 0}".toByteArray(Charsets.UTF_8)

    /** One account (or its tombstone) as a hive item, encrypted with hive key [key] (version [keyVersion]). */
    fun seal(key: ByteArray, keyVersion: Int, itemId: String, at: Long, by: String, body: JSONObject, gone: Boolean): JSONObject {
        val plain = body.toString().toByteArray(Charsets.UTF_8)
        try {
            val iv = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad(itemId, KIND_ACCOUNT, keyVersion, at, by, gone))
            val ct = cipher.doFinal(plain)
            return JSONObject().put("itemId", itemId).put("kind", KIND_ACCOUNT).put("keyVersion", keyVersion).put("at", at).put("by", by)
                .put("gone", gone).put("iv", b64(iv)).put("ct", b64(ct))
        } finally {
            plain.fill(0)
        }
    }

    /** Opens an item with the key version it names. Throws when it doesn't open (wrong key, changed metadata). */
    fun open(keys: Map<Int, ByteArray>, item: JSONObject): JSONObject {
        val version = item.getInt("keyVersion")
        val key = keys[version] ?: throw IllegalArgumentException("no key")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, unb64(item.getString("iv"))))
        cipher.updateAAD(aad(item.getString("itemId"), item.getString("kind"), version, item.getLong("at"), item.getString("by"), item.getBoolean("gone")))
        val plain = cipher.doFinal(unb64(item.getString("ct")))
        try { return JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
    }

    data class Outcome(val applied: Int, val skipped: Int, val outbox: List<JSONObject>)

    /**
     * Applies one sync to [book] (pure apart from the random IVs, so JVM-tested). [keys] are this phone's hive key
     * versions. Items that don't open, or that don't match what they claim, are skipped, never applied.
     */
    fun apply(book: IdentityBook, keys: Map<Int, ByteArray>, items: JSONArray, acked: JSONArray): Outcome {
        require(book.me.isNotEmpty()) { "Not in the hive." }
        for (i in 0 until acked.length()) {
            val a = acked.optJSONObject(i) ?: continue
            book.acked(a.optString("itemId"), a.optLong("at"))
        }
        var applied = 0
        var skipped = 0
        for (i in 0 until minOf(items.length(), MAX_IN)) {
            val item = items.optJSONObject(i)
            val ok = item != null && runCatching { applyOne(book, keys, item) }.getOrDefault(false)
            if (ok) applied++ else skipped++
        }
        val newest = keys.keys.maxOrNull() ?: throw IllegalStateException("no key")
        val outbox = book.pending().sorted().take(MAX_OUT).mapNotNull { id -> outgoing(book, keys.getValue(newest), newest, id) }
        return Outcome(applied, skipped, outbox)
    }

    private fun applyOne(book: IdentityBook, keys: Map<Int, ByteArray>, item: JSONObject): Boolean {
        if (item.optString("kind") != KIND_ACCOUNT || !ITEM_ID.matches(item.optString("itemId"))) return false
        val body = open(keys, item)
        val id = item.getString("itemId")
        if (body.optString("id") != id) return false
        if (item.getBoolean("gone")) {
            if (!body.optBoolean("gone")) return false
            book.applyGone(id, item.getLong("at"))
            return true
        }
        val remote = SavedIdentity.fromJson(body) ?: return false
        // What the item claims (when, by whom) must be what is inside it.
        if (remote.updatedAtMs != item.getLong("at") || remote.updatedBy != item.getString("by")) return false
        book.applyRemote(remote)
        return true
    }

    private fun outgoing(book: IdentityBook, key: ByteArray, version: Int, id: String): JSONObject? {
        val identity = book.outgoing(id)
        if (identity != null) {
            val body = identity.toJson().put("v", 1)
            try {
                return seal(key, version, id, identity.updatedAtMs, identity.updatedBy, body, gone = false)
            } finally {
                body.remove("password")
            }
        }
        val at = book.gone()[id] ?: return null
        return seal(key, version, id, at, book.me, JSONObject().put("v", 1).put("id", id).put("gone", true), gone = true)
    }

    // ------------------------------------------------------------------ the Android edge

    /** `hive.sync` from the PC. */
    fun sync(context: Context, args: JSONObject): JSONObject {
        if (args.keys().asSequence().any { it !in setOf("items", "acked") }) throw HiveKeyring.Rejected("SYNC_FIELDS")
        val items = args.optJSONArray("items") ?: JSONArray()
        val acked = args.optJSONArray("acked") ?: JSONArray()
        return HiveKeyring.withKeys(context) { keys ->
            val outcome = IdentityVault.change(context) { book, _ -> apply(book, keys, items, acked) }
            JSONObject().put("applied", outcome.applied).put("keyVersion", keys.keys.max()).put("outbox", JSONArray(outcome.outbox))
        }
    }

    /** Joined the hive (or got a newer key): every account here goes to it once. */
    fun joined(context: Context) {
        IdentityVault.change(context) { book, _ -> book.markAllPending() }
    }

    /** Left the hive: drop what came from other phones, then wipe the hive key. */
    fun left(context: Context): JSONObject {
        if (IdentityVault.isMain(context)) runCatching { IdentityVault.change(context) { book, _ -> book.leaveHive() } }
        return HiveKeyring.leave(context)
    }

    private fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
    private fun unb64(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)
}
