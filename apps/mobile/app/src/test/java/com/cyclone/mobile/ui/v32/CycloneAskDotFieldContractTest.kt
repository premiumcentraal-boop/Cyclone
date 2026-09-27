package com.cyclone.mobile.ui.v32

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneAskDotFieldContractTest {
    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/$relative"),
            File("app/src/main/java/$relative"),
            File("apps/mobile/app/src/main/java/$relative"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate source file $relative from ${File(".").absolutePath}")
    }

    @Test fun askCanvasUsesFullScreenBreathingDotFieldBehindComposer() {
        val page = source("com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt")
        assertTrue(page.contains("AskCycloneDotField(Modifier.matchParentSize())"))
        assertTrue(page.contains("durationMillis = 20_000"))
        assertTrue(page.contains("val pulseScale = 0.78f + localWave * 0.42f"))
        assertTrue(page.contains("drawCircle("))
        assertTrue(page.contains("padding(bottom = 12.dp)"))
    }

    @Test fun emptyStateKeepsGeminiLikeHierarchyWithCycloneIdentity() {
        val page = source("com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt")
        assertTrue(page.contains("\"Good morning\""))
        assertTrue(page.contains("\"Good afternoon\""))
        assertTrue(page.contains("\"Good evening\""))
        assertTrue(page.contains("\"What can I do for you?\""))
        assertTrue(page.contains("AskCycloneOrb()"))
    }

    @Test fun existingAppNavigationStillOwnsAskCycloneDestination() {
        val app = source("com/cyclone/mobile/ui/v32/CycloneV32App.kt")
        assertTrue(app.contains("if (!settingsOpen) CycloneV32BottomBar(destination)"))
        assertTrue(app.contains("V32Destination.AI -> V39AiChatPage(context, refreshTick)"))
    }
}
