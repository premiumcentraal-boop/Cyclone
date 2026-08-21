package com.cyclone.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ai.TaskResultNotifierV292
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.applearner.PageAwarenessRuntime
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.brain.BrainChatRuntime
import com.cyclone.mobile.brain.CycloneBrainRuntime
import com.cyclone.mobile.guided.RoutineTeachingRuntime
import com.cyclone.mobile.infrastructure.v31.CycloneV31ProductIntegration
import com.cyclone.mobile.infrastructure.v31.CycloneV31Runtime
import com.cyclone.mobile.ui.CycloneMobileV292App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeCyclone()
        migrateModelDefault()
        migrateCanonicalLearning()
        TaskResultNotifierV292.ensureChannel(this)
        setContent { CycloneMobileV292App() }
    }

    override fun onResume() {
        super.onResume()
        initializeCyclone()
        CycloneV31Runtime.servicesOrNull()?.refreshHealth()
    }

    /**
     * Keep the proven Cyclone product runtimes as compatibility providers, then install V3.1 as the
     * single supervising policy/capability/memory/recovery layer around them. All calls are
     * idempotent, so Activity resume cannot create duplicate runtimes or phone action engines.
     */
    private fun initializeCyclone() {
        AutomationRuntime.initialize(this)
        AppLearnerRuntime.initialize(this)
        PageAwarenessRuntime.initialize(this)
        AgentTraceRuntime.initialize(this)
        CycloneBrainRuntime.initialize(this)
        AdaptiveBrainRuntime.initialize(this)
        BrainChatRuntime.initialize(this)
        RoutineTeachingRuntime.initialize(this)
        BridgeClient.start(this)

        val v31 = CycloneV31Runtime.initialize(this)
        CycloneV31ProductIntegration.install(this, v31)
        CycloneV31ProductIntegration.finalizeStartupWhenReady(v31)
    }

    private fun migrateModelDefault() {
        val prefs = getSharedPreferences("cyclone_ai", MODE_PRIVATE)
        if (prefs.getBoolean("model_default_migrated", false)) return
        val selected = prefs.getString("openrouter_model", null)
        if (selected.isNullOrBlank()) prefs.edit().putString("openrouter_model", OpenRouterModelPresets.DEFAULT.id).apply()
        prefs.edit().putBoolean("model_default_migrated", true).apply()
    }

    /** Keep one canonical post-mission consolidator and disable the old optional duplicate worker. */
    private fun migrateCanonicalLearning() {
        val prefs = getSharedPreferences("cyclone_ai", MODE_PRIVATE)
        if (prefs.getBoolean("v292_learning_migrated", false)) return
        prefs.edit()
            .putBoolean("cloud_brain_refinement", false)
            .putBoolean("v292_learning_migrated", true)
            .apply()
    }
}
