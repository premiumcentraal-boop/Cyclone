package com.cyclone.mobile.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskAppTrailTest {
    @Test fun theTrailKeepsDistinctAppsInOrderWithTheCurrentOneLast() {
        val id = "mission-trail-" + System.nanoTime()
        TaskAppTrail.record(id, "com.google.android.calendar")
        TaskAppTrail.record(id, "com.whatsapp")
        TaskAppTrail.record(id, "com.whatsapp")
        assertEquals(listOf("com.google.android.calendar", "com.whatsapp"), TaskAppTrail.of(id))
        TaskAppTrail.record(id, "com.google.android.calendar")
        assertEquals(listOf("com.whatsapp", "com.google.android.calendar"), TaskAppTrail.of(id))
    }

    @Test fun cycloneItselfAndBlanksAreNotApps() {
        val id = "mission-trail-" + System.nanoTime()
        TaskAppTrail.record(id, "com.cyclone.mobile")
        TaskAppTrail.record(id, "")
        TaskAppTrail.record(id, null)
        assertEquals(emptyList<String>(), TaskAppTrail.of(id))
    }

    @Test fun theTrailKeepsTheLastTwelveAppsInVisitOrder() {
        val id = "mission-trail-" + System.nanoTime()
        val apps = (1..14).map { "app.n$it" }
        apps.forEach { TaskAppTrail.record(id, it) }
        assertEquals(apps.takeLast(TaskAppTrail.LIMIT), TaskAppTrail.of(id))
        assertEquals(12, TaskAppTrail.LIMIT)
        // A revisited app moves to the end: it is the one Cyclone is in now.
        TaskAppTrail.record(id, "app.n5")
        assertEquals("app.n5", TaskAppTrail.of(id).last())
    }
}
