package com.cyclone.mobile.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.interfaces.ECPublicKey
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Plan 59 (build 2, run 2a): this phone's copy of the hive key, the key every phone in the owner's hive shares.
 *
 * The owner's browser seals it to this phone's device key (HPKE, the sealed-delivery suite) and the PC relays it
 * (`hive.join`). Here the phone checks what the envelope is bound to:
 * - this phone's device key;
 * - this phone's id as the PC names it, and the join id;
 * - a version no older than the one it has;
 * - a time that hasn't passed.
 *
 * It then keeps the key wrapped by its own Keystore AES key, in Main only. Older versions are kept, so items written
 * before a key change still open. `hive.leave` wipes every version. Nothing here logs.
 */
object HiveKeyring {
    const val PREFS = "cyclone_hive_v1"
    const val INFO = "cyclone-hive-key/v1"
    private const val KEYS = "keys"
    private const val KEY_ALIAS = "cyclone.hive.aes.v1"
    private const val AAD = "cyclone-hive/v1/local"
    private val JOIN_ID = Regex("^hj_[A-Za-z0-9_-]{16,40}$")
    private val lock = Any()

    class Rejected(val code: String) : Exception(code)

    /** Seams for JVM tests; production uses the Keystore device key and the wall clock. */
    internal var ownFingerprint: () -> String = { com.cyclone.mobile.secrets.DeviceKey.ensure().fingerprint }
    internal var openSealed: (enc: ByteArray, ct: ByteArray, aad: ByteArray) -> ByteArray = { enc, ct, aad ->
        com.cyclone.mobile.secrets.Hpke.open(enc, ct, INFO.toByteArray(), aad, com.cyclone.mobile.secrets.DeviceKey.ensure().raw,
            { e: ECPublicKey -> com.cyclone.mobile.secrets.DeviceKey.decap(e) }, com.cyclone.mobile.secrets.DeviceKey.params())
    }
    internal var clock: () -> Long = System::currentTimeMillis

    /** Checks an envelope's bound data and opens it. Pure apart from the seams; returns (version, the 32-byte key). */
    fun open(args: JSONObject, currentVersion: Int): Pair<Int, ByteArray> {
        if (args.keys().asSequence().toSet() != setOf("joinId", "version", "enc", "ct", "aad")) throw Rejected("ENVELOPE_FIELDS")
        val joinId = args.optString("joinId")
        val version = args.optInt("version", -1)
        val aad = args.optString("aad")
        val enc = runCatching { java.util.Base64.getDecoder().decode(args.optString("enc")) }.getOrNull()
        val ct = runCatching { java.util.Base64.getDecoder().decode(args.optString("ct")) }.getOrNull()
        if (!JOIN_ID.matches(joinId) || version < 1 || enc == null || enc.size != 65 || ct == null || ct.size !in 17..128 || aad.length !in 1..600) {
            throw Rejected("ENVELOPE_SHAPE")
        }
        val bound = runCatching { JSONObject(aad) }.getOrNull() ?: throw Rejected("AAD")
        when {
            bound.keys().asSequence().toSet() != setOf("deviceKey", "deviceId", "joinId", "version", "expiresAt") -> throw Rejected("AAD_FIELDS")
            bound.optString("joinId") != joinId -> throw Rejected("AAD_JOIN")
            bound.optInt("version", -1) != version -> throw Rejected("AAD_VERSION")
            bound.optString("deviceKey") != ownFingerprint() -> throw Rejected("NOT_FOR_THIS_PHONE")
            bound.optLong("expiresAt") <= clock() -> throw Rejected("EXPIRED")
            version < currentVersion -> throw Rejected("OLDER_KEY")
        }
        val key = try { openSealed(enc, ct, aad.toByteArray()) } catch (_: Exception) { throw Rejected("OPEN_FAILED") }
        if (key.size != 32) { key.fill(0); throw Rejected("KEY_SIZE") }
        return version to key
    }

    fun join(context: Context, args: JSONObject): JSONObject = synchronized(lock) {
        if (!IdentityVault.isMain(context)) throw Rejected("NOT_MAIN")
        val keys = load(context)
        val (version, key) = open(args, keys.keys.maxOrNull() ?: 0)
        try {
            keys[version] = key.copyOf()
            store(context, keys)
        } finally {
            key.fill(0)
            keys.values.forEach { it.fill(0) }
        }
        JSONObject().put("joined", true).put("version", version)
    }

    fun leave(context: Context): JSONObject = synchronized(lock) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()) { "The hive key couldn't be wiped." }
        JSONObject().put("left", true)
    }

    /** The newest hive key version this phone holds, or 0. */
    fun version(context: Context): Int = synchronized(lock) {
        val keys = runCatching { load(context) }.getOrDefault(mutableMapOf())
        (keys.keys.maxOrNull() ?: 0).also { keys.values.forEach { it.fill(0) } }
    }

    private fun load(context: Context): MutableMap<Int, ByteArray> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEYS, null) ?: return mutableMapOf()
        val box = JSONObject(raw)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(box.getString("iv"), Base64.NO_WRAP)))
        cipher.updateAAD(AAD.toByteArray())
        val plain = cipher.doFinal(Base64.decode(box.getString("ct"), Base64.NO_WRAP))
        try {
            val json = JSONObject(String(plain, Charsets.UTF_8))
            return json.keys().asSequence().associate { it.toInt() to Base64.decode(json.getString(it), Base64.NO_WRAP) }.toMutableMap()
        } finally { plain.fill(0) }
    }

    private fun store(context: Context, keys: Map<Int, ByteArray>) {
        val json = JSONObject().apply { keys.forEach { (v, k) -> put(v.toString(), Base64.encodeToString(k, Base64.NO_WRAP)) } }
        val plain = json.toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(AAD.toByteArray())
            val ct = cipher.doFinal(plain)
            val box = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).put("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEYS, box.toString()).commit()) { "The hive key couldn't be saved." }
        } finally { plain.fill(0) }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return generator.generateKey()
    }
}
