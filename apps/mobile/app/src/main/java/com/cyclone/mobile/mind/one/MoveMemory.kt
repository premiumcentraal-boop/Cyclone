package com.cyclone.mobile.mind.one

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 72: what each app taught the crew, learned from proven moves only and kept on this phone. On by default.
 *
 * A move is remembered when the crew tapped a control, the phone proved the step (code's check held), the app stayed
 * the same and the page changed: "on this page, \"Profile\" led to a page with Edit profile". The next run on that page
 * sees it in the fused call as advice (the screen still decides). A remembered move that fails twice is forgotten, so
 * an app update re-learns instead of misleading.
 *
 * Structure only: control labels and page keys, never typed text, field values, message bodies or anything from a
 * sensitive screen. Labels that look like numbers, codes or addresses are never kept.
 */
interface MoveMemory {
    /** Lines for the fused call about [screen], best first; empty when nothing is known. */
    fun known(screen: StepScreen): List<String>
    fun proven(before: StepScreen, label: String, after: StepScreen)
    fun failed(before: StepScreen, label: String)
}

/** [MoveMemory] in one small JSON file per app under [dir]. Every failure to read or write is silent: memory is advice. */
class FileMoveMemory(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) : MoveMemory {
    private data class Entry(val from: String, val label: String, val to: String, val anchors: List<String>, val ok: Int, val miss: Int, val at: Long)

    private val cache = HashMap<String, MutableList<Entry>>()

    override fun known(screen: StepScreen): List<String> = synchronized(cache) {
        if (screen.sensitive || screen.pageKey.isBlank()) return emptyList()
        entries(screen.packageName).filter { it.from == screen.pageKey && it.ok > 0 }
            .sortedWith(compareByDescending<Entry> { it.ok }.thenByDescending { it.at })
            .take(FusedBoard.MAX_KNOWN)
            .map { e -> "\"${e.label}\" led to a page with " + (e.anchors.joinToString(", ").ifBlank { "other controls" }) + " (${e.ok}×)" }
    }

    override fun proven(before: StepScreen, label: String, after: StepScreen) = synchronized(cache) {
        if (!learnable(before, label, after)) return
        val list = entries(before.packageName)
        val anchors = anchors(before, after)
        val i = list.indexOfFirst { it.from == before.pageKey && it.label == label }
        val now = clock()
        if (i >= 0) {
            val e = list[i]
            // The same move to the same page counts up; to another page, the newer answer starts over.
            list[i] = if (e.to == after.pageKey) e.copy(ok = e.ok + 1, miss = 0, anchors = anchors.ifEmpty { e.anchors }, at = now)
                else e.copy(to = after.pageKey, anchors = anchors, ok = 1, miss = 0, at = now)
        } else {
            list += Entry(before.pageKey, label, after.pageKey, anchors, 1, 0, now)
            if (list.size > MAX_PER_APP) { list.sortBy { it.at }; while (list.size > MAX_PER_APP) list.removeAt(0) }
        }
        save(before.packageName, list)
    }

    override fun failed(before: StepScreen, label: String) = synchronized(cache) {
        if (before.sensitive || before.pageKey.isBlank()) return
        val list = entries(before.packageName)
        val i = list.indexOfFirst { it.from == before.pageKey && it.label == label }
        if (i < 0) return
        val e = list[i]
        if (e.miss + 1 >= FORGET_AFTER) list.removeAt(i) else list[i] = e.copy(miss = e.miss + 1, at = clock())
        save(before.packageName, list)
    }

    private fun learnable(before: StepScreen, label: String, after: StepScreen): Boolean =
        !before.sensitive && !after.sensitive && before.packageName.isNotBlank() && before.packageName == after.packageName &&
            before.pageKey.isNotBlank() && after.pageKey.isNotBlank() && before.pageKey != after.pageKey && keepable(label) &&
            before.controls.none { it.label == label && it.editable }

    /** What the new page has that the old one didn't: the labels that tell it apart. */
    private fun anchors(before: StepScreen, after: StepScreen): List<String> {
        val old = before.controls.map { it.label }.toSet()
        return after.controls.asSequence().filter { !it.editable }.map { it.label.trim() }
            .filter { it !in old && keepable(it) }.distinct().take(MAX_ANCHORS).toList()
    }

    private fun entries(pkg: String): MutableList<Entry> = cache.getOrPut(pkg) { load(pkg) }

    private fun file(pkg: String) = File(dir, pkg.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    private fun load(pkg: String): MutableList<Entry> = runCatching {
        val f = file(pkg)
        if (!f.exists()) return@runCatching mutableListOf()
        val a = JSONObject(f.readText()).optJSONArray("moves") ?: JSONArray()
        (0 until a.length()).mapNotNull { i ->
            val j = a.optJSONObject(i) ?: return@mapNotNull null
            val anchors = j.optJSONArray("anchors")?.let { x -> (0 until x.length()).map { x.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
            Entry(j.optString("from"), j.optString("label"), j.optString("to"), anchors, j.optInt("ok"), j.optInt("miss"), j.optLong("at"))
                .takeIf { it.from.isNotBlank() && it.label.isNotBlank() }
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    private fun save(pkg: String, list: List<Entry>) {
        runCatching {
            dir.mkdirs()
            val a = JSONArray()
            list.forEach { e ->
                a.put(JSONObject().put("from", e.from).put("label", e.label).put("to", e.to).put("anchors", JSONArray(e.anchors))
                    .put("ok", e.ok).put("miss", e.miss).put("at", e.at))
            }
            val tmp = File(dir, file(pkg).name + ".tmp")
            tmp.writeText(JSONObject().put("version", 1).put("moves", a).toString())
            if (!tmp.renameTo(file(pkg))) { file(pkg).delete(); tmp.renameTo(file(pkg)) }
        }
    }

    companion object {
        const val MAX_PER_APP = 300
        const val MAX_ANCHORS = 3
        const val FORGET_AFTER = 2
        private val SECRETISH = Regex("\\d{3,}|@|https?://|€|\\$|£")

        /** A label worth keeping: short, and nothing that looks like a number, an amount, an address or a link. */
        fun keepable(label: String): Boolean {
            val t = label.trim()
            return t.length in 1..MAX_LABEL && !SECRETISH.containsMatchIn(t)
        }

        const val MAX_LABEL = 40
    }
}
