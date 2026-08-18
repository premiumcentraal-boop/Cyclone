package com.cyclone.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.ui.CycloneMobileApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AutomationRuntime.initialize(this)
        BridgeClient.start(this)
        setContent {
            CycloneMobileApp()
        }
    }

    override fun onResume() {
        super.onResume()
        AutomationRuntime.initialize(this)
        BridgeClient.start(this)
    }
}
