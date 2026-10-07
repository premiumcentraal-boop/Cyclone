package com.cyclone.mobile.gesture.typing

import com.cyclone.mobile.gesture.GestureRng
import com.cyclone.mobile.gesture.HandsStyle
import kotlin.math.roundToLong

/** One step of key-by-key typing: wait [delayMs], then commit [text] (one character or a burst) or press Backspace. */
data class Keystroke(
    val delayMs: Long,
    val text: String?,
) {
    val backspace: Boolean get() = text == null

    companion object {
        fun key(delayMs: Long, text: String) = Keystroke(delayMs, text)
        fun backspace(delayMs: Long) = Keystroke(delayMs, null)
    }
}

/**
 * Plan 52 run 3: when, and how fast, each key goes in.
 *
 * Human typing is not an even beat. Gaps depend on the letter pair (an easy pair is quick, the same key twice or a
 * shift is slower), words come in bursts with short pauses between them, symbols and digits cost a keyboard-layer
 * switch, and people hesitate now and then. This planner builds that rhythm, seeded and bounded. It only ever sees
 * the value inside the phone process and returns nothing that is stored or exported: the plan is used and dropped.
 *
 * Typos are opt-in, rare, adjacent-key only and corrected at once; never in values that look like a username,
 * email, link, code or number (no spaces), so the final text is always exactly the value.
 */
object KeystrokePlanner {
    /** Values up to this length are typed key by key; longer ones get a typed opening and the rest in one burst. */
    const val KEYED_CHARS = 80
    const val MAX_VALUE_CHARS = 2_000
    /** The opening of a long value that is still typed key by key. */
    const val LONG_OPENING_CHARS = 40
    /** The whole typing step never takes longer than this (Natural); Relaxed gets [RELAXED_CAP_MS]. */
    const val NATURAL_CAP_MS = 9_000L
    const val RELAXED_CAP_MS = 14_000L
    private const val MIN_GAP_MS = 35L

    private val qwertyRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    /** True when key-by-key typing can represent [value]: single-line, no control characters, within bounds. */
    fun eligible(value: CharSequence): Boolean {
        if (value.isEmpty() || value.length > MAX_VALUE_CHARS) return false
        return value.none { it == '\n' || it == '\r' || it == '\t' || Character.isISOControl(it) }
    }

    /** Typos are only allowed in ordinary prose: it has spaces and is not an email or a link. */
    fun typoSafe(value: CharSequence): Boolean {
        val text = value.toString()
        return ' ' in text && '@' !in text && "://" !in text && !text.contains("www.", ignoreCase = true)
    }

    fun plan(value: CharSequence, style: HandsStyle, typos: Boolean, rng: GestureRng): List<Keystroke> {
        require(eligible(value)) { "value cannot be typed key by key" }
        val relaxed = style == HandsStyle.RELAXED
        val units = codePoints(value.toString())
        val keyedCount = if (units.size <= KEYED_CHARS) units.size else openingLength(units)
        val allowTypos = typos && typoSafe(value)
        val out = ArrayList<Keystroke>(keyedCount + 8)
        // Finding the first key after the keyboard opens.
        var delay = (if (relaxed) 260.0 else 160.0) + rng.nextUnit() * (if (relaxed) 260.0 else 180.0)
        var previous: String? = null
        var symbolLayer = false
        var sinceHesitation = 0
        val hesitateEvery = 6 + (rng.nextUnit() * 7).toInt()
        for (index in 0 until keyedCount) {
            val unit = units[index]
            if (index > 0) delay = gap(previous!!, unit, relaxed, rng)
            // Digits and symbols live on another keyboard layer: switching there or back costs a key press.
            val needsSymbols = isSymbolLayer(unit)
            if (needsSymbols != symbolLayer && unit != " ") {
                delay += (if (relaxed) 200.0 else 130.0) + rng.nextUnit() * 120.0
                symbolLayer = needsSymbols
            }
            sinceHesitation++
            if (sinceHesitation >= hesitateEvery && unit != " ") {
                delay += (if (relaxed) 300.0 else 150.0) + rng.nextUnit() * (if (relaxed) 500.0 else 250.0)
                sinceHesitation = 0
            }
            if (allowTypos && unit.length == 1 && unit[0].isLetter() && rng.nextUnit() < 0.015) {
                val wrong = neighbour(unit[0], rng)
                if (wrong != null) {
                    out += Keystroke.key(delay.roundToLong().coerceAtLeast(MIN_GAP_MS), wrong.toString())
                    out += Keystroke.backspace((180.0 + rng.nextUnit() * 170.0).roundToLong())
                    delay = 90.0 + rng.nextUnit() * 80.0
                }
            }
            out += Keystroke.key(delay.roundToLong().coerceAtLeast(MIN_GAP_MS), unit)
            previous = unit
        }
        if (keyedCount < units.size) {
            // The rest of a long value goes in as one burst after a short pause, as a person pastes or accepts a
            // suggestion; the exact text still ends up in the field.
            val rest = units.subList(keyedCount, units.size).joinToString("")
            out += Keystroke.key((250.0 + rng.nextUnit() * 250.0).roundToLong(), rest)
        }
        return capped(out, if (relaxed) RELAXED_CAP_MS else NATURAL_CAP_MS)
    }

    fun totalMs(strokes: List<Keystroke>): Long = strokes.sumOf { it.delayMs }

    /** Replays the plan onto an empty field: what the text would be after every stroke ran. */
    fun replay(strokes: List<Keystroke>): String {
        val text = StringBuilder()
        for (stroke in strokes) {
            if (stroke.backspace) {
                if (text.isNotEmpty()) {
                    val cut = Character.offsetByCodePoints(text, text.length, -1)
                    text.setLength(cut)
                }
            } else {
                text.append(stroke.text)
            }
        }
        return text.toString()
    }

    private fun gap(previous: String, next: String, relaxed: Boolean, rng: GestureRng): Double {
        // A quick thumb typist: about 7-10 keys a second in Natural; Relaxed is slower.
        val base = if (relaxed) 175.0 else 112.0
        val spread = if (relaxed) 70.0 else 45.0
        var gap = base + rng.nextSignedUnit() * spread
        when {
            next == " " -> gap += 25.0 + rng.nextUnit() * (if (relaxed) 140.0 else 90.0)
            previous == " " -> gap += 30.0 + rng.nextUnit() * 110.0
            previous == next -> gap += 35.0
            next == "@" || next == "." -> gap += 70.0 + rng.nextUnit() * 90.0
            next.length == 1 && next[0].isUpperCase() -> gap += 60.0 + rng.nextUnit() * 60.0
            sameSideOfKeyboard(previous, next) -> gap += 18.0
            else -> gap -= 12.0
        }
        return gap.coerceAtLeast(MIN_GAP_MS.toDouble())
    }

    /** Two thumbs alternate faster than one thumb hitting two keys on the same half. */
    private fun sameSideOfKeyboard(a: String, b: String): Boolean {
        val ca = a.singleOrNull()?.lowercaseChar() ?: return false
        val cb = b.singleOrNull()?.lowercaseChar() ?: return false
        val sa = side(ca) ?: return false
        val sb = side(cb) ?: return false
        return sa == sb
    }

    private fun side(c: Char): Int? {
        for (row in qwertyRows) {
            val at = row.indexOf(c)
            if (at >= 0) return if (at < row.length / 2) 0 else 1
        }
        return null
    }

    private fun isSymbolLayer(unit: String): Boolean {
        val c = unit.singleOrNull() ?: return true
        if (c == ' ' || c == '.' || c == ',') return false
        return !c.isLetter()
    }

    private fun neighbour(c: Char, rng: GestureRng): Char? {
        val lower = c.lowercaseChar()
        for ((rowIndex, row) in qwertyRows.withIndex()) {
            val at = row.indexOf(lower)
            if (at < 0) continue
            val options = buildList {
                if (at > 0) add(row[at - 1])
                if (at < row.length - 1) add(row[at + 1])
                qwertyRows.getOrNull(rowIndex + 1)?.getOrNull(at)?.let(::add)
            }
            if (options.isEmpty()) return null
            val pick = options[(rng.nextUnit() * options.size).toInt().coerceAtMost(options.size - 1)]
            return if (c.isUpperCase()) pick.uppercaseChar() else pick
        }
        return null
    }

    /** A long value's typed opening ends at a word boundary near [LONG_OPENING_CHARS]. */
    private fun openingLength(units: List<String>): Int {
        val limit = LONG_OPENING_CHARS.coerceAtMost(units.size)
        for (index in limit downTo LONG_OPENING_CHARS / 2) {
            if (index < units.size && units[index] == " ") return index + 1
        }
        return limit
    }

    private fun codePoints(text: String): List<String> {
        val out = ArrayList<String>(text.length)
        var index = 0
        while (index < text.length) {
            val next = text.offsetByCodePoints(index, 1)
            out += text.substring(index, next)
            index = next
        }
        return out
    }

    /** Scales every gap down (never below the minimum) when the whole plan would run past [capMs]. */
    private fun capped(strokes: List<Keystroke>, capMs: Long): List<Keystroke> {
        val total = totalMs(strokes)
        if (total <= capMs) return strokes
        val factor = capMs.toDouble() / total.toDouble()
        return strokes.map { it.copy(delayMs = (it.delayMs * factor).toLong().coerceAtLeast(MIN_GAP_MS / 2)) }
    }
}
