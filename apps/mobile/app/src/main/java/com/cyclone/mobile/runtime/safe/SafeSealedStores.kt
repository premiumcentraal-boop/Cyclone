package com.cyclone.mobile.runtime.safe

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.cyclone.mobile.mind.mission.KeystoreMemorySealer
import com.cyclone.mobile.secrets.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** All plaintext stays in memory until it has been re-sealed. No diagnostics, fallback-to-empty or plaintext files. */
internal class SafeSealedStores(private val context: Context) {
    private class Box(val prefs: String, val field: String, val alias: String, val aad: String?, val dot: Boolean = false)
    private val boxes = mapOf(
        "identities" to Box("cyclone_identities_v1", "book", "cyclone.identities.aes.v1", "cyclone-identities/v1"),
        "hive" to Box("cyclone_hive_v1", "keys", "cyclone.hive.aes.v1", "cyclone-hive/v1/local"),
        "provider" to Box("cyclone_ai_secrets", "openrouter_key_blob", "cyclone.openrouter.api_key.v1", null, true),
    )
    private fun preferences(name: String) = when (name) {
        "cyclone_identities_v1" -> context.getSharedPreferences("cyclone_identities_v1", Context.MODE_PRIVATE)
        "cyclone_hive_v1" -> context.getSharedPreferences("cyclone_hive_v1", Context.MODE_PRIVATE)
        "cyclone_ai_secrets" -> context.getSharedPreferences("cyclone_ai_secrets", Context.MODE_PRIVATE)
        else -> error("SAFE_SEALED_STORE_UNKNOWN")
    }

    private fun key(alias: String, create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "SAFE_SEALED_KEY_MISSING" }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun capture(): List<SafeSnapshot.Entry> {
        val entries = mutableListOf<SafeSnapshot.Entry>()
        try {
            for ((name, box) in boxes) {
                val prefs = preferences(box.prefs)
                val fields = if (name == "hive") setOf(box.field, "me") else setOf(box.field)
                check(prefs.all.keys.all { it in fields }) { "SAFE_SEALED_FIELD_UNKNOWN" }
                val value = prefs.getString(box.field, null) ?: continue
                val (iv, ct) = if (box.dot) {
                    val parts = value.split('.'); check(parts.size == 2) { "SAFE_SEALED_INVALID" }
                    SafeKey.un64(parts[0]) to SafeKey.un64(parts[1])
                } else JSONObject(value).let { SafeKey.un64(it.getString("iv")) to SafeKey.un64(it.getString("ct")) }
                check(iv.size == 12) { "SAFE_SEALED_INVALID" }
                val plain = SafeSealedBox.open(SafeSealedBox.Box(iv, ct), key(box.alias, false), box.aad)
                try {
                    val payload = JSONObject().put("plain", SafeKey.b64(plain))
                    if (name == "hive") payload.put("me", prefs.getString("me", ""))
                    entries += SafeSnapshot.Entry("sealed/$name", payload.toString().toByteArray(Charsets.UTF_8))
                } finally { plain.fill(0) }
            }
            val vaultPrefs = context.getSharedPreferences("cyclone_vault_secrets_v1", Context.MODE_PRIVATE)
            val index = vaultPrefs.getStringSet("slot_index", emptySet()).orEmpty()
            val expected = index.size
            check(vaultPrefs.all.keys.all { it == "slot_index" || (it.startsWith("slot:") && it.removePrefix("slot:") in index) }) { "SAFE_VAULT_RECORD_INVALID" }
            val records = AndroidSecretRecordStore(context).listAll()
            check(records.size == expected) { "SAFE_VAULT_RECORD_INVALID" }
            val sealer = AndroidKeystoreSecretSealer()
            val vault = JSONArray()
            records.forEach { record ->
                val meta = record.metadata
                val row = JSONObject().put("placeId", meta.key.placeId).put("persona", meta.key.persona.wireValue)
                    .put("slot", meta.key.slotName).put("present", meta.present)
                    .put("createdAtMs", meta.createdAtMs).put("updatedAtMs", meta.updatedAtMs)
                if (meta.present) {
                    val chars = sealer.open(meta.key, checkNotNull(record.sealed))
                    val encoded = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars))
                    val plain = ByteArray(encoded.remaining()).also(encoded::get)
                    try { row.put("plain", SafeKey.b64(plain)) }
                    finally { chars.fill('\u0000'); plain.fill(0); if (encoded.hasArray()) encoded.array().fill(0) }
                }
                vault.put(row)
            }
            entries += SafeSnapshot.Entry("sealed/vault", vault.toString().toByteArray(Charsets.UTF_8))
            val memory = File(context.filesDir, "Cyclone Brain/Mind memory.json")
            if (memory.exists()) entries += SafeSnapshot.Entry("sealed/mind", KeystoreMemorySealer.open(memory.readBytes()))
            val trust = context.getSharedPreferences("cyclone_gateway_trust_v33", Context.MODE_PRIVATE)
            check(trust.all.keys.all { it == "trusted_pc_records" }) { "SAFE_TRUST_FIELD_UNKNOWN" }
            trust.getString("trusted_pc_records", null)?.let {
                JSONArray(it)
                entries += SafeSnapshot.Entry("sealed/trust", it.toByteArray(Charsets.UTF_8))
            }
            return entries
        } catch (_: Exception) {
            entries.forEach { it.close() }
            throw IllegalStateException("SAFE_SEALED_CAPTURE_FAILED")
        }
    }

    fun restore(entry: SafeSnapshot.Entry) {
        val name = entry.path.removePrefix("sealed/")
        val box = boxes[name]
        if (box != null) {
            val payload = JSONObject(String(entry.bytes, Charsets.UTF_8))
            val plain = SafeKey.un64(payload.getString("plain"))
            try {
                val sealed = SafeSealedBox.seal(plain, key(box.alias, true), box.aad)
                val value = if (box.dot) SafeKey.b64(sealed.iv) + "." + SafeKey.b64(sealed.ct)
                    else JSONObject().put("v", 1).put("iv", SafeKey.b64(sealed.iv)).put("ct", SafeKey.b64(sealed.ct)).toString()
                val edit = preferences(box.prefs).edit().putString(box.field, value)
                if (name == "hive") edit.putString("me", payload.getString("me"))
                check(edit.commit()) { "SAFE_SEALED_WRITE_FAILED" }
            } finally { plain.fill(0) }
            return
        }
        when (name) {
            "vault" -> {
                val array = JSONArray(String(entry.bytes, Charsets.UTF_8))
                val store = AndroidSecretRecordStore(context); val sealer = AndroidKeystoreSecretSealer()
                for (i in 0 until array.length()) {
                    val row = array.getJSONObject(i)
                    val slot = SecretSlotKey.of(row.getString("placeId"), SecretPersona.parse(row.getString("persona")), row.getString("slot"))
                    val present = row.getBoolean("present")
                    val sealed = if (present) {
                        val plain = SafeKey.un64(row.getString("plain"))
                        val decoded = Charsets.UTF_8.decode(java.nio.ByteBuffer.wrap(plain))
                        val chars = CharArray(decoded.remaining()).also(decoded::get)
                        try { sealer.seal(slot, chars) }
                        finally { plain.fill(0); chars.fill('\u0000'); if (decoded.hasArray()) decoded.array().fill('\u0000') }
                    } else null
                    store.save(SecretRecord(SecretSlotMetadata(slot, present, row.getLong("createdAtMs"), row.getLong("updatedAtMs")), sealed))
                }
            }
            "mind" -> SafeFiles.atomic(File(context.filesDir, "Cyclone Brain/Mind memory.json"), KeystoreMemorySealer.seal(entry.bytes))
            "trust" -> {
                // Retain provenance but never resurrect authorization for the old signing key.
                val rows = JSONArray(String(entry.bytes, Charsets.UTF_8))
                for (i in 0 until rows.length()) rows.getJSONObject(i).put("revokedAtMs", System.currentTimeMillis())
                check(context.getSharedPreferences("cyclone_gateway_trust_v33", Context.MODE_PRIVATE).edit()
                    .putString("trusted_pc_records", rows.toString()).commit()) { "SAFE_TRUST_WRITE_FAILED" }
            }
            else -> throw SafeSnapshot.Invalid()
        }
    }
}
