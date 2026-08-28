package com.cyclone.teamworksniper

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.cyclone.teamworksniper.data.*
import com.cyclone.teamworksniper.runtime.*
import com.cyclone.teamworksniper.ui.SniperScreen

class MainActivity:ComponentActivity(){private lateinit var rules:RuleStore;private lateinit var settings:SettingsStore;private lateinit var activity:ActivityLogStore;private var state by mutableStateOf(UiState(SniperSettings(),PermissionState(false,false),emptyList(),emptyList()));override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);rules=RuleStore(this);settings=SettingsStore(this);activity=ActivityLogStore(this);refresh();setContent{SniperScreen(state,{settings.save(it);refresh()},{rules.save(it);refresh()},{startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))},{startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))},::evaluateNow)}};override fun onResume(){super.onResume();if(::rules.isInitialized)refresh()};private fun refresh(){state=UiState(settings.load(),PermissionChecker.read(this),rules.load(),activity.load())};private fun evaluateNow(){val elapsed=SystemClock.elapsedRealtime();val launch=TeamworkLauncher.open(this);SniperCoordinator.submit(TriggerEvent(TriggerSource.MANUAL,System.currentTimeMillis(),elapsed,launchOutcome=launch))}}
data class UiState(val settings:SniperSettings,val permissions:PermissionState,val rules:List<ShiftRule>,val activity:List<ActivityEntry>)
