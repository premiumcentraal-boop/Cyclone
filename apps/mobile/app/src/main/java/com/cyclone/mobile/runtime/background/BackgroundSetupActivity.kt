package com.cyclone.mobile.runtime.background

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.cyclone.mobile.permissions.CyclonePermissionSetup
import com.cyclone.mobile.ui.v32.CycloneIntelligenceTheme
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku

class BackgroundSetupActivity : ComponentActivity() {
    private fun open(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { Toast.makeText(this, "This settings shortcut is unavailable. Use Settings on this phone.", Toast.LENGTH_LONG).show() }
    }
    private fun web(url: String) = open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CycloneIntelligenceTheme {
            var status by remember { mutableStateOf(BackgroundSetup.read(this)) }
            LaunchedEffect(Unit) { while (true) { status = BackgroundSetup.read(this@BackgroundSetupActivity); delay(800) } }
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()
                .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = { finish() }) { Text("Back") }
                Text("Background tasks", style = MaterialTheme.typography.headlineMedium)
                Text("Keep using your phone while Cyclone works in a separate app screen.")
                SetupCard("Android 15+", status.android) {
                    Text(if (status.android) "This Android version supports separate task screens." else "This phone needs an Android update before background tasks can run.")
                    TextButton(onClick = { web("https://developer.android.com/reference/android/hardware/display/DisplayManager#VIRTUAL_DISPLAY_FLAG_OWN_FOCUS") }) { Text("Why Android 15?") }
                }
                SetupCard("Shizuku", status.running && status.authorized) {
                    Text("Shizuku gives Cyclone permission to create a separate app screen without rooting your phone.")
                    Text(when { !status.installed -> "Not installed"; !status.running -> "Not running — pairing or Start needed"; !status.authorized -> "Running — Cyclone needs permission"; else -> "Running and authorized" })
                    Row {
                        TextButton(onClick = { web("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api") }) { Text("Google Play") }
                        TextButton(onClick = { web("https://github.com/RikkaApps/Shizuku/releases") }) { Text("Official GitHub") }
                    }
                    OutlinedButton(onClick = {
                        packageManager.getLaunchIntentForPackage(BackgroundSetup.SHIZUKU_PACKAGE)?.let(::open)
                            ?: web("https://shizuku.rikka.app/download/")
                    }) { Text("Open Shizuku") }
                    Text("1. Connect to Wi-Fi. In Settings → About phone, tap Build number seven times to enable Developer options.")
                    Text("2. In Developer options, enable USB debugging and Wireless debugging.")
                    Text("3. In Shizuku, tap Pairing. Open Wireless debugging → Pair device with pairing code, then enter that code in Shizuku's notification.")
                    Text("4. Return to Shizuku and tap Start. Repeat Start after restarting your phone.")
                    TextButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }) { Text("Open Developer options") }
                    TextButton(onClick = { web("https://shizuku.rikka.app/guide/setup/#start-via-wireless-debugging") }) { Text("Illustrated official guide") }
                    Button(enabled = status.running && !status.authorized, onClick = {
                        runCatching { Shizuku.requestPermission(902) }.onFailure { Toast.makeText(this@BackgroundSetupActivity, "Open Shizuku and allow Cyclone, then recheck.", Toast.LENGTH_LONG).show() }
                    }) { Text("Allow Cyclone") }
                    TextButton(onClick = { status = BackgroundSetup.read(this@BackgroundSetupActivity) }) { Text("Check Shizuku") }
                }
                SetupCard("Accessibility", status.accessibility) {
                    Text("Enable Cyclone in Downloaded apps / Installed services. This also powers the task glass; no extra appear-on-top permission is needed.")
                    Button(onClick = { open(CyclonePermissionSetup.accessibilitySettings()) }) { Text("Enable Cyclone") }
                }
                SetupCard("Task notifications", status.notifications) {
                    Text("Keep View progress and Stop available when you leave Cyclone.")
                    Button(onClick = { ActivityCompat.requestPermissions(this@BackgroundSetupActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 903)
                        if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) open(CyclonePermissionSetup.appDetails(this@BackgroundSetupActivity))
                    }) { Text("Allow notifications") }
                    TextButton(onClick = { open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }) { Text("Notification settings") }
                }
                SetupCard("Notification triggers · optional", CyclonePermissionSetup.notificationAccessEnabled(this@BackgroundSetupActivity)) {
                    Text("Only needed for notification-triggered routines, not background Ask tasks.")
                    TextButton(onClick = { open(CyclonePermissionSetup.notificationAccessSettings()) }) { Text("Manage notification access") }
                }
                SetupCard("Your main screen", status.humanScreen) {
                    Text("Leave Cyclone and the target app before work starts. Start task takes you home, then checks this automatically. You can open Instagram afterwards.")
                    Button(enabled = status.setupFailure == null, onClick = {
                        open(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                        Handler(Looper.getMainLooper()).postDelayed({
                            val checked = BackgroundSetup.read(applicationContext)
                            Toast.makeText(applicationContext, if (checked.ready) "All checks green. Open Ask Cyclone to start." else checked.failure, Toast.LENGTH_LONG).show()
                            if (checked.ready) com.cyclone.mobile.ui.overlay.OverlayChromeRuntime.resetIdle()
                        }, 1200)
                    }) { Text("I've closed Cyclone · recheck on Home") }
                }
                Text(status.setupFailure ?: if (status.humanScreen) "All checks green" else "Access is ready. The main-screen check runs after you go Home.",
                    style = MaterialTheme.typography.titleSmall)
            }
        } }
    }
}

@Composable
private fun SetupCard(title: String, ready: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text((if (ready) "✓  " else "○  ") + title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
