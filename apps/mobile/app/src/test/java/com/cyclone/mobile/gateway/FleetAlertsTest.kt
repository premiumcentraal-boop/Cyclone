package com.cyclone.mobile.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FleetAlertsTest {
    private fun args(vararg extra: Pair<String, Any>) = JSONObject()
        .put("alertId", "alr_abcdefgh12").put("level", "critical").put("title", "Work Phone is offline")
        .put("text", "Work Phone has not answered for 12 minutes.").apply { extra.forEach { (k, v) -> put(k, v) } }

    @Test
    fun aPlainAlertIsRead() {
        val alert = FleetAlerts.parse(args())
        assertEquals("critical", alert.level)
        assertEquals("Work Phone is offline", alert.title)
    }

    @Test
    fun anAlertCarriesNoActionOrSecret() {
        assertThrows(GatewayProtocolException::class.java) { FleetAlerts.parse(args("action" to "approve")) }
        assertThrows(GatewayProtocolException::class.java) { FleetAlerts.parse(args("text" to "password: hunter2")) }
        assertThrows(GatewayProtocolException::class.java) { FleetAlerts.parse(args("level" to "urgent")) }
        assertThrows(GatewayProtocolException::class.java) { FleetAlerts.parse(args("alertId" to "../x")) }
        assertThrows(GatewayProtocolException::class.java) { FleetAlerts.parse(args("title" to " ")) }
        assertThrows(GatewayProtocolException::class.java) { FleetAlerts.parse(args("text" to "x".repeat(401))) }
    }

    @Test
    fun theSameAlertReplacesItsNotification() {
        assertEquals(FleetAlerts.noticeId("alr_abcdefgh12"), FleetAlerts.noticeId("alr_abcdefgh12"))
        assertNotEquals(FleetAlerts.noticeId("alr_abcdefgh12"), FleetAlerts.noticeId("alr_zzzzzzzz34"))
    }

    @Test
    fun heatAndStorageAreNamedTheWayThePcReadsThem() {
        assertEquals("none", FleetHealthFacts.thermal(0))
        assertEquals("severe", FleetHealthFacts.thermal(3))
        assertEquals("shutdown", FleetHealthFacts.thermal(6))
        assertNull(FleetHealthFacts.thermal(7))
        assertNull(FleetHealthFacts.thermal(-1))
        assertEquals(31.5, FleetHealthFacts.temperatureC(315)!!, 0.001)
        assertNull(FleetHealthFacts.temperatureC(Int.MIN_VALUE))
        assertEquals(2048L, FleetHealthFacts.freeMb(2048L * 1024 * 1024))
        assertNull(FleetHealthFacts.freeMb(-1))
    }
}
