package com.cyclone.teamworksniper.teamwork

import com.cyclone.teamworksniper.data.ShiftCode
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamworkNativeShiftIdTest {
    private val target = "tech.picnic.workapp:id/SGeyJzIjoiMjAyNi0wOS0wNlQxNDo1NTowMFoiLCJlIjoiMjAyNi0wOS0wNlQxNzozMDowMFoiLCJpIjoiVVRDIiwiciI6WyJoYXNTa2lsbCgnUnVubmVyJykiXSwibCI6WyJESVNUUklCVVRJT05fVEFTS3xUcmlwIiwiVFJJUF9CQVNFX1NISUZUU3xTMiJdLCJ0IjpudWxsLCJwIjoiT1BFTiIsImEiOiJSRUdVTEFSIiwiYyI6IlNUMDFKMFhBVjA0SDNLVEFQRVBWTjRBWUdaNzYiLCJ4IjpudWxsfQ=="

    @Test
    fun decodesExactOpenS2FromNativeResourceId() {
        val shift = TeamworkNativeShiftId.decode(target, ZoneId.of("Europe/Berlin"))!!
        assertEquals(LocalDate.of(2026, 9, 6), shift.date)
        assertEquals(LocalTime.of(16, 55), shift.startTime)
        assertEquals(LocalTime.of(19, 30), shift.endTime)
        assertEquals(listOf(ShiftCode.S2), shift.codes)
        assertEquals("ST01J0XAV04H3KTAPEPVN4AYGZ76", shift.teamworkShiftId)
    }

    @Test
    fun rejectsNonNativeAndNonOpenPayloads() {
        assertNull(TeamworkNativeShiftId.decode("tech.picnic.workapp:id/shift-text"))
        val json = """{"s":"2026-09-06T14:55:00Z","e":"2026-09-06T17:30:00Z","l":["TRIP_BASE_SHIFTS|S2"],"p":"CLOSED","c":"closed"}"""
        val closed = TeamworkNativeShiftId.PREFIX + Base64.getEncoder().encodeToString(json.toByteArray())
        assertNull(TeamworkNativeShiftId.decode(closed))
    }

    @Test
    fun flattenPreservesOriginalAccessibilityChildIndexes() {
        val tree = SemanticNode(children = listOf(SemanticNode(text = "target", sourceChildIndex = 7)))
        assertTrue(tree.flatten().any { it.node.text == "target" && it.path == listOf(7) })
    }
}
