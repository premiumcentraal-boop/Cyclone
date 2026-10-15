package com.cyclone.mobile.identity

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.Base64

class HiveKeyringTest {
    private val fp = "ABCD 0123 4567 89AB CDEF 0123 4567 89AB"
    private val key = ByteArray(32) { it.toByte() }
    private val join = "hj_" + "a".repeat(20)

    @Before
    fun seams() {
        HiveKeyring.ownFingerprint = { fp }
        HiveKeyring.clock = { 1_000L }
        HiveKeyring.openSealed = { _, _, _ -> key.copyOf() }
    }

    @After
    fun reset() {
        HiveKeyring.clock = System::currentTimeMillis
    }

    private fun envelope(version: Int = 2, bound: Map<String, Any> = emptyMap(), extra: Map<String, Any> = emptyMap()): JSONObject {
        val aad = JSONObject(mapOf("deviceKey" to fp, "deviceId" to "phone-a", "joinId" to join, "version" to version, "expiresAt" to 2_000L) + bound)
        return JSONObject(mapOf("joinId" to join, "version" to version, "enc" to Base64.getEncoder().encodeToString(ByteArray(65) { 4 }),
            "ct" to Base64.getEncoder().encodeToString(ByteArray(48)), "aad" to aad.toString()) + extra)
    }

    @Test
    fun anEnvelopeBoundToThisPhoneOpens() {
        val (version, opened) = HiveKeyring.open(envelope(), currentVersion = 1)
        assertEquals(2, version)
        assertArrayEquals(key, opened)
    }

    @Test
    fun anythingElseIsRefused() {
        val cases = listOf(
            envelope(bound = mapOf("deviceKey" to "0000")) to "NOT_FOR_THIS_PHONE",
            envelope(bound = mapOf("expiresAt" to 999L)) to "EXPIRED",
            envelope(bound = mapOf("version" to 3)) to "AAD_VERSION",
            envelope(bound = mapOf("joinId" to "hj_" + "b".repeat(20))) to "AAD_JOIN",
            envelope(bound = mapOf("extra" to 1)) to "AAD_FIELDS",
            envelope(extra = mapOf("key" to "x")) to "ENVELOPE_FIELDS",
        )
        for ((env, code) in cases) {
            val error = assertThrows(HiveKeyring.Rejected::class.java) { HiveKeyring.open(env, currentVersion = 1) }
            assertEquals(code, error.code)
        }
        assertEquals("OLDER_KEY", assertThrows(HiveKeyring.Rejected::class.java) { HiveKeyring.open(envelope(), currentVersion = 3) }.code)
        HiveKeyring.openSealed = { _, _, _ -> ByteArray(16) }
        assertEquals("KEY_SIZE", assertThrows(HiveKeyring.Rejected::class.java) { HiveKeyring.open(envelope(), currentVersion = 1) }.code)
    }
}
