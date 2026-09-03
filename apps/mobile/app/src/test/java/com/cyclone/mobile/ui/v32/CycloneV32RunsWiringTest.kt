package com.cyclone.mobile.ui.v32

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CycloneV32RunsWiringTest {
    private fun source(vararg candidates: String): String = candidates.map(::File).firstOrNull(File::isFile)?.readText()
        ?: error("Could not find production source: ${candidates.joinToString()}")

    @Test fun productionMainActivityStillUsesV32App() {
        val main = source(
            "src/main/java/com/cyclone/mobile/MainActivity.kt",
            "app/src/main/java/com/cyclone/mobile/MainActivity.kt",
            "apps/mobile/app/src/main/java/com/cyclone/mobile/MainActivity.kt",
        )
        assertTrue(main.contains("CycloneMobileV32App"))
    }

    @Test fun productionBrainHasKnowledgeRunsSwitchAndDownloadLog() {
        val featurePages = source(
            "src/main/java/com/cyclone/mobile/ui/v32/CycloneV32FeaturePages.kt",
            "app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV32FeaturePages.kt",
            "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV32FeaturePages.kt",
        )
        val runs = source(
            "src/main/java/com/cyclone/mobile/ui/v32/CycloneV32Runs.kt",
            "app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV32Runs.kt",
            "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV32Runs.kt",
        )
        val privacy = source(
            "src/main/java/com/cyclone/mobile/ai/AgentRunPrivacy.kt",
            "app/src/main/java/com/cyclone/mobile/ai/AgentRunPrivacy.kt",
            "apps/mobile/app/src/main/java/com/cyclone/mobile/ai/AgentRunPrivacy.kt",
        )
        val overlay = source(
            "src/main/java/com/cyclone/mobile/ui/overlay/OverlayChromeRuntime.kt",
            "app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayChromeRuntime.kt",
            "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayChromeRuntime.kt",
        )
        assertTrue(featurePages.contains("V32RunsPage"))
        assertTrue(featurePages.contains("Knowledge"))
        assertTrue(featurePages.contains("Runs"))
        assertTrue(runs.contains("Download log"))
        assertTrue(runs.contains("AgentRunLogExporter"))
        assertTrue(privacy.contains("screenshotsIncluded"))
        assertTrue(privacy.contains("hiddenChainOfThoughtIncluded"))
        assertTrue(overlay.contains("AgentRunEventBus.subscribe"))
        assertTrue(overlay.contains("AgentActivityStreamRuntime.message"))
    }
}
