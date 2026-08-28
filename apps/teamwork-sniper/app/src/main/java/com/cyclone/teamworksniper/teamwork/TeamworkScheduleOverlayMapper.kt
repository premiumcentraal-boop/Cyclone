package com.cyclone.teamworksniper.teamwork

import com.cyclone.teamworksniper.data.ShiftCode
import com.cyclone.teamworksniper.ui.overlay.ObservedOverlayDay
import com.cyclone.teamworksniper.ui.overlay.ObservedOverlaySchedule
import com.cyclone.teamworksniper.ui.overlay.ObservedOverlayShift
import com.cyclone.teamworksniper.ui.overlay.ObservedShiftStatus
import com.cyclone.teamworksniper.ui.overlay.ScheduleSemanticNode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.IsoFields

object TeamworkScheduleOverlayMapper {
    fun map(root: ScheduleSemanticNode): ObservedOverlaySchedule? {
        if (root.packageName != TEAMWORK_PACKAGE) return null
        val flat = flatten(root)
        if (flat.none { it.node.resourceId?.endsWith("agenda-list") == true }) return null
        val weekNumber = flat.asSequence()
            .map { it.node.ownText() }
            .mapNotNull { WEEK.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .firstOrNull() ?: return null
        val weekStart = weekStart(flat.mapNotNull { it.node.resourceId }, weekNumber) ?: return null

        val days = flat.asSequence()
            .filter { ref -> WEEKDAYS.containsKey(ref.node.ownText().uppercase()) }
            .mapNotNull { ref -> mapDay(ref, weekStart) }
            .groupBy { it.date }
            .mapNotNull { (_, candidates) -> candidates.minByOrNull { it.dayBounds.height } }
            .sortedBy { it.date }
        if (days.isEmpty()) return null
        return ObservedOverlaySchedule(weekStart, days)
    }

    private fun mapDay(ref: Ref, weekStart: LocalDate): ObservedOverlayDay? {
        val weekday = WEEKDAYS[ref.node.ownText().uppercase()] ?: return null
        val expectedDate = weekStart.plusDays((weekday.value - 1).toLong())
        val group = ref.ancestors.asReversed().firstOrNull { candidate ->
            candidate.bounds.isUsable &&
                candidate.bounds.height in MIN_DAY_HEIGHT..MAX_DAY_HEIGHT &&
                containsStandaloneDay(candidate, expectedDate.dayOfMonth) &&
                candidate.subtreeText().contains(weekday.name.take(3), ignoreCase = true)
        } ?: return null
        val groupFlat = flatten(group)
        val emptyAnchor = groupFlat.firstOrNull {
            it.node.visible && NO_SHIFT.matches(it.node.ownText())
        }?.node?.bounds
        val shifts = groupFlat.asSequence()
            .map { it.node }
            .filter { it.resourceId?.endsWith("/shift-item") == true }
            .mapNotNull { mapShift(it, expectedDate) }
            .distinctBy { it.semanticIdentity }
            .toList()
        return ObservedOverlayDay(expectedDate, group.bounds, emptyAnchor, shifts)
    }

    private fun mapShift(row: ScheduleSemanticNode, date: LocalDate): ObservedOverlayShift? {
        val flat = flatten(row).map { it.node }
        val text = row.subtreeText()
        val code = flat.asSequence().mapNotNull { node ->
            ShiftCode.fromRaw(node.resourceId?.substringAfterLast('/'))
                ?: ShiftCode.fromRaw(node.ownText())
        }.firstOrNull() ?: return null
        val status = when {
            Regex("(?i)\\bopen\\s+to\\s+take\\b").containsMatchIn(text) -> ObservedShiftStatus.OPEN
            Regex("(?i)\\bscheduled\\b").containsMatchIn(text) -> ObservedShiftStatus.ASSIGNED
            else -> return null
        }
        val time = TIME_RANGE.find(text)
        val start = time?.let { safeTime(it.groupValues[1], it.groupValues[2]) }
        val end = time?.let { safeTime(it.groupValues[3], it.groupValues[4]) }
        val identity = flat.firstNotNullOfOrNull { node ->
            node.resourceId?.takeIf { it.startsWith(NATIVE_SHIFT_PREFIX) }
        } ?: "$date|${code.name}|${start ?: "?"}|${end ?: "?"}|${status.name}"
        return ObservedOverlayShift(code, start, end, status, identity, row.bounds)
    }

    private fun weekStart(resourceIds: List<String>, weekNumber: Int): LocalDate? {
        val ranges = resourceIds.mapNotNull { id ->
            val match = WEEK_SELECTOR.find(id) ?: return@mapNotNull null
            runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull()
        }
        ranges.firstOrNull { it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) == weekNumber }?.let { return it }
        val year = ranges.firstOrNull()?.year ?: LocalDate.now().year
        val januaryFourth = LocalDate.of(year, 1, 4)
        val firstMonday = januaryFourth.minusDays((januaryFourth.dayOfWeek.value - 1).toLong())
        return firstMonday.plusWeeks((weekNumber - 1).toLong())
    }

    private fun containsStandaloneDay(node: ScheduleSemanticNode, day: Int): Boolean =
        flatten(node).any { DAY_NUMBER.matches(it.node.ownText()) && it.node.ownText().toIntOrNull() == day }

    private fun safeTime(hour: String, minute: String): LocalTime? =
        runCatching { LocalTime.of(hour.toInt(), minute.toInt()) }.getOrNull()

    private data class Ref(
        val node: ScheduleSemanticNode,
        val ancestors: List<ScheduleSemanticNode>,
    )

    private fun flatten(root: ScheduleSemanticNode): List<Ref> = buildList {
        fun visit(node: ScheduleSemanticNode, ancestors: List<ScheduleSemanticNode>) {
            add(Ref(node, ancestors))
            node.children.forEach { visit(it, ancestors + node) }
        }
        visit(root, emptyList())
    }

    private const val TEAMWORK_PACKAGE = "tech.picnic.workapp"
    private const val NATIVE_SHIFT_PREFIX = "tech.picnic.workapp:id/SG"
    private const val MIN_DAY_HEIGHT = 100
    private const val MAX_DAY_HEIGHT = 900
    private val WEEK = Regex("(?i)^Week\\s+(\\d{1,2})$")
    private val WEEK_SELECTOR = Regex("calendar-week-selector-(\\d{4}-\\d{2}-\\d{2})-(\\d{4}-\\d{2}-\\d{2})$")
    private val DAY_NUMBER = Regex("^\\d{1,2}$")
    private val NO_SHIFT = Regex("(?i)^no\\s+shift$")
    private val TIME_RANGE = Regex("(\\d{1,2})[:.](\\d{2})\\s*(?:-|–|—|to)\\s*(\\d{1,2})[:.](\\d{2})")
    private val WEEKDAYS = mapOf(
        "MON" to DayOfWeek.MONDAY,
        "TUE" to DayOfWeek.TUESDAY,
        "WED" to DayOfWeek.WEDNESDAY,
        "THU" to DayOfWeek.THURSDAY,
        "FRI" to DayOfWeek.FRIDAY,
        "SAT" to DayOfWeek.SATURDAY,
        "SUN" to DayOfWeek.SUNDAY,
    )
}
