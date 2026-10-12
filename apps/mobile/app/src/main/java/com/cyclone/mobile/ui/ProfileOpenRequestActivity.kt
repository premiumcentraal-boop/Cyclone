package com.cyclone.mobile.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.runtime.workspaces.ProfileApps
import com.cyclone.mobile.runtime.workspaces.ProfileOpenRequests
import com.cyclone.mobile.runtime.workspaces.ProfileSetupRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plan 57 (alpha.122, Cloak handoff CC7): Cyclone's own question when a connector asks to open a profile. Not exported;
 * only reachable from [ProfileOpenRequests]. The owner's tap on **Open** is the only thing that switches, through the
 * same staged switch as Profiles (way back armed, journaled).
 */
class ProfileOpenRequestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nonce = intent.getStringExtra(ProfileOpenRequests.EXTRA_NONCE).orEmpty()
        val request = ProfileOpenRequests.gate.peek(nonce)
        ProfileOpenRequests.dismissNotice(this)
        if (request == null) { finish(); return }
        setContent {
            com.cyclone.mobile.ui.v32.CycloneV32Theme {
                val scope = rememberCoroutineScope()
                var busy by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf<String?>(null) }
                fun open() {
                    busy = true
                    ProfileOpenRequests.gate.close(nonce)
                    scope.launch {
                        val problem = withContext(Dispatchers.IO) {
                            runCatching {
                                ProfileSetupRuntime.openProfile(this@ProfileOpenRequestActivity,
                                    if (request.profileId == ProfileApps.MAIN) null else request.profileId) { step -> message = step }
                            }.exceptionOrNull()?.let { it.message ?: "Cyclone couldn't open ${request.profileLabel}." }
                        }
                        if (problem == null) finish() else { message = problem; busy = false }
                    }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Open ${request.profileLabel}?", style = MaterialTheme.typography.headlineMedium)
                        Text("${request.connectorLabel} asks Cyclone to switch this phone to ${request.profileLabel}. " +
                            "Nothing changes unless you tap Open. Cyclone switches the usual way, with its way back armed.")
                        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(enabled = !busy, onClick = { open() }) { Text("Open") }
                            OutlinedButton(enabled = !busy, onClick = { ProfileOpenRequests.gate.close(nonce); finish() }) { Text("Not now") }
                        }
                    }
                }
            }
        }
    }
}
