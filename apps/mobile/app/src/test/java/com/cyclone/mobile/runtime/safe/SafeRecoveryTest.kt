package com.cyclone.mobile.runtime.safe

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class SafeRecoveryTest {
    private val key = ByteArray(32) { 8 }
    private fun snapshot(seq: Long, user: Int = 0) = ByteArrayOutputStream().also {
        SafeSnapshot.write(it, key, SafeSnapshot.Identity(user, "profile", "phone", seq, 1), emptyList(), JSONObject())
    }.toByteArray()
    @Test fun invalidNewestHeaderFallsBackToOlderAuthenticatedCopy() {
        val copies = mapOf(2L to byteArrayOf(0), 1L to snapshot(1))
        SafeRecovery.find(listOf(1, 2), 0, { copies.getValue(it).inputStream() }, { key.copyOf() }).use {
            assertEquals(1L, it.header.getLong("seq"))
        }
    }
    @Test fun tamperedBodyCannotSelectItsMetadata() {
        val copies = mapOf(2L to snapshot(2).dropLast(1).toByteArray(), 1L to snapshot(1))
        SafeRecovery.find(listOf(2, 1), 0, { copies.getValue(it).inputStream() }, { key.copyOf() }).use {
            assertEquals(1L, it.header.getLong("seq"))
        }
    }
    @Test fun wrongUserAndWrongKeyNeverReturnARecovery() {
        assertThrows(IllegalStateException::class.java) { SafeRecovery.find(listOf(1), 0, { snapshot(1, 17).inputStream() }, { key.copyOf() }) }
        assertThrows(IllegalStateException::class.java) { SafeRecovery.find(listOf(1), 0, { snapshot(1).inputStream() }, { ByteArray(32) }) }
    }
    @Test fun closingRecoveryWipesChosenKey() {
        val opened = SafeRecovery.find(listOf(1), 0, { snapshot(1).inputStream() }, { key.copyOf() })
        opened.close()
        assertTrue(opened.key.all { it == 0.toByte() })
    }
}
