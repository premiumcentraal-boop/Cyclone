package com.cyclone.mobile.runtime.safe

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec

class SafeSealedBoxTest {
    @Test fun secretsSurviveSnapshotAndResealUnderNewKeys() {
        for (aad in listOf("cyclone-identities/v1", "cyclone-hive/v1/local", null)) {
            val original = "unit-test-placeholder".toByteArray()
            val old = SecretKeySpec(SafeKey.fresh(), "AES"); val fresh = SecretKeySpec(SafeKey.fresh(), "AES")
            val safe = SafeKey.fresh()
            val oldBox = SafeSealedBox.seal(original, old, aad)
            val plain = SafeSealedBox.open(oldBox, old, aad)
            val entry = SafeSnapshot.Entry("sealed/test", plain)
            val bytes = ByteArrayOutputStream()
            try { SafeSnapshot.write(bytes, safe, SafeSnapshot.Identity(0, "main", "phone_a", 1, 1), listOf(entry), JSONObject()) }
            finally { entry.close() }
            assertTrue(plain.all { it == 0.toByte() })
            val restored = SafeSnapshot.read(bytes.toByteArray().inputStream(), safe).second
            try {
                val newBox = SafeSealedBox.seal(restored.single().bytes, fresh, aad)
                val opened = SafeSealedBox.open(newBox, fresh, aad)
                try { assertArrayEquals(original, opened) } finally { opened.fill(0) }
                try { SafeSealedBox.open(newBox, old, aad); fail("old key must not open new record") } catch (_: javax.crypto.AEADBadTagException) { }
            } finally { restored.forEach { it.close() }; safe.fill(0); original.fill(0) }
        }
    }
    @Test fun wrongAssociatedDataFailsClosed() {
        val key = SecretKeySpec(SafeKey.fresh(), "AES")
        val box = SafeSealedBox.seal(byteArrayOf(1), key, "identity")
        try { SafeSealedBox.open(box, key, "hive"); fail("wrong AAD") } catch (_: javax.crypto.AEADBadTagException) { }
    }
    @Test fun malformedNonceRejectedBeforeDecrypt() {
        try { SafeSealedBox.open(SafeSealedBox.Box(ByteArray(1), ByteArray(16)), SecretKeySpec(SafeKey.fresh(), "AES"), null); fail("invalid IV") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun ciphertextDoesNotPrint() {
        assertEquals("SafeSealedBox(redacted)", SafeSealedBox.Box(ByteArray(12), "example".toByteArray()).toString())
    }
}
