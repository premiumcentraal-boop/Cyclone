package com.cyclone.mobile.runtime.safe

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.spec.SecretKeySpec

class SafeSessionTest {
    @get:Rule val temp = TemporaryFolder()
    private val key = ByteArray(32) { 41 }
    private val copies = mutableMapOf<Long, ByteArray>()
    private fun session() = SafeSession(temp.root, { seq, target -> target.writeBytes(copies.getValue(seq)) }, { seq, file, _ -> copies[seq] = file.readBytes() })
    private fun save(seq: Long, content: ByteArray = "saved-data".toByteArray(), user: Int = 0, phone: String = "phone") {
        val entry = SafeSnapshot.Entry("files/Brain/data", content)
        try { session().save(key, SafeSnapshot.Identity(user, "profile", phone, seq, 1), listOf(entry), JSONObject()) }
        finally { entry.close() }
    }
    @Test fun simulatedUninstallRestoresWithFreshKeystoreWrappingKey() {
        val oldWrappingKey = SecretKeySpec(ByteArray(32) { 1 }, "AES")
        val rootKey = ByteArray(32) { 2 }
        val rootBox = SafeKey.wrapRoot(key, rootKey)
        val oldBox = SafeKey.wrap(key, oldWrappingKey, "local")
        save(1)
        // All app-private files and its Keystore wrapping key disappear. External ciphertext and R survive.
        temp.root.listFiles().orEmpty().forEach { assertTrue(it.delete()) }
        val recoveredKey = SafeKey.openRoot(rootBox, rootKey)
        val newWrappingKey = SecretKeySpec(ByteArray(32) { 3 }, "AES")
        val newBox = SafeKey.wrap(recoveredKey, newWrappingKey, "local")
        assertFalse(oldBox.toString() == newBox.toString())
        val restored = mutableMapOf<String, String>()
        val header = session().recover(SafeKey.open(newBox, newWrappingKey, "local"), 0, listOf(1), { true }, "phone") {
            restored[it.path] = String(it.bytes)
        }
        assertEquals("saved-data", restored["files/Brain/data"])
        assertEquals(1, header.getLong("seq"))
        assertEquals(0, temp.root.listFiles()!!.size)
    }
    @Test fun corruptNewestFallsBackBeforeWriting() {
        save(1); save(2)
        copies[2] = copies.getValue(2).dropLast(1).toByteArray()
        val header = session().recover(key, 0, listOf(1, 2), { true }) { }
        assertEquals(1, header.getLong("seq"))
    }
    @Test fun corruptAllNeverWrites() {
        save(1); copies[1] = byteArrayOf(1)
        var writes = 0
        assertThrows(IllegalStateException::class.java) { session().recover(key, 0, listOf(1), { true }) { writes++ } }
        assertEquals(0, writes)
        assertEquals(0, temp.root.listFiles()!!.size)
    }
    @Test fun otherUserNeverWrites() {
        save(1, user = 17)
        var writes = 0
        assertThrows(IllegalStateException::class.java) { session().recover(key, 0, listOf(1), { true }) { writes++ } }
        assertEquals(0, writes)
    }
    @Test fun otherPhoneNeverWrites() {
        save(1, phone = "other")
        assertThrows(IllegalStateException::class.java) { session().recover(key, 0, listOf(1), { true }, "phone") { fail("wrong phone") } }
    }
    @Test fun unsupportedEntryNeverWrites() {
        save(1)
        assertThrows(IllegalArgumentException::class.java) { session().recover(key, 0, listOf(1), { false }) { fail("unknown path") } }
    }
    @Test fun applyFailureDoesNotMergeAnOlderSnapshot() {
        save(1); save(2)
        var writes = 0
        assertThrows(IllegalStateException::class.java) { session().recover(key, 0, listOf(2, 1), { true }) { writes++; error("disk full") } }
        assertEquals(1, writes)
    }
    @Test fun failedPublishKeepsOldCopyAndRemovesPrivateStaging() {
        save(1)
        val failing = SafeSession(temp.root, { _, _ -> }, { _, _, _ -> error("root denied") })
        assertThrows(IllegalStateException::class.java) {
            failing.save(key, SafeSnapshot.Identity(0, "profile", "phone", 2, 1), emptyList(), JSONObject())
        }
        assertEquals(setOf(1L), copies.keys)
        assertEquals(0, temp.root.listFiles()!!.size)
    }
    @Test fun duplicateSequencesAreReadOnlyOnce() {
        save(1)
        var reads = 0
        val reader = SafeSession(temp.root, { _, file -> reads++; file.writeBytes(byteArrayOf(1)) }, { _, _, _ -> })
        assertThrows(IllegalStateException::class.java) { reader.recover(key, 0, listOf(1, 1), { true }) { } }
        assertEquals(1, reads)
    }
}
