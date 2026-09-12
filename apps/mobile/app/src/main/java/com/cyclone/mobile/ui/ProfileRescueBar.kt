package com.cyclone.mobile.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.runtime.workspaces.ProfileSetupRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Available before setup, profile inventory, Accessibility or PC pairing. */
@Composable
fun ProfileRescueBar() {
    if (ProfileSetupRuntime.currentUserId() == 0) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Separate phone profile")
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        error = withContext(Dispatchers.IO) {
                            runCatching { ProfileSetupRuntime.openProfile(context, null) }
                                .exceptionOrNull()?.let { "Root hasn't allowed the return switch. Android's profile switcher is available below." }
                        }
                        busy = false
                    }
                }) { Text(if (busy) "Returning…" else "Return to Profile A") }
            }
            if (error != null) {
                Text(error!!, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent("android.settings.USER_SETTINGS")) }
                        .onFailure { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                }) { Text("Open Android profile switcher") }
            }
        }
    }
}
