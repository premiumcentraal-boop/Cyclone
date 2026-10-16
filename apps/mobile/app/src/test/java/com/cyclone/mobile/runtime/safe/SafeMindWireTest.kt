package com.cyclone.mobile.runtime.safe

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class SafeMindWireTest {
    private val plain = "{\"schema\":\"cyclone-mind-memory-v2\",\"facts\":[],\"forgotten\":[]}".toByteArray()
    private fun seal(bytes: ByteArray, key: ByteArray): ByteArray = SafeSealedBox.seal(bytes, SecretKeySpec(key, "AES"), null).let { it.iv + it.ct }
    private fun open(bytes: ByteArray, key: ByteArray): ByteArray = SafeSealedBox.open(SafeSealedBox.Box(bytes.take(12).toByteArray(), bytes.drop(12).toByteArray()), SecretKeySpec(key, "AES"), null)
    @Test fun currentMemoryTextEnvelopeSurvivesFreshKeys() {
        val old = ByteArray(32) { 1 }; val fresh = ByteArray(32) { 2 }
        val original = SafeMindWire.seal(plain) { seal(it, old) }
        val snapshotPlain = SafeMindWire.open(original) { open(it, old) }
        val restored = SafeMindWire.seal(snapshotPlain) { seal(it, fresh) }
        assertTrue(String(restored).startsWith("CYM1:"))
        assertArrayEquals(plain, SafeMindWire.open(restored) { open(it, fresh) })
        assertFalse(original.contentEquals(restored))
    }
    @Test fun legacyPlaintextIsAcceptedOnlyAsValidJson() {
        assertArrayEquals(plain, SafeMindWire.open(plain) { error("legacy must not decrypt") })
        assertThrows(IllegalStateException::class.java) { SafeMindWire.open("not-json".toByteArray()) { it } }
    }
    @Test fun malformedEncryptedFileFailsClosed() {
        assertThrows(Exception::class.java) { SafeMindWire.open("CYM1:broken".toByteArray()) { error("invalid") } }
    }
    @Test fun wrongKeyDoesNotTurnMemoryIntoEmptyData() {
        val file = SafeMindWire.seal(plain) { seal(it, ByteArray(32) { 1 }) }
        assertThrows(Exception::class.java) { SafeMindWire.open(file) { open(it, ByteArray(32) { 2 }) } }
    }
}
