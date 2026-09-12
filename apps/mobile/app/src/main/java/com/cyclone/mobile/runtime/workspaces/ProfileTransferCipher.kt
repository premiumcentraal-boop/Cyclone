package com.cyclone.mobile.runtime.workspaces

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.X509EncodedKeySpec
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/** Credentials are encrypted for the destination's non-exportable Android Keystore key. */
internal object ProfileTransferCipher {
    private val parameters = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    fun encrypt(publicKey: String, plaintext: ByteArray): String {
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, parameters)
        return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext))
    }
    fun decrypt(privateKey: PrivateKey, ciphertext: String): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey, parameters)
        return cipher.doFinal(Base64.getDecoder().decode(ciphertext))
    }
}
