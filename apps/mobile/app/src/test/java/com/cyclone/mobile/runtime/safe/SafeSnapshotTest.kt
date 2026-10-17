package com.cyclone.mobile.runtime.safe

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec

class SafeSnapshotTest {
    private val key = ByteArray(32) { it.toByte() }
    private val identity = SafeSnapshot.Identity(17, "profile-a", "phone_a", 12, 1770000000000)
    private fun archive(entries: List<SafeSnapshot.Entry> = listOf(SafeSnapshot.Entry("files/a", "private-example".toByteArray()))): ByteArray =
        ByteArrayOutputStream().also { SafeSnapshot.write(it, key, identity, entries, JSONObject()) }.toByteArray()
    private fun rejected(bytes: ByteArray, safe: ByteArray = key) {
        try { SafeSnapshot.read(ByteArrayInputStream(bytes), safe); fail("must reject") } catch (_: SafeSnapshot.Invalid) { }
    }
    @Test fun allStoreKindsRoundTrip() {
        val names = listOf("databases/brain.db", "files/atlas/a", "prefs/ce/test.xml", "sealed/identity.json")
        val entries = names.map { SafeSnapshot.Entry(it, it.toByteArray()) }
        val (header, read) = SafeSnapshot.read(ByteArrayInputStream(archive(entries)), key)
        try {
            assertEquals(17, header.getInt("androidUserId")); assertEquals(names.sorted(), read.map { it.path })
            read.forEach { assertArrayEquals(it.path.toByteArray(), it.bytes) }
        } finally { read.forEach { it.close() }; entries.forEach { it.close() } }
    }
    @Test fun plaintextNeverAppearsInArchive() { assertFalse(String(archive(), Charsets.ISO_8859_1).contains("private-example")) }
    @Test fun chunkBoundaryRoundTrip() {
        val value = ByteArray(SafeSnapshot.CHUNK * 3 + 15).also(java.security.SecureRandom()::nextBytes)
        val read = SafeSnapshot.read(ByteArrayInputStream(archive(listOf(SafeSnapshot.Entry("files/b", value)))), key).second
        try { assertArrayEquals(value, read.single().bytes) } finally { read.forEach { it.close() } }
    }
    @Test fun tamperedCiphertextRejected() { val bytes = archive(); bytes[bytes.size - 20] = (bytes[bytes.size - 20].toInt() xor 1).toByte(); rejected(bytes) }
    @Test fun tamperedHeaderRejected() {
        val bytes = archive(); val at = String(bytes, Charsets.ISO_8859_1).indexOf("profile-a")
        bytes[at + 8] = 'b'.code.toByte(); rejected(bytes)
    }
    @Test fun missingTerminalRejected() { val bytes = archive(); rejected(bytes.copyOf(bytes.size - 32)) }
    @Test fun truncatedTagRejected() { val bytes = archive(); rejected(bytes.copyOf(bytes.size - 1)) }
    @Test fun appendedBytesRejected() { rejected(archive() + byteArrayOf(1)) }
    @Test fun wrongKeyRejected() { rejected(archive(), ByteArray(32)) }
    @Test fun eachWriteUsesFreshNonces() { assertFalse(archive().contentEquals(archive())) }
    @Test fun pathsRejectTraversal() { listOf("../x", "/x", "a/../x", "a\\b", "a//b").forEach { assertFalse(SafeSnapshot.validPath(it)) } }
    @Test(expected = IllegalArgumentException::class) fun duplicateEntryRejected() { archive(listOf(SafeSnapshot.Entry("a", byteArrayOf()), SafeSnapshot.Entry("a", byteArrayOf()))) }
    @Test fun emptyArchiveAuthenticated() { assertTrue(SafeSnapshot.read(ByteArrayInputStream(archive(emptyList())), key).second.isEmpty()) }
    @Test fun rootWrapSurvivesNewLocalKey() {
        val root = SafeKey.fresh(); val box = SafeKey.wrapRoot(key, root)
        assertArrayEquals(key, SafeKey.openRoot(box, root))
        try { SafeKey.open(box, SecretKeySpec(root, "AES"), "keystore"); fail("domain separation") } catch (_: Exception) { }
    }
    @Test fun hiveWrapBindsVersion() {
        val hive = SafeKey.fresh(); val box = SafeKey.wrapHive(key, hive, 3)
        assertArrayEquals(key, SafeKey.openHive(box, hive))
        box.put("version", 4)
        try { SafeKey.openHive(box, hive); fail("version bound") } catch (_: Exception) { }
    }
    @Test fun recoveryWordCountAndChecksum() {
        val words = (0..2047).map { "word$it" }
        val phrase = SafeKey.recoveryWords(ByteArray(32), words)
        assertEquals(24, phrase.size); assertTrue(phrase.take(23).all { it == "word0" }); assertEquals("word102", phrase.last())
    }
    @Test fun recoveryParametersAreFixedBeforeKdf() {
        try { SafeKey.openRecovery(JSONObject().put("n", 1).put("r", 8).put("p", 1), charArrayOf('a')); fail("KDF parameter injection") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun scryptMatchesIndependentOpenSslFixtureAtProductionCost() {
        val phrase = "test-only-recovery-phrase".toCharArray()
        val result = SafeKey.recoveryWrapping(phrase, ByteArray(32))
        try {
            assertEquals("992bf0fd07585a6436c767f98b869270b725396fbd4f7aeab1b475d332bfa69d", result.joinToString("") { "%02x".format(it) })
        } finally { result.fill(0); phrase.fill('\u0000') }
    }
    @Test fun entryCloseWipesSecret() { val entry = SafeSnapshot.Entry("a", byteArrayOf(1)); entry.close(); assertEquals(0.toByte(), entry.bytes.single()) }
}
