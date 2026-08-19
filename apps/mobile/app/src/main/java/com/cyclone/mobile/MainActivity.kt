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
import com.cyclone.mobile.ui.CycloneMobileV292App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeCyclone()
        migrateModelDefault()
        migrateV292Learning()
        TaskResultNotifierV292.ensureChannel(this)
        setContent { CycloneMobileV292App() }
    }

    override fun onResume() {
        super.onResume()
        initializeCyclone()
    }

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
    }

    private fun migrateModelDefault() {
        val prefs = getSharedPreferences("cyclone_ai", MODE_PRIVATE)
        if (prefs.getBoolean("model_default_migrated", false)) return
        val selected = prefs.getString("openrouter_model", null)
        if (selected.isNullOrBlank()) prefs.edit().putString("openrouter_model", OpenRouterModelPresets.DEFAULT.id).apply()
        prefs.edit().putBoolean("model_default_migrated", true).apply()
    }

    /** 2.9.2 has one canonical post-mission consolidator, so disable the old optional duplicate worker. */
    private fun migrateV292Learning() {
        val prefs = getSharedPreferences("cyclone_ai", MODE_PRIVATE)
        if (prefs.getBoolean("v292_learning_migrated", false)) return
        prefs.edit()
            .putBoolean("cloud_brain_refinement", false)
            .putBoolean("v292_learning_migrated", true)
            .apply()
    }
}
