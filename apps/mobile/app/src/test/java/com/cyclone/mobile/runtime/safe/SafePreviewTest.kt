package com.cyclone.mobile.runtime.safe

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SafePreviewTest {
    @Test fun countsContainNoAccountOrTaskValues() {
        val secret = "private-account-value"
        val entries = listOf(
            SafeSnapshot.Entry("sealed/identities", JSONObject().put("plain", SafeKey.b64("{\"identities\":[{\"password\":\"$secret\"}]}".toByteArray())).toString().toByteArray()),
            SafeSnapshot.Entry("prefs/ce/cyclone_automation_studio.xml", "<map><string name=\"automations\">[{},{}]</string><string name=\"skills\">[{}]</string></map>".toByteArray()),
            SafeSnapshot.Entry("databases/cyclone_page_awareness_v28.db", byteArrayOf(1)),
        )
        try {
            val result = SafePreview.counts(entries) { _, _, table -> assertEquals("pages", table); 3 }
            assertEquals(SafePreview.Counts(1, 2, 1, 3), result)
            assertFalse(result.toString().contains(secret))
        } finally { entries.forEach { it.close() } }
    }
    @Test fun absentStoresCountAsZero() {
        assertEquals(SafePreview.Counts(0, 0, 0, 0), SafePreview.counts(emptyList()) { _, _, _ -> fail("no database"); 0 })
    }
}
