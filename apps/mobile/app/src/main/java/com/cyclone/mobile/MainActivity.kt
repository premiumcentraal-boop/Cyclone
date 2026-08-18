package com.cyclone.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.ui.CycloneMobileV24App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AutomationRuntime.initialize(this)
        BridgeClient.start(this)
        setContent {
            CycloneMobileV24App()
        }
    }

    override fun onResume() {
        super.onResume()
        AutomationRuntime.initialize(this)
        BridgeClient.start(this)
    }
}
