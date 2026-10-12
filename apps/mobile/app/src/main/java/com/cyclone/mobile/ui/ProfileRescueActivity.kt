package com.cyclone.mobile.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.rounded.Home
import com.cyclone.mobile.MainActivity
import com.cyclone.mobile.runtime.workspaces.ProfileSetupRuntime
import com.cyclone.mobile.runtime.workspaces.ProfileBootstrapRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Deliberately does not initialize agents, learning, pairing or accessibility-dependent UI. */
class ProfileRescueActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            com.cyclone.mobile.ui.v32.CycloneV32Theme(glass = true) {
                val scope = rememberCoroutineScope()
                var busy by remember { mutableStateOf(false) }
                val setupState by ProfileSetupRuntime.state.collectAsState()
                var debug by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf(if (intent.getBooleanExtra("repair_failed", false)) "Repair couldn't finish. You can go back to Main safely." else "This profile hasn't got Cyclone's setup yet.") }
                fun perform(repair: Boolean) {
                    busy = true
                    scope.launch {
                        message = withContext(Dispatchers.IO) {
                            runCatching {
                                if (repair) ProfileBootstrapRuntime.requestRepair(this@ProfileRescueActivity)
                                else ProfileSetupRuntime.openProfile(this@ProfileRescueActivity, null)
                                if (repair) "Repair started. Cyclone reopens when it's ready." else "Going back to Main…"
                            }.getOrElse { "That didn't work. If root isn't available here, use Android's switcher." }
                        }
                        busy = false
                    }
                }
                com.cyclone.mobile.ui.v32.ProfileCalmPage {
                    Column(
                        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp, androidx.compose.ui.Alignment.CenterVertically),
                    ) {
                        com.cyclone.mobile.ui.v32.ProfileAvatar("Main", size = 96.dp, icon = androidx.compose.material.icons.Icons.Rounded.Home)
                        Text("Get back to Main", color = com.cyclone.mobile.ui.v32.ask.AskGlass.Ink, fontSize = 30.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(message, color = com.cyclone.mobile.ui.v32.ask.AskGlass.Muted, fontSize = 16.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        com.cyclone.mobile.ui.v32.ProfileButton("Back to Main", { perform(false) }, Modifier.fillMaxWidth(),
                            style = com.cyclone.mobile.ui.v32.ProfileButtonStyle.PRIMARY, enabled = !busy)
                        com.cyclone.mobile.ui.v32.ProfileButton("Repair from Main", { perform(true) }, Modifier.fillMaxWidth(), enabled = !busy)
                        com.cyclone.mobile.ui.v32.ProfileButton("Open Android's switcher", {
                            runCatching { startActivity(Intent("android.settings.USER_SETTINGS")) }
                                .onFailure { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                        }, Modifier.fillMaxWidth(), style = com.cyclone.mobile.ui.v32.ProfileButtonStyle.QUIET)
                        com.cyclone.mobile.ui.v32.ProfileButton("Stay in this profile", {
                            startActivity(Intent(this@ProfileRescueActivity, MainActivity::class.java).putExtra("skip_profile_rescue", true))
                            finish()
                        }, Modifier.fillMaxWidth(), style = com.cyclone.mobile.ui.v32.ProfileButtonStyle.QUIET)
                        // Plan 57 W8: the debug file is reachable even here, where nothing else of Cyclone runs.
                        com.cyclone.mobile.ui.v32.ProfileButton("Debug report", { debug = true },
                            style = com.cyclone.mobile.ui.v32.ProfileButtonStyle.QUIET, compact = true)
                    }
                }
                if (debug) com.cyclone.mobile.ui.v32.ProfileSheet({ debug = false }, "Debug report") { ProfileDebugButtons(setupState.issue) }
            }
        }
    }
}
