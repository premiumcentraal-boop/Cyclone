package com.cyclone.mobile.runtime.background

import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionTargetResolverTest {
    @Test fun ordinaryRequestsStayForeground() {
        listOf("open Chrome", "look for cool sneakers in Chrome", "Open Instagram", "help me", "Chrome or Firefox")
            .forEach { assertEquals(ExecutionTarget.CurrentForeground, ExecutionTargetResolver.resolve(it)) }
    }
    @Test fun isolationRequiresExplicitIntent() {
        assertEquals(ExecutionTarget.BackgroundWorkspace, ExecutionTargetResolver.resolve("do this in the background in Chrome"))
        assertEquals(ExecutionTarget.BackgroundWorkspace, ExecutionTargetResolver.resolve("find shoes without taking over my screen"))
    }
    @Test fun profileIntentIsNeverForegroundFallback() {
        assertEquals(ExecutionTarget.Profile("Profile B"), ExecutionTargetResolver.resolve("Use Profile B and open Chrome"))
    }
}
