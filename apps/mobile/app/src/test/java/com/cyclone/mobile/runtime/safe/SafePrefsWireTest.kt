package com.cyclone.mobile.runtime.safe

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafePrefsWireTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun settledPreferencesRoundTripUnchanged() {
        val data = "<?xml version=\"1.0\" encoding=\"utf-8\"?><map><string name=\"name\">owner &amp; family</string><int name=\"value\" value=\"2\"/></map>".toByteArray()
        val file = temp.newFile("prefs.xml").apply { writeBytes(data) }
        assertArrayEquals(data, SafePrefsWire.read(file))
    }
    @Test fun interruptedWriteFailsWithoutReadingBackupAsCurrent() {
        val file = temp.newFile("prefs.xml").apply { writeText("<map/>") }
        temp.newFile("prefs.xml.bak").writeText("<map/>")
        assertThrows(IllegalStateException::class.java) { SafePrefsWire.read(file) }
    }
    @Test fun truncatedAndWrongRootFailClosed() {
        for (xml in listOf("<map>", "<not-prefs/>", "")) {
            assertThrows(IllegalStateException::class.java) { SafePrefsWire.validate(xml.toByteArray()) }
        }
    }
    @Test fun externalAndInternalEntitiesAreNeverExpanded() {
        for (xml in listOf("<!DOCTYPE map SYSTEM 'file:///private'><map/>", "<!DOCTYPE map [<!ENTITY x 'secret'>]><map>&x;</map>", "\u0000<map/>")) {
            assertThrows(IllegalStateException::class.java) { SafePrefsWire.validate(xml.toByteArray()) }
        }
    }
}
