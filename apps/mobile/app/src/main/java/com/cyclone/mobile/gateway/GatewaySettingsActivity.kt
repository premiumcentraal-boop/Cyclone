package com.cyclone.mobile.gateway

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
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCodeScanner
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
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
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
    OFF("Gateway off", "Connect USB when you are ready. Cyclone can still receive a first-time trust request."),
    WAITING("Waiting for PC", "Cyclone is ready for your trusted PC to connect over USB."),
    CONNECTED("Connected", "Your trusted PC can use Cyclone's approved phone controls."),
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
    val bootstrapReady = status.optBoolean("pairingBootstrapListening")
    val socketReady = status.optBoolean("socketListening")
    val productionAuthority = status.optBoolean("productionActionAuthorityBound")
    val trust = status.optJSONObject("trust")
    val trustState = trust?.optString("trustState").orEmpty()
    val trustedPcCount = trust?.optInt("trustedPcCount", 0) ?: 0
    val trustedSessionCount = trust?.optInt("activeSessionCount", 0) ?: 0
    val pendingTrust = remember(refreshTick) { GatewayV33TrustManager.pendingForUser(context) }
    val pairingCode = remember(refreshTick) { GatewayDesktopPairingManager.codeForUser() }
    val pairingExpiresAt = remember(refreshTick) { GatewayDesktopPairingManager.expiresAtForUser() }
    val clipboardEnabled = remember(refreshTick) { GatewayDesktopPreferences.clipboardEnabled(context) }
    val state = when (status.optString("gatewayState")) {
        "CONNECTED" -> GatewayUiState.CONNECTED
        "WAITING_FOR_PC" -> GatewayUiState.WAITING
        "ATTENTION_NEEDED" -> GatewayUiState.ATTENTION
        else -> GatewayUiState.OFF
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PC Gateway", fontWeight = FontWeight.SemiBold)
                        Text("USB trust + control", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

            if (pendingTrust != null) {
                item {
                    Card(
                        shape = RoundedCornerShape(26.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                                    Icon(
                                        Icons.Rounded.Security,
                                        null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(9.dp).size(22.dp),
                                    )
                                }
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Allow this PC?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text(
                                        pendingTrust.pcLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                            Text(
                                "This creates revocable, device-bound Cyclone AI trust. It does not raise the AI permission profile or bypass confirmations for payments, credentials, final sends, destructive changes, or security settings.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "PC fingerprint ${pendingTrust.pcId.take(16)}… · expires shortly",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = {
                                    val accepted = GatewayV33TrustManager.decideTrust(context, pendingTrust.challengeId, true)
                                    Toast.makeText(
                                        context,
                                        if (accepted) "PC approved. Cyclone Desktop can finish trust now." else "Trust request expired. Request it again on the PC.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    refreshTick++
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Allow this PC") }
                            OutlinedButton(
                                onClick = {
                                    GatewayV33TrustManager.decideTrust(context, pendingTrust.challengeId, false)
                                    Toast.makeText(context, "PC trust rejected", Toast.LENGTH_SHORT).show()
                                    refreshTick++
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Reject") }
                        }
                    }
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
                            title = "USB bridge",
                            value = when {
                                !bootstrapReady -> "Starting"
                                enabled -> "Ready"
                                else -> "Trust bootstrap"
                            },
                            ready = bootstrapReady,
                        )
                        GatewayStatusLine(
                            icon = Icons.Rounded.PhoneAndroid,
                            title = "Phone control",
                            value = if (accessibilityReady) "Ready" else "Accessibility off",
                            ready = accessibilityReady,
                        )
                        GatewayStatusLine(
                            icon = Icons.Rounded.Computer,
                            title = "Cyclone AI trust",
                            value = when {
                                pendingTrust != null -> "Confirm on phone"
                                connected -> "Session active"
                                trustedPcCount > 0 -> "Trusted"
                                else -> "Not trusted"
                            },
                            ready = trustedPcCount > 0 && pendingTrust == null,
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

            if (trustedPcCount > 0 || connected) {
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
                                    Text("Trusted PCs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        "$trustedPcCount trusted · $trustedSessionCount active session${if (trustedSessionCount == 1) "" else "s"}. No reusable secret is shown or copied.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            if (connected) {
                                OutlinedButton(
                                    onClick = {
                                        GatewayRuntime.disconnect()
                                        refreshTick++
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Disconnect current PC session") }
                            }
                            OutlinedButton(
                                onClick = {
                                    val count = GatewayV33TrustManager.revokeAllLocal(context)
                                    GatewayRuntime.disconnect()
                                    Toast.makeText(
                                        context,
                                        if (count > 0) "Revoked $count trusted PC${if (count == 1) "" else "s"}" else "No trusted PCs to revoke",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    refreshTick++
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Revoke trusted PCs") }
                        }
                    }
                }
            }

            if (pairingCode != null) {
                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("Legacy fallback pairing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Only use this with the transition PC Companion or non-USB fallback. Normal V3.3 USB setup uses Allow this PC above.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                onClick = { scanDesktopPairingQr(context) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Scan fallback QR")
                            }
                            Text("Or enter this compatibility code on the PC", style = MaterialTheme.typography.labelMedium)
                            Text(
                                pairingCode,
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            val seconds = (((pairingExpiresAt ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L)
                            Text(
                                "Expires in about ${seconds}s. V3.3 treats credentials from this transition path as read-only.",
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
                                        "Cyclone Accessibility must be on before semantic observation and approved PC actions are ready.",
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

            item {
                Card(shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                        Text("Connect your PC", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        GatewayStep("1", "Connect USB", "Plug this phone into your Windows PC and allow Android USB debugging once.")
                        GatewayStep("2", "Choose this phone", "Cyclone Desktop discovers the ADB-authorized phone and requests Cyclone AI trust.")
                        GatewayStep("3", "Allow this PC", "Confirm the visible request on this phone once. Future connections open fresh short-lived sessions automatically.")
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
                                status.optString("lastSafeErrorCode").takeIf { it.isNotBlank() && it != "null" }?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall)
                                }
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
                            DiagnosticLine("Socket", status.optString("socketLifecycleState").ifBlank { "UNKNOWN" })
                            DiagnosticLine("Gateway authority", if (enabled) "ENABLED" else "DISABLED")
                            DiagnosticLine("ADB clients", (session?.optInt("clientCount") ?: 0).toString())
                            DiagnosticLine("AI trust", trustState.ifBlank { "UNKNOWN" })
                            DiagnosticLine("Semantic", status.optString("semanticObservationState").ifBlank { "UNKNOWN" })
                            DiagnosticLine("Action authority", status.optString("actionAuthorityState").ifBlank { if (productionAuthority) "READY" else "DEGRADED" })
                            DiagnosticLine("Clipboard", if (clipboardEnabled) "PC → PHONE" else "OFF")
                            DiagnosticLine("Protocol", status.optString("protocolVersion").ifBlank { "Unknown" })
                            HorizontalDivider()
                            Text(
                                "USB localabstract only · no phone LAN listener · no arbitrary shell/root tools · trust/session secrets, clipboard content and typed values are excluded from Gateway diagnostics.",
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

private fun scanDesktopPairingQr(context: Context) {
    val activity = context as? ComponentActivity ?: return
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .enableAutoZoom()
        .build()
    GmsBarcodeScanning.getClient(activity, options).startScan()
        .addOnSuccessListener { barcode ->
            val approved = barcode.rawValue?.let(GatewayDesktopPairingManager::approveQrPayload) == true
            Toast.makeText(
                context,
                if (approved) "Fallback pairing approved. Return to Cyclone on your PC."
                else "That fallback QR is invalid or expired. Request a new one on your PC.",
                Toast.LENGTH_LONG,
            ).show()
        }
        .addOnFailureListener {
            Toast.makeText(context, "QR scanner unavailable. Use the fallback code below.", Toast.LENGTH_LONG).show()
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
                    Text("Trust is separate from your AI permission profile.", style = MaterialTheme.typography.bodySmall)
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
