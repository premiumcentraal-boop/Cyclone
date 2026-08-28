package com.cyclone.teamworksniper.teamwork

import com.cyclone.teamworksniper.ui.overlay.OverlayRect
import com.cyclone.teamworksniper.ui.overlay.ScheduleSemanticNode
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamworkScheduleOverlayMapperTest {
    @Test fun onlyMapsTeamworkRecognizedSchedule() {
        assertNull(TeamworkScheduleOverlayMapper.map(schedule(packageName = "another.app")))
        assertNull(TeamworkScheduleOverlayMapper.map(schedule(includeAgenda = false)))
        val mapped = TeamworkScheduleOverlayMapper.map(schedule())!!
        assertEquals(LocalDate.of(2026, 8, 31), mapped.weekStart)
        assertEquals(LocalDate.of(2026, 9, 1), mapped.days.single().date)
        assertTrue(mapped.days.single().isEmptyDay)
    }

    @Test fun hierarchyChangesRegeneratePresentationGeometryOnly() {
        val first = TeamworkScheduleOverlayMapper.map(schedule(dayTop = 1156))!!.days.single()
        val moved = TeamworkScheduleOverlayMapper.map(schedule(dayTop = 700))!!.days.single()
        assertEquals(first.date, moved.date)
        assertNotEquals(first.dayBounds, moved.dayBounds)
    }

    private fun schedule(
        packageName: String = "tech.picnic.workapp",
        includeAgenda: Boolean = true,
        dayTop: Int = 1156,
    ): ScheduleSemanticNode {
        val day = ScheduleSemanticNode(
            bounds = OverlayRect(0, dayTop, 1080, dayTop + 226),
            children = listOf(
                ScheduleSemanticNode(text = "1", bounds = OverlayRect(42, dayTop + 53, 137, dayTop + 122)),
                ScheduleSemanticNode(text = "TUE", bounds = OverlayRect(42, dayTop + 131, 137, dayTop + 173)),
                ScheduleSemanticNode(text = "No shift", bounds = OverlayRect(199, dayTop + 76, 1006, dayTop + 134)),
            ),
        )
        val agenda = ScheduleSemanticNode(
            resourceId = if (includeAgenda) "agenda-list" else "not-agenda",
            bounds = OverlayRect(0, 405, 1080, 2190),
            children = listOf(ScheduleSemanticNode(text = "Week 36"), day),
        )
        return ScheduleSemanticNode(
            packageName = packageName,
            children = listOf(
                ScheduleSemanticNode(
                    resourceId = "tech.picnic.workapp:id/calendar-week-selector-2026-08-31-2026-09-06",
                ),
                agenda,
            ),
        )
    }
}
