package com.cyclone.mobile.runtime.safe

import org.bouncycastle.crypto.generators.SCrypt
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Secret arrays are owned by the caller, never persisted except wrapped, and never printable. */
internal object SafeKey {
    const val ALIAS = "cyclone.safe.v1"
    const val SCRYPT_N = 131072
    const val SCRYPT_R = 8
    const val SCRYPT_P = 1
    private val random = SecureRandom()
    fun fresh(): ByteArray = ByteArray(32).also(random::nextBytes)

    fun wrap(safe: ByteArray, wrapping: SecretKey, purpose: String): JSONObject {
        require(safe.size == 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapping)
        cipher.updateAAD("cyclone-safe/v1/$purpose".toByteArray(Charsets.UTF_8))
        return JSONObject().put("iv", b64(cipher.iv)).put("ct", b64(cipher.doFinal(safe)))
    }

    fun open(box: JSONObject, wrapping: SecretKey, purpose: String): ByteArray {
        val iv = un64(box.getString("iv")); val ct = un64(box.getString("ct"))
        require(iv.size == 12 && ct.size == 48)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrapping, GCMParameterSpec(128, iv))
        cipher.updateAAD("cyclone-safe/v1/$purpose".toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ct).also { check(it.size == 32) }
    }

    fun wrapRoot(safe: ByteArray, root: ByteArray) = wrap(safe, SecretKeySpec(root, "AES"), "root")
    fun openRoot(box: JSONObject, root: ByteArray) = open(box, SecretKeySpec(root, "AES"), "root")

    private fun hiveWrapping(hive: ByteArray): ByteArray {
        require(hive.size == 32)
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(hive, "HmacSHA256"))
            doFinal("cyclone-safe/v1/hive-wrapping".toByteArray(Charsets.UTF_8))
        }
    }
    fun wrapHive(safe: ByteArray, hive: ByteArray, version: Int): JSONObject {
        require(version > 0)
        val derived = hiveWrapping(hive)
        return try { wrap(safe, SecretKeySpec(derived, "AES"), "hive/$version").put("version", version) }
        finally { derived.fill(0) }
    }
    fun openHive(box: JSONObject, hive: ByteArray): ByteArray {
        val version = box.getInt("version"); require(version > 0)
        val derived = hiveWrapping(hive)
        return try { open(box, SecretKeySpec(derived, "AES"), "hive/$version") } finally { derived.fill(0) }
    }

    /** BIP-39 English word list, 256 entropy bits + 8 checksum bits = 24 words. */
    fun recoveryWords(entropy: ByteArray, words: List<String>): List<String> {
        require(entropy.size == 32 && words.size == 2048 && words.distinct().size == 2048)
        val checksum = java.security.MessageDigest.getInstance("SHA-256").digest(entropy)[0].toInt() and 255
        return (0 until 24).map { group ->
            var index = 0
            repeat(11) { bit ->
                val at = group * 11 + bit
                index = (index shl 1) or if (at < 256) ((entropy[at / 8].toInt() ushr (7 - at % 8)) and 1)
                else ((checksum ushr (7 - (at - 256))) and 1)
            }
            words[index]
        }
    }

    fun recoveryWrapping(phrase: CharArray, salt: ByteArray): ByteArray {
        require(salt.size == 32)
        val utf8 = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(phrase))
        val bytes = ByteArray(utf8.remaining()).also(utf8::get)
        return try { SCrypt.generate(bytes, salt, SCRYPT_N, SCRYPT_R, SCRYPT_P, 32) }
        finally { bytes.fill(0); if (utf8.hasArray()) utf8.array().fill(0) }
    }
    fun wrapRecovery(safe: ByteArray, phrase: CharArray): JSONObject {
        val salt = fresh(); val derived = recoveryWrapping(phrase, salt)
        return try { wrap(safe, SecretKeySpec(derived, "AES"), "recovery")
            .put("salt", b64(salt)).put("n", SCRYPT_N).put("r", SCRYPT_R).put("p", SCRYPT_P) }
        finally { derived.fill(0) }
    }
    fun openRecovery(box: JSONObject, phrase: CharArray): ByteArray {
        require(box.getInt("n") == SCRYPT_N && box.getInt("r") == SCRYPT_R && box.getInt("p") == SCRYPT_P)
        val derived = recoveryWrapping(phrase, un64(box.getString("salt")))
        return try { open(box, SecretKeySpec(derived, "AES"), "recovery") } finally { derived.fill(0) }
    }
    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun un64(value: String): ByteArray { require(value.length <= 64 * 1024 * 1024); return Base64.getDecoder().decode(value) }
}
