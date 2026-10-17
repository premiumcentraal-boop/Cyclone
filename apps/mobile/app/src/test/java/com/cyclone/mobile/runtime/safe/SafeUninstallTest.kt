package com.cyclone.mobile.runtime.safe

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.spec.SecretKeySpec

/** Actual filesystem loss + production archive/key/session code. This is not Android/package-manager evidence. */
class SafeUninstallTest {
    @get:Rule val temp = TemporaryFolder()
    private val safe = ByteArray(32) { 7 }
    private val rootKey = ByteArray(32) { 8 }
    private val hive = ByteArray(32) { 9 }
    private val words = "fixture recovery phrase only".toCharArray()
    private fun createExternalCopy(directory: File, recovery: Boolean = false): File {
        val app = temp.newFolder("old-app")
        val oldKey = SecretKeySpec(ByteArray(32) { 1 }, "AES")
        val plain = "fixture-account-private-value".toByteArray()
        val installed = SafeSealedBox.seal(plain, oldKey, "cyclone-identities/v1")
        val entries = listOf(
            SafeSnapshot.Entry("sealed/identities", SafeSealedBox.open(installed, oldKey, "cyclone-identities/v1")),
            SafeSnapshot.Entry("databases/brain.db", "fixture-consistent-database".toByteArray()),
            SafeSnapshot.Entry("files/skills/data", "fixture-skill".toByteArray()),
            SafeSnapshot.Entry("prefs/ce/settings.xml", "<map/>".toByteArray()),
        )
        val wraps = JSONObject().put("root", SafeKey.wrapRoot(safe, rootKey)).put("hive", SafeKey.wrapHive(safe, hive, 2))
        if (recovery) wraps.put("recovery", SafeKey.wrapRecovery(safe, words))
        val copy = File(directory, "safe-17-1.cyc")
        try {
            SafeSession(app, { _, _ -> error("not restoring yet") }, { _, file, _ ->
                file.copyTo(copy); copy.outputStream().use { out -> out.write(file.readBytes()); out.fd.sync() }
            }).save(safe, SafeSnapshot.Identity(17, "profile", "phone", 1, 1), entries, wraps)
        } finally { entries.forEach { it.close() }; plain.fill(0) }
        File(app, "disposable-local-wrap.json").writeText(SafeKey.wrap(safe, oldKey, "local").toString())
        app.listFiles().orEmpty().forEach { assertTrue(it.delete()) }
        assertTrue(app.delete()) // Entire fake app sandbox is gone, together with all references to oldKey.
        return copy
    }
    private fun restore(copy: File, key: ByteArray) {
        val fresh = temp.newFolder("reinstalled-app")
        val newKey = SecretKeySpec(ByteArray(32) { 2 }, "AES")
        val order = mutableListOf<String>()
        SafeSession(File(fresh, "ciphertext"), { _, target -> copy.copyTo(target, overwrite = true) }, { _, _, _ -> error("restore only") })
            .recover(key, 17, listOf(1), { true }, "phone") { entry ->
                order += entry.path
                if (entry.path.startsWith("sealed/")) {
                    val resealed = SafeSealedBox.seal(entry.bytes, newKey, "cyclone-identities/v1")
                    SafeFiles.atomic(File(fresh, "identities.sealed"), resealed.iv + resealed.ct)
                    val opened = SafeSealedBox.open(resealed, newKey, "cyclone-identities/v1")
                    try { assertEquals("fixture-account-private-value", String(opened)) } finally { opened.fill(0) }
                } else SafeFiles.atomic(File(fresh, entry.path), entry.bytes)
            }
        assertEquals("databases/brain.db", order.first())
        assertEquals("fixture-skill", File(fresh, "files/skills/data").readText())
        assertEquals("<map/>", File(fresh, "prefs/ce/settings.xml").readText())
        val raw = copy.readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("fixture-account-private-value"))
    }
    @Test fun rootWrappingRestoresAfterAllAppFilesDisappear() {
        val external = temp.newFolder("root-safe")
        val copy = createExternalCopy(external)
        val header = copy.inputStream().use(SafeSnapshot::peek)
        val key = SafeKey.openRoot(header.getJSONObject("wraps").getJSONObject("root"), rootKey)
        try { restore(copy, key) } finally { key.fill(0) }
    }
    @Test fun pcCiphertextCanBeRestoredUsingOnlyTheUnlockedHiveKey() {
        val copy = createExternalCopy(temp.newFolder("pc-ciphertext"))
        val header = copy.inputStream().use(SafeSnapshot::peek)
        val key = SafeKey.openHive(header.getJSONObject("wraps").getJSONObject("hive"), hive)
        try { restore(copy, key) } finally { key.fill(0) }
    }
    @Test fun folderCiphertextCanBeRestoredWithRecoveryWordsAndNoRootOrOldKeystore() {
        val copy = createExternalCopy(temp.newFolder("owner-folder"), recovery = true)
        val header = copy.inputStream().use(SafeSnapshot::peek)
        val key = SafeKey.openRecovery(header.getJSONObject("wraps").getJSONObject("recovery"), words)
        try { restore(copy, key) } finally { key.fill(0); words.fill('\u0000') }
    }
}
