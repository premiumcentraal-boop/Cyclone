package com.cyclone.mobile.runtime.safe

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class SafeScheduleTest {
    private fun clock(hour: Int, minute: Int) = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 16, hour, minute, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    @Test fun beforeNightlyTargetsSameDay0330() {
        assertEquals(30 * 60 * 1000L, SafeSchedule.nightlyDelay(clock(3, 0)))
    }
    @Test fun afterNightlyTargetsNextDay0330() {
        assertEquals((23 * 60 + 30) * 60 * 1000L, SafeSchedule.nightlyDelay(clock(4, 0)))
    }
    @Test fun exactNightlyDoesNotImmediatelyRescheduleItself() {
        assertEquals(24 * 60 * 60 * 1000L, SafeSchedule.nightlyDelay(clock(3, 30)))
    }
}
