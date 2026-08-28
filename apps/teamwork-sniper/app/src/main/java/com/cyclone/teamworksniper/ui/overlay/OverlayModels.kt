package com.cyclone.teamworksniper.ui.overlay

import com.cyclone.teamworksniper.data.ShiftCode
import java.time.LocalDate
import java.time.LocalTime

data class OverlayRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val isUsable: Boolean get() = width > 0 && height > 0
}

data class ScheduleSemanticNode(
    val packageName: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val visible: Boolean = true,
    val bounds: OverlayRect = OverlayRect(0, 0, 0, 0),
    val children: List<ScheduleSemanticNode> = emptyList(),
) {
    fun ownText(): String = listOfNotNull(text, contentDescription)
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun subtreeText(): String = buildList {
        ownText().takeIf(String::isNotBlank)?.let(::add)
        children.forEach { child -> child.subtreeText().takeIf(String::isNotBlank)?.let(::add) }
    }.joinToString(" ")
}

enum class ObservedShiftStatus { ASSIGNED, OPEN }

data class ObservedOverlayShift(
    val code: ShiftCode,
    val start: LocalTime?,
    val end: LocalTime?,
    val status: ObservedShiftStatus,
    val semanticIdentity: String,
    val bounds: OverlayRect,
)

data class ObservedOverlayDay(
    val date: LocalDate,
    val dayBounds: OverlayRect,
    val emptyAnchorBounds: OverlayRect?,
    val shifts: List<ObservedOverlayShift>,
) {
    val isEmptyDay: Boolean get() = emptyAnchorBounds != null
}

data class ObservedOverlaySchedule(
    val weekStart: LocalDate,
    val days: List<ObservedOverlayDay>,
)

data class OverlayShiftChoice(
    val date: LocalDate,
    val code: ShiftCode,
    val start: LocalTime?,
    val end: LocalTime?,
    val provenance: TemplateProvenance,
    val selected: Boolean,
    val openNow: Boolean,
    val claimed: Boolean,
) {
    val selectionKey: String get() = "$date|${code.name}"
}

data class OverlayDay(
    val date: LocalDate,
    val dayBounds: OverlayRect,
    val anchorBounds: OverlayRect,
    val choices: List<OverlayShiftChoice>,
)

enum class TemplateProvenance { LIVE_CONFIRMED, PROVISIONAL }

data class ShiftTemplate(
    val code: ShiftCode,
    val start: LocalTime?,
    val end: LocalTime?,
    val provenance: TemplateProvenance,
    val evidence: String,
)
