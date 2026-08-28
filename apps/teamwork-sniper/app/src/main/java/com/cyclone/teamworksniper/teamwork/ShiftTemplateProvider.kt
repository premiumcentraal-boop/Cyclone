package com.cyclone.teamworksniper.teamwork

import com.cyclone.teamworksniper.data.ShiftCode
import com.cyclone.teamworksniper.ui.overlay.ShiftTemplate
import com.cyclone.teamworksniper.ui.overlay.TemplateProvenance
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** Central schedule templates. Every concrete time below is backed by live Pixel 8 Teamwork evidence. */
class ShiftTemplateProvider {
    fun forDate(date: LocalDate): List<ShiftTemplate> = listOf(
        m1(date.dayOfWeek),
        provisional(ShiftCode.M2, "No live Teamwork M2 row observed"),
        confirmed(ShiftCode.S1, 14, 10, 16, 45, "Live scheduled Teamwork rows, week 36/2026"),
        confirmed(ShiftCode.S2, 16, 55, 19, 30, "Live scheduled Teamwork rows, week 36/2026"),
        confirmed(ShiftCode.S3, 19, 40, 22, 15, "Live Open to take Teamwork rows, week 36/2026"),
    )

    private fun m1(day: DayOfWeek): ShiftTemplate = when (day) {
        DayOfWeek.FRIDAY -> confirmed(
            ShiftCode.M1, 7, 30, 10, 5,
            "Live Friday Open to take Teamwork row, 2026-09-04",
        )
        DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> confirmed(
            ShiftCode.M1, 8, 0, 10, 35,
            "Live weekend Open to take Teamwork rows, 2026-09-05/06",
        )
        else -> provisional(ShiftCode.M1, "No live weekday M1 template observed")
    }

    private fun confirmed(
        code: ShiftCode,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        evidence: String,
    ) = ShiftTemplate(
        code = code,
        start = LocalTime.of(startHour, startMinute),
        end = LocalTime.of(endHour, endMinute),
        provenance = TemplateProvenance.LIVE_CONFIRMED,
        evidence = evidence,
    )

    private fun provisional(code: ShiftCode, evidence: String) = ShiftTemplate(
        code = code,
        start = null,
        end = null,
        provenance = TemplateProvenance.PROVISIONAL,
        evidence = evidence,
    )
}
