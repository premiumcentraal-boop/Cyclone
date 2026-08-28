package com.cyclone.teamworksniper.ui.overlay

import com.cyclone.teamworksniper.data.ActivityEntry
import com.cyclone.teamworksniper.data.RuleType
import com.cyclone.teamworksniper.data.ShiftCode
import com.cyclone.teamworksniper.data.ShiftRule
import com.cyclone.teamworksniper.teamwork.ShiftTemplateProvider
import java.time.LocalDate

class OverlayModelBuilder(
    private val templates: ShiftTemplateProvider = ShiftTemplateProvider(),
) {
    fun build(
        schedule: ObservedOverlaySchedule,
        rules: List<ShiftRule>,
        activity: List<ActivityEntry>,
    ): List<OverlayDay> {
        val selected = rules.asSequence()
            .filter { it.enabled && it.type == RuleType.EXACT && it.codes.size == 1 && it.dates.size == 1 }
            .map { "${it.dates.single()}|${it.codes.single().name}" }
            .toSet()
        val claimed = claimedKeys(activity)

        return schedule.days.mapNotNull { day ->
            val anchor = day.emptyAnchorBounds ?: return@mapNotNull null
            val assigned = day.shifts.filter { it.status == ObservedShiftStatus.ASSIGNED }.map { it.code }.toSet()
            val open = day.shifts.filter { it.status == ObservedShiftStatus.OPEN }.map { it.code }.toSet()
            val choices = templates.forDate(day.date)
                .filterNot { it.code in assigned }
                .map { template ->
                    val key = "${day.date}|${template.code.name}"
                    OverlayShiftChoice(
                        date = day.date,
                        code = template.code,
                        start = template.start,
                        end = template.end,
                        provenance = template.provenance,
                        selected = key in selected,
                        openNow = template.code in open,
                        claimed = key in claimed,
                    )
                }
            OverlayDay(day.date, day.dayBounds, anchor, choices)
        }
    }

    private fun claimedKeys(activity: List<ActivityEntry>): Set<String> = activity.asSequence()
        .filter {
            it.claimAttempted &&
                it.verificationResult in VERIFIED_RESULTS &&
                it.failureReason == null
        }
        .flatMap { entry -> entry.openShifts.asSequence() }
        .mapNotNull(::activityKey)
        .toSet()

    private fun activityKey(raw: String): String? {
        val match = ACTIVITY_SHIFT.find(raw) ?: return null
        val date = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull() ?: return null
        val code = ShiftCode.fromRaw(match.groupValues[2]) ?: return null
        return "$date|${code.name}"
    }

    companion object {
        private val VERIFIED_RESULTS = setOf("TARGET_NO_LONGER_OPEN", "VERIFIED_NOT_OPEN_AFTER_ACTION")
        private val ACTIVITY_SHIFT = Regex("^(\\d{4}-\\d{2}-\\d{2})\\s+(M1|M2|S1|S2|S3)(?:\\b|[- ])")
    }
}

object OverlaySelectionRules {
    private const val PREFIX = "overlay-target:"

    fun toggle(rules: List<ShiftRule>, date: LocalDate, code: ShiftCode): List<ShiftRule> {
        val matching = rules.filter { isExactTarget(it, date, code) }
        if (matching.isNotEmpty()) return rules.filterNot { isExactTarget(it, date, code) }
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
