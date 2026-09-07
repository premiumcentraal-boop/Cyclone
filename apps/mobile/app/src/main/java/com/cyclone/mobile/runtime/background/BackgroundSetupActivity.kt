package com.cyclone.mobile.runtime.background

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.cyclone.mobile.permissions.CyclonePermissionSetup
import com.cyclone.mobile.ui.v32.CycloneIntelligenceTheme
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku

private enum class InstallPhase { READY, CONFIRM, INSTALLING, INSTALLED, BLOCKED }
private enum class InstallStep { INSTALL_SHIZUKU, START_SHIZUKU, AUTHORIZE_SHIZUKU, ACCESSIBILITY, NOTIFICATIONS }

class BackgroundSetupActivity : ComponentActivity() {
    private fun open(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "Android could not open that approval screen. Tap Continue installation to retry.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openShizukuInstall() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${BackgroundSetup.SHIZUKU_PACKAGE}"))
        runCatching { startActivity(market) }.onFailure {
            open(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${BackgroundSetup.SHIZUKU_PACKAGE}")))
        }
    }

    private fun launchInstallStep(step: InstallStep) {
        when (step) {
            InstallStep.INSTALL_SHIZUKU -> openShizukuInstall()
            InstallStep.START_SHIZUKU -> {
                packageManager.getLaunchIntentForPackage(BackgroundSetup.SHIZUKU_PACKAGE)?.let(::open)
                    ?: openShizukuInstall()
            }
            InstallStep.AUTHORIZE_SHIZUKU -> {
                runCatching { Shizuku.requestPermission(REQUEST_SHIZUKU_PERMISSION) }.onFailure {
                    packageManager.getLaunchIntentForPackage(BackgroundSetup.SHIZUKU_PACKAGE)?.let(::open)
                }
            }
            InstallStep.ACCESSIBILITY -> open(CyclonePermissionSetup.accessibilitySettings())
            InstallStep.NOTIFICATIONS -> {
                val permissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (permissionGranted || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    open(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                    )
                } else {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        REQUEST_NOTIFICATIONS,
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CycloneIntelligenceTheme {
                var status by remember { mutableStateOf(BackgroundSetup.read(this@BackgroundSetupActivity)) }
                var phase by rememberSaveable {
                    mutableStateOf(if (status.setupFailure == null) InstallPhase.INSTALLED else InstallPhase.READY)
                }
                var retryNonce by remember { mutableIntStateOf(0) }
                var lastLaunchKey by remember { mutableStateOf<Pair<InstallStep, Int>?>(null) }

                LaunchedEffect(Unit) {
                    while (true) {
                        status = BackgroundSetup.read(this@BackgroundSetupActivity)
                        delay(650)
                    }
                }

                val nextStep = nextInstallStep(status)
                LaunchedEffect(phase, nextStep, retryNonce) {
                    if (phase != InstallPhase.INSTALLING) return@LaunchedEffect
                    if (!status.android) {
                        phase = InstallPhase.BLOCKED
                        return@LaunchedEffect
                    }
                    if (nextStep == null) {
                        phase = InstallPhase.INSTALLED
                        return@LaunchedEffect
                    }
                    val launchKey = nextStep to retryNonce
                    if (lastLaunchKey != launchKey) {
                        lastLaunchKey = launchKey
                        launchInstallStep(nextStep)
                    }
                }

                InstallerScreen(
                    phase = phase,
                    status = status,
                    step = nextStep,
                    onBack = { finish() },
                    onInstall = { phase = InstallPhase.CONFIRM },
                    onCancelConfirmation = { phase = InstallPhase.READY },
                    onConfirm = {
                        lastLaunchKey = null
                        retryNonce = 0
                        phase = InstallPhase.INSTALLING
                    },
                    onRetry = { retryNonce++ },
                    onDone = { finish() },
                )
            }
        }
    }

    companion object {
        private const val REQUEST_SHIZUKU_PERMISSION = 902
        private const val REQUEST_NOTIFICATIONS = 903
    }
}

private fun nextInstallStep(status: BackgroundReadiness): InstallStep? = when {
    !status.installed -> InstallStep.INSTALL_SHIZUKU
    !status.running -> InstallStep.START_SHIZUKU
    !status.authorized -> InstallStep.AUTHORIZE_SHIZUKU
    !status.accessibility -> InstallStep.ACCESSIBILITY
    !status.notifications -> InstallStep.NOTIFICATIONS
    else -> null
}

private fun completedSetupSteps(status: BackgroundReadiness): Int = listOf(
    status.installed,
    status.running,
    status.authorized,
    status.accessibility,
    status.notifications,
).count { it }

private fun installStepTitle(step: InstallStep?): String = when (step) {
    InstallStep.INSTALL_SHIZUKU -> "Installing secure workspace"
    InstallStep.START_SHIZUKU -> "Starting secure workspace"
    InstallStep.AUTHORIZE_SHIZUKU -> "Connecting Cyclone"
    InstallStep.ACCESSIBILITY -> "Enabling phone control"
    InstallStep.NOTIFICATIONS -> "Enabling task notifications"
    null -> "Finishing installation"
}

private fun installStepHint(step: InstallStep?): String = when (step) {
    InstallStep.INSTALL_SHIZUKU -> "Google Play may open once. Install Shizuku, then return to Cyclone."
    InstallStep.START_SHIZUKU -> "Shizuku may open once for Android's required start or pairing approval. Return when it is running."
    InstallStep.AUTHORIZE_SHIZUKU -> "Approve Cyclone when the Shizuku permission prompt appears."
    InstallStep.ACCESSIBILITY -> "Android will open Accessibility. Turn on Cyclone, then return."
    InstallStep.NOTIFICATIONS -> "Allow Cyclone task notifications when Android asks."
    null -> "Cyclone is checking the installation."
}

@Composable
private fun InstallerScreen(
    phase: InstallPhase,
    status: BackgroundReadiness,
    step: InstallStep?,
    onBack: () -> Unit,
    onInstall: () -> Unit,
    onCancelConfirmation: () -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        if (phase == InstallPhase.READY || phase == InstallPhase.INSTALLED) {
            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart)) { Text("Back") }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().align(Alignment.Center),
            shape = RoundedCornerShape(32.dp),
            tonalElevation = 1.dp,
        ) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (phase) {
                    InstallPhase.READY -> {
                        StatusOrb("↓")
                        Text("Background tasks", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "One install gives Cyclone a separate app screen so it can work while you keep using your phone.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) { Text("Install") }
                        Text(
                            "No root. Cyclone handles the setup flow and only opens Android or Shizuku when their approval is required.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    InstallPhase.CONFIRM -> {
                        StatusOrb("?")
                        Text("Install background tasks?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Cyclone will set up the secure workspace and open any Android-owned approvals in order. You can stop before installing.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = onCancelConfirmation, modifier = Modifier.weight(1f)) { Text("Cancel") }
                            Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text("Install") }
                        }
                    }

                    InstallPhase.INSTALLING -> {
                        CircularProgressIndicator(modifier = Modifier.size(54.dp))
                        Text("Installing…", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            installStepTitle(step),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        LinearProgressIndicator(
                            progress = { completedSetupSteps(status) / 5f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            installStepHint(step),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Installation continues automatically when you return.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Continue installation") }
                    }

                    InstallPhase.INSTALLED -> {
                        StatusOrb("✓")
                        Text("Installed", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Background tasks are ready. Cyclone can prepare work on its separate screen while your main screen stays yours.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                        Text(
                            "The main-screen safety check now happens automatically when a task starts.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    InstallPhase.BLOCKED -> {
                        StatusOrb("!")
                        Text("Android update required", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Background tasks need Android 15 or later on this phone.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusOrb(label: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Box(Modifier.size(70.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
