package com.cyclone.mobile.runtime.safe

import org.json.JSONArray
import org.json.JSONObject
import javax.xml.parsers.DocumentBuilderFactory

/** Counts only, derived from a fully authenticated archive. Never expose account or task values to the UI. */
internal object SafePreview {
    data class Counts(val accounts: Int, val routines: Int, val skills: Int, val maps: Int)
    fun counts(entries: List<SafeSnapshot.Entry>, databaseCount: (String, ByteArray, String) -> Int): Counts {
        var accounts = 0; var routines = 0; var skills = 0; var maps = 0
        for (entry in entries) when (entry.path) {
            "sealed/identities" -> {
                val plain = SafeKey.un64(JSONObject(String(entry.bytes, Charsets.UTF_8)).getString("plain"))
                try { accounts = JSONObject(String(plain, Charsets.UTF_8)).getJSONArray("identities").length() }
                finally { plain.fill(0) }
            }
            "prefs/ce/cyclone_automation_studio.xml" -> {
                SafePrefsWire.validate(entry.bytes)
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(entry.bytes.inputStream())
                val strings = doc.getElementsByTagName("string")
                for (i in 0 until strings.length) {
                    val node = strings.item(i)
                    when (node.attributes.getNamedItem("name")?.nodeValue) {
                        "automations" -> routines += JSONArray(node.textContent).length()
                        "skills" -> skills += JSONArray(node.textContent).length()
                    }
                }
            }
            "databases/cyclone_brain.db" -> routines += databaseCount(entry.path, entry.bytes, "routine_memory")
            "databases/cyclone_adaptive_brain_v27.db" -> skills += databaseCount(entry.path, entry.bytes, "micro_skills")
            "databases/cyclone_page_awareness_v28.db" -> maps += databaseCount(entry.path, entry.bytes, "pages")
        }
        return Counts(accounts, routines, skills, maps)
    }
}
