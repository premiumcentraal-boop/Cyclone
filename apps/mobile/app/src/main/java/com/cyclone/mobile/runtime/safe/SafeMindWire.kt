package com.cyclone.mobile.runtime.safe

import org.json.JSONObject

/** Preserve MindMemory's CYM1 text envelope; the AES blob is not the file format. */
internal object SafeMindWire {
    fun open(file: ByteArray, decrypt: (ByteArray) -> ByteArray): ByteArray {
        val raw = String(file, Charsets.UTF_8)
        val plain = if (raw.startsWith("CYM1:")) decrypt(SafeKey.un64(raw.removePrefix("CYM1:"))) else file.copyOf()
        try { JSONObject(String(plain, Charsets.UTF_8)); return plain }
        catch (_: Exception) { plain.fill(0); error("SAFE_MEMORY_INVALID") }
    }
    fun seal(plain: ByteArray, encrypt: (ByteArray) -> ByteArray): ByteArray {
        JSONObject(String(plain, Charsets.UTF_8))
        return ("CYM1:" + SafeKey.b64(encrypt(plain))).toByteArray(Charsets.UTF_8)
    }
}
