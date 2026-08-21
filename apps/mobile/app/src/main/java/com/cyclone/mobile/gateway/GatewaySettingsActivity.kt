package com.cyclone.mobile.gateway

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ui.CycloneTheme
import kotlinx.coroutines.delay

/** Polished in-app control center for Cyclone's USB-only PC/Codex bridge. */
class GatewaySettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GatewayRuntime.startPairingBootstrap(this)
        setContent {
            CycloneTheme {
                GatewayControlCenter(
                    context = this,
                    onClose = { finish() },
                )
            }
        }
    }
}

private enum class GatewayUiState(val label: String, val subtitle: String) {
    OFF("Gateway off", "Turn it on when you want to connect this phone to your PC."),
    WAITING("Waiting for PC", "Cyclone is ready. Connect the USB cable and start Cyclone Bridge on Windows."),
    CONNECTED("Connected", "Your PC can now use Cyclone's approved phone controls."),
    ATTENTION("Needs attention", "One part of the connection needs to be fixed before PC control is ready."),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GatewayControlCenter(
    context: Context,
    onClose: () -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    var technicalOpen by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) {
        while (true) {
            delay(750)
            refreshTick++
        }
    }

    val status = remember(refreshTick) { GatewayRuntime.status(context) }
    val enabled = status.optBoolean("gatewayEnabled")
    val accessibilityReady = status.optBoolean("accessibilityConnected")
    val session = status.optJSONObject("connectedSession")
    val connected = session?.optBoolean("connected") == true
    val socketReady = status.optBoolean("socketListening")
    val productionAuthority = status.optBoolean("productionActionAuthorityBound")
    val pairingCode = remember(refreshTick) { GatewayDesktopPairingManager.codeForUser() }
    val pairingExpiresAt = remember(refreshTick) { GatewayDesktopPairingManager.expiresAtForUser() }
    val clipboardEnabled = remember(refreshTick) { GatewayDesktopPreferences.clipboardEnabled(context) }
    val state = when (status.optString("gatewayState")) {
        "CONNECTED" -> GatewayUiState.CONNECTED
        "WAITING_FOR_PC" -> GatewayUiState.WAITING
        "ATTENTION_NEEDED" -> GatewayUiState.ATTENTION
        else -> GatewayUiState.OFF
    }
    val tokenReady = enabled && GatewayRuntime.tokenForUser(context)?.isNotBlank() == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PC Gateway", fontWeight = FontWeight.SemiBold)
                        Text("USB connection", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back to Cyclone")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                GatewayHero(state = state, enabled = enabled) { turnOn ->
                    runCatching {
                        if (turnOn) GatewayRuntime.enable(context) else GatewayRuntime.disable(context)
                    }.onFailure {
                        Toast.makeText(context, it.message ?: "Could not change Gateway state", Toast.LENGTH_LONG).show()
                    }
                    refreshTick++
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("Connection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        GatewayStatusLine(
                            icon = Icons.Rounded.Link,
                            title = "Gateway",
                            value = when {
                                !enabled -> "Off"
                                socketReady -> "Ready"
                                else -> "Starting"
                            },
                            ready = enabled && socketReady,
                        )
                        GatewayStatusLine(
                            icon = Icons.Rounded.PhoneAndroid,
                            title = "Phone control",
                            value = if (accessibilityReady) "Ready" else "Accessibility off",
                            ready = accessibilityReady,
                        )
                        GatewayStatusLine(
                            icon = Icons.Rounded.Computer,
                            title = "PC connection",
                            value = when {
                                connected -> "Connected"
                                enabled -> "Waiting"
                                else -> "Ready to pair"
                            },
                            ready = connected,
                        )
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Clipboard paste", fontWeight = FontWeight.Medium)
                                Text(
                                    "Opt in to PC → phone clipboard. Password, OTP and token-like values are blocked.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = clipboardEnabled,
                                onCheckedChange = {
                                    GatewayDesktopPreferences.setClipboardEnabled(context, it)
                                    refreshTick++
                                },
                            )
                        }
                    }
                }
            }

            if (pairingCode != null) {
                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("Desktop pairing code", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                pairingCode,
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            val seconds = (((pairingExpiresAt ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L)
                            Text(
                                "Enter this on your PC. Expires in about ${seconds}s. The four letters are only a confirmation challenge, not your real credential.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            if (!accessibilityReady) {
                item {
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Security, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Enable phone control", fontWeight = FontWeight.Bold)
                                    Text(
                                        "Cyclone Accessibility must be on before the PC can control apps.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            Button(
                                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Open Accessibility settings") }
                        }
                    }
                }
            }

            if (enabled) {
                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                                    Icon(
                                        Icons.Rounded.Lock,
                                        null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(9.dp).size(22.dp),
                                    )
                                }
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Legacy session token", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        if (tokenReady) "Kept for the existing PC/Codex bridge. Desktop fleet pairing does not require copying it." else "Creating a secure session token…",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            Button(
                                onClick = { copySessionToken(context) },
                                enabled = tokenReady,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Rounded.ContentCopy, null)
                                Spacer(Modifier.width(7.dp))
                                Text("Copy legacy session token")
                            }
                            OutlinedButton(
                                onClick = {
                                    GatewayRuntime.rotateToken(context)
                                    refreshTick++
                                    Toast.makeText(context, "New session token created", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Rounded.Refresh, null)
                                Spacer(Modifier.width(7.dp))
                                Text("Rotate session token")
                            }
                            if (connected) {
                                OutlinedButton(
                                    onClick = {
                                        GatewayRuntime.disconnect()
                                        refreshTick++
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Disconnect PC") }
                            }
                        }
                    }
                }
            }

            item {
                Card(shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                        Text("Connect your PC", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        GatewayStep("1", "Connect USB", "Plug this phone into your Windows PC and allow USB debugging.")
                        GatewayStep("2", "Pair from Cyclone Desktop", "Choose this phone and click Pair. Cyclone will show a four-letter code here.")
                        GatewayStep("3", "Confirm the code", "Enter the four letters on your PC. Cyclone then creates a separate strong session credential automatically.")
                    }
                }
            }

            status.optString("lastSafeError").takeIf { it.isNotBlank() && it != "null" }?.let { safeError ->
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.width(9.dp))
                            Column {
                                Text("Connection needs attention", fontWeight = FontWeight.Bold)
                                Text(safeError, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            item {
                FilledTonalButton(
                    onClick = { technicalOpen = !technicalOpen },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (technicalOpen) "Hide technical details" else "Technical details")
                }
            }

            if (technicalOpen) {
                item {
                    Card(shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Bridge diagnostics", fontWeight = FontWeight.Bold)
                            DiagnosticLine("Pairing bootstrap", if (status.optBoolean("pairingBootstrapListening")) "READY" else "OFF")
                            DiagnosticLine("Android bridge", if (socketReady) "READY" else if (enabled) "STARTING" else "PAIRING ONLY")
                            DiagnosticLine("ADB clients", (session?.optInt("clientCount") ?: 0).toString())
                            DiagnosticLine("Action policy", if (productionAuthority) "V3.1 ACTIVE" else "SAFE / NOT BOUND")
                            DiagnosticLine("Clipboard", if (clipboardEnabled) "PC → PHONE" else "OFF")
                            DiagnosticLine("Protocol", status.optString("protocolVersion").ifBlank { "Unknown" })
                            HorizontalDivider()
                            Text(
                                "USB only · no phone LAN listener · no arbitrary shell/root tools · pairing codes, clipboard content and typed values are excluded from Gateway diagnostics.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GatewayHero(
    state: GatewayUiState,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Usb, null, modifier = Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(state.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("PC Gateway", fontWeight = FontWeight.SemiBold)
                    Text("Only active after you enable it or confirm Desktop pairing.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
        }
    }
}

@Composable
private fun GatewayStatusLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    ready: Boolean,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Icon(
            if (ready) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
            null,
            modifier = Modifier.size(18.dp),
            tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(5.dp))
        Text(value, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun GatewayStep(number: String, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Text(number, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
    }
}

private fun copySessionToken(context: Context) {
    val token = GatewayRuntime.tokenForUser(context)?.takeIf(String::isNotBlank) ?: return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Cyclone Gateway legacy session token", token))
    Toast.makeText(context, "Legacy session token copied", Toast.LENGTH_SHORT).show()
}
