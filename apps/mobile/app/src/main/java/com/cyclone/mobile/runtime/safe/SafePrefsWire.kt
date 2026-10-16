package com.cyclone.mobile.runtime.safe

import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

/** Validate a settled Android preferences image; never resolve entities or print parser payloads. */
internal object SafePrefsWire {
    fun read(file: File): ByteArray {
        val backup = File(file.path + ".bak")
        check(!backup.exists()) { "SAFE_PREFS_UNSETTLED" }
        val size = file.length(); val modified = file.lastModified()
        check(size in 1..SafeSnapshot.MAX_ENTRY) { "SAFE_PREFS_INVALID" }
        val bytes = file.readBytes()
        try {
            check(!backup.exists() && file.length() == size && file.lastModified() == modified && bytes.size.toLong() == size) { "SAFE_PREFS_UNSETTLED" }
            validate(bytes)
            return bytes
        } catch (_: Exception) { bytes.fill(0); error("SAFE_PREFS_INVALID") }
    }

    fun validate(bytes: ByteArray) {
        try {
            // Android's factory does not implement the desktop Xerces feature switches. Preferences are UTF-8;
            // reject declarations before parsing, and refuse every external resolution as a second boundary.
            val text = String(bytes, Charsets.UTF_8)
            check('\u0000' !in text && "<!DOCTYPE" !in text && "<!ENTITY" !in text)
            val factory = DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> throw IllegalArgumentException("SAFE_PREFS_ENTITY") }
            builder.setErrorHandler(object : DefaultHandler() {
                override fun error(e: SAXParseException) { throw e }
                override fun fatalError(e: SAXParseException) { throw e }
            })
            val document = builder.parse(InputSource(ByteArrayInputStream(bytes)))
            check(document.documentElement.tagName == "map")
        } catch (_: Exception) { error("SAFE_PREFS_INVALID") }
    }
}
