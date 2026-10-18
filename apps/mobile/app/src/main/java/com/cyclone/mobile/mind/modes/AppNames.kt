package com.cyclone.mobile.mind.modes

/**
 * Alpha 144: which apps a request names, the same way for the router, the coverage check, the grammar and the Mind's
 * open_app. Apps are (label, package) pairs as the phone lists them.
 *
 * Field test 143: "pull up google maps" was read as two apps ("Maps" and "Google" both appeared in the text) and went to
 * the Mind, whose open_app then called "Google Maps" ambiguous. People say an app's maker with its name ("google maps",
 * "google photos", "samsung notes", "microsoft teams") and the launcher label often leaves it out. So:
 * - a label counts only as whole words, never inside another word ("Meet" is not in "meeting");
 * - the longest label wins a stretch of words, and a label inside it doesn't count again;
 * - a word next to a named app that is part of that app's package ("google" in com.google.android.apps.maps) belongs to
 *   that app, not to another one.
 * Pure.
 */
object AppNames {
    /** Labels shorter than this are too easily said by accident ("Go", "X"). */
    private const val MIN_LABEL = 3

    private data class Span(val start: Int, val end: Int, val app: Pair<String, String>) {
        val size get() = end - start
    }

    /** The apps [text] names, each once, in the order they are said. */
    fun mentioned(text: String, apps: List<Pair<String, String>>): List<Pair<String, String>> {
        val words = tokens(text)
        if (words.isEmpty()) return emptyList()
        val found = mutableListOf<Span>()
        for (app in apps.distinct()) {
            val label = tokens(app.first)
            if (label.isEmpty() || label.joinToString("").length < MIN_LABEL) continue
            var i = 0
            while (i + label.size <= words.size) {
                if (words.subList(i, i + label.size) == label) found += Span(i, i + label.size, app)
                i++
            }
        }
        // The longest name claims its words; a shorter one inside them is part of it.
        val claimed = BooleanArray(words.size)
        val kept = mutableListOf<Span>()
        for (span in found.sortedWith(compareByDescending<Span> { it.size }.thenBy { it.start })) {
            if ((span.start until span.end).any { claimed[it] }) continue
            (span.start until span.end).forEach { claimed[it] = true }
            kept += span
        }
        // "google maps": "Google" right next to Maps, and "google" is in Maps's package, so it is Maps's maker.
        val makers = kept.filter { a ->
            a.size == 1 && kept.any { b ->
                b !== a && b.app.second != a.app.second && (b.start == a.end || b.end == a.start) && words[a.start] in packageWords(b.app.second)
            }
        }
        return (kept - makers.toSet()).sortedBy { it.start }.map { it.app }.distinctBy { it.second }
    }

    /**
     * The one app [requested] means, or null with [Resolution.candidates] when it is ambiguous or unknown. In order: the
     * package itself, the exact label, the label without its maker's word ("Google Maps" → Maps), the only app [mentioned]
     * finds, and last (unless [strict]) the closest label by words, when one leads. The grammar uses [strict]: its matches
     * are trusted without a coverage check, so they must be exact.
     */
    fun resolve(requested: String, apps: List<Pair<String, String>>, strict: Boolean = false): Resolution {
        val wanted = requested.trim()
        if (wanted.isBlank()) return Resolution(null)
        apps.firstOrNull { it.second.equals(wanted, ignoreCase = true) }?.let { return Resolution(it) }
        val norm = InstantGrammar.normalize(wanted).removeSuffix(" app").trim()
        apps.filter { InstantGrammar.normalize(it.first) == norm }.distinctBy { it.second }.let { exact ->
            if (exact.size == 1) return Resolution(exact.single())
            if (exact.size > 1) return Resolution(null, exact)
        }
        val words = norm.split(' ').filter { it.isNotBlank() }
        if (words.size >= 2) {
            val rest = words.drop(1).joinToString(" ")
            apps.filter { InstantGrammar.normalize(it.first) == rest && words.first() in packageWords(it.second) }.distinctBy { it.second }
                .singleOrNull()?.let { return Resolution(it) }
        }
        mentioned(norm, apps).let { named ->
            // Strict: the whole request must be the app's name, perhaps with its maker ("google maps"), nothing more.
            if (named.size == 1 && (!strict || words.all { w -> w in tokens(named.single().first) || w in packageWords(named.single().second) })) {
                return Resolution(named.single())
            }
        }
        if (strict) return Resolution(null)
        // Closest: how much of the request the label (or its package) holds, and how much of the label the request says.
        // A word is the label's own (1), the start of one of its words ("insta", 0.75), or its package's (0.5).
        val scored = apps.distinctBy { it.second }.map { app ->
            val label = tokens(app.first)
            val pkg = packageWords(app.second)
            fun hit(w: String) = when {
                w in label -> 1.0
                w.length >= 3 && label.any { it.startsWith(w) } -> 0.75
                w.length >= 4 && w in pkg -> 0.5
                else -> 0.0
            }
            val said = words.sumOf { hit(it) } / words.size
            val covered = label.count { l -> words.any { w -> w == l || (w.length >= 3 && l.startsWith(w)) } }.toDouble() / label.size.coerceAtLeast(1)
            Triple(app, maxOf((said + covered) / 2, InstantGrammar.similarity(norm, app.first).takeIf { it >= InstantGrammar.MATCH } ?: 0.0), said)
        }.filter { it.second >= 0.5 }.sortedByDescending { it.second }
        if (scored.isEmpty()) return Resolution(null)
        val top = scored.first()
        // Close scores are a tie; so is a name that only starts the labels' words equally well ("map": Maps, Maps Plus).
        val rivals = scored.drop(1).filter { top.second - it.second < 0.1 || (top.third < 1.0 && it.third >= top.third) }
        return if (rivals.isEmpty()) Resolution(top.first) else Resolution(null, (listOf(top) + rivals).map { it.first }.take(10))
    }

    data class Resolution(val app: Pair<String, String>?, val candidates: List<Pair<String, String>> = emptyList())

    /** The words of a package that can name its maker or product ("com.google.android.apps.maps" → google, maps). */
    fun packageWords(pkg: String): Set<String> = pkg.lowercase().split('.', '_').filter { it.length >= 3 && it !in PACKAGE_NOISE }.toSet()

    private val PACKAGE_NOISE = setOf("com", "org", "net", "android", "apps", "app", "mobile", "client", "main", "release", "lite")

    private fun tokens(text: String): List<String> = InstantGrammar.normalize(text).split(' ').filter { it.isNotBlank() }
}
