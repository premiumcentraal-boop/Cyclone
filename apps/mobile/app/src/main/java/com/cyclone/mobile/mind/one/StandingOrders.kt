package com.cyclone.mobile.mind.one

import org.json.JSONObject

/**
 * Plan 69 §5.2: what a mind tells the crew to watch for, compiled by code into something the crew can check.
 *
 * - **wake_when** "Wake me when the invoice is open" → a yes/no question in every step's fused call.
 * - **wake_if_over** "Wake me if the amount is over €400" → a code comparison on a fact (decision models never compare).
 * - **dismiss_popups** "Dismiss popups in this app" → the crew may close easy-to-undo interruptions of any kind.
 * - **quiet_until** "Don't look in until checkout" → the mind's own chosen glances wait until its question holds.
 *
 * At most 8. Orders never grant anything: gates, approvals and the vault are code's, as always. Pure.
 */
object StandingOrders {
    /** One order line's object, or null when it can't be checked by the crew. */
    fun parse(j: JSONObject): StandingOrder? {
        val kind = when (j.optString("kind").lowercase()) {
            "wake_when", "when" -> StandingOrder.Kind.WAKE_WHEN
            "wake_if_over", "over" -> StandingOrder.Kind.WAKE_IF_OVER
            "dismiss_popups", "popups" -> StandingOrder.Kind.DISMISS_POPUPS
            "quiet_until", "quiet" -> StandingOrder.Kind.QUIET_UNTIL
            else -> return null
        }
        val text = j.optString("text").trim().take(200)
        val question = j.optString("question").trim().take(200).takeIf { it.isNotBlank() }
        val fact = j.optString("fact").trim().takeIf { FACT.matches(it) }
        val over = j.opt("over")?.let { v -> (v as? Number)?.toDouble() ?: number(v.toString()) }?.takeIf { !it.isNaN() }
        val order = StandingOrder(j.optString("id").trim().takeIf { ID.matches(it) } ?: "", text.ifBlank { question ?: kind.name.lowercase() },
            kind, question, fact, over)
        return order.takeIf(::valid)
    }

    fun valid(o: StandingOrder): Boolean = when (o.kind) {
        StandingOrder.Kind.WAKE_WHEN, StandingOrder.Kind.QUIET_UNTIL -> !o.question.isNullOrBlank()
        StandingOrder.Kind.WAKE_IF_OVER -> o.fact != null && o.over != null
        StandingOrder.Kind.DISMISS_POPUPS -> true
    }

    /** The orders a mind wrote, made ready for the Score: valid, numbered o1…o8, duplicates dropped. */
    fun compile(orders: List<StandingOrder>): List<StandingOrder> {
        val seen = HashSet<String>()
        return orders.filter(::valid)
            .filter { seen.add("${it.kind}|${it.question}|${it.fact}|${it.over}") }
            .take(Score.MAX_ORDERS)
            .mapIndexed { i, o -> o.copy(id = "o${i + 1}") }
    }

    /** The questions the fused call carries: wake-when and quiet-until orders. */
    fun questions(orders: List<StandingOrder>): List<StandingOrder> =
        orders.filter { (it.kind == StandingOrder.Kind.WAKE_WHEN || it.kind == StandingOrder.Kind.QUIET_UNTIL) && !it.question.isNullOrBlank() }

    /** Wake-if-over orders whose fact now reads over the limit, compared by code. */
    fun firedByFacts(score: Score): List<StandingOrder> = score.orders.filter { o ->
        o.kind == StandingOrder.Kind.WAKE_IF_OVER && o.fact != null && o.over != null &&
            score.fact(o.fact)?.value?.let(::number)?.let { it > o.over } == true
    }

    /** True while a quiet-until order hasn't fired: the mind's own chosen glances wait. */
    fun quiet(score: Score): Boolean = score.orders.any { it.kind == StandingOrder.Kind.QUIET_UNTIL }

    /** An order that fired is spent: it leaves the Score so it can't fire again. */
    fun spend(score: Score, id: String): Score = score.orders(score.orders.filterNot { it.id == id })

    /**
     * A number as people and apps write it: "€1.234,56", "1,234.56", "482.10", "400", "-3". Null when there is no
     * number. Code, never a model, reads amounts.
     */
    fun number(text: String): Double? {
        val m = NUMBER.find(text) ?: return null
        var s = m.value.replace("\u00a0", "").replace("\u202f", "").replace("'", "")
        val neg = s.startsWith("-") || text.substring(0, m.range.first).trimEnd().endsWith("-")
        s = s.trimStart('-')
        val comma = s.lastIndexOf(',')
        val dot = s.lastIndexOf('.')
        s = when {
            comma >= 0 && dot >= 0 -> if (comma > dot) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            comma >= 0 -> if (s.length - comma - 1 in 1..2 && s.count { it == ',' } == 1) s.replace(',', '.') else s.replace(",", "")
            dot >= 0 -> if (s.count { it == '.' } > 1) s.replace(".", "") else s
            else -> s
        }
        return s.toDoubleOrNull()?.let { if (neg) -it else it }
    }

    private val NUMBER = Regex("-?\\d[\\d.,'\u00a0\u202f]*\\d|-?\\d")
    private val FACT = Regex("^[a-z][a-z0-9_]{0,40}$")
    private val ID = Regex("^o[1-8]$")
}
