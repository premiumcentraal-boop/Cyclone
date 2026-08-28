package com.cyclone.teamworksniper.rules

import com.cyclone.teamworksniper.data.RuleType
import com.cyclone.teamworksniper.data.ShiftCode
import com.cyclone.teamworksniper.data.ShiftRule
import java.time.LocalDate

/** Persists native calendar choices as the same exact rules used by the claim engine. */
object TargetSelectionRules {
    private const val PREFIX = "target:"

    fun toggle(rules: List<ShiftRule>, date: LocalDate, code: ShiftCode): List<ShiftRule> {
        if (rules.any { isExactTarget(it, date, code) }) {
            return rules.filterNot { isExactTarget(it, date, code) }
        }
        return rules + ShiftRule(
            id = "$PREFIX$date:${code.name}",
            name = "${code.name} · $date",
            type = RuleType.EXACT,
            codes = listOf(code),
            enabled = true,
            dates = setOf(date),
        )
    }

    fun isExactTarget(rule: ShiftRule, date: LocalDate, code: ShiftCode): Boolean =
        rule.type == RuleType.EXACT && rule.codes == listOf(code) && rule.dates == setOf(date)
}
