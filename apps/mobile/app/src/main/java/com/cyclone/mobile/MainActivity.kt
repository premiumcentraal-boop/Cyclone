package com.cyclone.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.applearner.PageAwarenessRuntime
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.brain.BrainChatRuntime
import com.cyclone.mobile.brain.CycloneBrainRuntime
import com.cyclone.mobile.ui.CycloneMobileV27App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeCyclone()
        migrateV28ModelDefault()
        setContent {
            // V2.8 keeps the V2.7 five-tab product shell; the major upgrade is the runtime underneath.
            CycloneMobileV27App()
        }
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
        BridgeClient.start(this)
    }

    /**
     * V2.8 makes GPT-5.6 Luna Max the built-in default. Existing users who deliberately selected a
     * non-legacy model keep their choice; clean installs and the old DeepSeek default migrate once.
     */
    private fun migrateV28ModelDefault() {
        val prefs = getSharedPreferences("cyclone_ai", MODE_PRIVATE)
        if (prefs.getBoolean("v28_model_default_migrated", false)) return
        val selected = prefs.getString("openrouter_model", null)
        val shouldMigrate = selected.isNullOrBlank() || selected == "deepseek/deepseek-v4-flash-0731"
        prefs.edit().apply {
            if (shouldMigrate) putString("openrouter_model", OpenRouterModelPresets.DEFAULT.id)
            putBoolean("v28_model_default_migrated", true)
        }.apply()
    }
}
