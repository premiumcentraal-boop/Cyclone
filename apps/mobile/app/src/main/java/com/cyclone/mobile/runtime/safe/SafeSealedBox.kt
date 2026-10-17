package com.cyclone.mobile.runtime.safe

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Shared, testable re-sealing primitive; callers wipe plaintext after streaming it into a Safe. */
internal object SafeSealedBox {
    class Box(val iv: ByteArray, val ct: ByteArray) {
        override fun toString() = "SafeSealedBox(redacted)"
    }
    fun seal(plain: ByteArray, key: SecretKey, aad: String?): Box {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        aad?.let { cipher.updateAAD(it.toByteArray(Charsets.UTF_8)) }
        return Box(cipher.iv, cipher.doFinal(plain))
    }
    fun open(box: Box, key: SecretKey, aad: String?): ByteArray {
        require(box.iv.size == 12 && box.ct.size >= 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, box.iv))
        aad?.let { cipher.updateAAD(it.toByteArray(Charsets.UTF_8)) }
        return cipher.doFinal(box.ct)
    }
}
