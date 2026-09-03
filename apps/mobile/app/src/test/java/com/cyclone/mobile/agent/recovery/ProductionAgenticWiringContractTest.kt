package com.cyclone.mobile.agent.recovery

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ProductionAgenticWiringContractTest {
    @Test
    fun productionOverlayOrLocalAgentReferencesIntegratedRuntimeWhenGateEnabled() {
        assumeTrue(System.getenv(AgenticRecoveryIntegrationContract.PRODUCTION_WIRING_GATE) == "1")
        val symbol = System.getenv(AgenticRecoveryIntegrationContract.RUNTIME_SYMBOL_OVERRIDE)
            ?.takeIf { it.isNotBlank() }
            ?: AgenticRecoveryIntegrationContract.PRODUCTION_BINDING_SYMBOL
        val candidates = listOf(
            "app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayChromeMachine.kt",
            "app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt",
            "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayChromeMachine.kt",
            "apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt",
        ).map(::File).filter(File::isFile)

        assertTrue("Could not find production overlay/local-agent source files", candidates.isNotEmpty())
        assertTrue(
            "Agentic recovery/runtime exists but production path does not reference '$symbol'. " +
                "Bind Agent 1's persistent runtime through AgenticRecoveryRuntimePort before integration.",
            candidates.any { it.readText().contains(symbol) },
        )
    }
}
