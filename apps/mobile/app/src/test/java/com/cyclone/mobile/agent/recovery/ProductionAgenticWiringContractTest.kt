package com.cyclone.mobile.agent.recovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProductionAgenticWiringContractTest {
    private fun source(vararg candidates: String): String {
        val file = candidates.map(::File).firstOrNull(File::isFile)
            ?: error("Could not find production source: ${candidates.joinToString()}")
        return file.readText()
    }

    @Test
    fun productionOverlayUsesPersistentNativeToolAgent() {
        val adaptive = source(
            "src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt",
            "app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt",
            "apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt",
        )
        val overlay = source(
            "src/main/java/com/cyclone/mobile/ui/overlay/OverlayChromeRuntime.kt",
            "app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayChromeRuntime.kt",
            "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayChromeRuntime.kt",
        )
        assertTrue(adaptive.contains("PersistentToolAgentRuntime"))
        assertTrue(adaptive.contains("OpenRouterToolCallingProvider"))
        assertTrue(adaptive.contains("CycloneAndroidToolRuntime"))
        assertTrue(adaptive.contains("AgentRunRuntime"))
        assertTrue(overlay.contains("OpenRouterAdaptiveAgent"))
        assertTrue(overlay.contains("resumeSuspendedTask"))
        assertFalse(adaptive.contains("import com.cyclone.mobile.ai.PageAgentProtocol"))
        assertFalse(adaptive.contains("PageAgentProtocol."))
        assertFalse(adaptive.contains("requestPageDecision"))
        assertFalse(adaptive.contains("while (providerRequests < config.maxDecisions)"))
    }

    @Test
    fun nativeToolRuntimeConsumesCompoundVerifiedAndroidContract() {
        val runtime = source(
            "src/main/java/com/cyclone/mobile/agent/runtime/CycloneAndroidToolRuntime.kt",
            "app/src/main/java/com/cyclone/mobile/agent/runtime/CycloneAndroidToolRuntime.kt",
            "apps/mobile/app/src/main/java/com/cyclone/mobile/agent/runtime/CycloneAndroidToolRuntime.kt",
        )
        val compound = source(
            "src/main/java/com/cyclone/mobile/agent/tools/CycloneCompoundAgentTools.kt",
            "app/src/main/java/com/cyclone/mobile/agent/tools/CycloneCompoundAgentTools.kt",
            "apps/mobile/app/src/main/java/com/cyclone/mobile/agent/tools/CycloneCompoundAgentTools.kt",
        )
        assertTrue(runtime.contains("CycloneCompoundAgentTools"))
        assertTrue(runtime.contains("verifyCompletion"))
        assertTrue(compound.contains("CycloneAgentEnvironment"))
        assertTrue(compound.contains("envelope.verification.passed"))
        assertTrue(compound.contains("androidExecutionOk"))
        assertTrue(compound.contains("openApp(name"))
    }

    @Test
    fun providerUsesRealToolCallsAndRichToolResults() {
        val provider = source(
            "src/main/java/com/cyclone/mobile/agent/runtime/OpenRouterToolCallingProvider.kt",
            "app/src/main/java/com/cyclone/mobile/agent/runtime/OpenRouterToolCallingProvider.kt",
            "apps/mobile/app/src/main/java/com/cyclone/mobile/agent/runtime/OpenRouterToolCallingProvider.kt",
        )
        val runtime = source(
            "src/main/java/com/cyclone/mobile/agent/runtime/PersistentToolAgentRuntime.kt",
            "app/src/main/java/com/cyclone/mobile/agent/runtime/PersistentToolAgentRuntime.kt",
            "apps/mobile/app/src/main/java/com/cyclone/mobile/agent/runtime/PersistentToolAgentRuntime.kt",
        )
        assertTrue(provider.contains("tool_calls"))
        assertTrue(provider.contains("tool_call_id"))
        assertTrue(provider.contains("parallel_tool_calls"))
        assertTrue(provider.contains("tool_choice"))
        assertTrue(runtime.contains("AgentConversationEntry.ToolResult"))
        assertTrue(runtime.contains("COMPLETION_NOT_VERIFIED"))
        assertTrue(runtime.contains("verificationPassed"))
        assertFalse(runtime.contains("val verified = result.ok"))
    }
}
