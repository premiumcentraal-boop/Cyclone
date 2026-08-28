package com.cyclone.teamworksniper.teamwork

import com.cyclone.teamworksniper.data.ShiftCode
import com.cyclone.teamworksniper.ui.overlay.TemplateProvenance
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShiftTemplateProviderTest {
    private val provider = ShiftTemplateProvider()

    @Test fun liveConfirmedTemplatesMatchPhysicalEvidence() {
        val friday = provider.forDate(LocalDate.of(2026, 9, 4)).associateBy { it.code }
        assertEquals(LocalTime.of(7, 30), friday.getValue(ShiftCode.M1).start)
        assertEquals(LocalTime.of(10, 5), friday.getValue(ShiftCode.M1).end)
        assertEquals(LocalTime.of(14, 10), friday.getValue(ShiftCode.S1).start)
        assertEquals(LocalTime.of(16, 55), friday.getValue(ShiftCode.S2).start)
        assertEquals(LocalTime.of(19, 40), friday.getValue(ShiftCode.S3).start)
        assertEquals(TemplateProvenance.LIVE_CONFIRMED, friday.getValue(ShiftCode.S3).provenance)
    }

    @Test fun unknownTemplatesStayProvisional() {
        val tuesday = provider.forDate(LocalDate.of(2026, 9, 1)).associateBy { it.code }
        assertNull(tuesday.getValue(ShiftCode.M1).start)
        assertNull(tuesday.getValue(ShiftCode.M2).start)
        assertEquals(TemplateProvenance.PROVISIONAL, tuesday.getValue(ShiftCode.M2).provenance)
    }
}
