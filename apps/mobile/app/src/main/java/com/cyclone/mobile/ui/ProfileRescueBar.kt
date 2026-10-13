package com.cyclone.mobile.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore
import com.cyclone.mobile.runtime.workspaces.ProfileSetupRuntime
import com.cyclone.mobile.ui.v32.ProfileAvatar
import com.cyclone.mobile.ui.v32.ProfileButton
import com.cyclone.mobile.ui.v32.ProfileButtonStyle
import com.cyclone.mobile.ui.v32.ask.AskGlass
import com.cyclone.mobile.ui.v32.ask.GlassTier
import com.cyclone.mobile.ui.v32.ask.askGlass
import com.kyant.capsule.ContinuousCapsule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The way back, always in reach in any profile but Main: one glass capsule with the profile you're in and Back to
 * Main. Available before setup, profile inventory, Accessibility or PC pairing.
 */
@Composable
fun ProfileRescueBar() {
    val user = ProfileSetupRuntime.currentUserId()
    if (user == 0) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val record = remember(user) { runCatching { ProfileRegistryStore.records(context).firstOrNull { it.androidUserId == user } }.getOrNull() }
    val label = record?.label ?: "this profile"
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .askGlass(28.dp, GlassTier.CHROME, 0.2f, ContinuousCapsule).clip(ContinuousCapsule)
            .padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProfileAvatar(label, record?.emoji, record?.color, size = 34.dp)
            Text("You're in $label", Modifier.weight(1f), color = AskGlass.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            ProfileButton(if (busy) "Going back…" else "Back to Main", {
                busy = true
                scope.launch {
                    error = withContext(Dispatchers.IO) {
                        runCatching { ProfileSetupRuntime.openProfile(context, null) }
                            .exceptionOrNull()?.let { "Root didn't allow the switch. Use Android's switcher." }
                    }
                    busy = false
                }
            }, style = ProfileButtonStyle.PRIMARY, compact = true, enabled = !busy)
        }
        if (error != null) {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(error!!, Modifier.weight(1f), color = AskGlass.Muted, fontSize = 13.sp, maxLines = 2)
                ProfileButton("Open", {
                    runCatching { context.startActivity(Intent("android.settings.USER_SETTINGS")) }
                        .onFailure { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                }, style = ProfileButtonStyle.QUIET, compact = true)
            }
        }
    }
}
